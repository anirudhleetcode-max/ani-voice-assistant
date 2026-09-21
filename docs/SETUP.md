# Setup

## What you need

| | |
| --- | --- |
| JDK | 17 or newer |
| Android SDK | Platform 35, Build Tools 35.x |
| Android Studio | Ladybug or newer (optional — the CLI is enough) |
| Python | 3.11+ (only for the AI backend) |
| A phone | Android 8.0 (API 26) or newer, USB debugging on |

## 1. Clone

```bash
git clone <this repo>
cd ani-voice-assistant
```

## 2. The language engine

This needs no Android SDK and is the fastest way to confirm the checkout is sound:

```bash
cd core-nlu
./gradlew test
cd ..
```

106 tests, a few seconds. If these pass, the Telugu/Tanglish engine is working.

## 3. Point Gradle at your SDK

```bash
echo "sdk.dir=$HOME/Android/Sdk" > local.properties    # macOS: ~/Library/Android/sdk
```

Android Studio writes this for you on first open. It is git-ignored.

## 4. Build the app

```bash
./gradlew :app:assembleDebug
```

> **First build note.** The Android module has never been through AGP — `dl.google.com` is
> blocked in the environment it was written in. It *has* been compiled against the real
> Android 35 framework API and the real Compose API via `tools/compile-check`, so the
> Kotlin is sound. What has never run is resource merging, R8 and lint, so expect those to
> be where any remaining problems are.
>
> Run the harness first; it is faster than a full build and needs no SDK:
>
> ```bash
> cd tools/compile-check && gradle compileKotlin && cd ../..
> ```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

## 5. Install on a phone

```bash
adb devices                 # confirm the phone is listed
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 6. Run the AI backend (optional)

Ani works without it — every phone command is offline. The backend is only for open
questions and small talk.

```bash
cd backend
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements-dev.txt
cp .env.example .env         # fill in ANI_ANTHROPIC_API_KEY
pytest                       # 14 tests
uvicorn app.main:app --reload --port 8000
```

Then build the app pointing at it:

```bash
./gradlew :app:assembleDebug -Pani.backendUrl=http://192.168.1.5:8000
```

Use your machine's LAN address for a real phone, or `http://10.0.2.2:8000` for the
emulator. The URL is build config, not a secret — the API key never leaves the backend.

## 6b. The wake word

The wake word needs a one-time ~40 MB on-device speech model. Onboarding offers it, or
Settings → Assistant → *On-device wake model* → Download.

To bundle it in the APK instead, unzip
`https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip` into
`app/src/main/assets/vosk-model/` so that `assets/vosk-model/am/final.mdl` exists.

See [WAKE_WORD.md](WAKE_WORD.md) for the engine choice and for Porcupine setup.

## 6c. Keep Ani Ready — do not skip this on a realme

realme, OPPO, Xiaomi, vivo, Samsung and others run a battery manager on top of Android's
that will kill the listening service. **Settings → Keep Ani Ready** checks what Android
will report and takes you to the right screens.

The auto-start check shows "Can't check" on purpose: no app can read an OEM auto-start
list, so that one has to be confirmed by eye. Ani will not claim a state it did not read.

## 7. First run on the phone

Onboarding walks through this, one screen at a time:

1. **Wake phrase** — defaults to "Rey". Change it to anything.
2. **Microphone** — required. Nothing works without it.
3. **Notifications** — required on Android 13+, for the listening notification and reminders.
4. **Contacts** — for "Amma ki call chey".
5. **Phone** — optional. Without it Ani opens the dialler with the number filled in.
6. **Notification access** — a Settings toggle, not a dialog. See [NOTIFICATION_ACCESS.md](NOTIFICATION_ACCESS.md).
7. **Exact alarms** — so a 7:00 reminder fires at 7:00.

## 8. Check it works

Walk the Diagnostics screen (Settings → Diagnostics). Every row is read live.

| Row | Expected |
| --- | --- |
| Microphone | Ready |
| Speech recognition | Ready |
| Telugu voice | Installed — if not, see below |
| Notification access | Connected |

Then try, in order:

```
"Rey"                          → "Cheppu ra."
"Rey battery entha undi"       → "62 percent undi ra."
"Rey WhatsApp open chey"       → WhatsApp opens
"Rey em messages vachayi"      → a summary, or "Kotha notifications emi ledu ra"
"Rey repu 7 ki alarm pettu"    → "Morning 7 aa, evening 7 aa?"
"Morning"                      → alarm set in your clock app
```

## Installing a Telugu voice

If Diagnostics says the Telugu voice needs downloading, Ani will speak with the default
voice — which reads Telugu words with English phonetics and sounds wrong. To fix it:

**Settings → System → Languages & input → Text-to-speech output → Install voice data →
Telugu**

On most devices the engine is Google Speech Services; some OEM builds ship a different
one that has no Telugu voice at all, in which case installing Google's from Play is the
fix.

## Wake word and battery

Wake-word listening is **off by default**, and for an honest reason: it runs the platform
speech recogniser continuously, which uses noticeably more battery than a dedicated
hotword model would and keeps the microphone indicator lit. Android does expose a
low-power hotword API, but only to the system voice interaction service, on supported
hardware, for an enrolled phrase — not to a third-party app. Tap-to-talk costs nothing and
is the recommended default. The setting states this in the app.

## Running the tests

```bash
cd core-nlu && ./gradlew test        # 106 tests — the language engine
cd backend && pytest                # 14 tests — the API
./gradlew :app:testDebugUnitTest    # 13 tests — storage and settings
```

See [TESTING.md](TESTING.md) for what each covers and what is not covered.
