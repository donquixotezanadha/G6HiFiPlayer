# Build 40 — UI, Library Scan, and Swipe Transition

Build 40 continues from `build-39-smooth-queue-transition`. Keep the Build 39 queue/player fixes and validate the following requirements in this build.

## Implementation scope

- Bottom navigation: four equal-width touch targets; small, uniform 25 dp icons; icons and the active rounded pill centered symmetrically.
- Album scanning: after a folder is selected, navigate directly to Albums. Show scan progress on Albums (not in Settings), and publish discovered tracks/albums/artwork in small batches instead of waiting for the full scan to finish.
- Album interaction: keep the separate album-detail view and existing back/navigation behavior.
- Swipe-down Queue transition: ensure a visible, smooth downward panel transition without a first-frame stall. Keep the mini-player docked at the bottom of the Queue screen, directly above the bottom navigation.
- Queue behavior from Build 39: preserve full vertical scrolling, song-row ripple feedback, synchronized play/pause artwork, and icons matching Now Playing.
- Performance: keep batch updates modest and avoid per-file UI rebuilds on the LG G6 / Snapdragon 821.

## Verification checklist

- [ ] GitHub Actions completes `clean assembleDebug` successfully and uploads the Build 40 APK.
- [ ] On the LG G6 (LGM-G600L, Android 9), verify bottom-tab symmetry and active pill placement.
- [ ] Choose a music folder in Settings; confirm Albums opens automatically and progress is visible there.
- [ ] Confirm tracks, albums, and cover art appear while scanning; progress and final counts settle correctly.
- [ ] Swipe down from Now Playing; confirm the transition is visibly animated, Queue scrolls to the end, and mini-player stays docked above the tab bar.
- [ ] Verify mini-player play/pause updates immediately and matches the Now Playing control.
- [ ] Verify album-cover tap opens that album's dedicated track list and back navigation returns to the album grid.

Source changes and CI build status do not substitute for on-device verification. Record any remaining defects in the next build's conversation and update this checklist.


## Build 41 — UI navigation and queue fixes

Branch: `build-41-album-queue-transition`  
Version: `0.41.0`

### User-reported regressions addressed

- **Album details:** tapping an album now switches the Albums tab to a dedicated track-list view and returns to the album grid using the in-view back action or Android Back.
- **Queue overlay structure:** moved Queue out of the Now Playing panel and made it a sibling overlay over the content viewport. This avoids the old nested layout/scroll interaction that could hide the queue mini-player or prevent the queue list from scrolling.
- **Queue mini-player:** retained as a fixed dock below the queue's scrollable list; tapping it closes Queue and returns to Now Playing rather than toggling playback.
- **Queue transitions:** animate after queue rows are prepared and use cubic PathInterpolator timing, with hardware layer enabled only for the transition. Opening/closing and Back should be checked on-device for gesture smoothness.
- **Bottom tabs:** remove the stale image-matrix sizing path and use consistent native centered icon scaling in equal-width tab cells. This addresses the active Play icon appearing displaced/overlapped in the user's screenshot.
- **Previously requested Build 40 items remain in scope:** album-tab scan progress, automatic navigation to Albums on scan, album/song metadata styling, and prior playback/queue ripple and mini-player playback-state fixes.

### Verification status

Source changes and workflow configuration have been committed to the Build 41 branch. GitHub Actions must complete successfully before an APK is considered built. Device verification is still required for album tap/back, long queue scrolling, persistent mini-player dock, Play/Pause synchronization, bottom-tab alignment, swipe-up/down and Android Back transition smoothness on the LG G6 (LGM-G600L / Android 9). Do not treat source-level checks as proof of runtime behavior.


## Build 42 — tab indicator and mini-player gesture

Branch: `build-42-tabbar-swipe-player`  
Version: `0.42.0`

Build 41 is the accepted checkpoint for the user-confirmed fixes: Album taps now open a dedicated album track list; scan progress lives in Albums; Queue transitions are noticeably smoother; Queue scrolling and its dock were restructured; remaining issues were narrowed to the bottom-tab active indicator and a swipe-up gesture on the Queue mini-player.

### Build 42 changes

- Widened the selected bottom-tab capsule by reducing horizontal inset from 25dp to 12dp and vertical inset from 7dp to 4dp. Icon cells remain equally weighted; the active marker should read as a centered capsule rather than a small circle.
- Added an upward-dominant swipe gesture to the Queue mini-player dock. A swipe of at least 32dp with a vertical-dominant trajectory closes Queue using the existing animated close transition; ordinary taps remain wired to return to Now Playing.
- Preserved Build 41 album detail, album scan progress, queue layout/scroll, transition timing, and navigation behavior.

### Verification checklist

- Confirm selected capsule is centered and has visible side margins on all four tabs; check portrait 18:9 device layout.
- Open Queue and swipe upward from the mini-player dock; verify the Queue closes smoothly to Now Playing.
- Tap mini-player without swiping; verify ordinary tap behavior still works.
- Scroll Queue to the end, then use Android Back and swipe-down/up transitions.
- Confirm album detail/back, scan progress, playback controls and queue dock are unchanged.

Source and workflow commits are not a substitute for running the APK on the LG G6 (LGM-G600L, Android 9). Await GitHub Actions success before distributing an APK.
