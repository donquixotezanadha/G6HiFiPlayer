# Build 1.57 (Engine Lab)
Evidence (UAPP 7.1.2.9, libauaudioutils.so + logcat): UAPP's AudioTrackNative does dlopen(NULL, RTLD_NOW|GLOBAL|NOLOAD) and dlsym()s android::AudioTrack (C1Ev, set, start, stop, write, ...) from symbols already loaded in the process; fallback dlopen("/system/lib64/libmedia.so"). set(): streamType 3, 96000, format 0x6, mask 0x3, frameCount 38400, flags 1 (DIRECT), transfer CALLBACK -> policy picks direct_pcm, AudioFlinger opens a DIRECT thread (output 109), HAL "is_direct_pcm_track=true".
1.36 probe only tried System.load by path (blocked) and never dlsym on dlopen(NULL), so "native path impossible" was wrong.
Buttons: 4A symbol probe, 4B native DIRECT 96k/24-bit tone, 4C native DIRECT 48k/16-bit control. Uses TRANSFER_SYNC + blocking write.
