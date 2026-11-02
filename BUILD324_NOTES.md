# Build 3.24

Base: Build 3.23.

- Naming: the 4x1 widget provider class was still called G6Widget4x2Provider; now G6Widget4x1Provider (manifest label "Music 4x1"). Drawables g6_widget_bg_4x2(_light) renamed to g6_widget_bg_4x1(_light); unused layouts g6_widget_4x2_compact/large deleted. The 4x1 widget must be added again once on the home screen (the component name changed).
- Text 4x2 widget: left/right gap 20dp (same distance from the widget edge as the 4x1 content: 12dp + 8dp); title 18sp -> 26sp, artist 12 -> 15sp, times 10 -> 12sp, play 42 -> 48dp, icon 28 -> 34dp; two weighted spacers spread title / progress / play from top to bottom; top/bottom padding 14dp; min height 130dp.
- Audio path unchanged.
