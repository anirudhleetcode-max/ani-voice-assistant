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
   away. Levels do not travel down that channel at all now — they go to a conflated
   `StateFlow`, where a value nobody read is simply overwritten, and the event channel
   carries a handful of events per turn that nothing can crowd out or delay.

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
| `[RECOGNIZER]` | `ENGINE=ON_DEVICE` or `ENGINE=SYSTEM_NETWORK`, language, endpointing values, and each A/B trial's timings |
| `[LATENCY]` | One line per turn, every stage boundary |
| `[TTS]` | Init wait, request→first audio, first audio→done, and whether the voice is a network voice |
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

## Latency: where the seconds go

"Ani is slow" is not a diagnosis. The delay can be endpointing, a networked recogniser,
classification, a cold TTS engine or audio playback, and those have nothing in common
except how they feel. `TurnTimeline` marks each boundary and every turn logs one line:

```
[LATENCY] wakeToListening=180 listeningToReady=95 listeningToFirstPartial=420
          listeningToFinal=2600 endOfSpeechToFinal=1250 finalToNLU=140
          nluToTtsRequest=5 ttsRequestToAudio=1850 ttsAudioToComplete=900 total=5800
```

Read it segment by segment:

| Segment | If it is large |
| --- | --- |
| `wakeToListening` | Microphone handover. Check `[MIC]` — a release that took the full timeout |
| `listeningToFirstPartial` | The recogniser is slow to come alive. Usually a network round trip; compare against the on-device trial |
| `endOfSpeechToFinal` | **Endpointing.** This is the segment `EndpointingConfig` controls, and the only one worth changing those numbers for |
| `listeningToFinal` minus the above | The user simply spoke for that long. Not a bug |
| `finalToNLU` | Classification *and tool execution* — a contact lookup, or an AI backend call for an unhandled intent |
| `ttsRequestToAudio` | **Synthesis.** A cold engine, or a network voice. `[TTS] networkVoice=true` says which |
| `ttsAudioToComplete` | The length of the reply. Shorten the wording, not the code |

If `endOfSpeechToFinal` does not move when the endpointing values change, **the recogniser
is ignoring the extras** — Android permits that and several OEM implementations do it. The
latency is then somewhere else and no amount of tuning will find it.

### What was already wrong with these numbers

The silence windows have been wrong in both directions in this project:

- The stock ~1 s cut Telugu and Tanglish speakers off mid-sentence, because
  "Rey … Annayya ki … call chey" is one thought with real pauses in it.
- Widening them to 2.5 s fixed that and put a visible delay on every command — the
  complaint that followed.

Neither was measured. They are settings-backed now (`EndpointingConfig`, defaulting to
1200/900 ms with no minimum speech length), and `EndpointingConfig.PATIENT` keeps the
previous build's values so the two can be compared on device rather than argued about.

### TTS warm-up

`TextToSpeech` was already a single long-lived instance — it was never recreated per
reply — but two things were on the critical path and are not any more:

- `isLanguageAvailable` and setting `engine.language` are binder calls into the TTS
  service, and the second can trigger a voice load. They ran before *every* utterance
  even though the language almost never changes. Both are cached now.
- Nothing warmed the engine. The first reply after a cold start paid for service binding
  and voice loading. `TtsProvider.prepare()` is now called when an exchange begins — while
  the user is still speaking — so that cost overlaps recognition instead of following it.

A voice that synthesises over the network is a latency source that warming cannot remove.
`[TTS] networkVoice=true` names it.

## Which recogniser: the A/B test

**Google Assistant understands quiet speech on the same phone that Ani struggles with.**
That single observation rules out the microphone, the hardware and the gain stage — they
are shared. What differs is everything between the microphone and the transcript.

**Diagnostics → Recogniser A/B** holds the sentence and the microphone constant and varies
one thing at a time:

| Trial | Engine | Language |
| --- | --- | --- |
| A | system (usually networked) | en-IN |
| B | on-device | en-IN |
| C | system | te-IN |
| D | on-device | te-IN |
| E | system, previous build's patient endpointing | en-IN |

Each reports `engine`, `onDevice`, `language`, `firstPartialMs`, `finalResultMs`,
`totalLatencyMs`, the audio verdict, the exact error — and **the transcript it actually
produced**, because *audio → transcript* is the measurement that separates a recognition
problem from an NLU one:

- Google hears "annayya ki call chey" and a trial returns "anaya ki call che" →
  recognition or language model. The NLU never had a chance.
- A trial returns the right words and Ani still apologises → everything after recognition,
  and no audio change will help.

Say the same sentence at the same volume for every trial, then repeat the set quietly.

On-device support is queried properly through `SpeechRecognizer.checkRecognitionSupport`
(API 33+) rather than inferred from a failed attempt, so *supported but not downloaded* is
distinguishable from *not supported*. Where it cannot be queried the log says
`ON_DEVICE_SUPPORT_NOT_QUERIED`; where on-device recognition is absent it says
`ON_DEVICE_UNAVAILABLE`. Neither is ever faked.

The engine is named on every command — `[RECOGNIZER] ENGINE=ON_DEVICE` or
`ENGINE=SYSTEM_NETWORK` — and never changes part-way through one.

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
