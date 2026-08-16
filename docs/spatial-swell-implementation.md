# Spatial swells: the implementation

Companion to [`spatial-swell-plan.md`](spatial-swell-plan.md), which says *why* and
*what*. This says *how* — the files, the types, the formulas, the thresholds, and the
order to build them in. Same standard: every claim about existing code is **verified**
(read and quoted, with a line number) or marked **assumed**.

## Context

The design doc was written from a request made during device testing: a sound that
travels across the stereo field, or a swell that rises without hitting anything, should
move the room rather than just brighten it. The engine currently has no notion of pan
*movement* and no notion of what *shape* the lamps are in, so both gestures render as a
static per-lamp bias — the correct instantaneous answer, and not a gesture.

Nothing in the design doc has been built. This document is the buildable version of it,
and it is scoped to **the whole feature in one branch**, behind one off-by-default
setting, because the only honest judgement of "does a moving light read as the sound
moving" is a debug APK in a real room with real lamps — and that needs all four
topologies present, not one.

---

## What reading the code changed

Five findings. Three of them contradict the design doc, and two add constraints it did
not know about. All are verified.

### 1. `AudioAnalyzer.processStereo` does not exist — it is `pushStereo`

`AudioAnalyzer.kt:469`. Cosmetic, but it is the function the whole traversal detector
hangs off, so it should be named correctly before anyone greps for it.

### 2. A mono source produces `pan = [0, 0, …]`, **not** an empty array

The design doc's open question assumed "a mono stream leaves `pan` empty, which the
detector must treat as no data". It does not. `AudioAnalysisTap.kt:243-251` folds a mono
or multichannel source onto *both* sides:

```kotlin
// Left and right kept apart. A mono or multichannel source folds to
// the same value on both sides, so downstream never has to care.
l = buf.getShort(offset) / 32768f
r = if (ch > 1) buf.getShort(offset + 2) / 32768f else l
```

So `melL == melR`, and `pan` comes out sixteen exact zeros. Empty means *silence*
(`pushStereo` returns early on a noise-gated frame, `AudioAnalyzer.kt:473`) and nothing
at all currently distinguishes mono material from a perfectly centred mix.

**Consequence:** the detector needs its own stereo-ness measure, and it is cheap — see
`stereo` in [§2](#2-the-detectors). This also answers the Music Assistant open question
without needing a real MA stream to check: a downmixed MA stream cannot produce a false
traversal, because a signal that is identically zero has no excursion to detect. It can
only produce *silence* from the feature, which is the correct behaviour.

### 3. `LocalReducedMotion` is not reachable from the engine

`ui/design/Motion.kt:169` — it is a Compose `compositionLocalOf`, populated by
`rememberReducedMotion()` (`Motion.kt:183`), which reads
`ValueAnimator.areAnimatorsEnabled()`. `SyncoEngine` and `DirectLightSync` are not
composables and there is no path to it.

**Consequence:** honouring reduced motion means calling
`ValueAnimator.areAnimatorsEnabled()` directly in `DirectLightSync`, which already takes
a `Context` as its first constructor parameter (`DirectLightSync.kt:195`). Same source of
truth, no plumbing. See
[§6](#6-restraint-and-safety).

### 4. Hue entertainment positions are a **2-D floor plan**; `z` is usually zero

Verified against `Hue Entertainment API - Philips Hue Developer Program`:

> This area … can contain a list of the lights and their **x/y position** within in the
> room in relation to a screen and the position of the user.

and its own worked example reports `"position": {"x": -0.6, "y": 0.8, "z": 0.0}` for
every channel. The Hue app's area editor places lamps on a plan; height is not something
most users ever set.

**Two consequences, both structural:**

- **The ring fit must be done in the x–y plane**, not as a general 3-D plane fit. A
  plane fit through a set of points whose `z` is identically zero is degenerate.
- **The "bottom to top" half of the request will not render in most rooms.** There is no
  height to travel through. The feature must detect this and fall back to a uniform
  bloom rather than rendering a vertical sweep across lamps that are all at the same
  height, which would look like a random flicker. `normalizePositions`
  (`SpatialWaves.kt:46`) already collapses a spread-less axis to 0.5, so the fallback is
  a check, not a rewrite.

### 5. Classification must read **raw** positions, not normalised ones

`normalizePositions` maps each axis onto 0..1 over *the area's own extent*
(`SpatialWaves.kt:42-47`). Two lamps thirty centimetres apart on a shelf therefore
normalise to a full-width room. Every shape test in this feature that asks "is this
actually big enough to sweep across" must run on the raw `ChannelPosition` values, where
the room nominally spans −1..1. The shape tests (linearity, ring residual) are
scale-invariant and can use either; the *extent* test cannot.

---

## What the Hue documentation settles

The two documents named in the request turn out to answer three of the design doc's
harder questions directly, because Philips' own effect engine already has these
primitives.

| Their primitive | What it settles |
|---|---|
| **`LightSourceEffect`** — a virtual source with animated *position* and *radius*; their own example is "a fireball with a fixed radius which moves from the front to the back of the room" | This is the `FIELD` gesture, exactly. It confirms the design doc's instinct not to reuse `Wave`: an expanding shell with a moving origin is the wrong primitive and would cost `Wave.amplitudeAt` its sqrt-free optimisation (`SpatialWaves.kt:120`). A moving Gaussian source is the right one. |
| **`LightIteratorEffect`** — iterates over lights in an *order*, and the orders include "counter clockwise", with modes `single`/`cycle`/`bounce` | Confirms angular ordering is the right model for "lights in the corners go around in a circle", and that a single-pass (`single`) traversal and a repeating one are different things. We build `single`. |
| **`AreaEffect`** — "if a specific setup has no light in a certain area, the effect won't be visible. **This may be desired in case it is better to not show an effect at all, than to show it on the wrong location.**" | Philips' own justification for the `CLUSTER` refusal. Worth quoting in the code comment. |
| The **mixer**: every effect has a layer and an opacity, and the engine mixes them per frame | Confirms the additive-layer design. Our equivalent is the `flash` sum at `SyncoEngine.kt:1227`. |

The **Light Effects Experience guide book** adds four hard numbers and one warning that
this feature must respect, because a room-crossing sweep is precisely the case it warns
about:

- "Keep rapid changes in brightness to frequencies lower than 5 Hz."
- "**Sudden changes in brightness of lamps in our peripheral vision (to our side) or
  lamps near to us, can be unpleasant.**" — a sweep is peripheral motion by definition,
  so the front must fade in and out rather than appear.
- "People are more sensitive to rapid brightness changes and less sensitive to rapid
  colour changes… make the brightness transition slower than the colour transition." —
  the gesture is a brightness layer and must stay slow. It does not touch colour.
- Their swirl effect gives a per-lamp dwell: "each time a separate lamp lights up
  (flashes) will last **about 0.5 seconds**." That is a calibration point for
  `gestureWidth` against `durationS`, and it is well under the 12.5 Hz ceiling
  `FieldSafety.kt:36` already enforces.

---

## The build

Seven parts. Files named; new code marked **new**.

### 1. Geometry — `hue/SpatialWaves.kt`

All pure functions, all unit-testable with no bridge, no audio and no device — which is
how the rest of that file is already written and why its tests exist
(`SpatialWavesTest.kt`).

**New types:**

```kotlin
enum class RoomTopology { LINEAR, RING, FIELD, CLUSTER }

/** A least-squares circle through the lamps, in the room's floor plane. */
data class Ring(val centre: Vec3, val radius: Float, val residual: Float)
```

**New functions:**

- `principalAxis(positions: List<Vec3>): Pair<Vec3, Float>` — the dominant direction and
  the fraction of total variance it explains. 3×3 covariance, then power iteration from
  a fixed start vector (deterministic, ~48 iterations, no eigen library). Variance
  explained is `λ₁ / trace(C)` with `λ₁ = vᵀCv`.
- `fitRing(positions: List<Vec3>): Ring?` — Kåsa least-squares circle in **x–y**
  (finding 4). Solve
  `[Σx² Σxy Σx; Σxy Σy² Σy; Σx Σy n]·[a b c]ᵀ = ½[Σx·s, Σy·s, Σs]ᵀ` where `s = x²+y²`,
  by 3×3 Cramer; `R = √(a²+b²+c)`. Returns null on a near-zero determinant. `residual`
  is `RMS(|rᵢ − R|) / R`, so it is scale-free.
- `azimuthOf(pos: Vec3, ring: Ring): Float` — `atan2(y−cy, x−cx)` normalised to **0..1
  turns**, counter-clockwise from +x. Room-right is 0, front is 0.25, room-left is 0.5.
- `largestAzimuthGap(azimuths: List<Float>): Float` — sort, diff, wrap. This is the test
  that separates "round the room" from "four lamps in an arc along one wall".
- `axisPosition(pos, dir, origin): Float` — dot projection; the caller min-max
  normalises across the area, exactly as `buildChannelMap` already does for the colour
  axis (`SyncoEngine.kt:532-544`).
- `classifyTopology(channels: List<EntertainmentChannel>): RoomTopology` — takes
  **channels, not normalised positions** (finding 5).

**Classification, in order:**

| Step | Test | Result |
|---|---|---|
| 1 | `channels.size < 3`, or raw extent `< CLUSTER_EXTENT` on *every* axis | `CLUSTER` |
| 2 | variance explained by the principal axis `> LINEAR_VARIANCE` | `LINEAR` |
| 3 | `size ≥ RING_MIN_LAMPS` **and** `residual < RING_RESIDUAL_MAX` **and** `largestAzimuthGap < RING_GAP_MAX` | `RING` |
| 4 | otherwise | `FIELD` |

Starting constants, to be tuned on device:

```kotlin
private const val CLUSTER_EXTENT    = 0.40f  // raw Hue units; the room spans -1..1
private const val LINEAR_VARIANCE   = 0.80f
private const val RING_MIN_LAMPS    = 4
private const val RING_RESIDUAL_MAX = 0.25f
private const val RING_GAP_MAX      = 0.40f  // turns; 144°
```

**New gesture primitives**, living beside `Wave` for the same reason `Wave` lives there
— they are geometry with a clock, and they are the testable half of the renderer:

```kotlin
/**
 * A soft front travelling along one room coordinate.
 *
 * [circular] measures distance the short way round, for a ring. Widths are in
 * the coordinate's own units: normalised axis units for a line, turns for a ring.
 */
class TravellingFront(
    val from: Float, val to: Float,
    val durationS: Float, val strength: Float, val width: Float,
    val circular: Boolean = false,
    var age: Float = 0f,
) {
    fun advance(dt: Float) { age += dt }
    val progress: Float                      // age / durationS, clamped
    val done: Boolean
    val front: Float                         // smoothstep(progress) between from and to
    val envelope: Float                      // fade in over 0..0.15, out over 0.75..1
    fun amplitudeAt(coord: Float): Float     // strength * envelope * exp(-(d/width)²)
}

/** Hue's `LightSourceEffect`: a virtual source that moves through the room. */
class MovingSource(
    val from: Vec3, val to: Vec3,
    val durationS: Float, val strength: Float, val radius: Float,
    var age: Float = 0f,
) {
    fun amplitudeAt(pos: Vec3): Float        // one sqrt per lamp per frame, only while live
}
```

`MovingSource` is the one place in the render that costs a square root per lamp per
frame. That is acceptable *because gestures are rare by design* — a budget of four a
minute, each a few seconds long — and it must be documented as the reason `Wave` was not
extended instead.

### 2. The detectors — `audio/GestureTracker.kt` **(new file)**

One class, mirroring `StructureTracker` exactly: constructed with the analyzer's frame
period, `update()` once per hop, `reset()`, not thread-safe, owned by the analysis
thread. That is a shape the codebase already has and already tests.

```kotlin
enum class GestureKind { NONE, TRAVERSAL, SWELL }

data class GestureState(
    /** Bumped once per launched gesture. The renderer launches on a change. */
    val id: Long = 0L,
    val kind: GestureKind = GestureKind.NONE,
    val fromPan: Float = 0f,      // -1..+1, absolute — where it started
    val toPan: Float = 0f,        // -1..+1, absolute — where it ended
    val durationS: Float = 0f,    // measured, not assumed
    val strength: Float = 0f,     // 0..1 confidence × size
    val rise: Float = 0f,         // centroid climb over the window, 0..1
    val stereo: Float = 0f,       // how stereo the material is; 0 = mono (see finding 2)
)

class GestureTracker(private val framePeriod: Float) {
    fun update(frame: AnalysisFrame, structure: StructureState?): GestureState
    fun reset()
}
```

**History.** Decimated to 10 Hz (`DECIMATE = 5` frames — pan is already smoothed at
~100 ms by `panSmooth`, `AudioAnalyzer.kt:412`, so nothing is lost), over 4 s:

```kotlin
private const val DECIMATE  = 5
private const val HISTORY_S = 4.0f
private const val HIST      = 40                    // 4 s at 10 Hz
// MELBANK_BINS * HIST * 2 floats = 1280 floats. Nothing.
private val panHist = FloatArray(MELBANK_BINS * HIST)
private val melHist = FloatArray(MELBANK_BINS * HIST)
private val mixHist = FloatArray(HIST)              // broadband pan centroid
private val energyHist = FloatArray(HIST)
private val centroidHist = FloatArray(HIST)
private val quietHist = BooleanArray(HIST)          // no beat and flux under the gate
```

`frame.melbank` **must be copied** — it is `melFilter.state` returned by reference
(`AudioAnalyzer.kt:212`) and is mutated in place on the next hop. `frame.pan` is already
`.copyOf()`'d (`AudioAnalyzer.kt:482`) but we only store scalars from it anyway.

An empty `pan` (silence) **holds** the history rather than pushing zeros — a gap between
phrases is not evidence that a sound stopped moving.

**A — traversal.** Four steps, and step 2 is the one the design doc correctly called the
important one:

1. Broadband pan centroid, energy-weighted: `panMix = Σ mel[i]·pan[i] / Σ mel[i]`.
2. Per-bin relative pan: `rel[i] = pan[i] − panMix`. **This is what removes the
   common-mode trap.** A mix that leans left moves every bin together and cancels here;
   only a source moving *relative to the mix* survives.
3. Stereo-ness, so mono material can never fire (finding 2):
   `spread = Σ mel[i]·|pan[i] − panMix| / Σ mel[i]`, EMA'd with τ ≈ 5 s into `stereo`.
   Mono sits at exactly 0.
4. Score every (bin, window) pair over `WINDOWS_S = [0.6, 1.2, 2.4, 4.0]`:

```
exc      = |rel[end] − rel[start]|                       // a real crossing
tot      = Σ |Δrel| across the window                    // total wandering
mono     = exc / max(tot, 1e-6)                          // 1 = a sweep, ~0 = a wobble
eMin     = min(mel[bin]) across the window               // it never went silent
score    = smoothstep(0.30, 0.85, exc)
         × smoothstep(0.70, 0.92, mono)
         × smoothstep(0.08, 0.20, eMin)
         × smoothstep(0.02, 0.10, stereo)
```

**Adjacency requirement:** a real source occupies more than one mel bin, so a bin only
counts if a neighbour also scores above `0.6 × START`. A lone bin drifting is far more
likely to be the per-bin AGC (`AudioAnalyzer.kt:193-202`) rebalancing than a sound
moving. Final `strength` is the mean of the agreeing pair; `fromPan`/`toPan` are their
**absolute** `pan` values at the window ends, not the relative ones — relative pan is for
*detecting*, absolute pan is for *placing it in the room*.

**B — swell.** Scalar, over the same windows:

```
slope, r2 = linear regression of energyHist over the window
riseE     = slope × windowS                              // total climb
quiet     = fraction of the window with no beat and flux < 0.25
rise      = centroid slope × windowS
score     = smoothstep(0.02, 0.15, riseE)
          × smoothstep(0.55, 0.85, r2)                   // linear, not a pumping loop
          × smoothstep(0.75, 0.98, quiet)                // no onsets
          × (0.5 + 0.5 × structure.buildProgress)
```

`r²` is what makes this a *swell* rather than "the chorus got louder". `StructureState`
is passed in rather than recomputed — `DirectLightSync.onAnalysisFrame` already has it
one line above (`DirectLightSync.kt:563`).

**Arbitration and restraint,** all inside the tracker so the renderer cannot be handed
something it should refuse:

- **Hysteresis** is structural: while a gesture is live the detectors are *suspended
  entirely*. Two overlapping sweeps read as noise, so the second never exists.
- **Refractory** of `max(2.0s, durationS)` after one ends.
- **Budget** of `MAX_PER_MIN = 4` launches in a rolling 60 s of `frame.tAudio`.
  Exhaustion is logged once per track — the cap being *hit* is the interesting signal.
- **Traversal wins** when both fire, carrying `rise` through, so a riser that also pans
  is one gesture and not two.
- `durationS` is the winning window, clamped to 0.6..6.0 s. A slow pad and a fast riser
  must not look the same; this is the only thing that stops them.
- `reset()` clears history, refractory and the budget deque.

### 3. Wiring — `hue/DirectLightSync.kt`

Four small edits, each mirroring what `StructureTracker` already does:

| Site | Change |
|---|---|
| `:292` (field block) | `private var gestures: GestureTracker? = null` and `@Volatile private var latestGesture: GestureState? = null` |
| `:453` | `gestures = GestureTracker(framePeriod)` beside `structure = StructureTracker(framePeriod)` |
| `:563` in `onAnalysisFrame` | `latestGesture = gestures?.update(frame, arc)` — after `structure.update`, so it sees this frame's arc |
| `:648` in `onAnalysisReset` | `gestures?.reset()`; `latestGesture = null` |
| `:713` in `renderLoop` | `eng.render(frame, dt, latestGrid, latestStructure, latestGesture)` |

Plus the reduced-motion read (finding 3) and the setting collector — see §6 and §7.

Threading is unchanged and free: `onAnalysisFrame` is already the analysis thread and
already the sole owner of `TempoTracker` and `StructureTracker`
(`DirectLightSync.kt:59-62`), so `GestureTracker` inherits that ownership with no
locking.

### 4. The render layer — `hue/SyncoEngine.kt`, `hue/SyncoModes.kt`

**Construction-time geometry** (once per session; the lamps do not move while streaming,
which is the same argument `coupling` at `SyncoEngine.kt:386` already makes):

```kotlin
private val topology: RoomTopology = classifyTopology(channels)
private val ring: Ring? = if (topology == RoomTopology.RING) fitRing(positions.values.toList()) else null
/** Raw z spread — the Hue app is a floor plan, so this is usually false. See finding 4. */
private val hasHeight: Boolean = rawZSpread(channels) > HEIGHT_MIN_SPREAD
```

**`ChannelInfo` gains two precomputed coordinates** (`SyncoEngine.kt:352`), so the render
loop needs no trigonometry:

```kotlin
/** 0..1 along the principal axis, oriented so increasing is room-right. */
val axisPos: Float,
/** 0..1 turns around the fitted ring. Meaningless unless topology is RING. */
val azimuth: Float,
```

**`ModeParams` gains four fields** (`SyncoModes.kt`), following the house convention that
0 disables the layer and is the revert — the same shape as `tonalGain`, `colourTilt` and
`spatialCoupling`:

```kotlin
/** Brightness a full-strength room gesture adds. Zero disables the layer. */
val gestureGain: Float = 0f,
/** Front width along a linear axis, in normalised room units. */
val gestureWidth: Float = 0.30f,
/** Front width around a ring, in turns. 0.18 ≈ 65°. */
val gestureArcWidth: Float = 0.18f,
/** Moving-source radius for a FIELD room. */
val gestureRadius: Float = 0.45f,
```

Preset values: `SUBTLE 0.18` (its identity is a slow gradient, which a slow sweep suits),
`MEDIUM 0.30`, `HIGH 0.30`, `INTENSE 0.25`, `EXTREME 0`.

**Known limitation, stated up front:** Extreme and Fireworks cannot show gestures at all.
`render()` returns early for both (`SyncoEngine.kt:890-891`), before the flash overlay
this layer joins. That is correct rather than a gap — Extreme is a different renderer,
not a louder one — but it must be in the release note so it is not reported as a bug.

**Tunable.** `gestureGain` is scaled by `movement` in `withTunables`
(`SyncoEngine.kt:309`). No eighth slider: `movement`'s own blurb already promises exactly
this — *"How much the show travels between lamps rather than lighting them together. Up
for sweeps and chases across the room"* (`SyncoEngine.kt:257`). The existing rate
scalings (`rotateRate`, `rotateSwing`, `waveSpeed`) are untouched.

**Launch and advance.** `render()` takes `gesture: GestureState? = null` and launches on
an **id change** — never on a threshold, so a gesture cannot re-fire per frame the way
`waveArmed` (`SyncoEngine.kt:509`) exists to prevent for waves:

```kotlin
if (spatialGestures && p.gestureGain > 0f && gesture != null &&
    gesture.id != lastGestureId && gesture.kind != GestureKind.NONE) {
    lastGestureId = gesture.id
    launchGesture(gesture, p)
}
liveFront?.advance(dt); liveSource?.advance(dt)   // retire when done
```

**`launchGesture` maps pan to the room's own coordinate**, per topology:

| Topology | Traversal | Swell |
|---|---|---|
| `LINEAR` | `TravellingFront` on `axisPos`, `from = (fromPan+1)/2`, `to = (toPan+1)/2`, width `gestureWidth` | vertical front on `pos.z` if `hasHeight`, else uniform bloom |
| `RING` | `TravellingFront` on `azimuth`, `circular = true`, width `gestureArcWidth`, endpoints from `azimuthForPan` below | same |
| `FIELD` | `MovingSource` from `Vec3((fromPan+1)/2, meanY, meanZ)` to `Vec3((toPan+1)/2, meanY, meanZ)`, radius `gestureRadius` | source rising `z 0 → 1` if `hasHeight`, else uniform bloom |
| `CLUSTER` | **nothing** — returns without launching | uniform bloom only |

**The front arc, which is the part that is easy to get backwards.** With `azimuthOf`
measured counter-clockwise from +x, room-right is 0 turns, the front of the room is 0.25
and room-left is 0.5. So:

```kotlin
/** Hard left → half a turn; hard right → zero. Interpolating between them passes
 *  through 0.25 — the front of the room — which is where a sound crossing in front
 *  of the listener must appear to go. The other arc would send it behind them. */
private fun azimuthForPan(p: Float): Float = (1f - (p + 1f) / 2f) * 0.5f
```

Linear interpolation from `azimuthForPan(fromPan)` to `azimuthForPan(toPan)` therefore
*is* the front arc, with no arc-choosing logic. Circular wrapping affects only how
distance to a lamp behind the listener is measured.

**The additive contribution** joins the existing flash sum at `SyncoEngine.kt:1226-1227`:

```kotlin
val wave = if (p.waveGain > 0f) p.waveGain * waveAmplitude(info) * musicGate else 0f
val gest = if (p.gestureGain > 0f) gestureAmplitude(info) * musicGate else 0f
val flash = (lightFlash[cid] ?: 0f) + wave + swell + gest
```

`flash`, not `target`, deliberately — and for the opposite reason to the sustain bloom
three lines above it. The bloom is room-wide and belongs inside `briAttack`/`briDecay`;
a *travelling* front must not be, because `briDecay` would smear it as it moves off each
lamp and flatten the near-versus-far difference that makes it read as motion. It is
still slew-limited on the rise by `briSlew` at `SyncoEngine.kt:1267`, and it is smooth by
construction anyway.

`gestureAmplitude` is capped at `GESTURE_MAX = 0.35f` of full scale. This is a wash, not
a flash — Hue's guide book is explicit that sudden peripheral brightness changes are
unpleasant, and a room-crossing sweep is peripheral by definition.

### 5. Making the speed and brightness come from the sound

As asked, and it is three lines rather than a section:

- **Speed** is `durationS`, measured by whichever window won. Never a constant.
- **Brightness** is `strength × gestureGain × movement × musicGate`, capped at
  `GESTURE_MAX`.
- **Width has a floor tied to speed.** If `durationS / nChannels` would put a lamp's
  dwell under ~0.15 s, widen the front instead of letting it sharpen. Below that the room
  reads as a chase rather than a sweep, and Hue's own swirl guidance puts a comfortable
  per-lamp dwell at ~0.5 s.

### 6. Restraint and safety

1. **Confidence, not a boolean** — `strength` scales the whole layer, so a marginal
   detection is a barely-visible drift rather than a full sweep.
2. **Additive only.** The worst case with the detector wrong is the current show plus a
   soft wash. Nothing is replaced or suppressed.
3. **`FieldSafety` and `EffectRateLimiter` need no change** — they already sit downstream
   of `render()` in `renderLoop` (`DirectLightSync.kt:730-731`), so the layer is inside
   the flash budget and the 12.5 Hz ceiling for free. A test asserts a full-strength
   gesture does not increase `flashesInWindow`.
4. **Reduced motion** — `ValueAnimator.areAnimatorsEnabled()` read in `DirectLightSync`
   (finding 3), re-read on session start; false zeroes `gestureGain`.
5. **Off by default**, behind the setting in §7, until it has been judged in a real room.

### 7. The setting

The end-to-end pattern is already there; copy `lightSyncPrescan` (`AppSettings.kt:141`,
`:879`, `:1014`, `LightSyncSettings.kt:410`):

- `AppSettings.kt` — `private val LIGHT_SYNC_SPATIAL = booleanPreferencesKey("light_sync_spatial")`,
  `val lightSyncSpatial: Flow<Boolean> = … ?: false`, `suspend fun setLightSyncSpatial(on: Boolean)`.
- `DirectLightSync.observeSettings()` (`:1165`) — a collector setting
  `engine?.spatialGestures = on`, alongside the brightness and tunables collectors.
- `LightSyncSettings.kt` — a `ToggleRow` under the advanced section (`lightSyncAdvanced`,
  `AppSettings.kt:859`), worded against the effect rather than the mechanism, as the other
  rows are. Something close to: *"Room gestures — when a sound sweeps across the stereo
  field, or a swell rises with no beat under it, let the light travel across the room with
  it. Rooms with only a couple of lamps get a soft brightness swell instead."*

### 8. Tests

JUnit 4 via `kotlin-test`, same package as the code (so `internal` declarations are
reachable), backtick prose names, every assertion carrying the actual value — the
conventions every existing test in `audio/` and `hue/` follows.

**`hue/SpatialWavesTest.kt`** (extend) — synthetic rooms, no audio, no device:

- a line of five → `LINEAR`, and the principal axis is the line
- a hexagon → `RING`, residual near zero, azimuths evenly spread
- **four lamps in an arc along one wall → not `RING`** — the gap test earning its place
- two lamps 20 cm apart → `CLUSTER`, *even though `normalizePositions` stretches them to
  a full-width room* (finding 5; this is the test that would catch that regression)
- eight scattered → `FIELD`
- a flat-`z` room → `hasHeight` false (finding 4)
- `azimuthForPan(-1)` → 0.5, `azimuthForPan(+1)` → 0, and the interpolation passes
  through 0.25 — the front-arc test
- `TravellingFront` peaks at each lamp in room order and only once; `circular` measures
  the short way round

**`audio/GestureTrackerTest.kt`** **(new)** — synthetic frames, driven hop by hop at
`dt = 0.02f`, following `StructureTrackerTest`'s shape exactly:

- a tone panned hard left to hard right over 2 s → one traversal, `fromPan < −0.3`,
  `toPan > 0.3`, `durationS` within ~30% of 2 s
- **a whole mix that leans left → no traversal.** The common-mode test, and the one the
  feature is most likely to fail in the field
- a vibrato-ish pan wobble → no traversal (monotonicity near zero)
- a bin that goes silent and returns on the other side → no traversal (it cut, it did not
  travel)
- **mono input (`pan` all zeros) → no traversal, `stereo == 0`** (finding 2)
- a linear energy ramp with no beats → one swell; the same ramp with beats → none
- a track that trips the detector constantly → at most `MAX_PER_MIN` launches
- steady music for 60 s → zero gestures. The false-positive guard, and the most important
  test in the file
- `reset()` clears everything

**`hue/SyncoEngineGestureTest.kt`** **(new)**:

- a linear room, one traversal → the per-lamp peak arrives in left-to-right order, each
  lamp peaks once
- a ring room, one traversal → the peak order goes round the front, not the back
- a cluster room, one traversal → **no per-lamp variation at all**; a swell there still
  blooms
- `gestureGain = 0` → output is byte-identical to the current renderer (the revert test,
  matching how `colourTilt = 0` and `spatialCoupling = 0` are already guarded)
- a full-strength gesture through `FieldSafety` → `flashesInWindow` unchanged
- Extreme and Fireworks → unaffected, since they return early

---

## Order of work

One branch, one flag, but built in this order so each step is independently
falsifiable:

1. **Geometry and its tests.** No audio, no device. If the classifier cannot tell a ring
   from an arc, nothing downstream matters.
2. **`GestureTracker` and its tests**, plus a `logThrottled` line in
   `onAnalysisFrame` (`DirectLightSync.kt:973` already has the throttled logger) naming
   what fired and how strongly. Playing real music through a debug build at this point
   costs nothing and answers the only question that matters: *how often does this fire,
   and on what*.
3. **The render layer**, all four topologies, behind the setting.
4. **The setting and the UI row.**
5. **Tune on device** against `movement`, and against the log from step 2.

## Verification

**Off device:**

```
./gradlew :app:testDebugUnitTest
```

Everything in §8 runs here. This proves the detector fires on a synthetic sweep and not
on a synthetic wobble, which is necessary and nowhere near sufficient.

**On device** — the step this branch exists to reach:

```
./gradlew :app:assembleDebug     # app/build/outputs/apk/debug/
```

Then, with a phone on the same network as the bridge:

1. Enable the toggle under Lights → Advanced. Confirm that with it **off** the show is
   unchanged — the revert path, checked by eye and not only by test.
2. Check the classification is right for the actual room before judging anything else. A
   log line at session start naming the topology, the lamp count and the ring residual
   makes this a five-second check instead of a guess.
3. Play material that should fire: something with an actual production pan sweep, and a
   riser into a drop. Confirm the direction matches — a sound crossing left to right must
   not light the room right to left, which is the single most likely sign-error.
4. Play material that should **not** fire: a dense mix with a hard-panned but *static*
   guitar, and anything with an off-centre master. Watch for the common-mode failure.
5. Play a full album with the log on and read the budget line. If the cap is being hit
   regularly, the thresholds are wrong, not the room.
6. Repeat on a second area shape if one is available — a linear room and a corner room
   read completely differently and only one of them is likely to be set up first.

## What building it changed

Four things the tests found that this document had wrong. They are recorded here
rather than quietly fixed, because each one is a way the feature could have shipped
looking broken instead of absent.

### The detector needed two conditions this plan did not have

**Monotonicity alone calls a wobble a sweep.** Half a period of *any* oscillation is
perfectly monotone, so a 1.2 s vibrato scored as a full traversal four times a bar.
The missing idea is that a traversal is a **one-way trip**: it does not come back.
`GestureTracker.crossings` counts how many times a bin crosses the midpoint of the
candidate sweep across the whole four-second history — once for a real traversal,
repeatedly for anything oscillating.

**A loud one-sided source *stopping* looked like every other bin moving.** Subtracting
the broadband pan centroid is what removes a mix that leans, but it has a mirror-image
failure: when a loud hard-panned source cuts out, the centroid jumps, and every other
bin's position *relative to it* swings the other way without any of them having moved.
The fix is to require the **absolute** excursion to be large as well as the relative
one — the relative test says "it went somewhere the mix did not", and only the
absolute test says "the sound actually went somewhere".

Both are now in `scoreTraversal`, and both have a test that fails without them.

### The Kåsa circle fit was wrong, and only a test could tell

The linear system solves for `(2a, 2b, c)`, so only the centre terms are halved.
Halving the constant as well shrank every fitted radius by a room-dependent factor —
a perfect circle fitted with a residual of 0.18 against a threshold of 0.25. It would
have *mostly worked*, classifying obvious rings correctly and quietly failing on
rectangular rooms, which is the worst way for a geometry bug to behave.

### "The gesture spends none of the flash budget" was an over-claim

It spends one. A sweep genuinely brightens the room and then lets it go, and
`FieldSafety` counts that as one half-transition — correctly, since it is one. What
matters is the *rate*, and that is what the test now asserts: every individual step
stays under a quarter of `FLASH_DELTA`, so the room reads as a gradation rather than
as something switching. One flash of a three-flash budget, spread over a second and a
half, is a real and acceptable cost rather than a bug.

## Still assumed

- **How common a real traversal is.** Step 2's log answers it. If the answer is "rare
  outside electronic and film music", that is a good outcome and not a failure — a rare
  effect that is right when it fires is what was asked for.
- **Whether user-placed Hue positions are accurate enough for a ring fit.** Finding 4
  removed the worst version of this worry by fixing the fit to the floor plane, but a
  ring fit over four lamps someone dragged roughly into corners may still be noisy.
  `RING_RESIDUAL_MAX` is the dial; the on-device log line in verification step 2 is how
  it gets set.
- **Whether ~100 ms of pan smoothing is right.** It was chosen for the static per-lamp
  bias, where responsiveness matters; a traversal detector wants more. The 10 Hz
  decimation in `GestureTracker` is a second, slower filter in effect — deliberately
  inside the new code, so the shipped behaviour of `panSmooth` is untouched.
- **Every threshold in §1 and §2.** They are starting values reasoned from the signal,
  not measured ones.
