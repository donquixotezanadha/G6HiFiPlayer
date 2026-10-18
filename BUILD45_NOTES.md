# Build 45 — UI refinements

- Keep the Now Playing header left-aligned and preserve the Songs header sizing from Build 44.
- Add a translucent status-bar scrim only over the Now Playing page; keep Android's status bar itself transparent so content is blended beneath the system icons.
- Recalculate bottom navigation icon matrices whenever tab bounds change, preserving the 25 dp icon target and the existing selected-tab rectangle positioning.
- Widen the rounded Play/Pause background from 68 dp to 76 dp while retaining its 56 dp height and current icon size.
- App version: 45 (0.45.0).
