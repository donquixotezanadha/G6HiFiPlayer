# Build 1.32 — Direct PCM format compatibility probe

## Purpose
Build 1.32 is a diagnostic-only follow-up to Build 1.31. It does not change the PCM conversion or the selected playback transport.

Before creating the playback AudioTrack, it logs whether the Android framework exposes these encodings:
- PCM 16-bit
- PCM float
- PCM 8.24-bit
- packed PCM 24-bit
- PCM 32-bit

For each exposed encoding it logs the integer encoding value and the result of `AudioTrack.getMinBufferSize()` at the source sample rate and channel mask.

## Interpretation limits
- A missing encoding constant means the public Android framework API does not expose that named encoding on this OS build; it does not prove that no OEM-only path exists.
- A positive minimum buffer size is only a format capability hint. It does not prove that Audio Policy can open a matching output.
- The OEM DIRECT hook probe remains separate. A request is not counted as success unless live AudioFlinger/HAL diagnostics confirm a DIRECT output and the negotiated rate/format.
- Playback remains on the known normal AudioTrack path if no usable OEM hook exists.

## Test
On the LG G6 (Android 9), select and play the same local 96 kHz track used in Build 1.31, then export diagnostics. Compare the `PCM_ENCODING_CAPABILITY`, `DIRECT_REQUEST`, `AUDIOTRACK_INITIALIZED`, and external AudioFlinger/HAL records.
