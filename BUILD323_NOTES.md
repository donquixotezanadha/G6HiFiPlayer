# Build 3.23

Base: Build 3.22.

- Auto next album: when the last track of the live playlist starts playing (or the playlist ends), the next album in the alphabetical Albums list is appended to the Media3 playlist (gapless) and shown in the queue. Tracks inside the album follow the album view order (track number, then title). It chains: the last track of that album lines up the one after it. Stops after the last album in the list.
- Albums the user added to the queue that were not yet in the live playlist are appended first, before any alphabetical album.
- Function: `ensureNextAlbumQueued()` in MainActivity.kt, called from onMediaItemTransition, STATE_READY and STATE_ENDED.
- Audio path, widgets, icons unchanged.
