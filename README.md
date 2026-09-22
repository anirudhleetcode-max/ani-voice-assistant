# Ani

A personal voice assistant for Android that understands how a Telugu speaker actually
talks — Telugu, English, and the Tanglish mix people really use in one sentence.

```
You:  "Rey"
Ani:  "Cheppu ra."
You:  "Amma ki call chey."
Ani:  "Amma ki call chesthunna ra."   → places the call
```

```
You:  "Rey em messages vachayi?"
Ani:  "WhatsApp lo moodu messages vachayi. Amma nundi okati, Rahul nundi rendu."
```

## What makes it different

**It is not an English assistant with Telugu bolted on.** Tanglish has no spelling, so
every word is folded to a phonetic key before anything looks at it — `chey`, `cheyi`,
`cheyyi`, `che` and the transliteration of `చెయ్` all land on one lexicon entry. Native
Telugu script from a `te-IN` recogniser and Latin text from an `en-IN` one reach the same
code path. Case endings that Telugu glues onto nouns are stripped, so "ammaki call chey"
and "amma ki call chey" both find Amma.

**It does not lie about what it did.** Android will not let a third-party app press play
in Spotify, send a WhatsApp message, or toggle Wi-Fi. Ani does the closest legitimate
thing and says so — "Spotify lo search chesi icha, play press cheyyi" rather than "playing
now". Every such case is a typed `Limitation` result in the code, not a silent success.

**The model cannot touch your phone.** The set of actions Ani can take is a closed enum
fixed at compile time. The language model is asked for *sentences*, never for actions,
and it is never sent your contacts, notifications or messages.

**The wake word runs on the phone.** "Rey" is detected by a small speech model on the
device — no audio is uploaded and nothing is recorded. See
[docs/WAKE_WORD.md](docs/WAKE_WORD.md) for why the obvious choice (Porcupine) is not the
default, and what the three engines actually cost.

## Status

Read this table as written. The four words are not interchangeable.

| Part | Status | |
| --- | --- | --- |
| Language engine (`core-nlu`) | **VERIFIED** | 106 tests, 129 utterances, all passing |
| Backend | **VERIFIED** | 14 tests passing |
| App storage + settings | **VERIFIED** | 13 tests passing |
| Android app compiles | **BUILT** | All 67 files type-check against the real Android 35 API |
| APK | **BLOCKED** | No Android SDK in the build environment |
| Anything on a phone | **NOT TESTED** | No device has ever run this |

- **VERIFIED** — tested and passing.
- **BUILT** — compiles, not yet run.
- **BLOCKED** — a platform or environment limitation prevents it, evidenced in the docs.
- **NOT TESTED** — not attempted.

**There is no APK yet.** The environment this was built in blocks `dl.google.com`, which is
the only source of the Android SDK, the Android Gradle Plugin and every AndroidX artifact
— every mirror was checked too. So `:app:assembleDebug` has never run here.

What was done instead: [`tools/compile-check`](tools/compile-check) compiles every Android
source file against the genuine Android 35 framework jar and the genuine Compose API, both
of which *are* on Maven Central, plus the real Vosk and Porcupine AARs. That turns "never
compiled" into "compiles against the real APIs", and it caught a genuine type error on its
first run. It is not a substitute for a build.

Next step is yours: run `./gradlew :app:assembleDebug` on a machine with the SDK and work
through [docs/DEVICE_TEST_RESULTS.md](docs/DEVICE_TEST_RESULTS.md).

## Quick start

```bash
git clone <this repo> && cd ani-voice-assistant

# The language engine — runs anywhere with a JDK 17+, no Android SDK needed
cd core-nlu && ./gradlew test && cd ..

# The app (needs Android SDK 35)
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Full instructions, including the optional AI backend: [docs/SETUP.md](docs/SETUP.md).

## Documentation

| Document | What's in it |
| --- | --- |
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | How the pipeline fits together and why |
| [WAKE_WORD.md](docs/WAKE_WORD.md) | The three engines, and why Vosk is the default |
| [ASSISTANT_ARCHITECTURE.md](docs/ASSISTANT_ARCHITECTURE.md) | Assist gesture vs. system assistant, what each grants, and the order the rest lands in |
| [AUDIO_PIPELINE.md](docs/AUDIO_PIPELINE.md) | Microphone → recogniser → transcript: the stages, the log tags, and how to tell which one failed |
| [DEVICE_TEST_RESULTS.md](docs/DEVICE_TEST_RESULTS.md) | The physical test plan, and what is still untested |
| [SETUP.md](docs/SETUP.md) | Clone → build → run on a real phone |
| [PERMISSIONS.md](docs/PERMISSIONS.md) | Every permission, why, and what breaks without it |
| [AI_INTEGRATION.md](docs/AI_INTEGRATION.md) | Where the model sits and what it is not allowed to do |
| [SPOTIFY_INTEGRATION.md](docs/SPOTIFY_INTEGRATION.md) | What works, what cannot, and exactly why |
| [NOTIFICATION_ACCESS.md](docs/NOTIFICATION_ACCESS.md) | The one permission that is not a dialog |
| [PRIVACY.md](docs/PRIVACY.md) | What is stored, where, and how to delete it |
| [TESTING.md](docs/TESTING.md) | What is tested and what is not |
| [RELEASE.md](docs/RELEASE.md) | Signing, AAB, Play Store |
| [TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) | When Ani mishears, or does not answer at all |

## What Ani can do

| | |
| --- | --- |
| **Calls** | "Amma ki call chey", "call mom", "Rahul ki phone cheyyi" — with alias support, duplicate-number disambiguation, and a dialler fallback when call permission is refused |
| **Messages** | Composes into WhatsApp or SMS with the text filled in. You press send — see [SPOTIFY_INTEGRATION.md](docs/SPOTIFY_INTEGRATION.md) for why nobody can do better legitimately |
| **Notifications** | "Em messages vachayi?" — grouped by app and sender, with three privacy levels and lock-screen and headphone gates |
| **Music** | Real transport control (pause, next, previous) through the media session; search-and-open for a named track |
| **Alarms, timers, reminders** | Set in your own clock app; reminders delivered by Ani, surviving reboot |
| **Device** | Battery, storage, torch, volume, Do Not Disturb; Settings screens for everything Android reserves to itself |
| **Apps** | Launches anything installed, by nickname |
| **Custom commands** | "College mode" → a saved sequence of the above |
| **Memory** | "Amma ante Lakshmi" — aliases and facts, fully visible and deletable |

## Licence

Not yet chosen. Add one before distributing.
