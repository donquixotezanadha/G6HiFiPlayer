# Build 72 — widget layout and touch-latency follow-up

- Base: Build 67 branch `build-67-widget-touch-debounce`; prior builds and branches are unchanged.
- Version: 0.72.0 (versionCode 72).
- Widget toggle duplicate suppression reduced from 500 ms to 180 ms so closely spaced intentional taps are less likely to be ignored.
- 4x2 widget: symmetric outer padding, centered content column, proportional artwork and transport-button dimensions, slightly larger title.
- 2x2 widget: title remains hidden; artwork inset and Play/Pause control spacing adjusted.
- Audio engine and DAC diagnostics unchanged.
- Important: these source changes are not proof that all touch lag is fixed. Confirm the CI build succeeds, then verify both widgets and notification controls on the LG G6. If lag persists, profile the actual broadcast-to-player path rather than continuing to tune XML dimensions.
