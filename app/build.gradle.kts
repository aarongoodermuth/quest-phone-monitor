plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aarongoodermuth.questphonemonitor"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.aarongoodermuth.questphonemonitor"
        minSdk = 29
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"

        // Quest 3 (Snapdragon XR2 Gen 2) is ARM64 only. No x86 libs.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // No dependencies for v0.1 Hello World on purpose:
    // plain Activity + XML layout, so the build stays fast and the
    // APK stays small. Streaming deps (ExoPlayer / WebRTC) come in v0.2+.
}
