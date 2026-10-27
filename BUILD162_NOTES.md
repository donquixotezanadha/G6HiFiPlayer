# Build 1.62 (main player): real 24-bit reaches the DIRECT output

## Why
Build 1.61 phone test: hi-res files did not play right (no sound / error) and the info line was not green.
Reading the Media3 1.10.1 source (`DefaultAudioSink.configure`) showed the root cause: with the sink's float output OFF, the sink always puts `ToInt16PcmAudioProcessor` in front of the output. So even a real 24-bit WAV (made by FFmpeg from the FLAC) was cut to 16-bit **before** it reached our native DIRECT output. In 1.61 the 24-bit work was therefore undone inside Media3, and the info line could only show DEPTH REDUCED TO 16-BIT (orange).

## What changed
- `PlaybackKeepAliveService`: `DefaultAudioSink` now has float output ON (and `setEnableAudioFloatOutput(true)` on the renderers factory). 24-bit (and deeper) PCM now travels as float (`ToFloatPcmAudioProcessor`); 16-bit PCM stays 16-bit. No volume/EQ/sonic processing is added (volume is ignored by the output, playback-speed processing is off).
- `DirectAudioOutput.convert`: new float branch. A float carries a 24-bit integer exactly (24-bit mantissa; the scaling error of `ToFloatPcmAudioProcessor`, 1/0x7FFFFFFF, is ~5e-10, far below half a float step), so `round(f * 2^23)` returns the original integer: no rounding error, no gain, no dither. NaN -> 0, clamped to the 24-bit range. `carriedBits(float) = 24` for the info line.
- `DirectAudioOutputProvider`: float accepted (documented).
- Output hand-over between tracks (`DirectAudioOutput`): `release()` only switches "DIRECT active" off if it is still the current output (it used to switch it off after the next track's output was already open, turning the info line orange). `create()` waits up to 4 s for the DIRECT slot (was 1.5 s) and after 2 s closes the previous output itself instead of failing playback.
- `ExclusiveAudioFocus` rewritten: focus is kept when a track change/new queue passes through IDLE with playback still wanted, it is requested again when playback becomes active without it, and a pause caused by a focus loss is no longer mistaken for the user's own pause (the automatic resume after a call/alarm works again).
- `Pcm24Transcoder.prefetch` + a player listener: while a track plays, the next queue item is decoded in the background (single low-priority thread, no extra work for non-hi-res files), so the next hi-res track starts without waiting for FFmpeg.
- Playback error text now shows the cause chain and the last three native-output log lines, so a failure can be read off the screen.

## How to test
1. Play the 96 kHz / 24-bit FLAC: after "Decoding to 24-bit PCM…" the line should read `source 96.0 kHz / 24-bit -> output 96.0 kHz • PCM 24-bit` with the green BIT-PERFECT verdict.
2. Let it run into the next track (same and different sample rate): the green verdict must stay green, no error.
3. 44.1 kHz / 16-bit file: `PCM 16-bit padded to 24-bit`, green.
4. If something still fails: read the two lines under "Playback failed" on screen and send them.
5. Optional: `adb shell dumpsys media.audio_flinger`: DIRECT thread at the file's rate, HAL PCM_24_BIT_PACKED, the player's pid.

## Known limits
- Same as 1.61 (first play of a hi-res FLAC waits for FFmpeg; ALAC/WavPack/APE/DSD not converted).
- 32-bit integer sources are converted to float inside Media3 (24-bit mantissa) and end up as 24-bit at the output anyway (the DIRECT track is 24-bit packed).
