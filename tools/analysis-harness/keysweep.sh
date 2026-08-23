#!/usr/bin/env bash
# Key detection measured without ground truth.
#
# Nobody tagged these files with a key, and asking a second tool only moves the
# question — it can be wrong too. But a *relative* fact needs no oracle: shift a
# track up by three semitones and its key must move up by exactly three. A
# detector that gets that wrong is wrong about something, whatever the true key
# is, and one that gets it right for all eleven shifts is reading real tonal
# structure rather than landing near the answer by luck.
#
# Detuning is the same trick at a finer grain. A ±30-cent offset is well inside
# one semitone, so the key must not change at all; a detector with no tuning
# estimate smears its chroma across neighbouring pitch classes and eventually
# flips.
#
#   keysweep.sh <album-dir> <work-dir> <out-dir>
#
# Writes one TSV per offset into <out-dir>, deleting each offset's decoded audio
# before moving on — the sweep is 13 full decodes of an album and keeping them
# all would be some 2 GB for no reason.
set -euo pipefail

album="$1"; work="$2"; out="$3"
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
win() { command -v cygpath >/dev/null 2>&1 && cygpath -w "$1" || printf '%s' "$1"; }

mkdir -p "$out"
export JAVA_HOME="${JAVA_HOME:?set JAVA_HOME to a JDK}"

run() {  # run <tag> <semitones> <cents>
  local tag="$1" semis="$2" cents="$3"
  local pcm="$work/pcm/$tag"
  echo "=== $tag (${semis} semitones, ${cents} cents) ==="
  bash "$here/decode.sh" "$album" "$pcm" "$semis" "$cents" >/dev/null
  ( cd "$root" && ./gradlew :app:testDebugUnitTest --tests '*ScanHarnessTest*' \
      --console=plain -q \
      "-Dcamusic.audio.dir=$(win "$pcm")" \
      "-Dcamusic.harness.out=$(win "$out/$tag.tsv")" >/dev/null )
  rm -rf "$pcm"
  echo "  -> $out/$tag.tsv"
}

for s in 0 1 2 3 4 5 6 7 8 9 10 11; do
  run "shift$s" "$s" 0
done
run "detune-30" 0 -30
run "detune+30" 0 30
echo "done"
