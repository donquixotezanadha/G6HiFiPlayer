# Build 3.18 - fixed-height 4x2 widget card

Base: Build 3.17 (branch build-317-single-launcher-no-alias).

## Problem
The 4x2 widget (provider G6Widget4x2Provider, layout g6_widget_4x1.xml, listed as "Music 4x1") looked different
on the LG launcher and the Nova launcher. Screenshots from both showed identical content scale (cover, text,
controls, left/right margins) but a different card height: roughly 100dp on one launcher and 80dp on the other
(measured against the fixed 54dp cover).

## Cause
`w_card` used `layout_height="match_parent"`, so the card always filled the area the launcher gave the widget.
The widget declares minHeight 70dp, and each launcher turns that into a different cell height.
The content has a fixed size, so the space around it changed with the launcher.

## Change
- g6_widget_4x1.xml: `w_card` height is fixed at 78dp and centred (`layout_gravity="center"`).
  The tighter of the two measured heights was chosen on purpose: a card taller than the area the launcher gives
  would be cut off, while a card smaller than the area just sits centred.
- Version 3.18 (versionCode 318); workflow build-318-widget-fixed-card-height.yml.

## Unchanged
Cover shape (rounded square), colours, 2x2 widget, text-only 4x2 widget, audio engine, theme and splash from 3.17.

## Test
1. Install over 3.17. Widgets re-render on the package update.
2. Compare the 4x2 widget on the LG launcher and on Nova: same card height, content centred.
3. If the card should be taller or shorter, only the 78dp value in g6_widget_4x1.xml needs to change.
