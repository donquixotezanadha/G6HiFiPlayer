plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "id.g6hifiplayer"
    compileSdk = 36
    ndkVersion = "26.1.10909125"

    signingConfigs {
        getByName("debug") {
            storeFile = file("hifi-debug.keystore")
            storePassword = "hifi-debug"
            keyAlias = "hifi"
            keyPassword = "hifi-debug"
        }
    }

    defaultConfig {
        applicationId = "id.hifi.player"
        minSdk = 28
        targetSdk = 35
        versionCode = 96
        versionName = "2.7"

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
    implementation("androidx.media3:media3-datasource:1.10.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
}
