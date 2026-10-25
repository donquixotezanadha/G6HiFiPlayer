# Build 1.40 — Queue survives Close/restart + notification icons (based on Build 1.39)

Audio path and visuals of the player are unchanged. The adaptive Now Playing background is Build 1.41.

## Queue bug
Symptom: albums added to the queue were gone after closing the app; only the album of the last track remained.
Cause: only the last track URI and position were saved. On the next start the player was rebuilt from that single track, whose album became the whole queue. A library rescan also cleared the queue and the current track.
Fix:
- The queue (visible list) and the play order are saved as track URIs in `playback_queue.json` (app files, written in the background) every time they change: play, add album, remove, clear.
- On start the saved queue is restored: the last track is loaded (paused, at its saved position) with the whole saved order, and the visible queue is put back exactly as it was (including an emptied queue).
- If the playback service kept the player alive after the app was swiped away, the visible queue is also taken from the saved state instead of only the player's playlist.
- A rescan (and its partial updates) now keeps the queue, the play order and the current track by matching track URIs instead of wiping them.

## Notification buttons
Previous / Play-Pause / Next use the same vector icons as the in-app transport buttons (`ic_previous_24`, `ic_play_24`/`ic_pause_24`, `ic_next_24`) instead of the stock Android media icons. The Close action uses a matching `ic_close_24` cross instead of the stock menu icon.

## Observation, not changed
Adding an album to the queue updates the queue list and the play order, but the live ExoPlayer playlist is only rebuilt when a track is started (tap on an album or on a queue row). Check on the phone whether playback continues into a queued album by itself after the current album ends.
