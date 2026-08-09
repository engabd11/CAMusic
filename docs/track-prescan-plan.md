# Track pre-scan: the last gap to 1:1 with syncoV2

## Why this exists

Everything in syncoV2's light show that can be reproduced from a live audio tap
now is. Of its 61 `ModeParams` fields, 59 are wired; the two that are not
(`warmCalm`, `bandLoudStrength`) are unreachable for reasons given at the end.

What remains is not a missing effect. It is that the direct path has to *learn*
each track as it plays, where syncoV2 can know it in advance:

| | Live tap (today) | With a pre-scan |
|---|---|---|
| Beat grid | PLL needs ~6 s of flux to lock | exact from bar one |
| Auto intensity | 20 s character warm-up, starts neutral | correct from the first bar |
| Drop | heuristic, "a build that has nearly maxed out" | known ETA, counted down to |
| Section level | unknown, so the full range is always available | chorus gets the range, verse does not |

So the opening of every song is approximate, and the choreography never knows
what is coming. That is the residual "minor but felt" difference.

`AutoIntensityPicker.update` already accepts `character`, `dynamics`, `lo`, `hi`
and a lag-free `signal`. Nothing supplies them, so they always take their
live-estimated defaults. Those five parameters are the seam this work plugs into.

## Scope decision: current track only

syncoV2 pre-analyses the *library*. Do not port that. It carries a large amount
of machinery this app does not need:

- `library_match.py` — matching a playing track to a library entry by metadata.
  Unnecessary: we are the player, we know exactly what is playing.
- Library prewarm, progress notifications, disk budgets, retry policies.
- The MA URL resolution ladder in `audio/source.py`.

Scan the track that is playing, in the background, as it starts. A local or LAN
file decodes far faster than real time, so the scan finishes seconds into a song
that runs for minutes. Optionally prefetch the next queue item.

## Design

### New file: `audio/TrackScanner.kt`

```
suspend fun scan(uri: Uri): TrackScan?     // faster-than-realtime, cancellable
data class TrackScan(
    val durationS: Float,
    val bpm: Float,
    val confidence: Float,
    val beats: FloatArray,          // seconds
    val accents: FloatArray,        // 0..1, parallel to beats
    val downbeat: Int,              // 0..3
    val sections: List<Section>,
    val intensity: IntensityProfile,
)
```

Decode with `MediaExtractor` + `MediaCodec` in async mode, downmix to mono,
resample to 22050 Hz. **Reuse `AudioAnalyzer`'s existing geometry** — window
1024, hop 441, NFFT 2048, the same filterbank and melbank. syncoV2 keeps its
offline extractor bin-identical to its live one on purpose
(`trackmap.py:800-820` reuses `make_onset_filterbank` / `make_melbank`), because
replayed frames have to be interchangeable with live ones. Breaking that is the
easiest way to make this subtly wrong.

Guards from `trackmap.py`: under 200 frames (~4 s) → give up; `p95(rms) < 1e-4`
→ the file is silent; cap the decode at 720 s and 90 s of wall clock.

### Beat grid — `trackmap.py:938-1224`

1. **Global tempo** (`_estimate_tempo`) — autocorrelation of the onset envelope
   × a log-Gaussian prior, plus an octave guard using a harmonic comb
   `s + 0.5·ac[2λ] + 0.34·ac[3λ]` evaluated against the argmax and its ×0.5/×2
   relatives.
2. **Tempogram + Viterbi** (`_tempogram`, `_tempo_path`) for tracks ≥ 20 s.
   10 s windows, 1 s hop, whitened by subtracting a 0.5 s rolling mean, band-
   limited **upsampled ×4** so fractional lags exist — this is the fix for
   140 BPM landing on 21.43 frames. Transition cost `−40·(ln λᵢ − ln λⱼ)²`.
3. **Ellis DP** (`_track_beats_local`) — maximise
   `Σ onset(beat) − 100·Σ log²(interval/period)` with a *per-frame* period from
   the Viterbi path, so it rides drift and mid-song tempo changes.
4. **Confidence** (`_grid_confidence`) — grid-quality-first, not autocorrelation
   -first. Copy the formula verbatim; the constants are calibrated.
5. **Downbeat** — `argmax over k of Σ bass_env[beats[k::4]]`.

### Sections — `trackmap.py:1378`

0.75 s blocks, `log1p(10·bands)` L2-normalised → cosine self-similarity →
checkerboard kernel (half-size 8 blocks, Hann-tapered outer product × ±1
quadrants) → novelty. Peaks above `median + 5·MAD`, minimum gap 8 s. Each
section's `energy = mean(rms_block) / p95(rms_block)`.

### IntensityProfile — `trackmap.py:357`

This is the **cheapest useful subset**: it needs the decode and the envelope but
none of the DP, Viterbi or SSM above, and it alone feeds the four unused picker
parameters. If the full scan is too much in one go, land this first.

```
tempo  = clip((bpm − 85)/(150 − 85))
perc   = beat-rate curve (leaky integrator, tau 1.5, /3.0)
moment = energy·(0.55 + 0.45·energy)
raw    = clip(0.68·moment + 0.16·tempo + 0.16·perc)
curve  = centred 1.4 s moving average          # lag-free, unlike the live EMA
sig_lo = p10(curve) ; sig_hi = p95(curve) ; dynamics = hi − lo   (min span 0.06)
tilt   = energy-weighted (low − high)/(low + high) over the 5 bands
character = songCharacter(tempo, busy, attack, bass = 0.5 + 0.5·tilt)
```

`songCharacter` already exists in `AutoIntensityPicker.kt` and **must be shared**,
not duplicated. syncoV2 imports the same constants into `trackmap.py` for exactly
this reason (`trackmap.py:58-66`): if the offline and live character scores
disagree, a track changes rung the moment its scan lands.

`_PROFILE_LOOKAHEAD_S = 0.35` — sample the curve *ahead* of the playhead so a
rung switch lands on the section change rather than after it.

## Integration

**Adoption rule.** Adopt the scanned grid only if it is ready within the first
~6 s of the track (`_MAP_COMMIT_WINDOW_S`, `coordinator.py:1903`); otherwise stay
causal for the rest of that track. This is why a late-arriving scan never causes
a visible jump, and it is not optional.

**Where it plugs in** — `DirectLightSync`:

- Start a scan when `artUrls`-style track identity changes; cancel the previous.
- Feed `TempoTracker` the scanned grid instead of its own PLL output when
  committed, keeping the same `BeatGrid` shape so the engine is untouched.
- Fill `StructureState.dropEtaS` and `sectionLevel` from the section list — both
  fields already exist and are always at their unknown defaults today.
- Pass `character`, `dynamics`, `lo`, `hi`, `signal` into `picker.update`.

**Cache.** Key by track id. Small LRU in memory plus a disk cache; a compact
binary format, not `.npz`. Include a format version — syncoV2 is on its fifth
(`_CACHE_FORMAT = 5`) and reads 2–5.

**Threading.** Own background thread or `Dispatchers.Default`, never the analysis
thread. Cancel on track change. The scan must not compete with the render loop:
a stutter caused by scanning would cost more than the scan is worth.

## Verification

Little of this runs on the JVM, so plan for device work from the start:

- **Unit-testable** (do these first, they catch the most): Ellis DP against a
  synthetic click track at known BPM; the tempogram octave guard against
  140 BPM specifically; checkerboard novelty on a synthetic two-section signal;
  percentile and profile maths; cache round-trip including a version bump.
- **Parity, and the one that matters most**: scan a file and replay its frames
  through the engine, then play the same file live. Frames must be
  interchangeable — same bands, same melbank geometry. A diff here means the
  offline and live extractors have drifted.
- **On device**: decode speed on a real phone for a 5-minute FLAC; behaviour when
  the scan lands after the 6 s window; a track change mid-scan; no audio
  glitching while scanning (this is the risk).

## What stays out of reach

- **`warmCalm`** — belongs to the Movies effect, deliberately dropped: there is
  no video here for a calm, brightness-follows-the-soundtrack mode.
- **`bandLoudStrength`** — needs the per-bin `melbank_ref`, an absolute-loudness
  profile. A scan *could* produce it (`trackmap.py:1613`), so if this work lands
  in full, that field becomes reachable too and should be wired at the same time.

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
| Frame replay / `frame_at` | `audio/trackmap.py:616` |
| Adoption window | `coordinator.py:1903` |
