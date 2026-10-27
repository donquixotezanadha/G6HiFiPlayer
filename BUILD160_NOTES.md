# Build 1.60 (main player): native DIRECT output

## Why
Engine Lab 1.58/1.59 proved on the G6: a native android::AudioTrack (dlopen(NULL) + dlsym, flags = DIRECT, format 24-bit packed) gets its own DIRECT output thread at every rate 44.1-192 kHz, while 16-bit requests fall back to the mixer. So the player now always opens the native track as 24-bit packed at the source rate. 16-bit sources are padded to 24-bit (low byte 0 = same value, lossless).

## What changed (on top of player Build 1.55)
- `app/src/main/cpp/g6direct.cpp` + CMake (NDK 26.1.10909125, arm64-v8a): native track wrapper (open / start / pause / flush / stop / non-blocking write / getPosition / close).
- `NativeDirectStream.kt`: JNI wrapper, `DirectOutputSettings` (ON/OFF, default ON, prefs `g6_output`), `DirectOutputState` (status + short history for the info lines).
- `DirectAudioOutput.kt`: Media3 `AudioOutput` that feeds the native track. 24-bit copied as is, 16-bit padded, 32-bit int keeps top 24 bits, float scaled by 2^23. No gain, no dither, `setVolume` ignored (hardware / stream volume). Playback position comes from AudioTrack::getPosition, with a clock fallback.
- `DirectAudioOutputProvider.kt`: wraps the existing provider. Mono/stereo PCM 8-192 kHz goes native; everything else, and any failure to open the native track, uses the normal Media3 AudioTrack output automatically.
- `PlaybackKeepAliveService`: sink now uses the new provider.
- Setting: new "NATIVE DIRECT OUTPUT" ON / OFF. Applies the next time playback starts after Close.
- Now Playing info line shows `NATIVE DIRECT • 24-bit packed` (green) when the native output is active; the audio info text has a "Native DIRECT output" line with the AudioFlinger output (AudioOut_xx), rate, flags, session.
- Media3 API used: AudioOutputProvider / AudioOutput from media3-exoplayer 1.10.1 (dumps in branch ci-logs, workflow inspect-media3.yml).

## How to test
1. Headphones in the 3.5 mm jack, install the APK, play a 44.1 kHz / 16-bit file, then a 96 kHz / 24-bit one. Now Playing should show the green `NATIVE DIRECT` line with the file's rate.
2. Proof while playing: `adb shell dumpsys media.audio_flinger > af_player.txt`. Good = a DIRECT thread (type 1) at the file's rate, HAL PCM_24_BIT_PACKED, with the player's pid.
3. If there is no sound or the app misbehaves: Setting > NATIVE DIRECT OUTPUT > OFF, press Close in the notification, play again.

## Known limits / next
- If the platform decoder hands 16-bit PCM to the sink for a 24-bit FLAC, the sink gets 16-bit and nothing can restore the lost bits; check the line under the cover (`PCM 16-bit padded to 24-bit` vs `PCM 24-bit`). A decode path that keeps 24-bit (FFmpeg) would be the next step.
- One DIRECT track at a time: when the next track has another sample rate the old output is released first (Media3 does this); a very quick overlap would make the policy demote the second track to the mixer.
- Gapless between tracks with different sample rates reopens the output (short gap).
