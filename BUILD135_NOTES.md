# Build 1.35 — 24-bit packed direct-format attempt (Engine Lab)

Evidence (UAPP dump, LG G6, Android 9): the DIRECT output thread runs at 96 kHz, AUDIO_FORMAT_PCM_24_BIT_PACKED on AUDIO_DEVICE_OUT_WIRED_HEADPHONE (direct_pcm profile), and UAPP's client track format is 0x6 (24-bit packed). The 48 kHz/16-bit mixer is bypassed.

Change: for 24-bit sources the player first tries to open an AudioTrack with encoding 21 (ENCODING_PCM_24BIT_PACKED) at the source sample rate via (A) AudioTrack.Builder and (B) the legacy constructor. WAV 24-bit little-endian samples are copied unchanged (no float/16-bit conversion). If both are rejected the old float path is used and the reason is logged as PACKED24_ATTEMPT=... lines.

Verify (PC with adb, while a 96 kHz 24-bit file plays):
  adb shell dumpsys media.audio_flinger
Look for an "Output thread ... (DIRECT)" with Sample rate 96000 and HAL format PCM_24_BIT_PACKED, and the track list showing format 00000006.
