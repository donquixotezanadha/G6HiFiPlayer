# Build 3.25

Base: Build 3.24.

- Splash: the headphones logo is replaced by the text "PLAY / PURE / DIRECT" (centred, three lines). The word DIRECT spans 48% of the screen width, proportions taken from the reference picture.
- Theme: dark theme = white text on black; light theme = dark text on white (inverted per theme).
- Font: Product Sans is not available to build with, so the text is a pre-rendered PNG in Inter Display Thin with wide tracking (res/drawable-nodpi/splash_text_dark.png and splash_text_light.png).
- Fade into the app: splash shown ~1.5 s, then the text fades out and grows 4% over 520 ms while the background dissolves over 780 ms (starts 120 ms later), both on a smooth ease curve (0.4, 0, 0.2, 1), instead of the old single 320 ms fade.
- Audio path unchanged.
