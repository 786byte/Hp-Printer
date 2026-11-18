pluginManagement {
    repositories {
        google()
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
rootProject.name = "JustPrintIt"
include(":core")
// Pass -PcoreOnly to build/test the pure-Kotlin printer logic without an Android SDK.
if (!providers.gradleProperty("coreOnly").isPresent) include(":app")
