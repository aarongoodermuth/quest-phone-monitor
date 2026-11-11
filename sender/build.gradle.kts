plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aarongoodermuth.questphonemonitor.sender"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.aarongoodermuth.questphonemonitor.sender"
        minSdk = 29
        targetSdk = 34
        versionCode = 4
        versionName = "0.2.3"
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
}

dependencies {
    // No dependencies for v0.2 on purpose: plain Activity + XML layout,
    // MediaProjection + ImageReader + ServerSocket + NsdManager are all
    // Android built-ins. Keeps the build fast and the APK small.
}
