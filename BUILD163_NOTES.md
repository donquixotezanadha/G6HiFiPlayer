# Build 1.63.0

## Why (28 Oct)
The first 1.63 attempt (made outside this line) failed on the phone and the APK grew from 14 MB to 78 MB. Cause, read from the diff against 1.62:
- `externalNativeBuild` / CMake / `ndkVersion` / `abiFilters arm64-v8a` were removed from `app/build.gradle.kts`: the native DIRECT output (`libg6direct.so`) was no longer built, so `System.loadLibrary("g6direct")` fails at run time (the Kotlin code still calls it; it only compiles, it does not work).
- `ffmpeg-kit-audio` was swapped for `ffmpeg-kit-full` (all codecs, all ABIs): that is the size growth.

## What changed
- `app/build.gradle.kts` is again exactly the 1.62 configuration (NDK 26.1.10909125, CMake 3.22.1, arm64-v8a only, `ffmpeg-kit-audio`, `media3-datasource`), only versionCode 163 / versionName 1.63.0.
- Workflow `build-163-claude.yml` keeps the new SDK-licence handling and installs `ndk;26.1.10909125` and `cmake;3.22.1` again.
- Source code under `app/src` is unchanged from 1.62 in this first step.
