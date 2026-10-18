# Build 51 — Playback progress thickness and consistent headers

Version: 0.51.0 (versionCode 51), based on Build 50.

- Replace the thin platform-native SeekBar track with a purpose-built rounded drawable. Both background and played segment are drawn at 5dp thickness and the played segment follows the SeekBar level, avoiding the earlier ClipDrawable rendering failure on Android 9.
- Recreate the rounded progress drawable when the artwork accent changes, and preserve the accent thumb.
- Use one shared header factory for Now Playing, Albums, Songs, Settings, and Queue: 18sp, the same sans-serif bold weight, vertical centering, left alignment, and theme-aware black/white text.
- Align header baselines/row heights at 44dp and align the visible left edge consistently under both light and dark themes.

The GitHub Actions workflow builds a debug APK; confirm successful run and artifact availability before distributing.
