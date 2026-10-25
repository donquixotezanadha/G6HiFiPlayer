# HANDOFF — main player (read this first when asked to "lanjutkan" the player, not the Engine Lab)

User is not a coder: deliver ready APKs via GitHub Actions + simple steps, in Indonesian. Work in small steps: commit + push + build after each stage (usage limit cuts sessions).
Engine Lab (bit-perfect experiments) is a separate app on branches claude-build-135..138; see HANDOFF.md there. Do not mix it into the player.

## Branches (main player)
- claude-build-134: stable Build 91 + instant album art (cache hit + neighbour prefetch).
- claude-build-139-player-perf: Build 1.39 = 1.34 + performance work (see BUILD139_NOTES.md). Workflow: build-139-claude.yml (push builds the APK, artifact G6HiFiPlayer-Build139-v1.39.0).
- claude-build-140-queue-icons: Build 1.40 = 1.39 + queue survives Close/restart + notification buttons use the player icons (see BUILD140_NOTES.md). Workflow: build-140-claude.yml (artifact G6HiFiPlayer-Build140-v1.40.0).

## Done in 1.39
1. Album art: separate 48 MB player cache, JPEG disk cache, no blank flash on cache miss, background-priority prefetch, 512 px notification art.
2. Taps/notification: service is the only notification publisher, coalesced updates, direct service calls, 150 ms toggle guard, optimistic Play/Pause icon.

## Done in 1.40
- Queue persisted as URIs (playback_queue.json) and restored; rescan keeps queue/current track; notification icons = player icons.
- Open question for the user: does playback continue into a queued album after the current one ends? (see BUILD140_NOTES.md, Observation)

## Next (Build 1.41 = visual, only when the user asks for it)
- Adaptive Now Playing background like the notification: dominant colour of the cover on the left side fading right, text and buttons switching light/dark by luminance. Make it the default look (no theme option for it). vividArtworkColor() exists but applyArtworkAccent() currently forces DEFAULT_ACCENT.
- Remove dead code/visuals left from older builds (unused helpers, the unused accent plumbing once replaced).
- Keep the cover path from 1.39 (decode off the UI thread; compute the dominant colour in the same background step and cache it with the cover).
