# Wake word

The goal: phone locked, screen off, Ani's UI closed, say **"Rey"**, Ani answers.

## The three engines

Ani ships three, behind one `WakeWordEngine` interface. Settings picks one; Diagnostics
names which is actually running, because the factory falls back when the chosen one cannot
start.

| | **Vosk** (default) | **Porcupine** | **Phone recogniser** |
| --- | --- | --- | --- |
| Cost | Free, Apache-2.0 | **Paid** since 30 Jun 2026 | Free |
| Detection runs | On the phone | On the phone | **Usually in the cloud** |
| Any phrase? | Yes | Only trained ones | Yes |
| Battery | Moderate | Lowest | Highest |
| Setup | One ~40 MB download | AccessKey + trained `.ppn` | None |

### Why Vosk is the default

Porcupine is the better engine on the two axes that matter for an always-on listener —
CPU and false-accept rate — and if you have a licence you should use it. It is not the
default for two reasons.

**Picovoice discontinued its free tier on 30 June 2026**, replacing it with a seven-day
trial. A personal assistant that stops waking up when a trial lapses is not a personal
assistant.

**Porcupine cannot detect an arbitrary phrase.** It spots only phrases it has a trained
`.ppn` model for, and "Rey" is not among its fourteen built-in keywords. Vosk's grammar
mode will spot anything in the acoustic model's lexicon, so "Rey" works out of the box and
so does whatever the user changes it to. That is why `supportsCustomPhrase` is `true` for
Vosk and `false` for Porcupine, and why the Settings phrase field greys out when Porcupine
is selected rather than accepting text it would ignore.

### How Vosk grammar mode works

Instead of searching the model's whole vocabulary, the decoding graph is restricted to the
wake phrases plus `[unk]` — "anything else":

```json
["rey", "hey ani", "[unk]"]
```

Two things follow. The search space collapses, so CPU and battery drop a long way below
running a full recogniser. And false positives drop, because similar-sounding words are
not in the graph to be confused with.

If a wake phrase is not in the model's lexicon the grammar constructor throws, and the
engine falls back to full recognition with the same phonetic matching. It still works; it
costs more battery. Diagnostics reports which mode is live rather than hiding it.

The model is **vosk-model-small-en-in-0.4** — Indian English, about 40 MB. Indian English
rather than US because the phrase is spoken by a Telugu speaker, and the acoustic model
trained on Indian speakers is the one that will recognise "Rey" from one. Vosk has no
Telugu model; it does not need one, since it is only ever choosing between the wake phrase
and not-the-wake-phrase.

## Installing the model

Either works.

**In the app** — Settings → Assistant → *On-device wake model* → Download. Onboarding also
offers it. One fetch, then it works with no internet forever.

**Bundled at build time** — download
`https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip`, unzip it, and put the
contents at `app/src/main/assets/vosk-model/` so that `assets/vosk-model/am/final.mdl`
exists. Ani unpacks it on first run. This adds ~40 MB to the APK.

## Using Porcupine instead

You need a current Picovoice plan.

1. Get an AccessKey from `console.picovoice.ai`.
2. Train a custom keyword for "Rey", platform **Android**, and download the `.ppn`.
3. Put it at `app/src/main/assets/porcupine/rey_android.ppn`.
4. Settings → Assistant → Wake engine → Porcupine, and paste the AccessKey.

The key is stored in `EncryptedSharedPreferences` with its master key in the Android
Keystore. It is **never** written to the repository, to `gradle.properties`, to
`BuildConfig`, to logs or to any build output. There is no code path that would put it
there — `SecureStore.KEY_PICOVOICE_ACCESS_KEY` is the only place it lives.

## Why the wake engine is stopped, not paused, during a conversation

This is the single decision that makes screen-off operation work.

Every wake engine owns an `AudioRecord`. So does `SpeechRecognizer`. On most Android
devices the second one to ask for the microphone loses — which would mean Ani hears "Rey",
wakes, and then cannot hear the command that follows.

So `AniVoiceService` tears the wake loop down completely on a detection, releasing the
microphone, runs the exchange, and starts the engine again afterwards.

That also solves self-triggering for free. While Ani is speaking its reply, the wake engine
is not running at all, so "Rey" inside its own sentence cannot wake it again. There is no
timing window to tune and no echo-cancellation to get wrong.

## The microphone lifecycle

Stopping the engine instead of pausing it is the decision; making it *observable* is what
turned it from a plan into something testable. `MicLifecycle` is the whole wake-to-action
cycle as an explicit state machine, with no Android types in it:

```
IDLE
 └─ WAKE_ENGINE_STARTED ──▶ WAKE_LISTENING        owner = WAKE_ENGINE
     └─ WAKE_DETECTED   ──▶ WAKE_DETECTED         owner = WAKE_ENGINE
         └─ WAKE_AUDIO_RELEASED ──▶ WAKE_AUDIO_RELEASE   owner = NONE
             └─ COMMAND_LISTENING_STARTED ──▶ COMMAND_LISTENING  owner = COMMAND_RECOGNIZER
                 └─ COMMAND_CAPTURED ──▶ PROCESSING       owner = NONE
                     ├─ ACTION_STARTED  ──▶ ACTION_EXECUTING
                     ├─ SPEECH_STARTED  ──▶ SPEAKING
                     └─ COMMAND_LISTENING_STARTED ──▶ COMMAND_LISTENING   (follow-up turn)
                         └─ EXCHANGE_ENDED ──▶ WAKE_REARM ──▶ WAKE_LISTENING
```

Two properties are enforced rather than hoped for, and both are unit-tested in
`MicLifecycleTest`:

**One owner, ever.** Each stage declares who holds the recorder. `COMMAND_LISTENING` is
reachable only through `WAKE_AUDIO_RELEASE`, so the command recogniser cannot open a
microphone the wake engine has not finished letting go of. Overlapping recorders do not
throw on Android — the second one just receives silence — so the failure would otherwise
look like bad recognition and get chased in the wrong place.

**Nothing is terminal but a stop.** A command that times out, a recogniser that never
starts, an action that throws: all of them route through `EXCHANGE_ENDED` to
`WAKE_REARM`, never to `IDLE`. An assistant that can get stuck deaf until it is restarted
is worse than one that mishears.

Every transition is logged as `[MIC] FROM -> TO event=… owner=…` in debug builds, which
joins the existing `[WAKE]`, `[COMMAND]`, `[NLU]`, `[CONFIRM]` and `[ACTION]` tags. One
`adb logcat` filter follows a whole conversation without any of it containing a word the
user said.

## Acting on what was heard

Hearing the command is half of it. From Android 10 an app in the background cannot start
an activity: `startActivity` returns normally and nothing appears. A command spoken to a
phone lying on a table is, by definition, in that case.

`LaunchPolicy` decides the route and `ActivityLauncher` takes it:

| Situation | Route |
| --- | --- |
| Ani is on screen | direct start |
| API < 29 | direct start |
| "Display over other apps" granted | direct start |
| Otherwise | full-screen-intent notification, reported as *deferred* |
| Nothing handles the intent | reported as *no handler* |

`SYSTEM_ALERT_WINDOW` is the platform's own documented exemption from the background
start restriction, granted by the user in Settings and revocable at any time. It is
checked live before every launch — never cached — and Ani draws no overlay windows with
it. Without it Ani still works; it simply says the action is waiting behind a
notification, which is true, rather than claiming it dialled.

## Sensitivity

"Rey" is an extremely common word in casual Telugu — it is how people address each other.
A wake word that fires on every "rey" in a conversation is unusable, so the sensitivity
setting is three real options rather than decoration:

| | Behaviour |
| --- | --- |
| **Low** | Exact phonetic match, and only on a completed utterance. Fewest false wakes, a beat more latency. |
| **Medium** | The default. |
| **High** | Forgives a near-miss. Wakes more reliably, and more often by mistake. |

Expect to need **Low** if you speak Telugu around the phone a lot. Measuring this properly
is Phase 18 in `DEVICE_TEST_RESULTS.md` and has not been done.

## What is deliberately not implemented

- **Keyword matching on a cloud transcript as the default.** The phone-recogniser engine
  does exactly this and is available, but it is never chosen automatically over Vosk, and
  its description says plainly that audio leaves the device.
- **Continuous upload of microphone audio.** No engine does this.
- **Recording to disk.** There is no code path that writes audio anywhere.
- **`AlwaysOnHotwordDetector`.** Android's own low-power DSP hotword API is available only
  to the system voice-interaction service, on hardware with a supported DSP, for a phrase
  the platform will enrol. It is not available to a third-party app.

## Status

**BUILT, not VERIFIED.** All three engines compile against their real AARs. None has been
run on a phone — see `DEVICE_TEST_RESULTS.md`.
