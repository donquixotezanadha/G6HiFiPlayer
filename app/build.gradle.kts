plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "id.g6hifiplayer"
    compileSdk = 36
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "id.g6hifiplayer.enginelab"
        minSdk = 28
        targetSdk = 35
        versionCode = 138
        versionName = "1.38.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
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
    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-common:1.10.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-audio:8.1.8")

    // Build 1.38: native OpenSL ES packed-24 output lives in src/main/cpp (libg6sles.so).
    // Build 1.37: lifts Android 9 hidden-API restrictions so encoding 21 (24-bit packed) can be forced.
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")

    // Explicit runtime dependency for FFmpegKitConfig's referenced class.
    implementation("com.arthenica:smart-exception-java:0.2.1")
}
