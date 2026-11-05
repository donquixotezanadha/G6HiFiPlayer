#!/usr/bin/env bash
# Hifi 1.28: checks that AlacFrameDecoder (the player's own ALAC decoder) is bit-exact against ffmpeg's ALAC decoder.
# Makes ALAC test files with ffmpeg (24-bit and 16-bit, several rates, mono/stereo, compression levels, digital silence),
# decodes each with AlacFrameDecoder (through AlacMp4Reader) and with ffmpeg, compares the MD5 of the 24-bit PCM.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
JAVA="$HERE/../../app/src/main/java/id/g6hifiplayer"
WORK="$(mktemp -d)"
command -v ffmpeg >/dev/null || { sudo apt-get update -qq && sudo apt-get install -y -qq ffmpeg; }
javac -d "$WORK/out" "$JAVA/AlacFrameDecoder.java" "$JAVA/AlacMp4Reader.java" "$HERE/AlacTest.java"

gen() { # name rate channels sample_fmt extra-ffmpeg-args...
  local n=$1 r=$2 c=$3 f=$4; shift 4
  if [ "$c" = 2 ]; then
    ffmpeg -v error -y -f lavfi -i "sine=f=997:r=$r:d=6,volume=0.3" -f lavfi -i "anoisesrc=r=$r:d=6:a=0.05:c=pink" \
      -f lavfi -i "sine=f=1500:r=$r:d=6,volume=0.2" -f lavfi -i "anoisesrc=r=$r:d=6:a=0.04:c=white" \
      -filter_complex "[0][1]amix=inputs=2:normalize=0[l];[2][3]amix=inputs=2:normalize=0[r];[l][r]join=inputs=2:channel_layout=stereo" \
      -sample_fmt "$f" -c:a alac "$@" "$WORK/$n.m4a"
  else
    ffmpeg -v error -y -f lavfi -i "sine=f=997:r=$r:d=6,volume=0.3" -f lavfi -i "anoisesrc=r=$r:d=6:a=0.05:c=pink" \
      -filter_complex "[0][1]amix=inputs=2:normalize=0" -sample_fmt "$f" -c:a alac "$@" "$WORK/$n.m4a"
  fi
}
gen s24_96     96000  2 s32p
gen s24_96_c0  96000  2 s32p -compression_level 0
gen s24_96_c2  96000  2 s32p -compression_level 2
gen s24_96_p4  96000  2 s32p -min_prediction_order 1 -max_prediction_order 4
gen s24_44     44100  2 s32p
gen s24_192    192000 2 s32p
gen s24_48_m   48000  1 s32p
gen s16_44     44100  2 s16p
gen s16_96     96000  2 s16p
# digital silence in the middle (exercises the zero-run coder) and a quiet fade
ffmpeg -v error -y -f lavfi -i "sine=f=440:r=96000:d=2,volume=0.4" -f lavfi -i "aevalsrc=0:s=96000:d=2" -f lavfi -i "sine=f=880:r=96000:d=2,volume=0.001" \
  -filter_complex "[0][1][2]concat=n=3:v=0:a=1,aformat=channel_layouts=stereo" -sample_fmt s32p -c:a alac "$WORK/silence.m4a"

fail=0
for f in "$WORK"/*.m4a; do
  mine=$(java -cp "$WORK/out" AlacTest "$f" | tail -1 | cut -d' ' -f1)
  ref=$(ffmpeg -v error -i "$f" -c:a pcm_s24le -f s24le - | md5sum | cut -d' ' -f1)
  if [ "$mine" = "$ref" ]; then echo "MATCH     $(basename "$f")  $mine"; else echo "DIFFERENT $(basename "$f")  mine=$mine ffmpeg=$ref"; fail=1; fi
done
rm -rf "$WORK"
exit $fail
