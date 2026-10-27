# Build 1.61 (main player): pure 24-bit native DIRECT

## Why
Build 1.60 dumps (27 Oct) proved the native DIRECT output works (44.1 kHz and 96 kHz, HAL PCM_24_BIT_PACKED, own pid, mixer idle). But a real 24-bit/96 kHz file showed "PCM 16-bit" under the progress bar: Android's FLAC decoder hands 16-bit PCM to the player even for 24-bit FLAC, so the low 8 bits were lost before our output.

## What changed
- `Pcm24Transcoder` + `Pcm24DataSource`: for FLAC deeper than 16 bits (read from the FLAC header), the player's data source serves a 24-bit WAV made by FFmpeg (`-c:a pcm_s24le`, no -ar, no -ac, no filters: no resampling, no dither, no gain). The WAV is then read by Media3's WAV extractor as raw 24-bit PCM, without any MediaCodec. After decoding, the WAV header is checked (same rate, same channel count, 24-bit); if not, playback fails instead of playing a changed signal. Cache keeps the newest 4 files. 16-bit FLAC and WAV are played as they are; MP3/AAC etc. go through MediaCodec as before (lossy, nothing to preserve).
- `DirectAudioOutputProvider` is now the ONLY output (no more normal AudioTrack fallback, no TrackingAudioOutputProvider). Integer PCM 16/24/32-bit mono/stereo 8-192 kHz goes to the native track (24-bit packed, flags DIRECT, source rate). Float PCM is refused so Media3 never asks the decoder for float. If the native track cannot be opened (retries for 1.5 s while the previous track closes) playback shows an error.
- `ExclusiveAudioFocus`: holds AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE while playing (the system does not play notification sounds, other media apps are told to stop). A "duck" request is ignored (no software volume exists). A call/alarm pauses playback; it resumes after a transient loss and stays paused after a permanent one. Released on Pause, end and Close.
- Hardware volume: no software gain anywhere (`setVolume` ignored, player volume 1.0, native track at 0 dB). The volume keys change the music stream volume, which the HAL applies. No EQ/effects are created by the app.
- Removed old code: normal AudioTrack output and its tracking/route listeners/diagnostics, `DirectOutputSettings` ON/OFF switch and Setting card, FFmpeg 16-bit fallback decoder (`FfmpegAudioDecoder`), the unattached Audio/File info panels, LG configuration scan and diagnostics sharing, the single-file picker.
- Info line under the progress bar now shows source rate and bit depth (from the file header), the output rate and the PCM that reached the output, and the verdict: `BIT-PERFECT • NATIVE DIRECT • 24-bit packed` only when output is DIRECT, rate unchanged and depth not reduced; `DEPTH REDUCED TO 16-BIT` / `SAMPLE RATE CHANGED` otherwise.

## How to test
1. Headphones in the jack. Play the 96 kHz / 24-bit FLAC: wait for "Decoding to 24-bit PCM (no resampling)…" (a few seconds, only the first time per track), then the line should read `source 96.0 kHz / 24-bit → output 96.0 kHz • PCM 24-bit` with the green BIT-PERFECT verdict.
2. Play a 44.1 kHz / 16-bit file: `PCM 16-bit padded to 24-bit`, still BIT-PERFECT (16 = 16).
3. `adb shell dumpsys media.audio_flinger`: a DIRECT thread at the file's rate, HAL PCM_24_BIT_PACKED, with the player's pid; mixer threads idle.
4. Send yourself a notification / message while playing: no sound should come out. A phone call or alarm pauses the music.

## Known limits
- First play of each hi-res FLAC waits for FFmpeg (a few seconds); the next queue item is decoded in the background when ExoPlayer starts loading it (~50 s before the end), so gapless between hi-res tracks is likely but not guaranteed on slow decodes. Skipping to an undecoded track waits for its decode.
- Hi-res formats other than FLAC/WAV (ALAC 24-bit, WavPack, APE, DSD) are not converted; ALAC/others still pass through Android's 16-bit decoders (the info line will not claim bit-perfect for those unless the header was read).
- The system's "Volume listener for Music" effect (Qualcomm) sits on the DIRECT thread; it is part of the hardware-volume path and not under the app's control.
- Exclusive focus silences the system's notification sounds; alarms and calls still take over (and pause the player) by design.
