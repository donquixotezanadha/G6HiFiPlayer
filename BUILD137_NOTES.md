# Build 1.37 — Forced 24-bit packed output (Engine Lab)

Evidence from 1.36 diagnostics (LG G6, Android 9): dlopen of libaudioclient/libmedia/libaudioflinger/libaudiopolicyservice is blocked by linker namespaces, so a native C++ AudioTrack path is not possible. AudioFormat.Builder.setEncoding(21) and the legacy constructor both throw "Invalid encoding 21". Only PCM16 and PCM_FLOAT are accepted publicly.

Change: PACKED24 attempts C and D. Hidden-API exemption (LSPosed HiddenApiBypass), build a normal 16-bit AudioFormat, overwrite its private mEncoding with 21, then open the AudioTrack through the 5-argument constructor (C) or by injecting the format into AudioTrack.Builder (D). If the native layer accepts it, source 24-bit bytes are copied unchanged (no float conversion) and the G6 policy should open the DIRECT output like UAPP. If it fails, the float path is used and the exact reason is logged.

Also fixed: version labels in Engine Lab (title, APP_START, export subject) now say 1.37; player no longer calls Builder.setEncoding(21) when the packed track is already open.

Verify on a PC (Monday): adb shell dumpsys media.audio_flinger, look for DIRECT output thread at 96000 Hz with PCM_24_BIT_PACKED.
