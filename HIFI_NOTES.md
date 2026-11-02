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
