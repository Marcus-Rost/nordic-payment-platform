// JRPay Android client — standalone Gradle project.
// Kept separate from the JVM backend build (which lives at
// implementation/settings.gradle.kts) so the Android Gradle Plugin's
// requirements don't pollute the Spring services.

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

rootProject.name = "jrpay-android"
include(":app")
