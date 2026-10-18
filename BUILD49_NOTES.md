# Build 49 — Header alignment and Now Playing progress restoration

Version: 0.49.0 (versionCode 49), based on Build 48.

- Standardize all main page header top spacing and move them slightly lower, including Songs (which previously used a different vertical offset).
- Keep header text at a consistent 18sp across Now Playing, Albums, Songs, Settings, and Queue.
- Restore Android's native SeekBar drawable in Now Playing. Build 48's custom LayerDrawable/ClipDrawable caused the playback progress segment to disappear on the target API 28 device.
- Slightly increase the native Now Playing SeekBar minimum height while preserving its standard drawable rendering and existing live position/duration updates.
- Preserve the Build 48 audio engine, status-bar/insets behavior, and navigation UI.

GitHub Actions builds and uploads the debug APK as an artifact.
