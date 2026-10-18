# Build 64 — Jack disconnect and notification responsiveness

Base: Build 63 (v0.63.0), branch `build-63-audio-path-evidence`.

## Scope
- Preserve Build 63 audio output configuration, AudioTrack setup, sample-rate behavior, buffer behavior, and diagnostic claims.
- When Android reports removal of a wired headphone/headset, re-check connected output devices after a short 120 ms debounce. If no wired output remains, cancel pending autoplay and pause the player so playback cannot fall through to the phone speaker.
- Keep the current track and position available for deliberate manual resume; do not release the player or alter audio format settings.
- Move home-screen widget state/artwork rendering off the main/UI thread using a single-thread executor.
- Remove duplicate synchronous notification rebuilds during Play/Pause actions.
- Avoid persisting the entire diagnostic event snapshot for every notification refresh.
- Dispatch notification controls directly when already on the main thread, avoiding an unnecessary extra queue hop.

## Validation
- GitHub Actions must complete `gradle clean assembleDebug` successfully before the APK is considered build-verified.
- On the LG G6 / Android 9, test repeated 3.5 mm jack removal while playing and while paused, and repeatedly tap Play/Pause/Next in the notification.
- Expected: on jack removal, playback pauses quickly and remains paused on speaker; no automatic speaker playback. Notification controls respond without the former duplicate work.
- This code change cannot be confirmed on physical hardware from CI alone.
