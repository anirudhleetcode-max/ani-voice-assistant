import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * Type-checks the Android app sources without an Android SDK.
 *
 * This is not a substitute for `./gradlew :app:assembleDebug` — it produces no APK and
 * never runs aapt2, d8 or R8. What it does is compile every file under
 * `app/src/main/kotlin` against the *real* Android framework API and the *real* Compose
 * API, which catches the overwhelming majority of what a first Android build would catch:
 * wrong signatures, bad lambda arities, type errors, missing imports.
 *
 * Where the APIs come from:
 *   - Android framework: org.robolectric:android-all, which is the genuine android.jar
 *     for API 35, published to Maven Central.
 *   - Compose: JetBrains' Compose Multiplatform, which is the same androidx.compose.*
 *     source, published to Maven Central.
 *   - Everything else AndroidX (activity, core, lifecycle, navigation, datastore,
 *     security-crypto) is Google-Maven-only, so `stubs/` declares the exact slice the app
 *     uses. Those are hand-written signatures: a mismatch there is verified against the
 *     stub, not against the real library. Treat them as weaker evidence than the rest.
 *
 * Run:  cd tools/compile-check && gradle compileKotlin
 */
plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

sourceSets {
    main {
        kotlin.srcDirs("stubs", "../../app/src/main/kotlin")
    }
}

dependencies {
    // The genuine Android 15 (API 35) framework jar.
    compileOnly("org.robolectric:android-all:15-robolectric-12650502")

    // The genuine androidx.compose.* API, via Compose Multiplatform.
    implementation("org.jetbrains.compose.runtime:runtime-desktop:1.7.3")
    implementation("org.jetbrains.compose.ui:ui-desktop:1.7.3")
    implementation("org.jetbrains.compose.foundation:foundation-desktop:1.7.3")
    implementation("org.jetbrains.compose.material3:material3-desktop:1.7.3")
    implementation("org.jetbrains.compose.material:material-icons-extended-desktop:1.7.3")

    implementation("com.ani:core-nlu")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
