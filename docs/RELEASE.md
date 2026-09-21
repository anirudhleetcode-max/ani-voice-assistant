# Release

## Before anything else

The Android module has not been compiled — see [TESTING.md](TESTING.md). Get
`./gradlew :app:assembleDebug` green and the manual checklist walked before thinking about
a release build.

## Signing

Generate a key. Keep it somewhere you will still have it in five years; losing it means
you can never update the app on Play under the same listing.

```bash
keytool -genkey -v -keystore ani-release.jks \
        -keyalg RSA -keysize 2048 -validity 10000 -alias ani
```

Then, in the repository root:

```properties
# keystore.properties — git-ignored, never commit this
storeFile=../ani-release.jks
storePassword=...
keyAlias=ani
keyPassword=...
```

Without this file the release build still assembles, unsigned. That is deliberate: a
contributor or a CI job should be able to build release without holding your key.

## Build

```bash
# APK, for sideloading and testing
./gradlew :app:assembleRelease
#   → app/build/outputs/apk/release/app-release.apk

# AAB, for Play
./gradlew :app:bundleRelease
#   → app/build/outputs/bundle/release/app-release.aab
```

With the AI backend:

```bash
./gradlew :app:bundleRelease -Pani.backendUrl=https://your-backend.example.com
```

The URL is build config, not a secret. The API key never leaves the backend.

## Verify the release build

R8 is on, which means code that is only reached reflectively can disappear. The four
things most likely to break:

```bash
# 1. It installs and runs
adb install -r app/build/outputs/apk/release/app-release.apk

# 2. The services and receivers survived shrinking
#    (they are kept explicitly in proguard-rules.pro — check they actually work)
#    - turn on wake listening, confirm the notification appears
#    - set a reminder, reboot, confirm it still fires

# 3. kotlinx.serialization still round-trips
#    - save a custom command, force-stop, reopen, confirm it is still there

# 4. Nothing sensitive is in the logs
adb logcat -c && adb logcat | grep -i ani
```

Point 4 matters. Debug and info logging is compiled out of release builds, but check
anyway — a single interpolated string in a warn-level call would undo the whole logging
design.

## Target API level

Currently `compileSdk = 35`, `targetSdk = 35` (Android 15), `minSdk = 26` (Android 8.0).

Play requires apps to target within one year of the latest Android release, so **API 36
will be required**. It is a three-line change in `gradle/libs.versions.toml`:

```toml
agp = "8.9.2"        # or newer; 8.7.3 predates API 36 support
compileSdk = "36"
targetSdk = "36"
```

Re-test, in this order, because these are the areas API-level bumps break:

1. The foreground microphone service starting and showing its notification.
2. The notification listener binding.
3. Exact alarms firing at the right time.
4. Package visibility — that `AppResolver` still sees the launcher inventory.

`minSdk = 26` is what allows `java.time` without desugaring and gives the notification
channel APIs the assistant relies on. It covers roughly 99% of active devices.

## Play Store listing

### Declarations you will have to make

| Form | Answer |
| --- | --- |
| Data safety — collected | None. Nothing is uploaded except the text of questions the user asks the optional AI backend |
| Data safety — shared | None |
| Data safety — encrypted in transit | Yes (HTTPS to the backend) |
| Data safety — deletion | Yes, in-app: Privacy Center → Delete all Ani data |
| Sensitive permissions | `RECORD_AUDIO`, `READ_CONTACTS`, `CALL_PHONE` — each with a usage declaration |
| Notification listener | Requires a declaration explaining the core feature it serves |
| Restricted permissions | None requested. Say so |

The notification listener declaration is the one reviewers scrutinise. The honest answer
is short: the app's core function is a voice assistant that answers "what messages did I
get?", which cannot be done any other way.

### What to expect

A voice assistant with notification access and contacts will draw a manual review. Things
that help:

- No `QUERY_ALL_PACKAGES`, no `SEND_SMS`, no `READ_SMS`, no `READ_CALL_LOG`, no
  accessibility service. None of them are requested, and the reviewer can see that.
- A privacy policy that matches [PRIVACY.md](PRIVACY.md).
- The in-app Privacy Center, which demonstrates the deletion path a reviewer will look for.

### Still to do before listing

- [ ] Choose and add a licence
- [ ] Host a privacy policy at a public URL
- [ ] Feature graphic and screenshots (Home, Privacy Center, Commands)
- [ ] Decide whether wake-word listening should be behind an in-app disclosure prompt —
      it is off by default and the Settings text states the battery cost, which should be
      sufficient, but a prominent-disclosure dialog is the safer reading of the policy

## Versioning

`versionCode` and `versionName` are in `app/build.gradle.kts`. Bump both. Play rejects a
`versionCode` that is not strictly greater than the last one uploaded.
