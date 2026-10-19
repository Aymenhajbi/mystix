// Atelys Mobile WMS (ADR-0018, ADR-0019).
// device-hub-core is plain Kotlin/JVM: it builds and is tested without the Android SDK.
// The Android application module is added once the Android SDK is available on the build machine.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "atelys-mobile"

include(":device-hub-core")
