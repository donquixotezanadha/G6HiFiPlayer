# Build 1.39 — Player performance (based on Build 1.34)

Audio path unchanged. Visual changes (adaptive player background) follow in Build 1.40.

## Album art loading
- Now Playing covers have their own 48 MB cache. In 1.34 they shared a 20 MB cache with the library thumbnails, but one 1200 px cover is ~5.7 MB, so the prefetch of the next tracks evicted each other and the current cover.
- Covers are saved on app cache storage as JPEG after the first decode. The next play (also after the app was closed) reads that file instead of opening the audio file through the Storage Access Framework again.
- Tracks without a cover are remembered, so they are not probed again on every skip.
- A cache miss no longer blanks the screen: the library thumbnail (if already decoded) or the previous cover stays visible until the new cover is ready.
- Prefetch of the next, previous and next+1 covers runs on a separate background-priority thread and is cancelled when the user skips again; a decode that is already stale is skipped.
- Two requests for the same cover (prefetch + on-screen) share one decode.
- The technical file details (sample rate, bitrate) start 250 ms after the cover so the two do not compete for the same storage.
- The notification, lock screen and MediaSession get a 512 px copy of the cover (made off the UI thread) instead of the 1200 px bitmap; the notification also no longer shows the previous track's cover while the new one decodes.
