# Device test results

**Device:** realme NARZO 90 5G
**Android version:** _not recorded_
**realme UI version:** _not recorded_
**Build tested:** _none yet_
**Tested by:** _nobody yet_
**Date:** _not yet_

---

## Current status: NOT TESTED

Nothing in this document is a pass. No test below has been run, because the environment
this was built in has no Android SDK and no physical device attached. Both blockers are
real and are evidenced in the section at the bottom.

**Do not treat any row here as working until someone has run it on the phone and written
the result in.**

## Status vocabulary

| Status | Means |
| --- | --- |
| **PASS** | Run on the realme NARZO 90 and worked. |
| **FAIL** | Run on the device and did not work. |
| **PARTIAL** | Worked, with a caveat written in the notes. |
| **BLOCKED** | Cannot be run because Android, realme or a third party prevents it. |
| **NOT TESTED** | Has not been attempted. |

## Results

| Test | Result | Notes |
| --- | --- | --- |
| App launch | NOT TESTED | |
| Microphone | NOT TESTED | |
| TTS | NOT TESTED | Check Diagnostics for the Telugu voice first. |
| Speech recognition | NOT TESTED | |
| Telugu | NOT TESTED | Record the raw transcript, not just whether it worked. |
| English | NOT TESTED | |
| Tanglish | NOT TESTED | |
| Contacts | NOT TESTED | |
| Calls | NOT TESTED | |
| Notifications | NOT TESTED | |
| Spotify | NOT TESTED | Expected: opens a search. See SPOTIFY_INTEGRATION.md. |
| Alarm | NOT TESTED | |
| Wake word, app open | NOT TESTED | |
| Wake word, app closed | NOT TESTED | |
| Wake word, screen off | NOT TESTED | The one that matters most. |
| Wake word, locked | NOT TESTED | |
| Wake word after reboot | NOT TESTED | |
| Bluetooth | NOT TESTED | |
| Self-trigger protection | NOT TESTED | |
| Battery test | NOT TESTED | |

---

## How to run each test

### Build and install

```bash
git clone <this repo> && cd ani-voice-assistant
echo "sdk.dir=$HOME/Android/Sdk" > local.properties

# Confirm the language engine is sound before touching the Android build
cd core-nlu && ./gradlew test && cd ..

# Confirm the Android sources type-check (no SDK needed)
cd tools/compile-check && gradle compileKotlin && cd ../..

./gradlew :app:assembleDebug
adb devices                       # the NARZO 90 must be listed
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Record the build's version and the APK's path here once it exists.

### 1–4. Launch, microphone, TTS, recognition

Open Ani, complete onboarding, grant microphone and notifications. Then
**Settings → Diagnostics** and write down every row. That single screen answers "is the
recogniser there", "is the Telugu voice installed", "is the wake model installed".

If the Telugu voice row says *missing*, install it before testing any Telugu:
Settings → System → Languages → Text-to-speech → Install voice data → Telugu.

### 5–7. Language

Say each of these with the app open, and **write down what the transcript on the home
screen actually said** — not just whether Ani did the right thing. The transcript is the
only way to tell a recognition problem from an NLU problem, and they need different fixes.

```
Rey Amma ki call chey
Rey em messages vachayi
Rey Spotify open chey
Rey Arijit Singh play chey
Rey repu 7 ki alarm pettu
Rey battery entha undi
```

Then the English equivalents, then a mixed sentence such as
*"Rey tomorrow morning 7 ki alarm pettu"*.

- **Transcript right, action wrong** → NLU bug. Add the phrasing to
  `IntentClassificationTest` and fix the lexicon.
- **Transcript wrong** → recogniser limitation. Do not change the NLU for it; note the raw
  text here.

### 13–14. Wake word, app open then closed

Settings → Assistant → download the wake model, turn on *Listen for the wake phrase*.
Confirm the "Ani is listening for Rey" notification appears.

Say "Rey" with the app open. Then press Home so the UI is gone, wait 30 seconds, say "Rey"
again.

### 15. Wake word, screen off — **the one that matters**

1. Start the voice service.
2. Press Home.
3. Turn the screen off.
4. Wait 30 seconds.
5. Say **"Rey"**.
6. Expect "Cheppu ra."
7. Say **"Battery entha undi?"**
8. Expect a real percentage.

**Repeat five times and record how many worked.** One success is not a pass; this is
exactly the behaviour OEM battery managers break intermittently.

If it fails: Settings → **Keep Ani Ready**, work through every check, then repeat. The
auto-start one has to be confirmed by eye — no app can read it.

### 16. Lock screen

As above but with the phone locked. Then say **"Rey em messages vachayi?"**.

Expected, with the default privacy setting: Ani gives counts only and asks you to unlock
for detail. That is the lock-screen privacy rule working, not a bug. Note whether it
leaked any sender or message text — if it did, that is a **FAIL** regardless of everything
else.

### 17. After reboot

1. Configure Ani and confirm the wake word works.
2. Reboot the phone.
3. **Do not open Ani.**
4. Wait for boot, then 30 more seconds.
5. Lock the phone.
6. Say "Rey".

If it does not come back, find out which restriction caused it before concluding anything:

```bash
adb shell dumpsys activity services com.ani.assistant | head -40
adb shell dumpsys deviceidle whitelist | grep ani
adb shell cmd appops get com.ani.assistant RUN_IN_BACKGROUND
```

A force-stopped app receives no `BOOT_COMPLETED` at all until it is opened once — that is
Android, not a bug, and it is worth ruling out first.

### 18. False wakes

Have a normal Telugu conversation near the phone for ten minutes, using "rey" naturally as
people do, without intending to address Ani. Count the false wakes. Then repeat at each
sensitivity level.

Record: false wakes per ten minutes, and missed wakes out of ten deliberate attempts, at
Low / Medium / High.

### 19. Self-trigger

Say "Rey battery entha undi?" and let Ani answer in full. It must **not** wake itself on
its own reply.

By design it cannot: the wake engine is completely stopped while Ani speaks. This test
confirms the design holds in practice.

### 20. Battery

Charge to 100%, note the percentage, leave the phone idle with the wake word on and the
screen off for 30 minutes, note it again. Repeat for 2 hours if you can.

Record the actual numbers. Do not write "negligible".

### 21. Bluetooth

Connect earbuds, lock the phone, screen off, say "Rey". Check that detection works, which
microphone was used, and where the reply came out. Then disconnect and repeat on the
speaker.

### 22. During a call

Make a call. Confirm Ani does not act on call audio and does not interfere. Hang up and
confirm the wake word still works afterwards.

### 23. Notification privacy

Enable notification access, tick WhatsApp, send yourself a message, lock the phone, say
"Rey em messages vachayi?". Confirm the lock-screen privacy rule holds.

### Log check

After all of it:

```bash
adb logcat -d | grep -i ani > /tmp/ani-log.txt
```

Search that file for any contact name, message text or notification content. Finding any
is a **FAIL** — the logger is built to make it impossible, so a hit is a real bug.

---

## Why this document is empty

Two independent blockers, both verified rather than assumed.

**No Android SDK.** The build environment's egress proxy blocks `dl.google.com`, which is
the only source for the Android SDK, the Android Gradle Plugin and every AndroidX and
Compose artifact. Checked directly, and every mirror checked too — Aliyun, Tencent,
Huawei, JitPack, `maven.google.com` — all blocked. Maven Central, `services.gradle.org`
and `plugins.gradle.org` are reachable; nothing that carries AGP is.

```
$ curl -o /dev/null -w "%{http_code}" https://dl.google.com/dl/android/maven2/androidx/core/core/1.15.0/core-1.15.0.pom
000   # CONNECT tunnel failed, 403
$ curl -o /dev/null -w "%{http_code}" https://repo1.maven.org/maven2/
200
```

So `./gradlew :app:assembleDebug` cannot run, and **no APK exists**.

**No device.** This is a cloud container. There is no USB bus, `adb` is not installed, and
there is no realme NARZO 90 attached. Every test above needs the phone in someone's hand.

## What was done instead

`tools/compile-check` compiles all 67 Android source files against the genuine Android 35
framework jar and the genuine Compose API, both mirrored to Maven Central, plus the real
Vosk and Porcupine AARs. That moves the Android module from *never compiled* to *compiles
against the real APIs*, and it found a genuine type error on its first run.

It is not a build. It proves the Kotlin is sound; it proves nothing about resource
merging, R8, or how any of this behaves on a phone.
