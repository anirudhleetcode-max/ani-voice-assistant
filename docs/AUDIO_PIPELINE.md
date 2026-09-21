# The command audio pipeline

What happens between a spoken command and a transcript, where it used to break, and how
to find out which stage failed on a real phone.

## The bug this document exists for

Ani would answer **"Sarigga vinapadaledu ra, malli cheppu"** — *I didn't catch that, say
it again* — however loudly the user spoke. It also failed for quiet speech. Both symptoms
had the same cause, and it was not recognition quality.

**Two things wanted the microphone and neither asked the other.**

The wake engine holds an `AudioRecord` continuously while the listening service runs.
`SpeechRecognizer` opens its own recorder when a command is captured. Android does not
report the overlap: the second recorder opens successfully and receives **silence**. The
recogniser then ends with `ERROR_NO_MATCH`, which this app translated to "I didn't catch
that". The user was told they were not heard *clearly*. They were not heard *at all*.

It reached the user through three distinct paths:

1. **Tapping the orb released nothing.** `AniViewModel` called `startListening` straight
   into `VoiceSession` while the service still had the microphone open. There was no
   handover on this path — not a race, an omission. This is why it failed every time in
   the app.
2. **The wake path released without waiting.** `VoskWakeWordEngine.release()` set a flag
   and returned while a blocking `AudioRecord.read` was still in flight on another
   thread. "We called release()" and "Android has let go" are different facts.
3. **Results could be dropped even when recognition worked.** Every callback used
   `trySend` into a `callbackFlow` with the default 64-slot buffer, and `onRmsChanged`
   fires ten or more times a second. A full buffer made the `onResults` send fail — and
   `trySend` returns a result nobody was reading. The transcript existed and was thrown
   away.

## The pipeline, stage by stage

```
WAKE_LISTENING        VoskWakeWordEngine → WakeAudioPipeline → AudioRecord   [owner: wake]
   │ "Rey"
WAKE_DETECTED         the phrase matched; the recorder is still open         [owner: wake]
   │ releaseAndAwait() — blocks until AudioRecord.recordingState is not RECORDING
WAKE_AUDIO_RELEASE    nothing is recording                                   [owner: none]
   │ MicArbiter.acquireForCommand()
COMMAND_LISTENING     SpeechRecognizer owns the microphone            [owner: recognizer]
   │ onReadyForSpeech → onBeginningOfSpeech → onRmsChanged… → onResults
COMMAND_RESULT        transcript, plus what the level did
   │
NLU                   IntentClassifier over the existing normalisation/folding layer
   │
ACTION                ActivityLauncher
   │
WAKE_REARM            the engine starts again                               [owner: none]
```

Every arrow is a real transition in `MicLifecycle`, and `COMMAND_LISTENING` is reachable
**only** through `WAKE_AUDIO_RELEASE`. That is what makes the overlap impossible rather
than unlikely.

`MicArbiter` is the gate. Nothing opens a recorder without going through it: it asks the
registered wake owner to stop, waits for confirmation, and **denies** when confirmation
does not arrive. A denial is reported honestly instead of starting a recogniser that
would hear nothing.

## Reading the log

Debug builds tag every stage. One filter follows a whole command:

```bash
adb logcat -s AniVoiceService:* AniSpeech:* AniWakeAudio:* AniVoskWake:* AniMicTest:* \
  | grep -E "\[MIC\]|\[AUDIO\]|\[COMMAND\]|\[PIPELINE\]|\[NLU\]|\[CONFIRM\]|\[ACTION\]"
```

| Tag | What it carries |
| --- | --- |
| `[MIC]` | Ownership: capture open/close, source, rate, channels, encoding, buffer sizes, `AudioRecord` state, and every handover with whether it was **confirmed** |
| `[AUDIO]` | Real PCM statistics from the Mic Test: RMS, peak, min, max, noise floor, fraction above silence, verdict |
| `[COMMAND]` | `onReadyForSpeech`, `onBeginningOfSpeech`, `onEndOfSpeech`, `onPartialResults`, `onResults`, `onError` **with the exact platform constant by name** |
| `[PIPELINE]` | `COMMAND_LISTENING` → `COMMAND_AUDIO_RECEIVED` → `COMMAND_RESULT` |
| `[NLU]`, `[CONFIRM]`, `[ACTION]` | Classification, confirmation, launch |

Nothing in any of them is a transcript, a contact, a number or a message. Lengths,
counts, enum names and levels only.

## Deciding which stage failed

Run **Diagnostics → Mic Test**. It opens the recorder itself, while nothing else holds
it, so the numbers are genuine PCM in LSB rather than the platform's loosely-scaled
`onRmsChanged`.

Run the level test four times: silence, a whisper, normal speech, loud speech.

| What you see | Where the problem is |
| --- | --- |
| All four read the same, `NO AUDIO` | **Before recognition.** Something else holds the microphone. Check `[MIC]` for a handover that was not confirmed |
| Levels move correctly, recogniser reports `ERROR_NO_MATCH` | **Capture is fine.** The recogniser, its language, or the network |
| Levels move, a transcript appears, Ani still apologises | **Downstream.** Transcript handling or the NLU |
| Peak pinned near 32767 | **Clipping.** Too close to the microphone; consonants are being lost |
| Max RMS below ~700 | **Genuinely quiet.** The adaptive gain stage is the thing to look at |

`NO AUDIO` and `LOW AUDIO` are deliberately different verdicts. A whisper still moves the
level off the floor; a dead stream does not. Confusing the two is what produced the
misleading message in the first place.

## What Ani says now

`RecognitionRetryPolicy` decides, and the rule is that **a failure Ani caused is never
reported as a failure the user caused**.

| Cause | Attempt 1 | Attempt 2 |
| --- | --- | --- |
| Audio arrived, words did not resolve | "Sarigga vinapadaledu ra" then listen again | "Inkosari cheppu ra" then re-arm |
| No audio at all | retry silently (re-acquiring can genuinely fix it) | "Microphone inko app daggara undi ra" |
| Microphone busy | retry silently | "Microphone inko app daggara undi ra" |
| Permission missing | say so at once | — |
| No recogniser installed | say so at once | — |
| Network | say so at once | — |
| Silence | retry silently | say nothing |

Two attempts, then back to `WAKE_REARM`. One failed attempt is normal — a door closing, a
cough, a false wake — and narrating every one of them is nagging.

## Capture configuration

Wake capture (`WakeAudioPipeline`) negotiates rather than assumes:

- `VOICE_RECOGNITION` first, `MIC` as the fallback; both are logged
- mono, `ENCODING_PCM_16BIT`
- 16 kHz preferred, and where the device refuses it the rate is negotiated from
  `getMinBufferSize` and resampled to 16 kHz for the decoder
- buffer derived from `getMinBufferSize`, never a fixed guess
- 20 ms reads, so an unwind takes tens of milliseconds
- hardware AGC on, platform noise suppression off in low-voice mode — the suppressor
  cannot tell a whisper from the room tone it exists to remove

`SpeechRecognizer` owns its own capture and does not expose these; what it does take is
the silence windows, which were widened because a Tanglish sentence contains pauses that
the stock ~1 s values read as the end of the utterance:

| Extra | Value | Why |
| --- | --- | --- |
| `EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS` | 2500 ms | "Rey … Annayya ki … call chey" is one thought |
| `EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS` | 2000 ms | |
| `EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS` | 2000 ms | A quiet speaker gets cut off soonest |
| `EXTRA_LANGUAGE` + `EXTRA_LANGUAGE_PREFERENCE` | `en-IN` for mixed speech | Some OEM recognisers read only one of the two |

`en-IN` rather than `te-IN` for Tanglish is deliberate: it transcribes Telugu words into
Latin script, which is the shape the normalisation and phonetic-folding layer expects.
`te-IN` is used only when the user has explicitly set Telugu.

On-device recognition (`createOnDeviceSpeechRecognizer`) is a switch, off by default —
it needs a downloaded language pack and is markedly worse at code-switched speech. Which
recogniser ran is always announced through `SpeechEvent.Started` and always logged. There
is no silent fallback.
