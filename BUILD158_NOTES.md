# Build 1.58 (Engine Lab)
Why: the 1.57 dump (audio_flinger_build157.txt) was taken ~8 s after the tests and the 4C control (48 kHz/16-bit) overlapped the 96 kHz track. Findings:
- 4C (pid 5728, session 41) landed on AudioOut_15 = MIXER + DEEP_BUFFER although flags=DIRECT; it started while the 96 kHz track was still open, and direct_pcm holds one track, so the policy demoted it.
- 4B (96 kHz, format 6): set()=0, start=0, all 768000 frames written in real time, but no trace on any MIXER thread (D, 15, 1D); no DIRECT thread in the dump because AudioFlinger had already closed it. Likely DIRECT, unproven.
- A second 48 kHz run (pid 9213, session 89, 16:32:26) also went to AudioOut_15: needs a clean single run to know whether 48k/16-bit DIRECT is refused on its own.
Changes: native tests run one at a time (NATIVE_BUSY otherwise); 60 s tone; Stop button (4D); right after start() a NATIVE_PLAY_ROUTE line logs getOutput() (AudioFlinger thread = AudioOut_<hex handle>), sampleRate and track flags, sent to the log while the tone is still playing.
Test: headphones in the jack, press 4B only, wait for NATIVE_PLAY_ROUTE, run `adb shell dumpsys media.audio_flinger > af_4b.txt` during the tone, Stop. Repeat for 4C -> af_4c.txt. Optionally `adb shell dumpsys media.audio_policy > ap_4b.txt` during 4B.
Read: a thread with type 1 (DIRECT), 96000 Hz, HAL PCM_24_BIT_PACKED and our pid = success (same as UAPP's AudioOut_45).
