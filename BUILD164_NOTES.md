# Build 1.64 (main player): hi-res FLAC always plays, and a failure shows its reason

## Analysis (28 Oct) of 1.62 and the 1.63 attempt
- 1.63 (branch claude-build-163-player, first commit by another tool) was NOT a fix: `app/src` was identical to 1.62, but `app/build.gradle.kts` lost the native build (`externalNativeBuild`/CMake/NDK/`abiFilters`), so `libg6direct.so` was never built and `System.loadLibrary("g6direct")` fails at run time; it also swapped `ffmpeg-kit-audio` for `ffmpeg-kit-full` (APK 14 MB -> 78 MB). Do not build from that state. 1.64 starts from 1.62.
- Media3 1.10.1 sources were read (CI dump, branch ci-logs, media3src/): the 24-bit path itself is consistent. `DefaultAudioSink.getFormatSupport` maps 24-bit PCM to float and asks our provider (accepts float); `MediaCodecAudioRenderer` takes raw PCM without a codec when the sink supports it; `WavHeaderReader` accepts the WAVE_FORMAT_EXTENSIBLE header FFmpeg writes for pcm_s24le (validBits 24, channel mask 3). So the sink, extractor and output are not where a 24-bit file is lost.
- What 1.61 and 1.62 have in common, and what no 16-bit file goes through, is the FFmpeg conversion (`Pcm24Transcoder`). Until 1.63 any failure there ended playback with an error, and the reason was never visible. The phone result "cannot decode 24-bit / no sound" fits a failing FFmpeg step.

## What changed
- `Pcm24Transcoder.servedUri`: if the FFmpeg conversion throws, the original file is played (Android's own decoder, float output ON, which may give 16 or 24-bit) instead of failing. A failed track is remembered, so FFmpeg is not started again on every seek.
- `Pcm24Transcoder.lastFailure`: the reason of the last failed conversion; logged (`DirectOutputState`) and shown as a third line under the info line: `24-bit conversion failed: <reason>`. The verdict stays honest: `DEPTH REDUCED TO 16-BIT` (orange) when only 16 bits arrive, green only when the real depth reaches the DIRECT output.
- Info line may now use 4 lines.
- `app/build.gradle.kts` = 1.62 configuration (NDK 26.1.10909125, CMake 3.22.1, arm64-v8a, ffmpeg-kit-audio), versionCode 164 / 1.64.0. CI installs NDK and CMake and fails if `libg6direct.so` is missing from the APK.

## How to test
1. Install, play the 96 kHz / 24-bit FLAC.
2. If it works: `source 96.0 kHz / 24-bit -> output 96.0 kHz`, green BIT-PERFECT.
3. If it plays but the line is orange: read the third line `24-bit conversion failed: ...` and send a screenshot; that is the real cause.
4. If it still gives no sound: send the two lines under "Playback failed".

## Next
- If FFmpeg is the failing part: the reason on screen decides (e.g. missing codec in ffmpeg-kit-audio -> use another package, or a different command line).
