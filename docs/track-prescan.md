# Track pre-scan: the last gap to 1:1 with syncoV2

Planned in `docs/track-prescan-plan.md` (PR #31), built here. This document
records what was actually implemented, where it diverges from the plan and from
syncoV2, and what remains.

## Why it exists

Everything in syncoV2's light show that can be reproduced from a live audio tap
already was: 59 of its 61 `ModeParams` fields were wired. What remained was not a
missing effect. It was that the direct path had to *learn* each track as it
played, where syncoV2 can know it in advance.

| | Live tap alone | With a scan |
|---|---|---|
| Beat grid | PLL needs ~6 s of flux to lock | exact from bar one |
| Auto intensity | 20 s character warm-up, starts neutral | correct from the first bar |
| Drop | heuristic, "a build that nearly maxed out" | known ETA, counted down to |
| Section level | unknown, full range always available | chorus gets the range, verse does not |
| Per-band loudness | unknowable — a per-bin AGC divides it out | recovered from the whole track |

## What was built

| Concern | File |
|---|---|
| Offline extractor, tempo, beats, sections, intensity profile | `audio/TrackAnalysis.kt` |
| The scan model, its grid/section/profile queries | `audio/TrackScan.kt` |
| MediaCodec decode | `audio/TrackScanner.kt` |
| Versioned disk cache + memory LRU | `audio/TrackScanStore.kt` |
| Queue, prefetch, library sweep, progress | `audio/TrackScanRepository.kt` |
| Which tracks a sweep covers | `audio/ScanLibrarySource.kt` |
| Adoption, structure enrichment, picker parameters | `hue/DirectLightSync.kt` |
| Track position of the audio being analysed | `audio/AudioLeadProbe.kt`, `audio/AudioAnalysisTap.kt` |

### Shared geometry, not copied geometry

`OfflineExtractor` takes `ANALYSIS_WINDOW`, `ANALYSIS_HOP`, `NFFT`,
`makeOnsetFilterbank` and `makeMelbank` from `AudioAnalyzer` rather than
declaring its own, and `buildIntensityProfile` imports `songCharacter`,
`CHAR_ATTACK_LO/HI`, `CHAR_BUSY_FULL`, `PICK_BPM_LO/HI` and `PICK_BEAT_FULL`
from `AutoIntensityPicker`. syncoV2 does the same for the same reason: if the
offline and live character scores disagree, a track changes rung the moment its
scan lands.

The box-filter resample phase is shared too, as `BoxResampleClock`, so the tap
and the scanner downsample identically.

### Divergences from syncoV2

**Frame convention.** syncoV2's offline extractor frames as
`buf[i*hop : i*hop+window]`, labelling each frame by its window *start*, while
its live analyzer labels by the window *end* minus a hop. The two differ by
`window - hop` ≈ 26 ms, which is why a syncoV2 map's beats sit slightly ahead of
where the same track's live detector puts them, and part of why it carries a
timing calibrator. `OfflineExtractor` matches the *live* framing instead. Since
the point of a scan here is that its frames are interchangeable with the tap's,
matching the tap is the parity that counts. `TrackAnalysisTest` asserts it.

**No per-frame playback features.** syncoV2's `TrackFeatures` carries bands,
melbank, pan and more — ~400 KB a track — so a player it cannot tap (AirPlay,
Sonos, Chromecast) can have frames replayed at it. We *are* the player. Dropping
it takes a scan to roughly 10 KB, which is what makes a library-wide sweep fit on
a phone. The intensity curve is kept, decimated to 10 Hz and quantised to a byte:
it is a 1.4 s centred moving average, so anything faster is describing noise it
has already smoothed away.

**No library metadata matching.** `library_match.py`, the prewarm UI, the disk
budgets and the MA URL ladder are all absent. We know exactly what is playing.

**Cache format.** A compact binary layout, not `.npz`, and one file per track so
"forget this one" and "forget all of them" are trivial. Version 1, with the
compatibility set that future readers must extend rather than bump past.

### The position clock

Adopting a scanned grid needs to know where in the song the audio being analysed
comes from. The analyzer's own `tAudio` counts from the last flush, which is not
that.

`AudioLeadProbe` already sees each buffer's `presentationTimeUs` on the way into
the sink. The tap publishes that alongside its ring write position under a
seqlock; the analysis thread subtracts however far behind it is in samples. The
result is exact to within a sample, and needs nothing from the player's polled
position.

One trap, and it is a total one: `presentationTimeUs` is **renderer-domain**, not
per-track. ExoPlayer starts it at `INITIAL_RENDERER_POSITION_OFFSET_US` = 1e12 µs
and runs it continuously across a gapless queue. The renderer tells the sink the
offset through `AudioSink.setOutputStreamOffsetUs` precisely so it can be undone,
and `AudioLeadProbe` overrides it. Without that subtraction every position reads
as about eleven days, every track fails the adoption window, and the feature is
silently dead while appearing to work.

### Adoption

One regime per track, decided once, inside the first `MAP_COMMIT_WINDOW_S` = 6 s
(syncoV2's `coordinator._MAP_COMMIT_WINDOW_S`). A grid arriving mid-song would
replace a PLL that has by then found the beat itself, and the handover between
two clocks that agree on tempo but not quite on phase reads as the room
stumbling. A late scan is used on the *next* play instead.

This is why the next queue item is prefetched: without it a queue is only exact
from the second *listen*; with it, from the second song.

The causal tracker runs every frame regardless and is the floor — if the scan
does not cover this position, or its grid never earned `MIN_GRID_CONFIDENCE`, the
room still keeps time.

### What the scan feeds

- `TempoTracker`'s output is replaced by `TrackScan.gridAt`, same `BeatGrid`
  shape, so the engine is untouched. `scheduleStrength` is 1.0: an offline grid
  is authoritative, where the causal tracker ramps it with its own lock quality.
- `StructureState.sectionLevel` and `dropEtaS`, both of which existed at their
  unknown defaults and had nothing to fill them.
- `AutoIntensityPicker.update`'s `character`, `dynamics`, `lo`, `hi`, `signal`
  and `mood` — the parameters it has always accepted and nothing ever supplied.
- `AnalysisFrame.melbankRef`, which makes `bandLoudStrength` reachable.
- `TrackScan.key`, `beatsPerBar` and `ScanSection.label` — added after the
  original build, and covered below.

### What analyser version 3 added

The scan carries four things it did not, and one it carried badly.

- **`key`** — the track's musical key, from a key-profile correlation over a
  tuning-corrected whole-track chroma (`audio/KeyDetection.kt`). Version 2 had a
  key too, and it was close to arbitrary: it read a chroma built by assigning FFT
  bin magnitudes to their nearest semitone, and at this analyser's 46 ms window a
  partial's main lobe is 43 Hz wide — three and a half semitones at 200 Hz — so
  the bottom of the range carried no usable pitch information. Version 3 reads
  interpolated spectral peaks instead. Measured on real music it went from
  agreeing with itself about a transposed copy of the same track 12.4 % of the
  time to 71.1 %, against a 1-in-12 chance floor. See
  `tools/analysis-harness/README.md`.
- **`tuningCents`** — how far the record sits from A440, measured off the same
  peaks. Not used by the show; kept because it is the one number that says *why*
  a key came out the way it did, and without it a surprising key looks like a bug
  rather than a property of the file.
- **`beatsPerBar`** — measured rather than assumed. It was hardcoded to 4 in two
  places (`gridAt`'s `beatInBar` and its `barPhase`), so on anything in 3 the
  "downbeat" landed on a different beat of each bar in turn and every bar-synced
  effect in `SyncoEngine` walked round the bar instead of sitting on its one.
  `detectMetre` scores the 3 and 4 folds on bass onset, harmonic change and
  section-boundary alignment, with an asymmetric margin: four is overwhelmingly
  the prior, and calling a 4/4 track a waltz is the worse of the two errors.
- **`ScanSection.label`** — which sections sound alike. Boundaries alone say the
  track changed at 1:04; they cannot say that what started there is the chorus
  again. `MusicDnaLayer` steps its anchor hue by label, so a returning chorus
  returns to its own colour.
- **Sub-frame beat times.** The DP can only place a beat on a frame, so every
  beat time was quantised to the 20 ms hop — visible in a scan report as every
  BPM being an exact multiple of `60 / (k · 0.02)`. A parabola through the onset
  envelope either side of the chosen frame recovers the real position, clamped to
  ±½ a frame so a beat can never cross its neighbour.

The file format goes to 4, appending metre, tuning and one label byte per section
after the format-3 key — append-only, as every previous bump has been, so a
format-1, -2 or -3 file still loads and reads back with the defaults those scans
were already behaving as.

## Settings

On by default, under Light Sync → Track analysis:

- **Read tracks ahead** — the master toggle.
- **Wi-Fi only** — streamed tracks are downloaded to be read; downloads are
  always analysed.
- **Analyse library** — downloads plus the Navidrome library, which are the only
  tracks this phone plays itself and therefore the only ones direct mode ever
  sees. Skips what is already done, so stopping and restarting resumes.
- **Re-analyse this track** and **Delete analyses** (two taps).

Scans live in `filesDir/light-sync-scans`, not `cacheDir`: the OS may clear a
cache directory, and quietly discarding a library someone spent an hour and a
gigabyte of transfer building is not a reasonable thing to do behind their back.

A track that cannot be analysed is remembered for the session — silence and
too-little-audio permanently, a failed decode or fetch after three tries — so it
is not re-downloaded on every play for a result already known.

## Verification

**Unit tests** (`TrackAnalysisTest`, `TrackScanTest`, 41 in total):

- offline and live framing agree frame for frame, and the offline envelope peaks
  where the live detector fires — the parity check the plan called the one that
  matters most;
- Ellis DP lands on a synthetic click track at a known BPM, with the intervals
  and the phase both checked;
- the tempogram resolves 140 BPM rather than its half, which is the case the ×4
  upsampling exists for;
- checkerboard novelty finds a spectral boundary in a two-section signal of
  constant loudness, and the sections tile without gaps;
- the intensity profile spans a quiet-to-loud arc, and its span guard holds on a
  near-constant one;
- silence and too-short clips are refused rather than analysed into a dark show;
- a scheduled beat fires exactly once as the clock sweeps past it, and not at all
  on a backwards seek;
- cache round-trip, an unknown format, a truncated file, a hostile length field,
  delete-one, delete-all, rescan-replaces, and keys that are not filenames;
- the resample clock holds the true rate at 48 kHz and at 16 kHz;
- metre comes from harmony when the bass is flat — the case the old bass-only
  fold could not do at all — and ties still go to 4;
- refined beat times stay strictly ascending and inside the analysed span, and a
  flat envelope is left on its frame index rather than interpolated off it;
- sections that sound alike share a label and a different one does not;
- a synthesised harmonic tone lands on its own pitch class, the fifth its third
  harmonic manufactures does not outvote it, a 30-cent detune is measured to
  within a few cents, and a 40-cent-sharp tone is still the note it is rather
  than the one above;
- a format-3 file still loads, reading back as 4 beats to the bar with no labels.

**Measured against real music** (`tools/analysis-harness`, not in CI): shift
consistency, detune stability, and tempo against librosa. The synthetic tests
above say each algorithm does what it claims; only the harness says whether the
result is any good on a mix with a kick drum in it. It is what found all three of
the key-path faults version 3 fixes.

**Still needs a device.** None of the following runs on a JVM:

- decode speed for a 5-minute FLAC on a real phone;
- **no audio glitching while scanning** — the risk the plan named, and the reason
  the scanner runs at `THREAD_PRIORITY_BACKGROUND` through a single worker;
- a scan landing after the 6 s window, and a track change mid-scan;
- the gapless-transition case for the position clock, where the stream offset
  changes between the last buffer of one track and the first of the next;
- a library sweep over a real library, for memory and for the sweep's own
  progress accounting.

## What is still out of reach

`warmCalm` belongs to the Movies effect, deliberately dropped: there is no video
here for a calm, brightness-follows-the-soundtrack mode. It is the only
`ModeParams` field left unwired — `bandLoudStrength`, the other one the plan
listed, is reachable now and is wired.

## Reference map

| Concern | syncoV2 |
|---|---|
| Offline extractor, bin-identical to live | `audio/trackmap.py:784-935` |
| Global tempo + octave guard | `audio/trackmap.py:938` |
| Tempogram, Viterbi | `audio/trackmap.py:1009`, `:1072` |
| Ellis DP beats | `audio/trackmap.py:1164` |
| Grid confidence | `audio/trackmap.py:1107` |
| Sections | `audio/trackmap.py:1378` |
| IntensityProfile | `audio/trackmap.py:357` |
| Per-bin loudness reference | `audio/trackmap.py:1613` |
| Adoption window | `coordinator.py:1903` |
| Per-band loudness at render time | `effects/engine.py:845` |
