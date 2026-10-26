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

## Next
- Only what the user asks for after testing 1.41 (tune the fade width/strength, colour choice per cover).
