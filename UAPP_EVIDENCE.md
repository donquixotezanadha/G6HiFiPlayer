# UAPP evidence (dumps 26 Okt 2026, 11:41, headphone di jack 3.5 mm)
UAPP setting: internal audio driver = "hires direct driver"; internal hires driver, bit perfect mode ON; volume = hardware. File: lagu 96 kHz / 24-bit.

## Apa yang terlihat
- dumpsys media.audio_flinger: thread "AudioOut_45", type 1 (DIRECT), 96000 Hz, HAL format PCM_24_BIT_PACKED (0x6), AudioStreamOut flags 0x1 = AUDIO_OUTPUT_FLAG_DIRECT, device WIRED_HEADPHONE. Satu track: client pid UAPP, format 0x6, 96000 Hz, track flags 0x000, FrmCnt 38400, L/R dB 0.
- dumpsys media.audio_policy: output "direct_pcm" (flags 0x0001 = DIRECT saja) ada untuk Wired Headphones: PCM_16 / PCM_8_24 / PCM_24_BIT_PACKED, 8 kHz..192 kHz, stereo dan multichannel. "Output 69": Flags 1, 96000 Hz, format 6. Volume stream MUSIC (index -20.65 dB) dikelola policy pada output ini (volume hardware lewat HAL).
- dumpsys audio (PlaybackActivityMonitor): track DIRECT UAPP TIDAK terdaftar sebagai player (tidak ada piid). Yang terdaftar hanya satu AudioTrack Java singkat (probe 48 kHz PCM16, flags 0x0, masuk deep-buffer mixer) tepat sebelum track DIRECT. Engine Lab kita (Java AudioTrack dan OpenSL ES) selalu terdaftar. => track DIRECT UAPP dibuat lewat jalur native yang tidak lewat Java AudioTrack maupun OpenSL ES.

## Kesimpulan
- Policy Android 9 hanya memilih direct_pcm untuk PCM stereo <= 192 kHz kalau permintaan membawa flag DIRECT secara eksplisit. Java AudioTrack publik, OpenSL ES, dan AAudio (P) tidak menyediakan flag itu. Matrix 1.56: 14 varian masuk mixer 48k/16-bit, HW_AV_SYNC gagal dibuat.
- Jadi untuk meniru UAPP, app perlu klien native AudioTrack/AudioFlinger dengan flags=DIRECT. dlopen libaudioclient/libmedia dari app gagal (1.36, linker namespace).
- Belum diketahui: bagaimana UAPP memanggilnya (library apa). Bukti yang diminta: daftar .so milik UAPP (nama saja), props audio vendor (getprop), logcat saat UAPP mulai memutar.

## Update (1.57): how UAPP reaches native AudioTrack
libauaudioutils.so AudioTrackNative::init: dlopen(NULL, 0x106) then dlsym of android::AudioTrack symbols (fallback /system/lib64/libmedia.so, /system/lib64/libutils.so). Log: set() streamType 3, 96000, 0x6, 0x3, frameCount 38400, flags 1, session 1425 -> direct_pcm -> DIRECT thread -> HAL is_direct_pcm_track=true.
