# Build 1.59 (Engine Lab)

## Result of the 1.58 test (dumpsys media.audio_flinger, taken during the tone)
- 4B (native, 96 kHz, PCM_24_BIT_PACKED, flags DIRECT): thread AudioOut_75 (I/O handle 117), type 1 (DIRECT), HAL format PCM_24_BIT_PACKED, 96000 Hz, AudioStreamOut flags 0x1 (DIRECT), one track from our pid (28448), track format 0x6, 96000 Hz, FrmCnt 38400, L/R 0 dB. Same picture as UAPP's AudioOut_45. => the native DIRECT path works from our app.
- 4C (native, 48 kHz, PCM_16_BIT, flags DIRECT, run alone): our track (pid 28448, format 0x1, 48000 Hz) is on AudioOut_15 = MIXER + DEEP_BUFFER (HAL 48000 Hz, PCM_16_BIT, processing PCM_16_BIT). So 48 kHz / 16-bit is NOT given DIRECT even without overlap, although the direct_pcm profile in the UAPP policy dump lists PCM_16 and 8 kHz..192 kHz. Something on this firmware sends 16-bit (maybe also <= 48 kHz) requests to the mixer. Consistent with the stock LG player resampling CD quality.
- Both threads carry one insert effect on the music session: "Volume listener for Music" (Qualcomm, UUID 08b8b058-0590-11e5-ac71-0025b32654a0). On the DIRECT thread it is configured at 96 kHz PCM_FLOAT. Not yet known whether it changes samples, and whether it is also present in UAPP's DIRECT dump.

## Changes in 1.59
- Button 5: automatic matrix, one native track at a time, 6 s tone + 4 s pause: 96k/24p (reference), 44.1k/24p, 48k/24p, 88.2k/24p, 176.4k/24p, 192k/24p, 44.1k/16, 48k/16. Each variant logs set()/start() results and NATIVE_PLAY_ROUTE (output handle, rate, flags). A handle that is not 13/21/29 (the mixer threads on this boot) means a separate DIRECT thread. No adb needed for the first answer.
- 4D Stop also stops the matrix.
- Goal: find out whether 44.1 kHz CD material can reach DIRECT by padding 16-bit to 24-bit packed (lossless, bit-perfect in value), which is the plan for the real player.

## Next
- Read the matrix log. If 44.1k/24p is DIRECT: take one adb dump for it to confirm, then wire the native DIRECT path into the main player (16-bit sources padded to 24-bit, 24-bit sources as is, hardware volume).
- If 44.1k/24p is not DIRECT: test 8_24 (format 4) and other rates before deciding.
