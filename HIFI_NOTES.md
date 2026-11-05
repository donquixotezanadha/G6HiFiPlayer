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

## v1.30 (branch hifi-v1-30, from hifi-v1-29) - headset-only mode
- The player plays only while a wired headset / headphone (the jack) is connected. HeadsetGuard.kt: play without the headset is refused (pause + message); unplugging while playing stops playback (rechecked after 150 ms); DirectAudioOutput does not open, start or write without the headset, so the speaker, Bluetooth and USB can never be used through this app.
- Route check: if the track starts and the output is read as NOT the DIRECT thread (flags without the DIRECT bit), playback is stopped with a message. Unreadable flags are not treated as a failure.
- Volume: the native track is STREAM_MUSIC, so ringer / notification volume cannot change it. The volume keys inside the app are bound to the music stream (volumeControlStream). While playing, a sudden single jump of 4 steps or more of the music volume (system restoring another device's level) is put back to the previous level and a toast says so.
- Not changed: audio focus / exclusive takeover from 1.61-1.67 (calls and alarms still pause and resume).

## v1.31 (branch hifi-v1-31, from hifi-v1-30) - headset plugged in after the track was opened
- Bug in 1.30: a track opened (or play pressed) without the headset left the player in an error state; plugging the headset in did not help until the app was closed and opened again.
- Fix: HeadsetGuard now (a) flags the player when the output was refused for lack of the headset (DirectOutputState.needsPrepare) and prepares it again 300 ms after a wired headset appears (queue and position kept, no autoplay unless it had been requested); (b) on unplug stops the player (queue and position kept) so the output is released and the next start opens a fresh DIRECT track on the headset; (c) the DIRECT-thread check now runs at every start, not only the first one; (d) the error text for the missing-headset case is "Headset-only mode: plug in the headset, the track loads by itself" instead of "Playback failed".

## v1.32 (branch hifi-v1-32, from hifi-v1-31) - headset prompt instead of "NOT BIT-PERFECT"
- Row 2 under the progress bar shows orange "PLUG IN THE HEADSET" (with "this player works with the headset jack only") while no wired headset is connected; it turns to the normal verdict (green BIT-PERFECT / DIRECT) by itself when the headset is plugged in. The play button is dimmed (alpha 40%) in that state; tapping it still shows the guard's message.
- The old "NOT BIT-PERFECT" (speaker) label and the "speaker" word are gone: that case can no longer play. Unplugging while playing shows one short toast "Headset removed: playback stopped". The status line follows plug / unplug events and refreshes when the app comes to the front.
