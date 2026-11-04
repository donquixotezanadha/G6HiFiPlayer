#!/usr/bin/env bash
# Hifi 1.27: checks that FlacFrameDecoder (the player's own FLAC decoder) is bit-exact against ffmpeg's FLAC decoder.
# Generates 24-bit test files (several rates, compression levels, stereo modes, mono, wasted bits), decodes each with
# FlacFrameDecoder and with ffmpeg, and compares the MD5 of the 24-bit PCM. Exits 1 on the first difference.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/../../app/src/main/java/id/g6hifiplayer/FlacFrameDecoder.java"
WORK="$(mktemp -d)"
command -v ffmpeg >/dev/null || { sudo apt-get update -qq && sudo apt-get install -y -qq ffmpeg; }
javac -d "$WORK/out" "$SRC" "$HERE/FlacTest.java"

gen() { # name rate channels extra-ffmpeg-args...
  local n=$1 r=$2 c=$3; shift 3
  if [ "$c" = 2 ]; then
    ffmpeg -v error -y -f lavfi -i "sine=f=997:r=$r:d=6,volume=0.3" -f lavfi -i "anoisesrc=r=$r:d=6:a=0.05:c=pink" \
      -f lavfi -i "sine=f=1500:r=$r:d=6,volume=0.2" -f lavfi -i "anoisesrc=r=$r:d=6:a=0.04:c=white" \
      -filter_complex "[0][1]amix=inputs=2:normalize=0[l];[2][3]amix=inputs=2:normalize=0[r];[l][r]join=inputs=2:channel_layout=stereo" \
      -sample_fmt s32 "$@" "$WORK/$n.flac"
  else
    ffmpeg -v error -y -f lavfi -i "sine=f=997:r=$r:d=6,volume=0.3" -f lavfi -i "anoisesrc=r=$r:d=6:a=0.05:c=pink" \
      -filter_complex "[0][1]amix=inputs=2:normalize=0" -sample_fmt s32 "$@" "$WORK/$n.flac"
  fi
}
gen c0_96  96000  2 -c:a flac -compression_level 0  -bits_per_raw_sample 24
gen c5_96  96000  2 -c:a flac -compression_level 5  -bits_per_raw_sample 24
gen c12_96 96000  2 -c:a flac -compression_level 12 -bits_per_raw_sample 24
gen ms_44  44100  2 -c:a flac -compression_level 8 -ch_mode mid_side   -bits_per_raw_sample 24
gen ls_44  44100  2 -c:a flac -compression_level 8 -ch_mode left_side  -bits_per_raw_sample 24
gen rs_44  44100  2 -c:a flac -compression_level 8 -ch_mode right_side -bits_per_raw_sample 24
gen c8_192 192000 2 -c:a flac -compression_level 8 -bits_per_raw_sample 24
gen mono48 48000  1 -c:a flac -compression_level 8 -bits_per_raw_sample 24
# 16-bit content stored in a 24-bit file: low byte empty, FLAC codes it as wasted bits
ffmpeg -v error -y -i "$WORK/c5_96.flac" -af "aresample=osf=s16:dither_method=none" -sample_fmt s16 "$WORK/t16.wav"
ffmpeg -v error -y -i "$WORK/t16.wav" -sample_fmt s32 -c:a flac -bits_per_raw_sample 24 "$WORK/wasted.flac"

fail=0
for f in "$WORK"/*.flac; do
  mine=$(java -cp "$WORK/out" FlacTest "$f" | tail -1 | cut -d' ' -f1)
  ref=$(ffmpeg -v error -i "$f" -c:a pcm_s24le -f s24le - | md5sum | cut -d' ' -f1)
  if [ "$mine" = "$ref" ]; then echo "MATCH     $(basename "$f")  $mine"; else echo "DIFFERENT $(basename "$f")  mine=$mine ffmpeg=$ref"; fail=1; fi
done
rm -rf "$WORK"
exit $fail
