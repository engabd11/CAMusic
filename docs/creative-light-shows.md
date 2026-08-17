# Creative Light Show Layers

> Four additive layers that use CAMusic's existing analysis infrastructure to
> do things no Hue app can. Each is a toggle, off by default, layered on top of
> the existing music sync — not a replacement for it.

This describes the layers **as built**. Where the implementation departed from
the original design, [Changed during implementation](#changed-during-implementation)
says what moved and why — those divergences are the useful part of the record,
so they are kept rather than tidied away.

---

## Why these are unique

The app already has infrastructure that no Hue app or competitor has:

- **Pre-scanned track data** (`TrackScanStore`) — beat grid, key, intensity
  profile, section structure, per-band loudness, cached per track on disk
- **Real-time structure detection** (`StructureTracker`) — steady, building,
  drop, breakdown, classified live from the audio stream
- **Melbank-to-position mapping** (`SpatialWaves`) — frequency bins already
  assigned to physical lamp positions in 3D space
- **Spatial field renderer** — Gaussian kernel blending, room topology
  classification (line/ring/field/cluster), tilted colour axis, gestures
- **Phone sensors** — accelerometer, gyroscope, always present, never used
  for lighting
- **60 Hz DTLS pipeline** — `HueStreamEncoder` takes `Map<Int, Rgb>`,
  `FieldSafety` and `EffectRateLimiter` always apply, transport-agnostic

Each layer is **additive**: it blends on top of the existing
`SyncoEngine.render()` output. The base music sync continues underneath, and the
layer modulates or augments it.

---

## Architecture: how a layer plugs in

```
SyncoEngine.render(frame)  →  base RGB per lamp  (brightness ceiling applied)
                                    ↓
                            delay queue (audio/light alignment)
                                    ↓
                            LayerChain.apply(base, context)
                                    ↓
                      MusicDnaLayer → EmotionalArcLayer →
                      PhantomStageLayer → PhoneConductorLayer
                                    ↓
              FieldSafety → EffectRateLimiter → HueStreamEncoder → DTLS
```

The chain is a straight fold, not a fan-out: each layer sees the previous
layer's output, which is why `PhoneConductorLayer` is last — it modulates the
combined result of everything else. Any combination can be on or off.

Each layer implements:

```kotlin
interface LightShowLayer {
    val id: String
    fun apply(base: Map<Int, Rgb>, context: LayerContext): Map<Int, Rgb>
    fun reset() {}
}
```

- `id` is the settings-facing identity. `DirectLightSync` keeps a set of enabled
  ids and filters one ordered `allLayers` list by it, so enabling one layer
  never changes where the others sit relative to each other.
- `apply` must be pure in `base` and `context` — no I/O, no blocking — because
  it runs on the 60 Hz render loop. A layer needing external state
  (`PhoneConductorLayer`'s sensors) reads a snapshot before calling in.
- `reset()` drops everything carried between frames. It is called when the
  analyzer resets (a seek or a track change — the same hook that clears the
  tempo and structure trackers), when a stream starts, and when a layer is
  switched on. Default no-op, so a stateless layer stays a one-method contract.

`LayerContext` carries what a layer needs without coupling it to `SyncoEngine`
internals:

```kotlin
data class LayerContext(
    val frame: AnalysisFrame,          // live audio analysis
    val structure: StructureState?,    // from StructureTracker, null before one exists
    val scan: TrackScan?,              // adopted pre-scan, null before one lands
    val positions: Map<Int, Vec3>,     // normalised room-cube positions
    val topology: RoomTopology,        // LINEAR / RING / FIELD / CLUSTER
    val trackPositionS: Float,         // seconds into the track, -1f when unknown
    val dt: Float,                     // seconds since the previous frame
    val brightness: Float = 1f,        // the user's brightness ceiling
)
```

`brightness` is the one field that is easy to think unnecessary and is not.
`SyncoEngine` applies the user's setting as the *last* step of its own render,
and the chain runs after that — so a layer that adds or multiplies without
knowing the ceiling is working above a limit the user set, and `FieldSafety`
will not catch it. It limits how often the field may flash, not how bright it
may sit.

The chain is skipped entirely while idle: the frame is `SILENCE` and the scan
and structure are stale in that branch, so every layer would either no-op or
read nothing useful.

**Shared files:**
- `hue/LightShowLayer.kt` — interface, `LayerContext`, `LayerChain`
- `hue/LayerColour.kt` — the HSV/hue arithmetic all four layers work in
- `hue/DirectLightSync.kt` — the chain in the render loop, enable flags, resets
- `data/AppSettings.kt` — four boolean settings, all off by default
- `ui/screens/LightSyncScreen.kt` — four toggles

---

## Layer 1 — Music DNA

**A deterministic visual fingerprint per track.** Tempo sets a slow colour
drift's speed, key sets its anchor hue, the intensity profile shapes a
brightness arc, and section boundaries step the anchor as the track moves
through its own structure. Same song, same fingerprint, every time.

### What makes it unique

No Hue app has pre-scanned track data. This needed one thing the scan did not
yet carry — a musical key — so `audio/KeyDetection.kt` adds
Krumhansl-Kessler major/minor profile correlation over a whole-track chroma
vector accumulated during the offline scan, using the same `CHROMA_FMIN` /
`CHROMA_FMAX` bounds the live analyzer uses so the two cannot silently desync.
That is `ANALYSER_VERSION` 2 and scan file format 3; a version-1 scan simply
has no key and is flagged for re-analysis by the version bump.

### How it layers

```
fingerprint (once per scan)  ←  bpm → wave rate, key → anchor hue + saturation
per frame                    ←  section index → hue step, intensity → level
output                       ←  engine hue blended toward the anchor,
                                engine value scaled by the arc, under the ceiling
```

- **Wave rate** — `bpm / 240`, so 120 BPM is one cycle per two seconds. It
  drives a ±0.04-turn drift *around* the anchor, not a sweep through the wheel.
- **Anchor hue** — `tonic / 12`, the chromatic pitch-class wheel `SongPalette`
  already established, so adjacent semitones stay adjacent in hue.
- **Blend weight** — interpolated by the key's own confidence, between 0.15
  (a guess) and 0.35 (a clean read). `detectKey` measures confidence as the gap
  between the best and second-best correlation, because two keys a fifth apart
  scoring alike is this method's standard failure. At zero confidence the weight
  lands exactly on the no-key weight, so an unresolvable key degrades smoothly
  rather than falling off a step.
- **Mode** — minor desaturates slightly (×0.85).
- **Brightness arc** — the intensity profile at the playhead maps to a
  0.85..1.15 multiplier on the engine's value, clamped to the ceiling. A quiet
  verse sits under the engine's own level, a loud chorus above it.

### When the scan is missing

A no-op. The whole premise is a *known* fingerprint; approximating one from a
track still being learned live would drift as more of it is heard and then jump
when the real scan lands. This matches how the rest of the direct path already
treats "no scan yet". A scan with no key still fingerprints via tempo and
structure, at the lighter blend weight.

**Files:** `hue/MusicDnaLayer.kt`, `audio/KeyDetection.kt`, and the `key` field
threaded through `TrackScan`, `TrackAnalysis` and `TrackScanStore`.

---

## Layer 2 — Emotional Arc

**The room's colour temperature follows the song's live structure.** Calm reads
cool, a build warms toward the drop, the drop is hot, a breakdown pulls back
cold, and the very end of a track fades warm.

### The real phase set

`StructureTracker` classifies four phases, and this is the whole table:

| `SongPhase` | Target temperature | Reads as |
|---|---|---|
| `STEADY` | `-0.3`, **but only once the track has shown an arc** | cool |
| `BUILDING` | `-0.3` → `1.0` by `buildProgress` | warming |
| `DROP` | `1.0` | hot, more saturated |
| `BREAKDOWN` | `-1.0` | coldest, less saturated |

There is no intro, outro or unknown phase. Two consequences shape the layer:

- **The end-of-track fade is computed here**, from how close `trackPositionS` is
  to the scan's `durationS` — a window of `duration × 0.08` clamped to 8..30
  seconds, adding up to `+0.6`. Arithmetic on two floats already in the context,
  not a fifth phase in the tracker.
- **`STEADY` is not "a calm section".** `StructureTracker` documents it as
  "everything else", and it is `StructureState`'s default, so on ambient,
  classical, folk or spoken word it is the *only* phase that ever reports.
  Reading it as cool there would tint the whole track blue for its whole length
  and call that an emotional arc. So the layer waits for evidence the track has
  an arc at all: until a `BUILDING`, `DROP` or `BREAKDOWN` has been seen, it
  passes through. Afterwards `STEADY` means "the calm between the loud parts"
  and reads cool as designed. That evidence is per-track, and `reset()` is where
  it is forgotten.

### How it layers

A directional *nudge*, never a replacement. The temperature is smoothed with an
EMA — 1.2 s toward warm, 2.5 s back toward cool, so it lags the tracker's own
hysteresis rather than snapping — then applied as:

- a hue blend toward a warm (0.04) or cool (0.58) anchor, weighted by `|t|` up
  to 0.5, and
- a saturation multiplier of `1 + 0.25 t`.

Value is untouched, which is why this is the one layer with no ceiling
interaction. Below `|t| < 0.02` it returns `base` unchanged.

**Files:** `hue/EmotionalArcLayer.kt`

---

## Layer 3 — Phantom Stage

**Instrument groups at fixed physical positions in the room, for the session.**
Bass in one corner, vocals centre, guitar to one side, drums and synths spread
by depth. When that part of the mix hits, that part of the room flashes.

### What it actually is

There is no instrument-separation signal in the analyser, and none could exist
from a single mixed-down stream. The five groups are a **frequency-band proxy**,
the same idea `SpatialWaves.HEIGHT_BANDS` already uses:

| Group | Driven by | Room target (normalised) |
|---|---|---|
| `BASS` | `frame.bassBeat` / `bassStrength` | `(0.10, 0.5, 0.10)` |
| `DRUMS` | `frame.beat` / `beatStrength` | `(0.5, 0.5, 0.55)` |
| `GUITAR` | `frame.midBeat` / `midStrength` | `(0.9, 0.5, 0.4)` |
| `VOCALS` | sustained `bands["mid"]` | `(0.5, 0.3, 0.5)` |
| `SYNTHS` | sustained top-melbank level | `(0.5, 0.5, 0.9)` |

"Vocals" in practice means "midrange energy that holds rather than attacks",
which includes vocals but is not exclusive to them. `VOCALS` and `SYNTHS` are
held rather than onset-gated because neither has an onset boolean of its own.

### Assignment

One lamp per group: each group in priority order (`BASS`, `VOCALS`, `DRUMS`,
`GUITAR`, `SYNTHS`) claims the nearest *unclaimed* lamp to its target region.
With fewer than five lamps the later groups get none — claim order is priority
order, and re-claiming a taken lamp would let a low-priority group silently
steal it from an earlier one.

Assignment runs when the positions change identity, which is once per stream,
not per frame. It deliberately survives `reset()`: the stage is fixed for the
*session*, and the bassist staying in the same corner all night is the point.

A `CLUSTER` room, or an area with no positions, is a no-op — two lamps on a
shelf cannot be "the bassist's corner", the same precedent `RoomTopology.CLUSTER`
already carries for room gestures.

### How it layers

Additive in RGB: a fixed per-group hue at 0.9 saturation, at a level of
`0.12 persistent glow + sustained glow + decaying flash` (0.25 s decay).
Because it is additive it has never been through the engine's brightness
multiply, so the level is **scaled** by the ceiling and the sum clamped to it —
otherwise at a 20 % setting the glow alone would be most of the visible field.

**Files:** `hue/PhantomStageLayer.kt`

---

## Layer 4 — Phone Conductor

**The phone's motion becomes a lighting controller.**

| Gesture | Effect |
|---|---|
| Tilt left/right | hue shifts across the room, along the colour axis |
| Tilt up/down | brightness rises to 1.5× or falls to 0.5×, under the ceiling |
| Sharp flick | every lamp brightens, decaying over ~0.35 s |
| Sustained rotation | a hue wave travels around the room |
| Phone left still | after 5 s the layer becomes a full pass-through |

### What makes it unique

No Hue app uses phone motion as a lighting controller, and no new manifest
permission is needed — `SensorManager.getDefaultSensor` requires none. Sensors
are registered only while both the setting and a stream are live, in either
order: the toggle collector handles the setting changing, and `start()` handles
the setting already being on when a stream begins.

### Structure

Split in two, so the interesting half is testable with no `Context` in sight:

- **`DeviceMotionSource`** owns `SensorManager`. It low-passes the
  accelerometer for a gravity direction (tilt), detects a flick as a
  >15 m/s² jump in acceleration magnitude between samples, tracks yaw rate from
  the gyroscope, and reports `active = false` once nothing has moved for 5 s.
  `snapshot()` consumes the flick flag — an `AtomicBoolean`, because the set and
  the clear happen on different threads — and is a function, not a property,
  precisely because reading it twice in a frame is not the same as reading it
  once.
- **`ConductorRenderer`** is the pure transform: tilt → hue via the same
  `colourAxisProjection` the engine uses for room-wide drift, rotation → a
  travelling wave (azimuth on a fitted ring, otherwise the colour axis), flick →
  a decaying brightness add. All of it clamped to the ceiling.

Two details worth keeping:

- **The spin has an envelope.** Without it, a phone lying still — active, but
  never once rotated — would still show a static hue offset from
  `rotationPhase`'s resting zero, which is a wave sitting still rather than a
  wave that isn't there. The envelope ramps over 0.3 s and falls over 1.0 s.
- **Auto-disable is a full revert.** Once inactive, `apply` returns `base`
  untouched, including cancelling a flash mid-decay. Putting the phone down
  should read as "back to normal", not "normal, except for one more pulse".

### Safety

`FieldSafety` caps flashes at 3 Hz on the safe rungs and `EffectRateLimiter`
enforces the 12.5 Hz physical limit, both after the chain, so a flick cannot
strobe. The 15 m/s² flick threshold is high enough that walking or holding the
phone does not reach it. Brightness cannot exceed the user's ceiling.

**Files:** `hue/PhoneConductorLayer.kt`, `hue/DeviceMotionSource.kt`

---

## Changed during implementation

The original plan for this feature made six assumptions the code could not
keep. Each is a real constraint rather than a shortcut, so they are recorded
here.

1. **`SongPhase` has four values, not six.** The plan mapped verse, build,
   drop, breakdown, outro and unknown, with per-phase numbers for each. The
   tracker classifies `STEADY`, `BUILDING`, `DROP`, `BREAKDOWN`. The outro
   became the position-driven end-of-track fade; the `UNKNOWN → pass through`
   behaviour became the `structureSeen` gate, which is what preserves the
   plan's actual intent — leave structureless music alone — against an enum
   with no way to say "no structure".

2. **Instruments cannot be separated, so the stage is claimed by position.**
   The plan assigned groups by melbank bin range with per-topology `LINEAR` /
   `RING` / `FIELD` rules. Bin ranges do not identify instruments, and the
   per-topology rules turned out to be a more elaborate way of expressing
   "nearest lamp to where this instrument should be" — so the shipped version
   says that directly, with five fixed target regions.

3. **Music DNA nudges rather than flooring.** The plan had it replace the
   engine's `base`/`floor` parameters and combine as
   `max(DNA base, engine reactive)`. A `max` against the engine's output fights
   its brightness slew limiter, which exists to keep peripheral-vision changes
   comfortable. A multiplicative arc under the ceiling gets the same "quiet
   verse dims, loud chorus lifts" result without reaching into the engine.

4. **The colour work is in HSV, not CIELAB.** The plan specified CIELAB
   temperature shifts via `AlbumColours.kt`. Every layer turned out to want the
   same small vocabulary — round-trip to HSV, blend a hue the short way round —
   so that lives in `LayerColour.kt` instead. `AlbumColours` keeps its CIELAB
   conversions for its own job.

5. **`LayerContext` is narrower, except for brightness.** `t`, `beatGrid`,
   `palette` and `deviceMotion` are gone: no layer reads them, device motion has
   its own lifecycle rather than being per-frame render data, and `dt` is what a
   layer that integrates actually needs. `brightness` went the other way — it
   was nearly dropped as dead weight, and is in fact required, because the
   engine applies it before the chain runs.

6. **The interface carries `id` and `reset()`, not `enabled`.** Enablement lives
   in `DirectLightSync`'s set of enabled ids, which is what makes "reset only
   the layer that just came on" expressible. `reset()` was not in the plan at
   all; without it, a layer's smoothed state outlived the track it described.

## Deliberately not built

- No new DTLS transport — reuses `HueDtlsClient` + `HueStreamEncoder`
- No new colour palettes — reuses `SyncoPalette` with hue rotation
- No new manifest permissions
- No change to `SyncoEngine.render()` — layers apply after the base render
- No change to `FieldSafety` or `EffectRateLimiter` — they run after the chain,
  so every layer gets safety limiting for free
- No change to the HA light sync path — direct-to-bridge only, since the HA
  path has no access to the analysis tap
