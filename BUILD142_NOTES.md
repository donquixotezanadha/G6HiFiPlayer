# Build 1.42 — Adaptive pages, one bottom-up shade (based on Build 1.41)

Audio path, queue and notification are unchanged. Only colours change.

## What changed
- Now Playing: the left-to-right shade over the cover is gone. Only the shade rising from the bottom stays.
- The bottom bar (Player / Albums / Setting) no longer has its own black background. It shows the same colour as the page above it, and the Android navigation bar uses that colour too, so the player and the bar are one surface.
- Every page (Now Playing, Albums, album song list, Setting, Queue overlay) now uses the dominant cover colour as its background. Text, cards, buttons, progress bars, selected-tab pill and status/navigation bar icons switch between white and near-black by contrast.
- The Light/Dark theme button in Setting is removed (the colour now always follows the cover). With no cover the default purple surface is used.

## How it works
- One palette: `playerSurface` (cover colour) and `playerInk` (white or near-black). `BG`, `CARD`, `ART`, `LINE`, `WHITE`, `MUTED` are computed from them.
- `themed { ... }` registers a view's colours so they are re-applied when the palette changes. Text built with the page ink/muted ink is registered automatically.
- Now Playing, the bottom bar and the system bars change immediately. Other tabs are repainted when they are next shown (or at once if one is visible), so a track change does not repaint the Albums list while you are on Now Playing.
