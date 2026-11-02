# Build 3.24

Base: Build 3.23.

- Naming: the 4x1 widget provider class was still called G6Widget4x2Provider; now G6Widget4x1Provider (manifest label "Music 4x1"). Drawables g6_widget_bg_4x2(_light) renamed to g6_widget_bg_4x1(_light); unused layouts g6_widget_4x2_compact/large deleted. The 4x1 widget must be added again once on the home screen (the component name changed).
- Text 4x2 widget: left/right gap 20dp (same distance from the widget edge as the 4x1 content: 12dp + 8dp); title 18sp -> 26sp, artist 12 -> 15sp, times 10 -> 12sp, play 42 -> 48dp, icon 28 -> 34dp; two weighted spacers spread title / progress / play from top to bottom; top/bottom padding 14dp; min height 130dp.
- Audio path unchanged.
- Fix (same 3.24): the two spacers were plain <View>, which RemoteViews does not allow, so the launcher drew the text 4x2 widget as a blank white box; replaced by empty FrameLayout.
- Tweak (same 3.24): play icon 48->40dp, music icon 34->28dp, play icon shifted left (-12dp margin, cancels the glyph's built-in inset) to align with the title's left edge, bottom padding 14->4dp so the play icon sits lower.
- Tweak (same 3.24): play/pause icon margin -12dp -> -8dp so the pause bars are no longer clipped at the left edge of the view.
- Splash (same 3.24): dark theme no longer colour-inverts the logo; it keeps the original black headphones and adds a thin white outline (new ic_splash_logo_outlined.png, 744x806 with transparent padding; size adjusted so the headphones stay 40% of screen width). ic_splash_logo_inverted.png removed. Also play/pause icon margin -12dp -> -8dp so the pause bars are not clipped.
