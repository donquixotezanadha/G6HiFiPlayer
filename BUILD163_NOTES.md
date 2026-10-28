# Build 1.63.0

## Build configuration
- Android application version: `1.63.0`
- `versionCode`: `163`
- Android compile SDK: `36`
- Android target SDK: `35`
- Minimum SDK: `28`
- Java source/target compatibility: `17`
- Kotlin JVM target: `17`

## CI build
The workflow `.github/workflows/build-163-claude.yml` builds the debug APK with Gradle 8.9, Android platform 36, and Android build-tools 36.0.0. It verifies that `app/build/outputs/apk/debug/app-debug.apk` exists and is non-empty, then uploads it as `G6HiFiPlayer-Build163-v1.63.0`.

This is a CI packaging/version checkpoint. A successful build confirms compilation and packaging only; it does not claim device playback validation or bit-perfect output.
