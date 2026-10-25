# Build 1.41 — Adaptive Now Playing (based on Build 1.40)

Audio path, queue and notification are unchanged. Only the Now Playing page changes look.

## What changed
- The dominant colour of the cover is used as the page surface: strongest on the left, fading to the right over the cover, and rising from the bottom behind the title and controls.
- Title, artist, album, times, audio info line, previous/next, play/pause and the progress bar switch between white and near-black, whichever has better contrast on that colour. Secondary text is the same colour at about 70 % opacity.
- This is the default look of Now Playing. There is no setting for it. The Light/Dark app theme in Settings still applies to Albums, Songs, Queue and Settings.
- No cover: the page uses the default purple surface with white text.

## How it works
- `dominantArtworkColor()` samples about 32 x 32 pixels, groups them by colour family (saturated pixels count more than grey) and averages the winning family.
- The colour is computed in the same background step that decodes the full cover (`decodePlayerArtwork`) and remembered per track (`dominantColorCache`). A library thumbnail shown while the full cover decodes is sampled for a colour but not remembered.
- `applyPlayerPalette()` paints only the Now Playing views and does nothing if the colour did not change. It no longer redraws the Albums/Songs lists on a track change.

## Removed
- `vividArtworkColor()` and `applyArtworkAccent()` (the accent plumbing that was forced to the default colour).
- The light/dark gradients inside the Now Playing page.
