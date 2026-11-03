# Hifi (separate app, cloned from G6HiFiPlayer Build 3.25)

- applicationId `id.hifi.player` (the old app stays `id.g6hifiplayer`), so both install side by side.
- Name "Hifi", versionName 1.0 / versionCode 1. Next builds: 1.1, 1.2 ... on new branches from the previous one.
- Fixed signing key (app/hifi-debug.keystore): later versions install OVER the previous one, no uninstall needed.
- Audio, widgets, theme code unchanged from 3.25. Visual-only changes continue from here.

## v1.1 (branch hifi-v1-1, from hifi-v1-from-325) - visual only
- Backdrop veil 50% -> 35% (0x80 -> 0x59), both the flat veil and the bottom gradient, Dark and Light.
- Player buttons (previous/play/pause/next, also the mini-player play) are outline icons: ic_*_outline.xml. Notification and widgets keep their filled icons.
- Progress bar 4dp -> 2dp, with a small round 12dp thumb (white in Dark, ink in Light).
- Audio code unchanged.

## v1.2 (branch hifi-v1-2, from hifi-v1-1) - visual only
- Outline icons thinner: stroke about 2dp on screen, same as the progress bar.
- Icons larger: glyphs fill more of the 24 viewport and the padding shrank (play pad 11->8dp, side pad 10->6dp). Hit areas unchanged (60dp / 52dp).
- Previous/next moved toward the centre: transport spacer weights outer 0.6->1.3, inner 1->0.5.

## v1.3 (branch hifi-v1-3, from hifi-v1-2) - visual only
- Transport icons slightly smaller: play pad 8->9dp, side pad 6->7dp.
- Backdrop veil 35% -> 40% (0x59 -> 0x66), flat veil and bottom gradient, both themes.
- Unplayed progress track alpha 70% (0xB3) in Dark and Light.
- Button boxes at 80% (0xCC): Setting buttons (primary accent box too), mini-player card (was 72%), album-options dialog buttons. Others (search field, selected row) were already 80%.

## v1.4 (branch hifi-v1-4, from hifi-v1-3) - navigation redesign
- Bottom tab bar removed. Two pages only: Now Playing and Album, switched by a horizontal swipe (left = Album, right = Now Playing). Back still returns to Now Playing.
- Swipe is ignored when it starts on the seek bar, on a horizontal list (More albums), or while the queue overlay is open.
- Header row above the page: title on the left (Now Playing / Album), settings gear on the right (ic_settings_outline). It owns the status-bar inset, so page paddings no longer add it.
- Setting is a floating dialog from the gear. On Album: music folder scan + album columns. On Now Playing: two clickable icons, moon (Dark) and sun (Light), the active one in the accent colour.
- The old Setting page is no longer attached; Songs/Setting nav views are created but unattached.
- 20dp spacer replaces the 64dp bar at the bottom, so the controls dock sits lower than in v1.3.

## v1.5 (branch hifi-v1-5, from hifi-v1-4)
- Header fix: v1.4 offset the title and the gear by the status-bar height twice (bar padding + child margin), so they were clipped. Now only the bar padding applies.
- Bottom fix: the 20dp spacer is gone and the system navigation-bar inset is padding inside the scroller (and the queue overlay), so lists scroll down to the real nav bar instead of being cut above it. The controls dock keeps its position (bottom padding 4 -> 24dp).
- Now Playing header: moon and sun icons apply Dark/Light directly (active one in accent). Album header: gear for the floating window (scan + columns).
- Swipe is now a drag: the page follows the finger, slides out/in on release (distance > 28% of width or a fling) or springs back; children get a cancel when the drag starts.
- Button boxes: LINE (Setting/Album buttons) was already 0xCC (80%) before v1.3; v1.3 only changed the accent button, mini-player and dialog boxes. Waiting for the user's target value.

## v1.6 (branch hifi-v1-6, from hifi-v1-5)
- Now Playing content about 12dp lower: dock bottom padding 24 -> 12dp, and the cover 6dp lower inside the same stack height (sizeDarkArt).
- Album windows: header bar 48 -> 42dp, Album page top padding 6 -> 0dp, queue overlay top padding 6 -> 0dp, so content starts higher.

## v1.7 (branch hifi-v1-7, from hifi-v1-6)
- Queue list is a floating rounded card (same text sizes, 56% of the screen height at most, scrolls inside, last row may be cut off). The rest of the screen is a dim area; tapping it closes the queue. Open/close animation: card drops in and the dim area fades.
- The mini player moved out of the queue and is docked at the bottom of the Album page (above the nav bar). Tapping it or swiping it up goes to Now Playing. The Album list has 92dp bottom padding so the last row stays clear of it.
- Swipe between pages: unchanged (the user likes the animation; behaviour tweaks later).
