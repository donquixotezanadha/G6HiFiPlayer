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

## v1.8 (branch hifi-v1-8, from hifi-v1-7)
- Album page (list, opened album): 5dp gap between the header bar and the first content (was 0 in 1.6/1.7, 6 in 1.5). Header bar unchanged at 42dp.

## v1.9 (branch hifi-v1-9, from hifi-v1-8)
- Album list remembers its scroll position: Back from an opened album (button, system Back) and coming back from Now Playing return to the same spot instead of the top. Opening an album still starts at its top.
- Queue card is taller: it ends just above the elapsed/duration row (start of the progress area), still scrollable inside.
- Queue layer moved from the page area to the whole screen, so the dim covers the header too (v1.7/1.8 left a lighter top strip, visible in Light theme).
- Backdrop veil 40% -> 50% (0x66 -> 0x80), flat veil and bottom gradient, both themes.

## v1.10 (branch hifi-v1-10, from hifi-v1-9)
- Queue is no longer a floating card. It is a slide-up list that takes the place of the album art: it covers the page from just under the header down to the top of the track title block. No card, no dim; same width (16dp page padding) and text as the Album track list. Open: list slides up 56dp and fades in over 300ms while the album art fades out; close is the reverse. Back also closes.
- Gesture: swipe UP on Now Playing opens the queue, swipe DOWN closes it (inside the list it only closes once the list is scrolled back to the top). In 1.9 and earlier it was swipe down to open.
- Page swipe (Now Playing <-> Album) stays blocked while the queue is open.

## v1.11 (branch hifi-v1-11, from hifi-v1-10)
- The queue area (art area) stays fixed and clips; the list itself starts below that area and slides UP into it (340ms, fading in), while the album art fades out. Closing slides it back DOWN out of the area (260ms) and the art fades back in. v1.10 only moved it 56dp.
- Opening gesture: swipe up that STARTS on the track title or anything below it (title/artist, progress, controls, info). Starting on the cover no longer opens it. Closing: swipe down (list at its top, or from the dock), Back.

## v1.12 (branch hifi-v1-12, from hifi-v1-11) - queue transition redesigned
- Title, artist, album, progress, controls and info line are one unit (the "dock"). One swipe UP that starts on that unit moves it up until it sticks just under the "Now Playing" header; the album art fades away ("sinks") and the queue list appears directly below the dock, rising with it. Swipe DOWN reverses everything (also closes with Back).
- It is a finger-following drag like the page swipe: progress p (0 = Now Playing, 1 = queue) is applied in one place (applyQueueProgress): dock.translationY = -shift*p, art alpha = 1-p, list translationY = shift*(1-p) with fade-in. On release it commits with distance (>50%) or a fling (>500dp/s) and animates the rest (340ms open, 280ms close), otherwise springs back.
- Drag start area: closed = on the dock (title..info) or anything below its top edge; open = on the dock, or on the list while it is scrolled to the top. Page swipe and the seek bar are unchanged; page swipe is blocked while the queue is up.
- Replaces the v1.10/v1.11 mechanics (list sliding inside a fixed area; SwipeFrameLayout callbacks are now no-ops).
