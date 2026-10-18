# Build 48 — Header refinement and Now Playing seekbar fix

Version: 0.48.0 (versionCode 48)

Changes:
- Lower the shared page headers slightly and reduce their size from 20sp to 18sp across Now Playing, Albums, Songs, Settings, and Queue.
- Fix Now Playing progress after changing the app theme: rebuilding the view now initializes the seekbar with the current track duration and playback position, and the ticker keeps its maximum synchronized with the active duration.
- Match the Now Playing seekbar track thickness to the mini-player's 3dp line while preserving a comfortable touch target.
- Preserve the Build 47 transparent status-bar/insets behavior, artwork and controls, bottom-tab icon sizing/centering, and existing playback engine.

GitHub Actions builds the debug APK and uploads it as a Build 48 artifact.
