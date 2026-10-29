# HANDOFF — main player (read this first when asked to "lanjutkan" the player, not the Engine Lab)

User is not a coder: deliver ready APKs via GitHub Actions + simple steps, in Indonesian. Work in small steps: commit + push + build after each stage (usage limit cuts sessions).
Engine Lab (bit-perfect experiments) is a separate app on branches claude-build-135..138; see HANDOFF.md there. Do not mix it into the player.

## Branches (main player)
- claude-build-134: stable Build 91 + instant album art (cache hit + neighbour prefetch).
- claude-build-139-player-perf: Build 1.39 = 1.34 + performance work (see BUILD139_NOTES.md). Workflow: build-139-claude.yml (push builds the APK, artifact G6HiFiPlayer-Build139-v1.39.0).
- claude-build-140-queue-icons: Build 1.40 = 1.39 + queue survives Close/restart + notification buttons use the player icons (see BUILD140_NOTES.md). Workflow: build-140-claude.yml (artifact G6HiFiPlayer-Build140-v1.40.0).

- claude-build-141-adaptive-player: Build 1.41 = 1.40 + adaptive Now Playing (see BUILD141_NOTES.md). Workflow: build-141-claude.yml (artifact G6HiFiPlayer-Build141-v1.41.0).

- claude-build-142-adaptive-flat: Build 1.42 = 1.41 + one bottom-up shade, bottom bar blends in, all tabs adaptive (see BUILD142_NOTES.md). Workflow: build-142-claude.yml (artifact G6HiFiPlayer-Build142-v1.42.0).

- claude-build-143-ink-icons: Build 1.43 = 1.42 + notification-style ink rule + prev/next icons un-swapped (BUILD143_NOTES.md). Workflow: build-143-claude.yml (artifact G6HiFiPlayer-Build143-v1.43.0).

- claude-build-144-settings-cleanup: Build 1.44 = 1.43 + columns in Setting, no status-bar scrim, folder cover fallback (incl. WAV), Setting reduced to scan + columns (BUILD144_NOTES.md). Workflow: build-144-claude.yml (artifact G6HiFiPlayer-Build144-v1.44.0).

- claude-build-145-classic-theme: Build 1.45 = 1.44 + Classic theme option (Setting), cover box on Now Playing in Classic, WAV tag + embedded cover reader (BUILD145_NOTES.md, WavTags.kt). Workflow: build-145-claude.yml (artifact G6HiFiPlayer-Build145-v1.45.0).

## Done in 1.39
1. Album art: separate 48 MB player cache, JPEG disk cache, no blank flash on cache miss, background-priority prefetch, 512 px notification art.
2. Taps/notification: service is the only notification publisher, coalesced updates, direct service calls, 150 ms toggle guard, optimistic Play/Pause icon.

## Done in 1.40
- Queue persisted as URIs (playback_queue.json) and restored; rescan keeps queue/current track; notification icons = player icons.
- Open question for the user: does playback continue into a queued album after the current one ends? (see BUILD140_NOTES.md, Observation)

## Done in 1.41
- Now Playing: dominant cover colour on the left fading right and rising from the bottom; text/buttons/progress bar switch white or near-black by contrast; no setting for it. Old accent code (vividArtworkColor, applyArtworkAccent) removed. See BUILD141_NOTES.md.
- Not yet checked on the phone: how it looks with very bright, very dark and grey covers, and with no cover.

## Done in 1.42
- Left shade removed; bottom bar and Android nav bar use the page surface; Albums/Setting/Queue follow the cover colour; Light/Dark button removed. Not yet checked on the phone with very bright covers and in Setting/Albums.

## Done in 1.44
- See BUILD144_NOTES.md. Not yet checked on the phone: folder cover for WAV, status bar legibility over bright covers on Now Playing.

## Done in 1.45
- See BUILD145_NOTES.md. Not yet checked on the phone: Classic on bright covers, WAV tags/cover on the user's real files.

## In progress: Build 1.46 (branch claude-build-146-layout)
- Baca BUILD146_NOTES.md: posisi kontrol Classic = Adaptive, cover diperbesar, navbar transparan, cek tombol notifikasi prev/next. Belum ada kode diubah.

## Build 1.55 (branch claude-build-155-player)
- See BUILD155_NOTES.md. Workflow build-155-claude.yml (artifact G6HiFiPlayer-Build155-v1.55.0). Not yet checked on the phone.

## Build 1.60 (branch claude-build-160-engine)
- Native DIRECT output in the main player (Media3 AudioOutputProvider + native track from Engine Lab 1.58/1.59). See BUILD160_NOTES.md. Workflow build-160-claude.yml (artifact G6HiFiPlayer-Build160-v1.60.0). Not yet checked on the phone.

## Build 1.61 (branch claude-build-161-player)
- Pure 24-bit native DIRECT: FFmpeg pcm_s24le for FLAC > 16-bit via Pcm24DataSource, DirectAudioOutputProvider is the only output, exclusive audio focus, old AudioTrack/diagnostics code removed. See BUILD161_NOTES.md. Workflow build-161-claude.yml (artifact G6HiFiPlayer-Build161-v1.61.0). Not yet checked on the phone.

## Build 1.62 (branch claude-build-162-player)
- Real 24-bit to the DIRECT output: sink float output ON (Media3 1.10.1 cuts 24-bit to 16-bit when it is OFF), float -> 24-bit exact in DirectAudioOutput, output hand-over fixes, exclusive focus rewrite, background prefetch of the next hi-res FLAC. See BUILD162_NOTES.md. Workflow build-162-claude.yml (artifact G6HiFiPlayer-Build162-v1.62.0; success marker build162_ok.txt on branch ci-logs). Not yet checked on the phone.

## Next
- Only what the user asks for after testing 1.41 (tune the fade width/strength, colour choice per cover).

## Build 1.64 (branch claude-build-164-player)
- Starts from 1.62 (NOT from the 1.63 branch: it dropped the native build and swapped ffmpeg-kit-audio for -full). Hi-res FLAC that FFmpeg cannot convert is played as it is, and the info line shows the failure reason. See BUILD164_NOTES.md. Workflow build-164-claude.yml (artifact G6HiFiPlayer-Build164-v1.64.0). Not yet checked on the phone.

## Build 1.65 (branch claude-build-165-player)
- 1.64 phone test: plays, DIRECT 96 kHz PCM_24_BIT_PACKED, green; FFmpeg failed with NoClassDefFoundError and was not needed (platform FLAC decoder + float output gives 24-bit). 1.65 removes FFmpeg and adds a real-24-bit check on the info line. See BUILD165_NOTES.md. Workflow build-165-claude.yml (artifact G6HiFiPlayer-Build165-v1.65.0). Not yet checked on the phone.

## Build 2.13 (branch claude-build-213-player)
- 2.12 + play icon 38dp, previous/next icons 32dp (padding only, positions unchanged). See BUILD213_NOTES.md. Workflow build-213-claude.yml (artifact G6HiFiPlayer-Build213-v2.13.0; success marker build213_ok.txt on branch ci-logs). Not yet checked on the phone.
