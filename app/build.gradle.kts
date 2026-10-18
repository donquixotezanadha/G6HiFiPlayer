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
        versionCode = 70
        versionName = "0.70.0"

        // This build targets the 64-bit ARM LG G6. Avoid bundling unused x86_64 native libraries.
        ndk {
            abiFilters += listOf("arm64-v8a")
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
    // Audio-only FFmpeg fallback: no video or HTTPS-input feature set is needed by this local-file player.
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-audio:8.1.7")
}
