# HANDOFF (read this first when asked to "lanjutkan")

Goal: personal bit-perfect player for LG G6 (Android 9, no root, 3.5 mm jack), no EQ. User is not a coder: deliver ready APKs via GitHub Actions + simple steps, in Indonesian. Work in small steps: commit + push + build after each stage (user's usage limit cuts sessions).

## Branches
- claude-build-134: stable Build 91 + instant album art (cache hit + neighbour prefetch). Main player. Done, built OK.
- claude-build-135: Engine Lab + try 24-bit packed AudioTrack (encoding 21) via Builder / legacy ctor. Built OK.
- claude-build-136: 1.35 + FrameworkProbe (isPublicEncoding, dlopen libaudioclient.so, AudioTrack symbols). Built OK.
- claude-build-137: 1.36 + forced 24-bit packed AudioTrack via reflection (attempts C/D, HiddenApiBypass). Built OK.
- claude-build-138: 1.37 + native OpenSL ES 24-bit packed output (NDK, libg6sles.so, SlesPacked24.kt). CURRENT HEAD for audio work. Not yet confirmed to build/run on device.

- claude-build-156-engine: 1.38 + route matrix (BUILD156_NOTES.md). 1.38 dump showed MIXER/DEEP_BUFFER 48k/16-bit, NOT direct.

## Evidence (UAPP dump, G6_FULL_DUMP.txt)
UAPP opens DIRECT output thread: 96000 Hz, PCM_24_BIT_PACKED, WIRED_HEADPHONE, track format 0x6. Mixer is fixed 48 kHz/16-bit. direct_pcm profile supports 16-bit, 8.24, 24-bit packed up to 192 kHz.

## Findings from 1.36 diagnostics (user ran it)
- dlopen of libaudioclient/libmedia/libaudioflinger/libaudiopolicyservice FAILED (linker namespace) -> native C++ AudioTrack path is NOT possible. Do not retry it.
- Builder and legacy ctor reject encoding 21 ("Invalid encoding 21"); getMinBufferSize(21) = -2. Public: only PCM16 and PCM_FLOAT. Float path plays fine (no underruns).

## Waiting on user
Engine Lab 1.38 diagnostics export: look for SLES_PACKED24_OPEN (OK or result codes) and PLAYBACK_RESULT path=SLES_PACKED24, then dumpsys DIRECT thread. Older: 1.37 export: look for PACKED24_HIDDEN_API_EXEMPTION, PACKED24_FORCED_FORMAT, PACKED24_ATTEMPT=C/D lines. dumpsys from PC not possible until Monday (look for DIRECT thread, 96000 Hz, PCM_24_BIT_PACKED).

## Next steps
1. If C or D = OK: confirm DIRECT with dumpsys, then merge the packed path into the main player (from 1.34), keep album art fix.
2. If both fail: read the exact exception (likely audioParamCheck / JNI), then try 16-bit at source rate (direct_pcm) or other routes.
3. Polish: bit-perfect indicator, format choice, stability. No EQ.

## Build 1.38 note
AAudio is NOT an option on Android 9 (only I16/float; PCM_I24/I32 arrived in API 31). Do not suggest it again. OpenSL ES 24-bit (PCM_EX signed 24) is the route being tested.
