# Build 47 — Shared header and status-bar layout

Version: 0.47.0 (versionCode 47)

Changes:
- Keep the Build 46 bottom-navigation implementation: fixed 25dp icon child centered by FrameLayout gravity, with equal-width tab touch targets.
- Standardize the main headers' left text inset and vertical baseline across Now Playing, Albums, Songs, and Settings.
- Keep all main header text at 20sp.
- Move the semi-transparent status-bar scrim from the Now Playing-only panel to the shared page stage so it appears consistently on every tab.
- Add matching status-bar top inset to non-Now Playing page content so headers and their content start below the system status area without overlapping it.
- Preserve the Play/Pause rounded rectangle sizing from Build 45/46.

GitHub Actions compiles the debug APK and uploads it as a Build 47 artifact.
