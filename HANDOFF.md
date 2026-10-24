# HANDOFF (read this first when asked to "lanjutkan")

Goal: personal bit-perfect player for LG G6 (Android 9, no root, 3.5 mm jack), no EQ. User is not a coder: deliver ready APKs via GitHub Actions + simple steps, in Indonesian. Work in small steps: commit + push + build after each stage (user's usage limit cuts sessions).

## Branches
- claude-build-134: stable Build 91 + instant album art (cache hit + neighbour prefetch). Main player. Done, built OK.
- claude-build-135: Engine Lab + try 24-bit packed AudioTrack (encoding 21) via Builder / legacy ctor. Built OK.
- claude-build-136: 1.35 + FrameworkProbe (isPublicEncoding, dlopen libaudioclient.so, AudioTrack symbols). Built OK. CURRENT HEAD for audio work.

## Evidence (UAPP dump, G6_FULL_DUMP.txt)
UAPP opens DIRECT output thread: 96000 Hz, PCM_24_BIT_PACKED, WIRED_HEADPHONE, track format 0x6. Mixer is fixed 48 kHz/16-bit. direct_pcm profile supports 16-bit, 8.24, 24-bit packed up to 192 kHz.

## Waiting on user
Engine Lab 1.36 diagnostics export (PACKED24_ATTEMPT, PROBE_JAVA_ENCODING_CHECK, PROBE_DLOPEN, PROBE_SYMBOL lines). dumpsys from PC not possible until Monday.

## Next steps
1. Read diagnostics -> Build 1.37: native C++ path (NDK) using the real exported AudioTrack symbols if dlopen is allowed; else try 16-bit at source rate (direct_pcm) or other routes.
2. Merge working audio path into main player (from 1.34), keep album art fix.
3. Polish: bit-perfect indicator, format choice, stability. No EQ.
