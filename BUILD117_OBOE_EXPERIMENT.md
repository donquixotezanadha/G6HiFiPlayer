# Build 1.17 — isolated Oboe/AAudio experiment

## Baseline and isolation
- Based on `build-116-album-art-fastpath` at commit `510229ca0adf4a7ffa7507fab071ce937fc7f025`.
- The main `id.g6hifiplayer` app only advances to version 1.17.0; its Media3 playback implementation, notification, and album-art code are not replaced by this experiment.
- A separate `id.g6hifiplayer.oboeprobe` APK is built so the existing player can remain installed and untouched.

## What the probe tests
- Requests Oboe output at 44.1, 48, 96, or 192 kHz.
- Requests low-latency Float PCM stereo.
- Attempts Exclusive sharing first. If the exclusive open fails, it explicitly retries Shared and logs both results; a separate Shared-only control is also available.
- Reports actual stream sample rate, format, channel count, sharing mode, performance mode, buffer size, frames per burst, state, and open/start/stop/close results.
- Emits a quiet 440 Hz test tone only. It is a path-opening diagnostic, not music playback and not a bit-perfect claim.

## Device test
1. Install the separate **G6 Oboe Probe** APK on the LG G6 Android 9.
2. Connect wired headphones and select 96000 Hz.
3. Tap **Open EXCLUSIVE (fallback to SHARED)**, then **Start 440 Hz test tone**.
4. Copy the diagnostics shown in the app and capture `adb shell dumpsys media.audio_flinger` while the tone is running.
5. Repeat with **Open SHARED only**, then 44100/48000 Hz. Test 192000 only if the earlier tests are stable.
6. Stop the tone and close the stream before leaving the probe.

## Interpretation limits
- Oboe's requested or actual Exclusive mode is not proof of AudioFlinger DIRECT, a specific LG Quad DAC backend, or bit-perfect output.
- Compare the reported stream properties with AudioFlinger thread type/rate and the wired route. Keep each result paired with the selected rate and sharing mode.
- This prototype deliberately does not connect to Media3, alter the main player's playback service, or decode local music. Only integrate a PCM source/ring buffer into the player after the device tests and playback/pause/seek/track-change/fallback behavior are independently verified.
