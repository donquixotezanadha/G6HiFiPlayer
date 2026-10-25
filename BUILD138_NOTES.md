# Build 1.38 — Native OpenSL ES 24-bit packed output (Engine Lab)

Why: the UAPP dump shows its track as format 0x6 (AUDIO_FORMAT_PCM_24_BIT_PACKED), flags 0, on the direct_pcm DIRECT output thread at 96 kHz. Java AudioTrack on Android 9 rejects encoding 21 (Builder, legacy ctor), and libaudioclient/libmedia cannot be dlopen'ed (linker namespace), so builds 1.35-1.37 could not request that format.

Idea: libOpenSLES.so is a public NDK library. Its 24-bit signed PCM source (SL_ANDROID_DATAFORMAT_PCM_EX, SIGNED_INT, containerSize 24, or plain SL_DATAFORMAT_PCM 24-bit) is mapped inside wilhelm to AUDIO_FORMAT_PCM_24_BIT_PACKED (checked in AOSP android-9.0.0_r61 src/android/android_sles_conversions.h). Wilhelm creates the native AudioTrack itself, with flags NONE, which is the same track UAPP shows in the dump. No hidden Java API is needed.

Change:
- app/src/main/cpp/g6sles.cpp + CMakeLists.txt (arm64-v8a): OpenSL ES engine, output mix, buffer-queue player (16 buffers), blocking write, frames-played counter.
- SlesPacked24.kt: JNI wrapper.
- StandalonePcmPlayer: for 24-bit WAV sources it tries the native path first (source bytes copied unchanged, no float or 16-bit conversion). If the native open fails, the 1.37 AudioTrack/reflection attempts and then the float path run exactly as before.
- Diagnostics: SLES_PACKED24_OPEN (OK or the exact OpenSL result code, for both format attempts), SLES_PACKED24_PLAY_CALLED, SLES_PACKED24_PROGRESS, PLAYBACK_RESULT path=SLES_PACKED24.

Verify while a 96 kHz / 24-bit file plays:
  adb shell dumpsys media.audio_flinger
Expect an "Output thread ... (DIRECT)" with Sample rate 96000 and HAL format PCM_24_BIT_PACKED, and one track with format 00000006.

Not verified yet: that the G6 policy picks direct_pcm for this track exactly as for UAPP. If SLES_PACKED24_OPEN is OK but the dump shows a MIXER thread, send the dump.
