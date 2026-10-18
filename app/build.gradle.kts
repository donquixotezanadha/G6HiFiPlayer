plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "id.g6hifiplayer"
    compileSdk = 36

    defaultConfig {
        applicationId = "id.g6hifiplayer"
        minSdk = 28
        targetSdk = 35
        versionCode = 55
        versionName = "0.55.0"
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
    // Build 37 audio fallback: decode unsupported formats to 24-bit PCM WAV only after Media3 fails.
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.7")
}
