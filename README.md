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

## Status

| Part | State |
| --- | --- |
| Language engine (`core-nlu`) | Built and tested — 106 tests, 129 utterances, all passing |
| Backend | Built and tested — 14 tests passing |
| Android app | Complete source; **not compiled in this environment** — see below |

The container this was built in has `dl.google.com` blocked by network policy, which means
no Android SDK, no Android Gradle Plugin and no AndroidX artifacts. The Android module has
therefore never been compiled. Everything that could be verified without them was, which
is why the language engine — the part that needed the most iteration — is a plain JVM
library rather than an Android one. See [docs/SETUP.md](docs/SETUP.md) for the first
build, and be ready for the ordinary first-compile fixes in the app module.

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
