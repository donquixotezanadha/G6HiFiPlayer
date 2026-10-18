# Build 50 — Now Playing metadata recovery and consistent headers

Version: 0.50.0 (versionCode 50), based on Build 49.

- Restore the selected track title, artist, album, elapsed time, duration, and seek position whenever the UI is rebuilt. This fixes Now Playing metadata disappearing after a theme switch while the playback engine remains active.
- Reload current artwork for the recreated Now Playing views without resetting the active player.
- Normalize the top offset and 44dp header row across Now Playing, Albums, Songs, Settings, and Queue so their titles align consistently below the status bar.
- Make the Now Playing header text slightly heavier while retaining its 18sp size and left alignment.
- Preserve the native API 28 SeekBar rendering and the theme-switch playback behavior from Build 49.

Build validation: GitHub Actions debug APK build is triggered by the Build 50 branch workflow update; verify the run and artifact before distributing.
