// Atelys Mobile WMS (ADR-0018, ADR-0019).
// device-hub-core is plain Kotlin/JVM: it builds and is tested without the Android SDK.
// app is the Android application; it needs the Android SDK (local.properties or ANDROID_HOME).
pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "atelys-mobile"

include(":device-hub-core")
include(":app")
