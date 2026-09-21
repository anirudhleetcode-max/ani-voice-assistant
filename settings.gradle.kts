pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Ani"

include(":app")

// The language engine is a standalone JVM build so it can be compiled and tested with
// no Android SDK present. Gradle substitutes the published coordinates
// (com.ani:core-nlu) for this build automatically wherever :app depends on them.
includeBuild("core-nlu")
