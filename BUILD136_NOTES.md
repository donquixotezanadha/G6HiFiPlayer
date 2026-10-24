# Build 1.36 — Framework / native library probe

Adds FrameworkProbe, run at the start of every play: reports whether Java treats encoding 21 (24-bit packed) as valid, whether libaudioclient.so can be dlopen'ed by the app, and the exported android::AudioTrack constructor/set symbols. These facts decide the C++ implementation (Build 1.37). Playback behavior is otherwise identical to 1.35.
