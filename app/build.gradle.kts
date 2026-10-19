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
        versionCode = 86
        versionName = "0.86.0"

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
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-audio:8.1.7")
}
