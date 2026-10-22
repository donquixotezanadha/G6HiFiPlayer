# Build 125 — Standalone PCM Engine Lab

## Baseline and isolation
- Based on stable Build 91 commit `e90323a5e6b92c17ae60e08faa8f0559e2b67895`.
- Stable MainActivity and playback service are preserved.
- Uses a distinct applicationId (`id.g6hifiplayer.enginelab`) and separate launcher screen, allowing side-by-side installation.

## Experimental path
1. Select an audio file through Android's Storage Access Framework.
2. Existing FFmpeg decoder produces a PCM WAV cache file without explicit sample-rate or channel-count overrides.
3. New code parses RIFF/WAVE chunks itself and streams bounded PCM chunks.
4. The pipeline converts signed PCM24 to PCM16 (or accepts PCM16/32 input) and writes through one streaming AudioTrack instance.

## First milestone
Determine whether decoding and a custom PCM streaming loop can produce audible playback independently of ExoPlayer/Media3 playback.

## Limitations
- Final output uses Android AudioTrack. This does NOT bypass AudioFlinger or prove DIRECT routing, native Hi-Fi DAC selection, or bit-perfect output.
- PCM24 -> PCM16 conversion is intentional for the first Android 9 playback milestone.
- This is not yet a from-scratch codec implementation: FFmpeg decodes compressed formats. The WAV parser, sample conversion, bounded streaming loop, and output lifecycle are newly implemented.
- Do not integrate with the stable player until audible playback and lifecycle behavior are verified.
