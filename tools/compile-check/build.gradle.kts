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

/*
 * Generate the R stub from the real resource files rather than maintaining it by hand.
 * A hand-written copy drifts the moment someone adds a string, and then the harness fails
 * for a reason that has nothing to do with the code under test.
 */
val generateResourceStub by tasks.registering {
    val resDirectory = layout.projectDirectory.dir("../../app/src/main/res")
    val outputDirectory = layout.buildDirectory.dir("generated/res-stub")
    inputs.dir(resDirectory)
    outputs.dir(outputDirectory)

    doLast {
        val names = mutableMapOf<String, MutableSet<String>>()

        // <string name="..."/>, <color name="..."/>, <style name="..."/>
        val declared = Regex("""<(string|color|style|bool|integer|dimen)\s+name="([^"]+)"""")
        resDirectory.asFile.walkTopDown().filter { it.extension == "xml" }.forEach { file ->
            declared.findAll(file.readText()).forEach { match ->
                names.getOrPut(match.groupValues[1]) { mutableSetOf() }.add(match.groupValues[2])
            }
        }
        // Drawables and mipmaps are files, not declarations.
        listOf("drawable" to "drawable", "mipmap" to "mipmap").forEach { (type, prefix) ->
            resDirectory.asFile.listFiles()
                ?.filter { it.isDirectory && it.name.startsWith(prefix) }
                ?.flatMap { it.listFiles()?.toList().orEmpty() }
                ?.forEach { names.getOrPut(type) { mutableSetOf() }.add(it.nameWithoutExtension) }
        }

        val target = outputDirectory.get().asFile.resolve("ResourceStub.kt")
        target.parentFile.mkdirs()
        target.writeText(
            buildString {
                appendLine("// Generated from app/src/main/res by the compile-check harness.")
                appendLine("@file:Suppress(\"unused\")")
                appendLine()
                appendLine("package com.ani.assistant")
                appendLine()
                appendLine("object R {")
                var id = 1
                names.toSortedMap().forEach { (type, entries) ->
                    appendLine("    object $type {")
                    // AGP turns "Theme.Ani" into "Theme_Ani"; match that.
                    entries.map { it.replace('.', '_').replace('-', '_') }
                        .distinct()
                        .sorted()
                        .forEach { entry ->
                            appendLine("        const val $entry = ${id++}")
                        }
                    appendLine("    }")
                }
                appendLine("}")
            }
        )
    }
}

sourceSets {
    main {
        kotlin.srcDirs("stubs", "../../app/src/main/kotlin")
        kotlin.srcDir(generateResourceStub.map { layout.buildDirectory.dir("generated/res-stub") })
    }
    // The app's own unit tests, so `gradle test` here actually runs them. Tests that need
    // a real Android runtime (Robolectric, instrumented) are excluded: they belong to
    // `./gradlew :app:testDebugUnitTest`.
    test {
        kotlin.srcDirs("../../app/src/test/kotlin")
    }
}

/*
 * Vosk and Porcupine ship as AARs, which a plain JVM project cannot consume. Both are on
 * Maven Central, so fetch them and unpack the classes.jar rather than committing binaries.
 */
val wakeEngineAars: Configuration by configurations.creating

val extractWakeEngineClasses by tasks.registering(Copy::class) {
    val destination = layout.buildDirectory.dir("wake-engine-classes")
    from(wakeEngineAars.elements.map { aars ->
        aars.map { zipTree(it.asFile).matching { include("classes.jar") } }
    })
    // Two AARs each contain a file called classes.jar, so give them distinct names.
    eachFile { path = "${file.parentFile.name}-classes.jar" }
    includeEmptyDirs = false
    into(destination)
}

dependencies {
    wakeEngineAars("com.alphacephei:vosk-android:0.3.75@aar")
    wakeEngineAars("ai.picovoice:porcupine-android:4.0.2@aar")
    compileOnly(files(extractWakeEngineClasses.map { it.destinationDir.listFiles().orEmpty().toList() }))
    // JNA comes through as a normal jar and Vosk's public API exposes it.
    compileOnly("net.java.dev.jna:jna:5.13.0")

    // The Compose compiler plugin runs over the test compilation too and refuses to work
    // without the runtime on the classpath, even though no test touches Compose.
    testCompileOnly("org.jetbrains.compose.runtime:runtime-desktop:1.7.3")

    testImplementation("junit:junit:4.13.2")
    // The microphone arbiter is a suspending API, so its tests need runTest. Same
    // version the app module uses, from Maven Central.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.ani:core-nlu")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testCompileOnly("org.robolectric:android-all:15-robolectric-12650502")

    // The genuine Android 15 (API 35) framework jar.
    compileOnly("org.robolectric:android-all:15-robolectric-12650502")

    /*
     * The genuine androidx.compose.* API, via Compose Multiplatform.
     *
     * compileOnly rather than implementation: Compose's *runtime* classpath pulls real
     * androidx artifacts from Google Maven, which is exactly what this harness exists to
     * do without. Nothing here is ever executed, so a compile-time-only classpath is all
     * that is needed — and it keeps `gradle test` resolvable.
     */
    compileOnly("org.jetbrains.compose.runtime:runtime-desktop:1.7.3")
    compileOnly("org.jetbrains.compose.ui:ui-desktop:1.7.3")
    compileOnly("org.jetbrains.compose.foundation:foundation-desktop:1.7.3")
    compileOnly("org.jetbrains.compose.material3:material3-desktop:1.7.3")
    compileOnly("org.jetbrains.compose.material:material-icons-extended-desktop:1.7.3")

    implementation("com.ani:core-nlu")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

tasks.test {
    // The Android framework jar is compile-only; anything needing it at runtime is out of
    // scope for this harness by design.
    testLogging { events("passed", "failed") }
}
