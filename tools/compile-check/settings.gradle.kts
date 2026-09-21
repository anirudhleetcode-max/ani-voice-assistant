pluginManagement {
    repositories { mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral() }
}
rootProject.name = "compile-check"
includeBuild("../../core-nlu")
