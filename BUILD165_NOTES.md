# Build 1.65 (main player): FFmpeg removed, real-24-bit check

## Result of the Build 1.64 phone test (28 Oct, 96 kHz / 24-bit FLAC, Muse - Apocalypse Please)
- Plays. Info line green: `source 96.0 kHz / 24-bit -> output 96.0 kHz, PCM 24-bit, BIT-PERFECT, NATIVE DIRECT, 24-bit packed`.
- `dumpsys media.audio_flinger`: DIRECT thread AudioOut_6D, 96000 Hz, HAL PCM_24_BIT_PACKED, track of the player's pid (26450), flags DIRECT; the mixer threads are idle (standby). Only the "Volume listener for Music" effect sits on the DIRECT thread (hardware-volume path, as known).
- Third line: `24-bit conversion failed: NoClassDefFoundError`. So the FFmpeg conversion (1.61-1.63) never worked on this phone (a class of ffmpeg-kit is missing at run time). The 1.64 fallback played the file through Android's own FLAC decoder with float output ON, and that already delivers the 24-bit samples to the DIRECT output.

## What changed
- FFmpeg path removed (`Pcm24Transcoder`, `Pcm24DataSource`, prefetch, the `ffmpeg-kit-audio` dependency). `AudioSniffer` keeps only the FLAC/WAV header reader for the info line. No more copy of each FLAC into the cache; smaller APK.
- New check that the data is really 24-bit: the converted samples are counted; if samples with a non-empty low byte appear (>= 200) the line says `BIT-PERFECT • NATIVE DIRECT • real 24-bit data` (green). If 400,000 non-zero samples pass and the low 8 bits are always empty, the line turns orange: `24-BIT CARRIER, BUT LOW 8 BITS ARE EMPTY` (the decoder gave 16-bit content). Not applied to 16-bit sources. The check restarts when a new DIRECT output opens.
- Info line back to 3 lines max. `app/build.gradle.kts`: versionCode 165 / 1.65.0, native build config unchanged (NDK/CMake, arm64-v8a).

## How to test
1. 96 kHz / 24-bit FLAC: after a few seconds the second line should say `real 24-bit data` (green). Also test a 44.1 kHz / 24-bit FLAC and a 16-bit FLAC (`24-bit packed`, green).
2. Skip to the next hi-res track: no error, no pause for conversion.
3. If it says `LOW 8 BITS ARE EMPTY` for a file that is really 24-bit: the Android decoder is truncating; send a screenshot.

## Known limits
- ALAC/WavPack/APE/DSD still depend on what Android's decoders deliver (the info line shows the depth that arrives).
