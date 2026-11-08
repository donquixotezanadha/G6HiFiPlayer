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

## v1.13 (branch hifi-v1-13, from hifi-v1-12)
- The album art is now part of the rising unit: it moves up by the same distance as the info dock (artBox.translationY = -shift*p) instead of fading in place. It leaves through the top edge of its host and is cut there, with a thin 22dp fade (DST_OUT gradient, only while the queue transition is active, so the resting look and shadow are untouched).
- Cover host: new TopFadeLayout wraps artBox inside the Now Playing stack; sizeDarkArt now reads MarginLayoutParams.
- Still fading (alpha) only the "NO MUSIC PLAYING" placeholder. Everything else from 1.12 unchanged.

## v1.14 (branch hifi-v1-14, from hifi-v1-13)
- Returning from Album to Now Playing uses the page-swipe slide for Back, tapping or swiping up the mini player, and the play actions (play album, tap a track, album options): showPlayerAnimated() (old page slides out 110ms, Now Playing slides in 240ms).
- Mini player box background 80% -> 92% (0xEB).
- Buttons are filled again, as in the original: previous/play/pause/next and the mini-player play use ic_*_24 (pads back to 11dp play / 10dp side, spacer weights unchanged); header gear, moon and sun are new filled icons (ic_settings_fill, ic_moon_fill, ic_sun_fill). The *_outline drawables are left in res but unused.

## v1.15 (branch hifi-v1-15, from hifi-v1-14) - sharper album thumbnails
- Cause of the blurry Album grid: thumbnails were decoded with power-of-two halving toward 320 px, so a 1400 px cover became about 175 px (160-320 px range), stored as JPEG q88, reloaded in RGB_565, then stretched to about 630 px (2 columns) or 420 px (3 columns).
- Now: decodeArtworkExact (largest power-of-two sample that stays >= target, then smooth scale to exactly 512 px, never upscaled), full colour (ARGB_8888), JPEG q92 on disk, THUMB_PX = 512.
- Memory cache for thumbnails and album-detail covers 20 -> 64 MB (a 512 px thumbnail is about 1 MB). Start-up preload now covers only the first 48 albums of the alphabetical grid (the old preload loaded every album, which would evict itself with bigger bitmaps).
- One-time purge: the old 320 px thumbs and "no cover" markers are deleted on first start (pref thumb_px) and rebuilt on demand, so the first opening after the update shows covers appearing progressively.
- Unchanged on purpose: Now Playing (1200 px, still power-of-two sampled), album-detail cover (800 px RGB_565), notification and widgets, the library scan.

## v1.16 (branch hifi-v1-16, from hifi-v1-15) - smoother queue slide
- Heavy work moved off the first drag frame: finger-down on the dock/info area now pre-builds the queue rows (only if the queue changed, via a signature), sets the list geometry (layout params only when they differ) and lays the list out invisibly. A plain tap or a page swipe puts it back to GONE.
- Hardware layers on dock, cover and list while the queue moves (drag or animation), removed afterwards.
- Cover top fade: only the 22dp top strip uses an offscreen layer now; the rest of the cover is drawn directly (was a full-size saveLayer every frame).
- Drag feels shorter: the finger travels 70% of the distance for full progress (queueTravelFactor). Commit at 30% progress in the direction of travel (was 50%); fling threshold 300dp/s (was 500). Start slop 9dp (was 14), dominance 1.15 (was 1.4), start area begins 24dp above the dock (was 6dp).
- Audio, library, thumbnails untouched.

## v1.17 (branch hifi-v1-17, from hifi-v1-16) - swipe down to Album, mini player bounce
- Now Playing: swipe UP (from the info dock) still opens the queue; swipe DOWN (anywhere except the seek bar) goes to Album. The sideways page swipe is switched off (sidewaysPageSwipe = false), so there are only these vertical moves.
- Now Playing -> Album: the drag follows the finger. Now Playing fades and sinks a little (28dp) while the mini player rises from the bottom. Commit at 25% of 220dp or a downward fling (300dp/s), otherwise it springs back. On commit the Album page only fades in where it is (no slide, so its remembered scroll position is untouched) and the mini player settles with a soft overshoot (OvershootInterpolator 1.15, 440ms).
- Album -> Now Playing: tap the mini player, swipe it up, Back, or any play action (showPlayerAnimated). The mini player sinks and fades, the Album page fades out, then Now Playing rises 28dp into place with a fade (380ms). The old sideways slide is gone.
- Hardware layers on the page content and the mini player only while the transition runs. Version 1.17 (code 18), own workflow build-hifi-v1-17.yml.

## v1.18 (branch hifi-v1-18, from hifi-v1-17) - docked mini player
- The mini player is no longer a floating rounded card. It is a stuck bar at the bottom of the Album page, like the header at the top: same content (cover, title, artist, progress, play), full width, square, solid background (card colour 92%) that runs down to the navigation bar.
- The Album list is cut where the bar starts instead of scrolling under it (applyMiniDock: scroller bottom margin = bar height while on Album; the old 92dp bottom padding is now 8dp and the nav-bar inset padding is not used there). On Now Playing the scroller is full height again.
- The transition from 1.17 is unchanged (the bar still rises with the soft bounce and sinks on the way back).

## v1.19 (branch hifi-v1-19, from hifi-v1-18) - mini player lift
- Swipe up on the mini player now follows the finger like the swipe down on Now Playing: the bar lifts up to 28dp and fades (full fade at about 2/3 of the 160dp drag). Release past 25% or with an upward fling (300dp/s) goes to Now Playing, otherwise it springs back.
- Tapping the mini player or Back uses the same exit: the bar lifts a little and fades (it used to sink), the Album page fades out, then Now Playing rises in. A drag that is committed continues from where the finger left it.
- The old swipe detector on the bar (decided only on release) is removed; the drag is handled in dispatchTouchEvent (startMiniDrag, applyMiniLift, finishMiniDrag).

## v1.20 (branch hifi-v1-20, from hifi-v1-19) - floating mini player again, soft fade behind it
- The mini player is a floating rounded card again (same size and margins as 1.17), with a fully solid background for now (card colour at 100%, was 92%). The docked bar of 1.18 is gone. All animations of 1.17 and 1.19 are unchanged.
- New BottomFadeLayout around the page scroller: on Album the list fades toward the page backdrop (it is a transparency mask, not a solid colour band). Full strength 24dp above the top of the card, gone at the vertical middle of the card, and fully transparent from there down over the navigation-bar area, so scrolling covers never show through the transparent bar. Only that bottom band uses an offscreen layer. Off on Now Playing.
- The list's bottom spacing grew 92 -> 112dp (plus the nav inset) so the last row rests about 12dp above the fade start when scrolled to the end.

## v1.21 (branch hifi-v1-21, from hifi-v1-20) - rounded mini art, single backdrop layer
- Mini player cover: the ImageView only had a rounded background, the picture was drawn square over it. Now the view itself is clipped to rounded corners (roundCorners(8)), like the other covers.
- Backdrop: yes, there was a second layer. DarkBackdropDrawable drew a flat veil (black 50% in Dark, white 50% in Light) AND a gradient from 45% of the height down to the bottom (black / white up to 50% more). The gradient is removed in both themes; only the flat veil is left, so the page looks the same from top to bottom.

## v1.22 (branch hifi-v1-22, from hifi-v1-21) - darker grey text in Light
- Measured: the grey (secondary) text is the same strength in both themes. Dark 0xFFA4A4B2 on black with white main text = 64% of the way from background to main text. Light 0xFF686875 on 0xFFF8F8FC with 0xFF171720 main text = 64% as well.
- Light grey text is now 0xFF484855 (about 78% of the way from background to main text, so clearly darker). Dark is unchanged. Only the MUTED colour changed; it is used by all grey text (artist, times, labels, album info).

## v1.23 (branch hifi-v1-23, from hifi-v1-22) - one edge fade everywhere, mini player in the queue
- One fade style for every cut of the screen (EdgeFadeLayout, replaces BottomFadeLayout): a transparency mask toward the backdrop. Bottom as in 1.20 (full 24dp above the mini player card, gone at the middle of the card). Top is the same profile mirrored, 56dp long (FADE_TOP_DP), with strength: Album list = how far it is scrolled (none at rest, full after 24dp), queue list = same, Now Playing = how far the queue has risen. Places covered: Album list (top under the header, bottom at the mini player), queue list (top and bottom), cover and info dock leaving through the top. The old 22dp strip on the cover host is no longer driven.
- Mini player: background opacity 0 (no fill at all), so the faded list behind it reads as a soft blur.
- Queue now has the mini player, in the same place as on Album (bottom, same size and margins). It rises with the finger while the queue is dragged open and bounces in (OvershootInterpolator 1.15, 440ms) when the queue opens; tap on it or swipe it up (it lifts 28dp and fades) goes back to Now Playing; swiping the queue down closes it as before and the mini player sinks with it. The mini player layer is above the queue, so it stays tappable.
- Queue geometry: the queue list no longer sits under the info dock. The dock and cover rise together and leave through the top edge (fade), the list uses the whole page from under the header down to the mini player, last row rests 112dp (+ nav inset) above the bottom so it clears the fade. The list content moves inside a fixed panel so its fade zones stay where the mini player is.
- Setting window and the long-press album window are as wide as the mini player card (screen minus 16dp each side); Setting is centred under the header.

## v1.24 (branch hifi-v1-24, from hifi-v1-23) - MD5 check of the samples sent to the DAC (test build)
- Nothing in the audio path or the look changed. A read-only check was added: while a track plays, the 24-bit packed samples that the native DIRECT track accepts are hashed (OutputMd5.kt, two MD5 digests: all 3 bytes and the top 2 bytes, the right one is chosen by the file's bit depth). When a whole track has gone through, the result is compared with the MD5 of the original audio: FLAC = the MD5 stored in the STREAMINFO header (AudioSniffer now reads the full 34-byte block: MD5 and total sample count), WAV 16/24-bit stereo = MD5 of the data chunk (read once in the background at low priority).
- Result: a toast (about 3.5 s, at the end of the track) with MATCH / DIFFERENT / no MD5 in file / not comparable (length), the file name and format, and the same text kept at the end of the second status line until the next verdict. Details (frames, both MD5 values, top-16 MD5 for 24-bit files) go to the diagnostics log (DirectOutputState.log, line starting "MD5 check").
- Valid only when the track starts at its very beginning: a new output, or a flush whose first data has a presentation time within 150 ms. A seek (or a restored mid-track position) switches the check off for that track. Tracks that follow in the same output (gapless) are checked too; after a sample-rate change the next track opens a new output and is checked only when it begins right after a flush at position 0, otherwise it is skipped. Stereo 16/24-bit FLAC and WAV only; other formats show nothing.
- How to use: play a 16-bit and a 24-bit FLAC from the start to the end without seeking, read the toast at the end.
- Plan: this is a test build. Later work (queue, mini player) continues from v1.23 unless the check is asked to stay.

## v1.25
- Branch from v1.24 (checksum test build). For FLAC files whose header MD5 is all zeros (many hi-res files), the app now decodes the file with an independent in-app decoder (FlacReference.java, never used for playback) and compares its MD5 with what is sent to the DAC. Verdict labelled "FLAC (own decoder)".
- Decoder validated locally against ffmpeg: 16/24-bit, 44.1/96 kHz, compression levels 0/5/8 – all MD5 identical.

## v1.26
- 96 kHz / 24-bit FLAC without header MD5 gave "MD5 DIFFERENT" in v1.25 (own decoder vs sent samples). To tell a decoder bug from a real difference, the check now also compares per-second digests, reports the first second that differs, the share of samples with an empty low byte (sent vs file) and logs the first 48 bytes of both sides.

## v1.27 (branch hifi-v1-27, from hifi-v1-26) - own FLAC decoder for hi-res files
- Cause found in 1.26 diagnostics: "MD5 DIFFERENT ... empty low byte sent 99% file 0%" on every 24-bit FLAC. The phone's MediaCodec FLAC decoder (Android 9) cuts hi-res FLAC to 16 bits before the player sees it; DirectAudioOutput only pads it back to 24 bits. The DIRECT route, rate and packing were fine; the data was not.
- New: FlacFrameDecoder.java (pure Java, one FLAC frame -> exact integers), FlacPcmDecoder.java (Media3 SimpleDecoder), FlacPcmRenderer.java (DecoderAudioRenderer). Added BEFORE MediaCodecAudioRenderer in PlaybackKeepAliveService, so it wins FLAC files of 17-24 bits (mono/stereo, 8-192 kHz). 16-bit FLAC, MP3, AAC, ALAC, WAV stay on the normal renderer as before.
- No gain, dither or rounding: 24-bit goes to the sink as PCM_24BIT, travels as float (exact) and DirectAudioOutput converts it back to the same integers, unchanged.
- Checksum: OutputMd5 still hashes what the DIRECT track accepts. The toast and log now also say which decoder made the track ("own FLAC decoder" or "Android MediaCodec").
- Label: BIT-PERFECT is now green only after MD5 MATCH for that track (or when no check is possible and the low byte carries data). MD5 DIFFERENT shows orange "DATA DIFFERS FROM FILE"; a 24-bit file with the low 8 bits empty and no MD5 verdict shows orange "LOW 8 BITS EMPTY". The MD5 note in the status line is cleared at each new track.
- CI: tools/flac-selftest/run.sh runs before the Gradle build: it makes 24-bit test files with ffmpeg (96/44.1/192 kHz, compression 0/5/12, mid/left/right side, mono, 16-bit-in-24 wasted bits), decodes them with FlacFrameDecoder and compares the PCM MD5 with ffmpeg's. All 9 matched when written; a difference fails the build.
- Expected on the phone: the same two tracks (Mattel, Apocalypse Please) now say "MD5 MATCH" and "decoder: own FLAC decoder".

## v1.28 (branch hifi-v1-28, from hifi-v1-27) - own ALAC decoder, 16-bit, WAV passthrough, fade-in fix
- Why MD5 was still DIFFERENT in v1.27 (only second 0 differed, every track, also on speaker): Media3 1.10.1 DefaultAudioSink.maybeRampUpVolume fades in the first 20 ms after every start / flush by changing the samples themselves, before they reach the DIRECT output.
- Fix: every own decoder puts DirectOutputState.padFrames(rate) = rate/50 + 1 silent frames in front of the first block after a start or flush (new decoder or flush), so the ramp only touches silence. OutputMd5 skips exactly those frames at each track start and after each finish.
- New decoders: AlacFrameDecoder / AlacPcmDecoder / AlacPcmRenderer (ALAC 16 / 20 / 24-bit, mono or stereo, in .m4a), PcmCopyDecoder / PcmCopyRenderer (WAV 16 / 24-bit LE passed through byte for byte). FLAC now covers 8 to 24 bits (16-bit FLAC included). All 8 to 192 kHz.
- MD5 for ALAC: ALAC has no stored MD5, so AlacReference decodes the whole file once in the background with the same decoder (AlacMp4Reader finds the packets) and the check proves that what reaches the DIRECT track equals the decoder output. Decoder correctness itself is proven by tools/alac-selftest (CI) against ffmpeg: 10 files (24-bit 44.1 / 48 mono / 96 / 192 kHz, 16-bit 44.1 / 96, compression levels, digital silence) all bit-exact.
- 20-bit ALAC could not be generated for the test (ffmpeg only writes 16 / 24-bit); the code path is the same as 24-bit.
- Next (v1.29): strict mode (only FLAC / ALAC / WAV play; no other renderer; stop with a message for other files and on MD5 mismatch; FLAC frame CRC check).

## v1.29 (branch hifi-v1-29, from hifi-v1-28) - strict mode
- Only three formats can play: FLAC (8-24 bit), ALAC in .m4a (16-24 bit) and WAV (16 / 24-bit PCM), mono or stereo, 8-192 kHz. The renderer list contains only FlacPcmRenderer, AlacPcmRenderer and PcmCopyRenderer: no MediaCodec audio renderer, no video / camera renderer, no ffmpeg or other decoder dependency (build.gradle has only media3 exoplayer, common and datasource). There is no second route to the Android mixer: if the native DIRECT output cannot open, playback fails with an error (as since 1.61).
- Any other file (MP3, AAC, OGG, DSD, AIFF, 32-bit ...): ExoPlayer selects no audio track, onTracksChanged sees that, the player is paused, a message and toast say "Not supported (strict mode: FLAC, ALAC, WAV only)" with the file's mime type; pressing play again pauses it again until another track is chosen.
- A track that does not match its check stops playback by itself: when OutputMd5 ends a track with "MD5 DIFFERENT" or "MD5 not comparable (length)", DirectOutputState.strictStopHandler pauses the player and the status line / toast say STOPPED. The check runs when the track has been fully handed to the output, so the stop comes at the end of that track, before the next one.
- FLAC frames are verified while decoding: header CRC-8 and frame CRC-16. A damaged frame is a decode error, so playback stops at once in the middle of the track. tools/flac-selftest now also flips one bit in a frame and requires it to be rejected.

## v1.34 (branch hifi-v1-34, rebuilt from hifi-v1-29) - speaker kept
- Headset-only mode (1.30-1.32: refusing play without the headset, unplug stop, DIRECT route stop, "PLUG IN THE HEADSET", dimmed play button) is NOT in this line: the speaker works again as in 1.29. The jack and the speaker are told apart by the LG hi-fi indicator, so no extra code is needed for that.
- Music volume guard (VolumeGuard.kt, works on any output): while playing, a sudden jump of 4 steps or more of the music volume in one change is put back to the previous level and a toast says so; the volume keys inside the app are bound to the music stream (volumeControlStream).
- Audio focus as in 1.33: another app taking the audio for good (YouTube, video or music players, games) pauses the player at once, status line "Stopped: another app took the audio"; no fight to get the focus back. Pressing play asks for the focus again. Calls, alarms and other transient losses still pause and resume by themselves.
- The MD5 toast is removed. The verdict stays in the status line, a mismatch still stops playback with its message, and a file the own decoders cannot play is still refused (strict mode of 1.29). The strict-stop message is now shown outside the MD5 block so the audio focus message appears too.

## v1.35 (branch hifi-v1-35, from hifi-v1-34) - visual only: cut spacers instead of fades, one floating window background
- Every fade is gone (Album list top and bottom, queue list top and bottom, the cover and info dock leaving Now Playing). EdgeCutLayout (was EdgeFadeLayout) now clips the content hard: at its top edge (under the header) and 8dp above the mini player card at the bottom; no mask, no gradient, no offscreen layer. The cover host is a plain FrameLayout (its fade was never switched on).
- The Setting window and the album long-press window used a solid background (page surface mixed 12% toward the ink colour, 24dp corners; the only transparency is the dim behind the window, 45% and 55%). That is now floatingBackground() and the mini player card (Album and queue) uses it too, so the three always match.
- Audio, check, volume guard and audio focus are unchanged from 1.34.

## v1.36 (branch hifi-v1-36, from hifi-v1-35) - smaller window corners, no mini player in the queue, Album bottom fade as in 1.20
- The plan to bring back the slide-left-to-Album is cancelled (nothing changed there).
- Corners: floatingBackground() 24dp -> 12dp, so the Setting window, the album long-press window and the mini player card are all less round (still one shared background).
- Mini player: removed from the queue view. It only exists on Album now (shown only when the page is Album); the queue no longer raises, lowers or animates it. The queue list has no extra bottom room (12dp) and is cut plainly at the top of the navigation bar.
- Bottom of Album: extra bottom space reduced (list bottom padding 112 -> 88dp), and the 8dp cut spacer is replaced by the 1.20 soft fade (transparency mask toward the backdrop, full 24dp above the card, gone at the middle of the card and over the navigation-bar area). The fade is automatic and only on the Album list, behind the mini player; no fade anywhere else (top of the lists, queue, cover and dock stay plain cuts).
- Background: the flat theme veil over the cover backdrop 50% -> 60% (Dark black, Light white); still one layer.
- Audio, check, volume guard and audio focus unchanged from 1.35. Version 1.36 (code 37), installs over 1.35.

## v1.37 (branch hifi-v1-37, from hifi-v1-36) - floating queue window, queue button next to the title
- The queue is now a floating window like Setting and the album options: the same floatingBackground() (same colour, 12dp corners), same width (screen minus 16dp each side), 80% of the screen height, dim 55% behind it. No mini player in it.
- Tapping a track plays it and closes the window (back on Now Playing, no page animation needed because the window only opens from Now Playing). Tapping outside the window or Back closes it too. CLEAR stays in the window header.
- Trigger: a 3-line queue icon (new ic_queue_24.xml, follows Dark / Light ink) level with the track title on the right. Its right edge is the end of the progress bar (same 30dp side padding); the title text gets 40dp shorter. The old swipe up to open the queue is off. Swipe down to Album, the Album mini player and everything else are unchanged.
- The rows are the same renderQueueRows rows; the list view is moved into the window while it is open and taken out when it closes. The old slide-up overlay code (queuePanel, applyQueueProgress ...) is still in the file but can no longer be triggered.
- Audio, check, volume guard and audio focus unchanged from 1.36. Version 1.37 (code 38), installs over 1.36.

## v1.38 (branch hifi-v1-38, from hifi-v1-37) - slide left to Album, old code removed
- Going to Album is the sideways page slide again, like the old one: drag left on Now Playing, the page follows the finger, slides out and Album slides in (or springs back). Dragging right on Album comes back the same way. The seek bar, horizontal lists and a running animation never start a drag. The Album mini player still returns to Now Playing by tap or swipe up, and Back / playing a track still use the mini-player return animation. Swipe down to Album is gone.
- Code that was only switched off in 1.37 is deleted (file went from 4490 to about 4035 lines): the whole slide-up queue overlay (queuePanel, queue progress / drag / prewarm / geometry, the mini player animation inside the queue), the swipe-down-to-Album drag and its commit animation (applyAlbumProgress, startAlbumDrag, commitToAlbum ...), the sidewaysPageSwipe switch, the plain-cut mode of EdgeCutLayout (now only the Album fade), the artHost field and the unused swipe callbacks of SwipeFrameLayout (now TouchFrameLayout, same touch behaviour). makeQueuePanel became makeMiniPlayer (builds the Album mini player and the empty queue row list).
- Nothing else changed: queue window, button, Album fade, themes, audio. Version 1.38 (code 39), installs over 1.37.

## v1.39 (branch hifi-v1-39, from hifi-v1-38) - mini player returns with the slide, swipe up removed
- Coming back to Now Playing (tapping the mini player, Back, and the play actions of Album) now uses the same slide as dragging right on Album: the Album page leaves to the right, Now Playing enters from the left. The mini player sinks while the page leaves.
- Going to Album (slide left) the mini player rises from the bottom edge with the soft overshoot (as in 1.17); in 1.38 it had just appeared in place, because the old animation was deleted with the swipe-down code.
- The swipe up gesture and animation of the mini player is removed (finger-following lift, its fade, its touch hit-test and state): only a tap on the mini player returns. Also removed: the layer switching and the shared animator of the old transition.
- One shared routine (slidePageTo / slideCommit) drives drag-release, tap, Back and play actions; a token makes an older slide's end action do nothing when a newer one starts.
- Audio, queue window and everything else unchanged. Version 1.39 (code 40), installs over 1.38.

## v1.40 (branch hifi-v1-40, from hifi-v1-39) - LOG AUDIO PATH in Setting, no MD5 text in the info line
- The MD5 verdict is no longer written into the info (status) line on Now Playing. The green / orange BIT-PERFECT label still follows the verdict, and a strict stop still shows its message.
- New subtitle LOG AUDIO PATH in the Setting window (the gear on Album), under ALBUM COLUMNS: a scrollable list (220dp high) of the last 50 tracks, newest first, saved across app starts (new AudioPathLog.kt, SharedPreferences "audio_path_log"). Each entry: date and time, verdict, track name, then a grey line with source format, bit depth, kHz, which decoder made it (own FLAC / ALAC / WAV or Android MediaCodec) and, for a mismatch, the per-second diagnosis.
- What is logged: every track whose check finished (MD5 MATCH, no MD5 in file, MD5 DIFFERENT, not comparable), files refused by strict mode (NOT PLAYED, with the mime type) and playback errors. Colours: normal lines use the regular text colours; DIFFERENT / not comparable are orange; refused files and errors are red.
- Removed because nothing used them any more: DirectOutputState.checksumText and checksumToast (the toast was already gone since 1.34).
- The audio path, the check itself, strict mode, volume guard and audio focus are unchanged; the log only records what the check already decided. Version 1.40 (code 41), installs over 1.39.

## v1.41 (branch hifi-v1-41, from hifi-v1-40) - fixed Now Playing layout, one-line audio path log
- Now Playing: the title is always exactly one line. A long title is cut with "..." where the queue button starts (the title already ends 40dp before the button). The artist line is also one line with "..." (it could wrap and move things before). The title padding at build time now equals the padding applied when a track starts (it was 0/2dp at first and 2/1dp afterwards, a 1dp jump), and an empty title still keeps one line of height. Result: the cover size, the position of the title, the queue button and the lines below no longer change with the length of the title or artist.
- LOG AUDIO PATH: one line per entry: the track name on the left, cut with "..." at the start of the result, the result on the right (MD5 MATCH, MD5 DIFFERENT, NOT PLAYED ...). The time and detail line are no longer shown (still saved in the entry). Colours as in 1.40.
- Audio, queue window and everything else unchanged. Version 1.41 (code 42), installs over 1.40.

## v1.42 (branch hifi-v1-42, from hifi-v1-41) - the Now Playing title really is one line
- Found why long titles still took 2 lines in 1.41 (and 2+ lines in 1.37 - 1.40): the title view called isSingleLine = false AFTER setting maxLines, and Android's setSingleLine(false) resets maxLines to unlimited, so the line limit never applied. The call is removed; the title is now minLines = 1 / maxLines = 1 with "..." at the end (at the queue button), so cover size and positions stay fixed.
- Audio dumps with the own decoders (hasil_dump_update.zip, nine files: FLAC 16/24-bit at 44.1 / 48 / 88.2 / 96 kHz, ALAC 16 / 24-bit 44.1 / 96 kHz, WAV): every one shows the DIRECT output thread, AUDIO_FORMAT_PCM_24_BIT_PACKED at the file's own sample rate, wired headphone device, one track at 0 dB (L, R, VS), no underruns, only the Qualcomm "Volume listener for Music" effect (volume indication only).
- Nothing else changed. Version 1.42 (code 43), installs over 1.41.

## v1.43 (branch hifi-v1-43, from hifi-v1-42) - Now Playing only: clear cover backdrop, no album art, no thumb, bolder progress
- Progress bar: the thumb is gone (thumb = null, seekThumb() deleted) and the track is bolder: 2dp -> 4dp (PROGRESS_THICKNESS_DP). Dark and Light.
- The separate album art box is removed (artBox, artImage, its size / centre routine sizeDarkArt, the pause scale animation and constants are deleted). The controls column stays exactly where it was (anchored to the bottom of the page).
- Visual order on Now Playing, from the bottom: the shared blurred cover backdrop with the dark / light veil (unchanged, as on the other pages), fading upward into the clear cover. New NowPlayingCoverView sits in the stage behind the header: the sharp cover (centre-crop, full width, 66% of the screen height) is plain with no veil and no extra layer from the top down to 50% of the screen height, then fades out between 50% and 66% (alpha 100% -> 44% -> 0%) over the backdrop. On the 18:9 G6 the top 50% is exactly a full-width square.
- Only Now Playing: the view is visible on that page only and it slides / fades with the page (drag, tap, Back, play actions) because every page slide is mirrored on it (coverSet / coverFollow). Album and the other pages keep their blurred backdrop.
- Gone with the box: the Hi-Res badge on the big cover (the mini player art on Album keeps its badge). Tracks without a cover show "NO COVER ART" in the middle of the page above the controls on the plain backdrop.
- The cover is the 1200 px Now Playing bitmap scaled up (about 1.6x with the crop), so it is a little softer than a native-size image. Audio, queue window, log and everything else unchanged. Version 1.43 (code 44), installs over 1.42.

## v1.44 (branch hifi-v1-44, from hifi-v1-43) - Now Playing: full-width cover above the title, blur closes both edges
- The Now Playing cover is the whole picture fitted to the full screen width (no left/right cut), placed right above the title row (its bottom edge is the top of the title row; the header gap is ignored). Plain, sharp, no veil.
- Its bottom 30% fades into the blurred backdrop and its top 12% fades too (when there is room above it, i.e. under the status bar / header), both down to 0%, so the blur closes the edges exactly. Where the cover would run past the top of the screen it is simply cut there.
- Hi-Res logo: stays at the right corner, drawn in front of the blur, so it sits above the queue symbol (same right edge, hangs above the title row without moving anything).
- Resolution: the Now Playing cover is decoded at 1440 px (the screen width, exact scale, never upscaled; was 1200 px with power-of-two halving, often 600-750 px), cache back to 48 MB, own disk-cache key. Not raised further.
- Background veil (Dark black / Light white) is 0x99 = 60%, unchanged: it was already set to 60% in 1.36.
- Audio, queue window, log unchanged. Version 1.44 (code 45), installs over 1.43.

## v1.45 (branch hifi-v1-45, from hifi-v1-44) - Now Playing: cover clear up to the status bar, bigger Hi-Res logo, logo no longer clipped
- The cover now starts at the very top of the screen (status bar) and has no top fade: clear from the top. Still full width, whole picture; only the bottom 30% fades into the blur.
- Hi-Res logo: 12% of the screen width (the same share the old album-art badge had of the cover), and hangs higher (8dp above the title row, same right edge as the queue symbol).
- Bug fixed: the top of the logo was cut because playerPanel, the page content and the scroller clipped their children; clipping is off on them now.
- Version 1.45 (code 46), installs over 1.44. Audio unchanged.

## v1.46 (branch hifi-v1-46, from hifi-v1-45) - Now Playing: cover under the status bar, softer fade, smaller Hi-Res logo
- The cover starts right under the status bar; the status bar area is a hard cut showing the plain blurred backdrop (no fade at the top).
- Softer bottom fade, taken from the reference (Apple Music-style): it now starts at 50% of the cover height (was 70%) and follows a smootherstep curve with 17 stops down to 0% at the cover's lower edge, so the gradient has no visible start or end.
- Hi-Res logo 12% -> 8% of the screen width, same position (hangs above the title row, right edge as the queue symbol).
- Version 1.46 (code 47), installs over 1.45. Audio unchanged.

## v1.47 (branch hifi-v1-47, from hifi-v1-46) - Now Playing: shorter bottom fade
- The cover fade now starts at 60% of the cover height (was 50%), so the clear part is larger; same smootherstep curve, still ends at 0% at the cover's lower edge. Nothing else changed. Version 1.47 (code 48), installs over 1.46.

## v1.48 (branch hifi-v1-48, from hifi-v1-47) - Now Playing: content raised, bigger text, Hi-Res logo beside the text, queue button at the bottom centre
- Queue button moved from the title row to the bottom centre, in its own row right after the two info lines (56 x 40dp touch area, 26dp icon). The controls column is anchored to the bottom, so this row pushes the whole column (title, progress, controls, info) up under the cover by its height; the bigger text raises it a little more. The cover and its fade are unchanged.
- Text: title 17 -> 19sp, artist 13 -> 15sp, album 11 -> 13sp (still one line each, cut with "...").
- Hi-Res logo 8% -> 10% of the screen width, now on the right, vertically centred on the title / artist / album block, right edge at the end of the progress bar. The text block always leaves room for it, so lines do not shift whether or not the track is hi-res.
- Version 1.48 (code 49), installs over 1.47. Audio unchanged.

## v1.49 (branch hifi-v1-49, from hifi-v1-48) - Now Playing: cover to the top edge, logo 9% level with the title, queue button above the info
- The cover (and so its fade) goes up to the very top edge of the screen, behind the status bar (no cut spacer any more); the fade itself is unchanged, so the whole picture and fade move up by the status bar height.
- Hi-Res logo 10% -> 9% of the screen width, top edge level with the title (not centred any more).
- Dock order: transport, queue button (centred), then the two info lines at the very bottom as before 1.48 (queue button and info swapped).
- Version 1.49 (code 50), installs over 1.48. Audio unchanged.

## v1.50 (branch hifi-v1-50, from hifi-v1-49) - Now Playing: title block raised, side gaps as the header / Album
- Title, artist, album and the Hi-Res logo sit 16dp higher (META_RAISE_DP): the gap between that block and the time / progress row grew by 16dp; the dock is anchored to the bottom, so progress, controls, queue button and info do not move.
- Side gaps: the title block and the progress bar (time labels too) had 30dp on both sides, which did not match the header (20dp text inset) or the Album content. They now use 20dp on both sides (SEEK_SIDE_PAD_DP 30 -> 20), so the progress bar and the title area are 20dp longer in total, left and right equal. Transport buttons and the info lines keep their old 30dp (new DOCK_SIDE_PAD_DP) so they do not move.
- Version 1.50 (code 51), installs over 1.49. Audio unchanged.

## v1.51 (branch hifi-v1-51, from hifi-v1-50) - Now Playing: no header title, time labels and queue button up, info down
- The "Now Playing" text in the header is gone on Now Playing (empty title; the dark / light icons stay). Album keeps its "Album" title.
- Time labels (elapsed / duration): 4dp higher above the progress bar (gap bar-labels +4dp, gap title block-labels -4dp), so the title block and the bar do not move.
- Queue button 4dp higher (gap under the transport controls 2dp -> -2dp). Info lines 4dp lower (STACK_BOTTOM_PAD_DP 8 -> 4 and gap above the info 2dp -> 10dp); the sums are balanced so the progress bar and the transport controls stay where they were.
- Version 1.51 (code 52), installs over 1.50. Audio unchanged.

## v1.52 (branch hifi-v1-52, from hifi-v1-51) - Now Playing: two-line queue icon, content up, bigger controls
- Queue icon: 3 lines -> 2 lines (same width and thickness, centred in the 24dp icon).
- Transport buttons a little bigger: play 60 -> 66dp (icon 38 -> 44dp), previous / next 52 -> 56dp (icon 32 -> 36dp).
- Raised: title / artist / album, the Hi-Res logo, progress bar, controls and queue button all ride up. The gap above the info lines grew 10 -> 14dp (+4dp) and the bigger play row adds 6dp more above the controls; the info lines (anchored to the bottom) do not move. Everything above the controls rises about 10dp, the queue button 4dp.
- Version 1.52 (code 53), installs over 1.51. Audio unchanged.

## v1.53 (branch hifi-v1-53, from hifi-v1-52) - widget theme fix, sleeker queue icon, backdrop veil 70%
- Widgets never followed the theme since the rebrand. Cause (found in code, an app bug, not the launcher): the player saves "light_theme" with Activity.getPreferences(), whose file is named after the Activity class. In the old app (id g6hifiplayer) Android shortened it to "MainActivity"; in Hifi the app id is id.hifi.player while the class is still id.g6hifiplayer.MainActivity, so the file is "id.g6hifiplayer.MainActivity". The widgets (G6Widgets.kt, four places) still read the file "MainActivity", which does not exist in Hifi, so they always rendered Dark. Fix: one helper isLightTheme() reads the real file first and the old name as a fallback; setLightTheme() already calls G6Widgets.renderAll(), so the widgets repaint when the theme is switched and on every app start.
- Queue icon: lines longer (21 -> 23 of 24 units wide) and closer (gap 6 -> 4 units), so it is sleeker.
- Backdrop veil 60% -> 70% (0x99 -> 0xB3), Dark black / Light white, one flat layer.
- Version 1.53 (code 54), installs over 1.52. Audio unchanged.

## v1.54 (branch hifi-v1-54, from hifi-v1-53) - widget colours as the app, queue icon smaller and at 70%
- Widgets, grey text (artist / time, 4x1 and Text 4x2): Light 0xFF686875 -> 0xFF484855 (the darker grey the app got in 1.22; the widgets never received it), Dark 0xFFE0E0E6 -> 0xFFA4A4B2 (the app's grey). The placeholder cover icon on Light follows the Light grey too.
- Widgets, progress bar: the unplayed part now has the player's opacity and colours (Dark 0xB35E5E66, Light 0xB3D6D6DC = 70%) instead of the old opaque 0xFF303038 / 0xFFD8D8E2; the played part was already the same accent 0xFF6662C6.
- Queue icon: 26 -> 22dp, the 56 x 40dp touch area is unchanged; its opacity is 70%, the same as the unplayed part of the progress bar.
- Version 1.54 (code 55), installs over 1.53. Audio unchanged.

## v1.55 (branch hifi-v1-55, from hifi-v1-54) - queue icon 24dp, longer, exactly the unplayed progress colour
- Why the icon still looked brighter than the progress bar in 1.54: a white icon at 70% view alpha is 70% white, while the unplayed track is 70% of a dark grey (0x5E5E66); the same opacity gives a much lighter result. Now the icon is tinted with the track's own ARGB (Dark 0xB35E5E66, Light 0xB3D6D6DC), so it has the same colour and opacity as the unplayed track, on both themes (also after a theme switch).
- Size 22 -> 24dp (touch area 56 x 40dp unchanged). Longer lines: the 1.53/1.54 vector had bars starting at x = 0.5 with 1.5-unit round ends, so both ends were clipped by the 24-unit icon box; the bars now span the full 0..24 with proper round ends (about 2dp longer on screen with the bigger size).
- Version 1.55 (code 56), installs over 1.54. Audio unchanged.

## v1.56 (branch hifi-v1-56, from hifi-v1-55) - queue icon like the reference, grey text colour
- Queue icon redrawn like the reference picture: two rows, each a round dot on the left and a long rounded bar (dot and bar the same thickness, 3.6 of 24 units; rows 7.2 units apart; the bars run to the right edge). The drawing spans the whole 24 x 24 box, shown at 24dp (touch area 56 x 40dp unchanged).
- Colour: no longer the unplayed-progress colour with opacity; it is the grey text colour of each theme (Dark 0xFFA4A4B2, Light 0xFF484855), also after a theme switch.
- Version 1.56 (code 57), installs over 1.55. Audio unchanged.

## v1.57 (branch hifi-v1-57, from hifi-v1-56) - rounded icons everywhere (player, widgets, notification)
- Rounded-icon philosophy: each solid icon now has a round-joined, round-capped stroke in its own colour on top of the fill, so sharp corners turn into small radii (the radius is half the stroke width). Not "super round", just no sharp tips. The icons grow by half the stroke width on each side.
- Big player icons (play / pause / previous / next on the transport row, shown at 36 - 44dp): stroke 0.9 units, only a little, because they are scaled up (play 44dp => about 0.8dp radius).
- Small contexts get separate copies ic_play_s / ic_pause_s / ic_previous_s / ic_next_s with stroke 1.6 units: notification actions, the 4x1 and Text 4x2 widgets (layouts and code), and the Album mini player. Notification "Close player" X and the 2x2 widget play / pause: stroke 1.6, in place. Settings gear: stroke 1.0. Queue icon (1.56), moon, sun and the Hi-Res logo were already round.
- Not changed: ic_stat_music.png (status-bar small icon and the Text widget's open-player icon) is a raster picture, not a vector, so it keeps its shape; say if it should be redrawn as a rounded vector.
- Version 1.57 (code 58), installs over 1.56. Audio unchanged.

## v1.58 (branch hifi-v1-58, from hifi-v1-57) - LOG AUDIO PATH names the cause of every self-pause (diagnostics only)
- Why: the user reported playback stopping mid-track in some sessions (resume stops again; fixed by closing and reopening the player; same album, no other media app). The code could not tell who paused it, so this build records it.
- New PauseWatch.kt (display / diagnostics only; it never pauses or changes anything): whenever the player goes to pause on its own it adds one orange line to LOG AUDIO PATH: head = cause + the position in the track (for example "PAUSE: FOCUS LOST 1:23"), left = the track title, details saved = cause text, "at 1:23 / 3:43" and ExoPlayer's reason code.
- Causes named: FOCUS LOST (another app took the audio for good), FOCUS (CALL/ALARM) (transient loss), MEDIA BUTTON (media-session pause: headset button, another app or a connected device), JACK UNPLUGGED (the app's wired-headset watcher), STRICT CHECK (MD5 / check stop), and for pauses with no mark the reason ExoPlayer reports: OUTPUT CHANGED (audio becoming noisy), REMOTE COMMAND, SUPPRESSED, or UNKNOWN (APP). A held playback (transient focus suppression) is logged as "HELD: FOCUS (TEMP)".
- Not logged: the user's own pauses (in-app button, notification) and the end of a track.
- Version 1.58 (code 59), installs over 1.57. Audio path and look unchanged.

## v1.59 (branch hifi-v1-59, from hifi-v1-58) - MD5 check no longer starts mid-track and never stops the music
- Cause found by the user and confirmed in the code: when the player started a track from a saved / non-zero position (for example 1:50), OutputMd5 was armed from the start (armed = true at creation) and counted from there as if it were the start of the track. The count ran on into the next track and reached the file length at 1:50 of that next track (the 1:50 skipped at the start is "repaid" there), so it gave a false MD5 DIFFERENT and strict mode paused the music in the middle of the next track; after a resume the count restarted from zero and stopped again. Starting from 0:00 was always fine. The 1.58 log showed it: "PAUSE: STRICT CHECK 1:50" on the track after one that was started at 1:50.
- Fix 1: the very first data of an output now decides, like after a flush: only a stream whose first presentation time is within 150 ms of zero is checked; a mid-track start is not checked (no entry), and the rest of that session's tracks stay unchecked until the next flush (skip / seek / new start), as already happened after any seek.
- Fix 2: the MD5 verdict no longer stops playback at all (the strict stop is gone for MD5 DIFFERENT / not comparable): the verdict only exists after the track was played, so a stop could only hold back the next track and a wrong verdict used to pause the music. It is still written to LOG AUDIO PATH (orange, "playback continues (log only since 1.59)"). The format restriction of strict mode (only FLAC, ALAC, WAV) is unchanged, and so are 1.58's pause-cause lines.
- Version 1.59 (code 60), installs over 1.58. The audio data path is not touched (OutputMd5 only reads what the sink accepted).

## v1.60 (branch hifi-v1-60, from hifi-v1-59) - widgets: text widget bigger and tighter, 4x1 fills its area
- Decided with the user: no widget settings (he is not sure the launcher shows what the code says, it sometimes keeps old widget states), only direct fixes from his screenshot.
- Text 4x2 widget: title, progress and play are one tight group centred vertically in whatever height the launcher gives (the two flexible gaps are gone; they spread the parts apart and left big gaps); text 26 / 15 / 12 sp x 1.25 = 32.5 / 19 / 15 sp (was 26 / 15 / 12); play icon 40 -> 44dp; gaps between the three parts 6dp (TEXT_WIDGET_GAP_DP).
- 4x1 widget: the card fills the area the launcher gives (it was a fixed 70dp card centred in the area, so it looked small in a tall area); cover grows with the card height (adjustViewBounds), the button row takes the remaining height (buttons 40dp wide, icons scale); text 13 / 10 sp x 1.10; edge gap top / bottom 6dp (CARD_EDGE_DP).
- Sizes are constants at the top of G6Widgets.kt (TEXT_WIDGET_SCALE, TEXT_WIDGET_GAP_DP, CARD_SCALE, CARD_EDGE_DP); layout bases are in the two layout files.
- After installing: remove the two widgets from the home screen and add them again (a launcher keeps the old layout of a widget that is already placed).
- Version 1.60 (code 61), installs over 1.59. Audio unchanged.

## v1.61 (branch hifi-v1-61, from hifi-v1-60) - 4x1 widget content with a gap on all sides, text widget gaps bigger
- 4x1 widget: the card's inner padding grew (left 8 -> 12dp, right 8 -> 14dp, top and bottom 3 -> 10dp), so the cover is smaller and nothing touches the edges of the card background any more; the gap between the cover and the text column 8 -> 10dp. The card still fills the area the launcher gives (edge gap 6dp), the cover and the button row still scale with its height.
- Text 4x2 widget: gap between title, progress and play 6 -> 16dp (TEXT_WIDGET_GAP_DP).
- Version 1.61 (code 62), installs over 1.60. After installing, remove and re-add the widgets. Audio unchanged.

## v1.62 (branch hifi-v1-62, from hifi-v1-61) - text widget gaps a little bigger
- The user called the 4x1 widget perfect (1.61, unchanged). Text 4x2 widget: gap between title, progress and play 16 -> 22dp (TEXT_WIDGET_GAP_DP). Nothing else changed. Version 1.62 (code 63), installs over 1.61.

## v1.63 (branch hifi-v1-63, from hifi-v1-62) - widget progress bar thicker, 4x1 controls moved down
- Progress bar in both widgets (4x1 and text 4x2): 2dp -> 4dp thick (bitmap 6 -> 10 px, still fully rounded).
- 4x1: the button row is aligned to the bottom (icons sit level with the bottom of the cover; top padding 6dp, bottom 0), progress margin 2 -> 3dp.
- Version 1.63 (code 64), installs over 1.62. After installing, remove and re-add the widgets. Audio unchanged.

## v1.64 (branch hifi-v1-64, from hifi-v1-63) - widget background and corners like the mini player
- Checked: mini player card and floating windows use floatingBackground() = page surface mixed 12% towards the ink colour, corners 12dp. Dark theme = #1E1E1E, light theme = #DADADD (opaque).
- 4x1 widget card and the 2x2 play button background now use exactly those colours (opaque, no longer 70% black / white) and 12dp corners (were 22dp and 18dp). Pressed 2x2 button: 12% -> 20% mix (#333333 / #C6C6C9). Text 4x2 has no background, unchanged.
- Version 1.64 (code 65), installs over 1.63. Remove and re-add the widgets after installing. Audio unchanged.

## v1.65 (branch hifi-v1-65, from hifi-v1-64) - real previews in the widget picker
- The picker showed the app icon in a white rounded square for all three widgets (no preview was declared). Each widget provider now has android:previewImage: g6_preview_4x1 / g6_preview_4x2 / g6_preview_2x2 (PNG in drawable-nodpi, drawn like the real widgets in the dark theme: dark card #1E1E1E 12dp, sample cover, title, artist, progress, controls). No previewLayout (it would show the empty layout on Android 12+).
- Version 1.65 (code 66), installs over 1.64. The picker may cache the old preview: if it still shows the icon, remove the widgets, restart the launcher or the phone. Audio unchanged.

## v1.66 (branch hifi-v1-66, from hifi-v1-65) - widget corners 12 -> 16dp
- 4x1 widget card and 2x2 play button: corners 16dp (the user found 12dp slightly too square once the widget was resized). Picker preview images redrawn with 16dp. Colours unchanged. Version 1.66 (code 67), installs over 1.65. Audio unchanged.

## v1.67 (branch hifi-v1-67, from hifi-v1-66) - 4x1 widget card narrower
- The 4x1 card stuck out to the left of the home-screen icons and the text widget. Gap between the card and the left / right of the widget area 12 -> 20dp (CARD_SIDE_DP, same as the text widget's 20dp), so the card edge lines up with the icons. Card inner padding, cover and controls unchanged (they follow the card). Version 1.67 (code 68), installs over 1.66. Audio unchanged.

## v1.69 (branch hifi-v1-69, from hifi-v1-67; 1.68 is skipped) - new widget: Hifi Cover 4x2
- New provider G6WidgetCover4x2Provider (label "Hifi Cover 4x2"), layout g6_widget_cover_4x2, info g6_widget_cover_4x2_info (250x130dp, horizontal resize), preview g6_preview_cover_4x2.png. Based on the text 4x2 widget: no background, side gap 20dp (like the other widgets).
- Left: big rounded cover (corner 16% of its side, COVER_WIDGET_FRACTION; Hi-Res badge as on the other covers), fills the height; tapping it opens Now Playing. Right (shorter area because of the cover): title (18sp bold, 2 lines max), artist 13sp, progress bar, play / pause, as one group centred vertically; gaps 10dp (COVER_WIDGET_GAP_DP). No player logo, no time labels.
- Wired into refresh / refreshProgress / refreshPlayback / renderAll. The other three widgets are unchanged. Version 1.69 (code 70, above the dropped 1.68 which had 69), installs over 1.67. Audio unchanged.

## v1.70 (branch hifi-v1-70, from hifi-v1-69) - cover widget layout
- Cover: max 146dp wide (android:maxWidth, still height-bound, centred), so its right edge lines up with the right edge of the second home-screen icon column (measured on the user's screenshot at 3.5 px/dp: icons 124..636 px = 146dp).
- Right of the cover: title (24sp bold, max 2 lines, ellipsis "..."), artist 15sp, below them prev / play / next (38dp each, left-aligned with the title). Progress moved to the bottom over the full width. Prev / next actions wired (codes 4403 / 4404). Preview image redrawn.
- Version 1.70 (code 71), installs over 1.69. Audio unchanged.

## v1.71 (branch hifi-v1-71, from hifi-v1-70) - cover widget: bigger cover, bigger title
- In 1.70 the cover (adjustViewBounds + maxWidth) came out smaller than the available height (118dp instead of ~137dp) and left a gap above the progress bar. Now the cover box is a fixed 146dp wide x full row height with scaleType fitStart, so the cover is as big as the row allows (max 146dp, right edge in line with the second icon column, left edge fixed). Gap between row and progress 8 -> 6dp.
- Right column is top-aligned: title (30sp bold, includeFontPadding off, max 2 lines + "...") starts at the top of the cover, then artist 16sp, then prev / play / next.
- Version 1.71 (code 72), installs over 1.70. Audio unchanged.

## v1.72 (branch hifi-v1-72, from hifi-v1-71) - cover 4x2 centred + new "Hifi Simple 4x1"
- Real density on the user's launcher is ~2.8 px/dp (not 3.5): the 146dp cover of 1.71 ended at 532 px while the second icon column ends at 636 px, so the cover box is now 183dp x 183dp (fitStart) -> right edge in line with the icons.
- Cover 4x2: the whole group (cover row + 6dp + progress) is centred vertically in the widget (root gravity center_vertical, no weights), so the gap above and below is equal and small. prev / play / next boxes 38 -> 60dp wide (wider spacing), row marginStart -16dp to keep it in line with the title. Title still top-aligned with the cover.
- New widget G6WidgetSimple4x1Provider "Hifi Simple 4x1" (layout g6_widget_simple_4x1, info g6_widget_simple_4x1_info, preview g6_preview_simple_4x1): no background, side gap 20dp, cover on the left (full height, rounded 16%), on the right song 22sp bold / artist 16sp / album 14sp (one line each, "..."), tap anywhere opens Now Playing. No buttons, no progress.
- Version 1.72 (code 73), installs over 1.71. Audio unchanged.

## v1.73 (branch hifi-v1-73, from hifi-v1-72)
- Cover 4x2: cover back to 146dp, buttons back to 38dp wide; controls pushed to the bottom of the cover (weighted spacer above them, group still centred in the widget); title 32.5sp / artist 18.75sp (same as the text 4x2: 26 / 15 sp x 1.25), title one line + "...".
- Simple 4x1: song 26sp, artist 19sp, album 16sp; only the cover opens Now Playing (no more tap on the text).
- Version 1.73 (code 74), installs over 1.72. Audio unchanged.

## v1.74 (branch hifi-v1-74, from hifi-v1-73) - "Hifi Cover 4x2" widget removed
- The Cover 4x2 widget (added in 1.69) did not work out and is removed completely: provider, manifest receiver, layout, info xml, preview image and every code path. Widgets left: 4x1 card, 2x2, Text 4x2, Simple 4x1 (kept as in 1.73).
- Version 1.74 (code 75), installs over 1.73. Audio unchanged.

## v1.75 (branch hifi-v1-75, from hifi-v1-74) - Blur 4x1 widget + one more blur level
- Blur strength lives in one place now: BackdropBlur.kt (RADIUS, PASSES). App backdrop blur radius 3 -> 4 (one level more; 2 passes, cover scaled to 96 px, then flat 70% veil as before). The widget uses the same object, so "more / less blur" changes both.
- New widget G6WidgetBlur4x1Provider "Hifi Blur 4x1" (layout g6_widget_blur_4x1, info g6_widget_blur_4x1_info, preview g6_preview_blur_4x1): same outer size and gaps as the 4x1 card (20dp sides, 6dp top/bottom). Background = one bitmap drawn to the widget's own size (from launcher options; theme surface colour + blurred cover centre-cropped + 70% veil, corners 16dp). Left: title, artist, progress bar inside the box; right: one big 52dp round play button (circle = ink colour, icon = surface colour, so it follows the theme). Tap on the card opens the app; the progress and play icon are updated by partial updates like the other widgets.
- Version 1.75 (code 76), installs over 1.74. Audio unchanged.

## v1.76 (branch hifi-v1-76, from hifi-v1-75) - Blur 4x1: rounded-square play button
- The round play button of 1.75 is replaced by a rounded square like the 2x2 widget's button (theme colours #1E1E1E / #DADADD, corners 0.28 of the side), icon in the theme ink colour, size = 60% of the card's inner height (20% free above and below, computed from the launcher's widget height; the square bitmap is drawn by playSquare()). Preview image redrawn. 1.75 (round button) was superseded and not used.
- Version 1.76 (code 77), installs over 1.75 / 1.74. Audio unchanged.

## v1.77 (branch hifi-v1-77, from hifi-v1-76) - Blur 4x1 tweaks
- Play / pause icon smaller (padding 22% -> 31% of the square; square size unchanged, the user found it right). Left column gets the same top/bottom padding as the square, with a weighted spacer: title top = top of the square, progress bar bottom = bottom of the square. Title 15 -> 17sp, artist 12 -> 13.5sp.
- Version 1.77 (code 78), installs over 1.76. Audio unchanged.
