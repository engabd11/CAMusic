# Spatial swells: sound that moves, and lights that move with it

**Status: a plan, not an implementation.** Nothing in this document has been built.
It is written to the same standard `docs/v0.9-remaining.md` asks for — every claim
about the existing code is **verified** (read and quoted) or explicitly marked
**assumed**, and the assumed ones are tasks to check before anyone starts building.

## The request

> Some music contains swell tones which in headphones or a proper sound system feel
> like the sound is going from right to left or the other way around, sometimes from
> bottom to top — more like a 3D spatial sound wave. This should be included in the
> lights show: when a swell or similar linear tone is detected, or 3D spatial sounds
> move from one side to another, this could be represented with lights actually moving
> across/around the room depending on the light positions. A room with lights in a
> linear pattern should have the swell in a linear manner; a room with lights in the
> corners should have the swell go around in a circle. The speed and brightness depend
> on the actual sound. However, this needs to be implemented carefully as not all songs
> have 3D spatial sounds or actual linear swell.

The last sentence is the hard requirement, and the rest of this document is organised
around it. A feature that fires on a track that has no swell in it is worse than one
that never fires, because it makes every track look the same as the ones it is
supposed to distinguish.

## Why this is its own PR

It is a genuinely new capability rather than an adjustment to an existing one, and it
lands in three separate places at once: a new audio-analysis feature, a new geometric
classification of the room, and a new layer in the render loop. Each of the three is
independently testable and independently able to be wrong. Sequencing them in one
branch behind one flag is how it stays diagnosable.

---

## What already exists

Four things, all verified by reading the source, and together they are most of the
groundwork.

### 1. The analyzer already measures where a sound sits in the stereo field

`AnalysisFrame.pan: FloatArray` — **per melbank bin**, −1 hard left to +1 hard right,
16 bins (`MELBANK_BINS = 16`). Computed in `AudioAnalyzer.processStereo` and smoothed
by `panSmooth`, an `ExpFilter` at α 0.25 rise and decay, which its own comment
describes as ~100 ms.

Crucially, the tap carries **real stereo**, not a downmix. `AudioAnalysisTap`'s ring is
interleaved stereo and says why:

> Both channels are carried rather than a downmix, because where a hit sits in the
> stereo field is what decides […]

So the input this feature needs is already in hand, already smoothed, already at the
analysis rate (~50 frames/s: `ANALYSIS_HOP = 441` at `ANALYSIS_SAMPLE_RATE = 22050`).

**And it is barely used.** `SyncoEngine` consumes `pan` in exactly two places, both the
same expression:

```kotlin
if (usePan) v *= (1f + p.panGain * pan[i] * info.side).coerceIn(0f, 2f)
```

That is a *static* bias: a lamp on the left side of the room gets more of whatever is
panned left, **right now**. There is no notion of pan *movement* anywhere in the engine.
A sound sweeping from one side to the other currently produces a left lamp getting
quieter and a right lamp getting louder — which is the correct instantaneous answer and
not a gesture. Turning that into a gesture is most of this feature.

### 2. The room's geometry is already known and already normalised

`SpatialWaves.kt` has `Vec3`, and `normalizePositions(channels)` maps every Hue
entertainment channel's `(x, y, z)` onto 0..1 over the area's *actual* extent, collapsing
an axis with no spread to 0.5 rather than dividing by zero. `floorOrigin` and
`phraseOrigins` give sensible wave origins, and `Wave` is an expanding spherical pulse
with per-channel precomputed distances.

**What is missing is any angular notion.** There is no azimuth helper, no ring fit, and
no classification of what *shape* the lamps are in. `phraseOrigins` cycles origins
centre → left → right → centre, which is a three-position approximation of exactly the
thing this feature needs to do properly.

### 3. Musical structure is already tracked

`StructureTracker` publishes `StructureState`: `phase` (STEADY / BUILDING / DROP /
BREAKDOWN), `buildProgress` 0..1, `dropNow`, `dropImminent`, `dropEtaS`, `sectionLevel`.

This is the natural gate for the *swell* half. A crescendo is a build, and
`SongPhase.BUILDING` with a climbing `buildProgress` is the tracker already saying so.

### 4. There is a safety layer and a movement tunable

`FieldSafety.kt` exists and holds the flicker/rate limits. `movement` is already one of
the seven tunables (`SyncoEngine.TUNABLE_DEFS`), documented as "how much the show travels
between lamps rather than lighting them together" — which is the dial this feature should
hang off rather than introducing an eighth.

---

## The two phenomena, which are not the same thing

The request describes two effects and they need separate detectors. Conflating them is
the most likely way to build something that fires at the wrong times.

### A — a **traversal**: a source moving across the stereo field

A pad, a synth line or an effect that pans while it sounds. Measurable directly from
`pan[]`: a bin whose pan value drifts **monotonically** over a window of roughly
0.5–4 s while that bin holds meaningful energy.

**Proposed detector.** Per bin, keep a short ring of recent `(t, pan[i], melbank[i])`.
A traversal is a bin where, over a window:

- the total pan excursion exceeds a threshold (a real crossing, not jitter);
- the excursion is **monotone enough** — the sum of absolute per-step changes is close
  to the net change, so a vibrato-ish wobble scores near zero while a sweep scores near
  one;
- the bin's own energy stayed above a floor for the whole window (a bin that went silent
  and came back somewhere else did not *travel*, it cut);
- and the pan is not simply following the whole mix (see the common-mode note below).

Output: a `Traversal(fromPan, toPan, durationS, bins, strength)`.

**The common-mode trap, and it is the important one.** Whole-mix level imbalance moves
*every* bin's pan together, and that is not a traversal — it is a mix that is louder on
one side. The detector must subtract the broadband pan centroid before scoring, so what
is measured is a source moving *relative to the mix* rather than the mix leaning. Failing
to do this would make the effect fire on any track with an off-centre master, which is a
lot of them.

### B — a **swell**: a linear rise with no onsets

The "swell tone" half. Rising level, low flux, no beats — a crescendo or a riser rather
than a hit. Every input for this already exists on `AnalysisFrame`: `energy`, `flux`,
`beat`/`bassBeat`/`midBeat`, `centroid`, plus `StructureState.phase` and `buildProgress`.

**Proposed detector.** A swell is a window where `energy` rises approximately linearly,
`flux` stays below the onset gate throughout, and no beat fired. `centroid` climbing with
it distinguishes a riser (brightening) from a simple volume ramp, and is worth carrying
in the output because it should drive the *direction* of a vertical gesture: brightness
rising is the "bottom to top" the request names.

Output: a `Swell(slope, durationS, centroidRise, strength)`.

The two are independent and can co-occur — a riser that also pans is the strongest case
and should render as one gesture, not two.

---

## Room topology: what shape are the lamps in?

The request is explicit that the *same* musical gesture should render differently
depending on how the lamps sit in the room, and this is the part with no existing code
at all.

**Proposed: a `RoomTopology` classified once per session** from the normalised positions,
not per frame.

| Topology | Test | Gesture |
|---|---|---|
| `LINEAR` | PCA over the positions: one eigenvalue dominates (say > 80% of variance) | A travelling front along the principal axis |
| `RING` | A least-squares circle fit in the dominant plane leaves small residuals *and* the lamps are spread in azimuth rather than clustered in an arc | An azimuthal sweep — round the room |
| `FIELD` | Neither | The existing spherical `Wave`, with a moving origin |
| `CLUSTER` | Fewer than 3 lamps, or an extent below a threshold on every axis | **No spatial gesture at all** — fall back to a brightness swell on all lamps together |

`CLUSTER` matters more than it looks. Two lamps on a shelf cannot express "around the
room", and pretending otherwise produces a left-right flicker that reads as a fault. A
topology classifier that refuses is doing its job.

**New geometry needed in `SpatialWaves.kt`**, all pure functions and all unit-testable
without a bridge or a server, which is how the rest of that file is already written:

- `principalAxis(positions): Vec3` and the variance explained — PCA over three axes.
- `fitRing(positions): Ring?` — centre, radius, normal, residual.
- `azimuthOf(position, ring): Float` — 0..1 around the ring. **This is the helper the
  "lights in the corners go around in a circle" case is missing.**
- `classifyTopology(positions): RoomTopology`.

---

## Choreography: one gesture type, four renderings

A detected gesture becomes a `SpatialGesture(kind, axisFrom, axisTo, durationS, strength)`
and the topology decides how it is drawn:

- **LINEAR** — a soft front travelling along the principal axis, position interpolated
  over `durationS`, width from `strength`. This is the "linear pattern → linear swell"
  case named in the request.
- **RING** — the same front, but the coordinate is azimuth, so it goes round. A traversal
  detected as left→right maps onto the half-turn that passes through the front of the
  room, not the back, or a sound crossing in front of the listener would render as one
  going behind them.
- **FIELD** — reuse `Wave`, but with the origin *moving* along the gesture rather than
  fixed. `Wave` currently takes an immutable `origin`; this needs either a mutable origin
  or a second wave type. **Prefer a second type** — `Wave`'s `amplitudeAt` is deliberately
  sqrt-free because distances are precomputed per origin, and a moving origin would
  destroy that optimisation for the existing beat pulses too.
- **CLUSTER** — brightness only.

Speed and brightness come from the sound, as asked: `durationS` from the measured
traversal or swell duration (**not** from a fixed constant — a slow pad and a fast riser
must not look the same), and peak brightness from `strength` scaled by the `movement`
tunable so the existing dial still governs it.

---

## Restraint, which is the actual hard part

Everything above is ordinary signal processing. This section is where the feature is won
or lost, and it should be built *first* — a detector with no renderer can be logged and
judged against real tracks, and a renderer with no detector cannot be judged at all.

1. **Confidence, not a boolean.** Both detectors should emit a strength, and the renderer
   should fade in over the low end of it rather than switching on at a threshold. A
   marginal detection that produces a barely-visible drift is a good failure; one that
   produces a full sweep is not.
2. **Hysteresis and a refractory period.** Once a gesture fires, nothing else fires for
   at least its own duration. Two overlapping sweeps read as noise.
3. **A budget.** A cap on gestures per minute, so a track that trips the detector
   constantly — and there will be one — degrades to the normal show rather than becoming
   a strobe. The cap being *hit* is the interesting signal and should be logged.
4. **It must be additive.** The gesture layer renders *on top of* the existing show and
   never replaces it, so the worst case with the detector wrong is the current behaviour
   plus a soft wash.
5. **`LocalReducedMotion` and `FieldSafety`.** A room-crossing sweep is exactly the kind
   of large peripheral movement the reduced-motion setting exists for, and it must go
   through the existing safety limiter rather than beside it.
6. **Off by default, behind a setting**, until it has been judged on real tracks in a real
   room. There is no way to validate this from a unit test — the tests can prove the
   detector fires on a synthetic sweep and not on a synthetic wobble, which is necessary
   and nowhere near sufficient.

---

## Suggested sequence

1. **Geometry first.** `principalAxis`, `fitRing`, `azimuthOf`, `classifyTopology`, with
   unit tests over synthetic rooms — a line of five, a ring of six, two on a shelf, a
   scattered eight. No audio, no rendering, no device. Entirely verifiable off-device and
   the foundation everything else stands on.
2. **Detectors, logging only.** Both detectors, wired to nothing but a log line naming
   what they saw and how strongly. Play real music through it and read the log. **This is
   the step that decides whether the feature is viable**, and it costs nothing to abandon
   at.
3. **The renderer**, LINEAR only, behind a setting. One topology is enough to judge
   whether a moving light reads as the sound moving.
4. **RING and FIELD**, once LINEAR has proven the idea in a room.
5. **Tuning**, against the `movement` dial, and a decision about whether this needs its
   own tunable or rides the existing one.

## Open questions, all currently assumed

- **Does Music Assistant's stream survive the stereo field?** The tap is stereo and the
  Sendspin path decodes to the same tap, so this *should* hold — but MA can be configured
  to downmix, and a mono stream leaves `pan` empty, which the detector must treat as "no
  data" rather than "no movement". **Assumed, needs checking on a real MA stream.**
- **Is ~100 ms of pan smoothing right for this?** It was chosen for the static per-lamp
  bias, where responsiveness matters. A traversal detector wants *more* smoothing, not
  less. Possibly a second, slower filter rather than a change to the existing one — which
  would alter the current behaviour of a shipped feature. **Assumed.**
- **How common is a real traversal?** Unknown, and step 2 above exists to answer it. If
  the honest answer is "rare outside electronic and film music", that is still a good
  outcome — a rare effect that is *right* when it fires is exactly what was asked for.
- **Does the Hue bridge's own position data justify a ring fit?** Entertainment area
  positions are set by the user in the Hue app and are frequently approximate. A ring fit
  over four lamps someone dragged roughly into corners may be too noisy to classify.
  **Assumed; check against a real area's reported positions before building `fitRing`.**

## Critical files

**Existing, for reuse:** `audio/AudioAnalyzer.kt` (`AnalysisFrame.pan`, `melbank`,
`energy`, `flux`, `centroid`), `audio/AudioAnalysisTap.kt` (the stereo ring),
`audio/StructureTracker.kt` (`SongPhase`, `buildProgress`), `hue/SpatialWaves.kt`
(`Vec3`, `normalizePositions`, `Wave`), `hue/SyncoEngine.kt` (the render path and the
`movement` tunable), `hue/FieldSafety.kt`.

**New:** a detector alongside the analyzer, the geometry additions to `SpatialWaves.kt`,
and a gesture layer in `SyncoEngine`'s render.
