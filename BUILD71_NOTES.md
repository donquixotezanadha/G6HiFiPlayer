# Build 71 — Widget visual and touch baseline

- Base: Build 67 `build-67-widget-touch-debounce`; existing branches/builds remain unchanged.
- Version: 0.71.0 (versionCode 71).
- Preserves the Build 67 audio engine, widget broadcast action dispatch, 500 ms duplicate-toggle guard, and broadcast-based notification transport actions.
- 4x2 widget: balanced outer padding, aligned artwork inset, slightly larger title, consistent transport-button sizing, clearer duration text, and centered controls.
- 2x2 widget: title hidden as requested; compact metadata remains on the artwork overlay and the play/pause target remains a large broadcast button.
- No audio pipeline, sample-rate, buffer, or DAC logic changed.
- Build is not verified until the GitHub Actions job completes successfully.
