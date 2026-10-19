plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "ma.mystix.atelys.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "ma.mystix.atelys.mobile"
        // Android 8.0. The CK67 ships with Android 14 (API 34); older Honeywell terminals are not tested.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":device-hub-core"))
}
