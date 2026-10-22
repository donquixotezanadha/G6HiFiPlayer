plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "id.g6hifiplayer.directprobe"
    compileSdk = 36

    defaultConfig {
        applicationId = "id.g6hifiplayer.directprobe"
        minSdk = 28
        targetSdk = 28
        versionCode = 121
        versionName = "1.21.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
