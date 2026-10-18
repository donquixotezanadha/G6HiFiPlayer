# Build 46 — Header and bottom navigation alignment

Version: 0.46.0 (versionCode 46)

Changes:
- Keep the semi-transparent status-bar scrim on Now Playing from Build 45.
- Align the Now Playing header's text inset and top baseline with the Albums/Songs header convention.
- Reduce the main page header font size from 22sp to 20sp for Now Playing, Albums, Songs, and Settings.
- Replace matrix-positioned bottom navigation icons with fixed 25dp ImageView children centered by FrameLayout gravity. Each tab remains an equal-width full touch target, and the selected rounded rectangle keeps symmetric insets.
- Preserve the Play/Pause rounded rectangle dimensions from Build 45.

Build validation is provided by the GitHub Actions debug APK workflow.
