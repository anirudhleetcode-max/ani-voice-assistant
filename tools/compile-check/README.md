# compile-check

Type-checks `app/src/main/kotlin` **without an Android SDK**.

```bash
cd tools/compile-check && gradle compileKotlin
```

## What this is

It compiles every Android source file against:

- **the real Android framework** — `org.robolectric:android-all`, the genuine `android.jar`
  for API 35, published to Maven Central;
- **the real Compose API** — JetBrains' Compose Multiplatform, built from the same
  `androidx.compose.*` source and published to Maven Central;
- **hand-written stubs** in `stubs/` for the AndroidX libraries that exist only on Google
  Maven: activity, core, lifecycle, navigation, datastore, security-crypto.

It exists because the environment this project was first built in had `dl.google.com`
blocked, so `:app:assembleDebug` could not run at all. Rather than ship unverified Kotlin,
this harness compiles it against the real APIs. It found a genuine type error on its first
run that static analysis had missed.

## What this is not

It is **not** a build. No APK, no `aapt2`, no resource merging, no `d8`, no R8, no lint.
Those still need the SDK. Use `./gradlew :app:assembleDebug`.

Treat the stubbed packages as weaker evidence than the rest: a signature mismatch there is
checked against the stub, not the real library. The Android framework and Compose halves
are genuine.

## Keeping it working

If the app starts using a new AndroidX API, the harness will fail with an unresolved
reference. Add the signature to the matching file in `stubs/` — copying the real
signature from the AndroidX source — rather than working around it in app code.
