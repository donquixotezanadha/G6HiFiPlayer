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
