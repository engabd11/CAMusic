# Analysis harness

Measuring the offline analyser against real music, because the unit tests
cannot.

`TrackAnalysisTest` and `KeyDetectionTest` check that each algorithm does what it
claims against synthetic signals — a click track has beats where the clicks are,
novelty peaks where the material changes, a synthesised harmonic tone lands on
its own pitch class. That is necessary and it is not sufficient. A click track
cannot tell you whether the key detector survives a mix with a kick drum in it,
and nearly every constant in `TrackAnalysis.kt` and `KeyDetection.kt` was
originally reasoned from the signal rather than measured against one.

Nothing here runs in CI. The corpus is somebody's music library; the JVM side
skips itself unless pointed at one.

## The problem: no ground truth

The obvious way to measure a key detector is to compare it against a corpus with
known keys. There isn't one to hand — the files here carry no key or BPM tags —
and asking a second tool only moves the question, because the second tool can be
wrong too.

So the two headline measurements need no oracle at all:

**Shift consistency.** Transpose a track up by three semitones and its detected
key must move up by exactly three. A detector that gets that wrong is wrong about
something, whatever the true key is; one that gets it right across all eleven
transpositions is reading real tonal structure rather than landing near an answer
by luck. Chance is 1 in 12, so the floor is 8.3 %.

**Detune stability.** A ±30-cent offset is well inside a semitone and changes no
note in the music, so the key must not change. A detector with no tuning estimate
smears its chroma across neighbouring pitch classes and eventually flips.

librosa is used as well, but only as a *second opinion* — it shares no code with
the app, so where the two agree the answer is probably the music. Its own key
estimate is a template correlation and is wrong often enough that a disagreement
is a prompt to listen, not a verdict.

## Running it

Needs `ffmpeg` on the path, and Python with `numpy`, `scipy`, `librosa` and
`soundfile` for the reference.

```bash
export JAVA_HOME=...            # a JDK; the Gradle wrapper needs one
ALBUM="/path/to/an/album"       # a directory of .flac
WORK=/tmp/harness

# One pass over the corpus: BPM, key, sections, metre, per track.
bash tools/analysis-harness/decode.sh "$ALBUM" "$WORK/pcm" 0 0
./gradlew :app:testDebugUnitTest --tests '*ScanHarnessTest*' \
    -Dcamusic.audio.dir="$WORK/pcm" \
    -Dcamusic.harness.out="$WORK/report.tsv"

# The full sweep: twelve transpositions and two detunes, scored.
bash tools/analysis-harness/keysweep.sh "$ALBUM" "$WORK" "$WORK/sweep"
python tools/analysis-harness/score.py "$WORK/sweep"

# And the independent reference to compare tempo against.
python tools/analysis-harness/reference.py "$ALBUM" --out "$WORK/reference.tsv"
python tools/analysis-harness/score.py "$WORK/sweep" --ref "$WORK/reference.tsv"
```

The sweep is thirteen full decodes of the album and a scan of each, so it takes
a few minutes; it deletes each offset's decoded audio before moving to the next
rather than keeping some two gigabytes of it around.

## The files

| | |
|---|---|
| `decode.sh` | album → mono float32 at 22050 Hz, the format `ScanHarnessTest` reads, optionally transposed or detuned |
| `keysweep.sh` | drives `decode.sh` + the harness across twelve transpositions and two detunes |
| `score.py` | turns a sweep directory into shift consistency and detune stability |
| `reference.py` | librosa's independent tempo, beats and key |
| `ScanHarnessTest.kt` | the JVM side, in `app/src/test/.../audio/` |

`decode.sh` writes exactly what `TrackScanner`'s `Pump` hands to
`OfflineExtractor` after its own downmix and resample, so the harness exercises
the real analysis path from the first frame on without needing a decoder on the
JVM. `reference.py` reads the *original* files rather than the decoded ones, so a
bug in `decode.sh` cannot make the two agree for the wrong reason.

## What it found

Run against an eleven-track album, on the analyser as it stood at v0.10.5:

| | before | after |
|---|---|---|
| shift consistency | 12.4 % | 71.1 % |
| detune stability | 72.7 % | 90.9 % |
| median key confidence | 0.31 | 0.47 |
| tonics agreeing with librosa | 0 / 11 | 7 / 11 |

12.4 % against a 8.3 % chance floor means the old detector was reading almost
nothing. Three separate causes, each found by measurement rather than by reading
the code:

1. **The tuning estimate was measuring the FFT.** The first attempt took a
   circular mean over sub-bin *assignments*. FFT bins are spaced linearly and
   pitch classes logarithmically, so the positions of the bins themselves modulo
   a semitone are strongly non-uniform at the low end — the statistic reported
   the shape of the bin grid. It read every track on a +4-to-+7-cent album as 13
   to 18 cents flat and responded to a real 30-cent detune with 13 cents of
   movement. Reading interpolated peak positions instead removed the bias
   entirely.

2. **Leakage, not resolution, was the ceiling.** The analysis window is 46 ms, so
   a partial's main lobe is about 43 Hz wide — three and a half semitones at
   200 Hz. No scheme that assigns bin *magnitudes* to pitch classes can work down
   there however carefully it weights the assignment, because the energy really is
   spread across three semitones. Parabolic peak interpolation locates a partial
   to a fraction of a bin regardless of how wide its lobe is.

3. **The detector was hearing the third harmonic and calling it the tonic.** Once
   the first two were fixed, the remaining failures were not random: they were
   *exactly +5 semitones*, on eight separate tracks. Every note's third harmonic
   lands a fifth above it, so a chroma that credits each peak to its own pitch
   class manufactures a dominant nobody played. Crediting each peak to every
   fundamental it could be a harmonic of — the standard HPCP treatment — took
   shift consistency from 62.8 % to 71.1 % and moved seven of eleven tonics onto
   librosa's answer.

The harmonic weight is the literature-standard `0.6^(n-1)`, deliberately **not**
fitted to this corpus: eleven tracks by one artist in one genre is not enough to
tune a constant against without overfitting it to that artist.

Two tracks still report a key confidence near zero. That is the detector saying it
cannot tell, which is the honest answer for them — and `MusicDnaLayer` already
weights how hard the key anchors the room's hue by exactly that number, so a
coin-flip key barely moves the lights.
