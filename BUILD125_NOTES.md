# Build 126 — Standalone PCM Engine Lab

## Baseline and isolation
- Based on stable Build 91 commit `e90323a5e6b92c17ae60e08faa8f0559e2b67895`.
- Stable MainActivity and playback service are preserved.
- Uses a distinct applicationId (`id.g6hifiplayer.enginelab`) and separate launcher screen, allowing side-by-side installation.

## Experimental path
1. Select an audio file through Android's Storage Access Framework.
2. Existing FFmpeg decoder produces a standard PCM16 WAV cache file without explicit sample-rate or channel-count overrides.
3. New code parses RIFF/WAVE chunks itself and streams bounded PCM chunks.
4. The pipeline streams PCM16 directly through one streaming AudioTrack instance, avoiding an unnecessary PCM24 intermediate.

## First milestone
Determine whether decoding and a custom PCM streaming loop can produce audible playback independently of ExoPlayer/Media3 playback.

## Limitations
- Final output uses Android AudioTrack. This does NOT bypass AudioFlinger or prove DIRECT routing, native Hi-Fi DAC selection, or bit-perfect output.
- FFmpeg decodes to PCM16 directly because the Android 9 output track is PCM16 in this test.
- This is not yet a from-scratch codec implementation: FFmpeg decodes compressed formats. The WAV parser, bounded streaming loop, validation, diagnostics, and output-drain wait are implemented in the experimental engine.
- Do not integrate with the stable player until audible playback and lifecycle behavior are verified.
