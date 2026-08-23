#!/usr/bin/env bash
# Decode an album to the raw format ScanHarnessTest reads: mono float32 LE at
# the analyser's own 22050 Hz, which is exactly what TrackScanner's Pump hands
# to OfflineExtractor after its downmix and box resample. Doing the conversion
# in ffmpeg rather than in the test keeps the harness free of a decoder.
#
#   decode.sh <album-dir> <out-dir> [semitones] [cents]
#
# `semitones`/`cents` retune the source, which is what the pitch-shift and
# detune checks need: the same track re-analysed at a known offset, so the
# detected key can be checked against an answer we chose rather than one we
# had to be told. The shift is a resample with no duration correction — every
# partial moves by the same ratio, exactly as a varispeed transfer does — so
# it also moves the tempo, which is why the tempo checks use the 0/0 decode.
set -euo pipefail

# ffmpeg here is a native Windows build, and MSYS's automatic path translation
# mangles an argument containing an apostrophe ("09 Echo's Ballad.flac"). Doing
# the conversion explicitly is the fix; on a real POSIX box cygpath is absent
# and the path is already native, so this is a no-op there.
win() { command -v cygpath >/dev/null 2>&1 && cygpath -w "$1" || printf '%s' "$1"; }

src="$1"; out="$2"; semis="${3:-0}"; cents="${4:-0}"
mkdir -p "$out"
ratio=$(python -c "print(2 ** (($semis * 100 + $cents) / 1200.0))")
for f in "$src"/*.flac; do
  base=$(basename "$f" .flac)
  if [ "$semis" = "0" ] && [ "$cents" = "0" ]; then
    filter="aresample=22050"
  else
    rate=$(ffprobe -v error -select_streams a:0 -show_entries stream=sample_rate -of csv=p=0 "$(win "$f")")
    shifted=$(python -c "print(int(round($rate * $ratio)))")
    filter="asetrate=$shifted,aresample=22050"
  fi
  ffmpeg -v error -y -i "$(win "$f")" -af "$filter" -f f32le -ac 1 -ar 22050 "$(win "$out/$base.f32")" </dev/null
  echo "  $base"
done
