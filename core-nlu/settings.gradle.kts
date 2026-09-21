/*
 * `core-nlu` is a standalone, platform-independent Gradle build.
 *
 * It is included into the Android build as a composite ("included") build so that
 * the whole language-understanding engine can be compiled and unit-tested on any
 * machine, with no Android SDK installed. That is deliberate: the Telugu/Tanglish
 * brain is the part of Ani that needs the most test iterations, and it must never
 * be blocked on an emulator or an SDK download.
 */
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "core-nlu"
