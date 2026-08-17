# Creative Light Show Layers — Implementation Plan

> Four additive layers that use CAMusic's existing analysis infrastructure to
> do things no Hue app can. Each is a toggle, off by default, layered on top of
> the existing music sync — not a replacement for it.

---

## Why these are unique

The app already has infrastructure that no Hue app or competitor has:

- **Pre-scanned track data** (`TrackScanStore`) — beat grid, key, intensity
  profile, section structure, per-band loudness, cached per track on disk
- **Real-time structure detection** (`StructureTracker`) — verse, build,
  drop, breakdown, outro, classified live from the audio stream
- **Instrument-to-position mapping** (`SpatialWaves` melbank windows) —
  frequency bins already assigned to physical lamp positions in 3D space
- **Spatial field renderer** — Gaussian kernel blending, room topology
  classification (line/ring/field/cluster), tilted colour axis, gestures
- **Phone sensors** — accelerometer, gyroscope, always present, never used
  for lighting
- **60 Hz DTLS pipeline** — `HueStreamEncoder` takes `Map<Int, Rgb>`,
  `FieldSafety` and `EffectRateLimiter` always apply, transport-agnostic

These four layers draw on that infrastructure. Each is an **additive layer**
that blends on top of the existing `SyncoEngine.render()` output — the base
music sync continues underneath, and the layer modulates or augments it.

---

## Architecture: how a layer plugs in

```
SyncoEngine.render(frame)  →  base RGB per lamp
                                    ↓
                            LayerChain.apply(base, context)
                                    ↓
              ┌─────────────────────┼─────────────────────┐
              ↓                     ↓                     ↓
        MusicDnaLayer         EmotionalArcLayer    PhantomStageLayer
              ↓                     ↓                     ↓
              └─────────────────────┼─────────────────────┘
                                    ↓
                          PhoneConductorLayer (modulates
                          the base + layers using motion)
                                    ↓
                          FieldSafety → HueStreamEncoder → DTLS
```

Each layer implements:

```kotlin
interface LightShowLayer {
    val enabled: Boolean
    fun apply(
        base: Map<Int, Rgb>,      // what SyncoEngine produced
        context: LayerContext,     // engine state, scans, sensors, time
    ): Map<Int, Rgb>              // modified output
}
```

`LayerContext` carries everything a layer needs without coupling it to
`SyncoEngine` internals:

```kotlin
data class LayerContext(
    val t: Float,                          // elapsed seconds
    val frame: AnalysisFrame,              // live audio analysis
    val beatGrid: BeatGrid?,               // from TempoTracker
    val structure: StructureState?,        // from StructureTracker
    val scan: TrackScan?,                   // pre-scanned track data
    val lampPositions: Map<Int, Vec3>,      // normalised 3D positions
    val roomTopology: RoomTopology,         // LINEAR / RING / FIELD / CLUSTER
    val palette: Palette,                  // current colour palette
    val deviceMotion: DeviceMotion?,        // accelerometer + gyroscope
    val brightness: Float,                  // user brightness setting
)
```

`DirectLightSync.kt` runs the chain after `SyncoEngine.render()` and before
`FieldSafety`. Layers are independent — any combination can be on or off.
The chain order matters only for `PhoneConductorLayer` (last, because it
modulates the combined output of everything else).

**Files (shared):**
- `hue/LightShowLayer.kt` — interface + `LayerContext`
- `hue/DirectLightSync.kt` — insert layer chain in render loop
- `hue/SyncoModes.kt` — add per-layer enable flags to settings
- `data/AppSettings.kt` — four new boolean settings
- `ui/screens/LightSyncScreen.kt` — four new toggles

---

## Layer 1 — Music DNA (visual fingerprint per track)

### What it does

Every track has a deterministic visual identity derived from its pre-scanned
data. When the track starts, the lights assume a **signature pattern** that is
unique to that song and consistent every time it plays. The pattern is
computed once from the scan and held as a slow-moving base underneath the
reactive beat flash.

### What makes it unique

No Hue app has pre-scanned track data. The fingerprint uses:
- **Tempo** → wave speed in the spatial field (120 BPM = one cycle per 2s)
- **Key** (from chroma) → base hue on the colour wheel (C = red, G = orange,
  D = yellow, A = green, E = blue, B = violet)
- **Intensity profile** (from `ScanResult.intensityProfile`) → brightness
  arc — a quiet verse dims the base, a loud chorus lifts it
- **Section structure** (from `ScanSection[]`) → colour shifts at section
  boundaries, each section getting a slightly rotated hue so the room
  changes colour as the song moves through its parts

### How it layers

The layer produces a **per-lamp base colour and brightness** that replaces
`SyncoEngine`'s `base` and `floor` parameters. The beat flash, spectral pop,
and spatial effects from the main engine still fire on top — DNA shapes the
*floor*, not the *peaks*.

```
DNA base colour  ←  key → hue, section → hue rotation
DNA base level   ←  intensity profile at current position
SyncoEngine      ←  beat flash + spectral pop + gestures (unchanged)
Output           ←  max(DNA base, engine reactive) per lamp
```

### Design

1. On track change (or scan adoption), compute the fingerprint:
   - Extract key from `scan.chroma` (domant pitch class → hue)
   - Extract tempo from `scan.beatGrid.bpm` → wave cycle period
   - Extract intensity profile from `scan.intensityProfile` → brightness curve
   - Extract sections from `scan.sections` → colour rotation points
2. Store as `MusicDnaFingerprint` (computed once, immutable for the track)
3. Each frame, sample the intensity profile at the current playback position
   to get a base brightness multiplier (0.5..1.0)
4. Each frame, compute the hue from the key + section index + slow drift
5. Apply as a per-lamp colour tint and brightness floor

### When the scan is missing

If the track hasn't been scanned yet (first play), the layer does nothing —
the base `SyncoEngine` output passes through unchanged. Once the scan
completes (within 6s of playback start per `TrackScanRepository`), the
fingerprint is computed and the layer activates. This means the first 6
seconds of a new track look normal, then the DNA "locks in".

### Files

- `hue/MusicDnaLayer.kt` — fingerprint computation + layer logic
- `hue/LightShowLayer.kt` — shared interface
- Uses: `TrackScanStore`, `TrackScan.kt` (scan data models),
  `SyncoPalette.kt` (hue mapping)

---

## Layer 2 — Emotional Arc (structure-driven colour temperature)

### What it does

The room's **colour temperature** follows the song's structural journey.
Instead of the colour drifting randomly through the palette, it is anchored
to the musical structure:

| Section | Colour character | Why |
|---|---|---|
| Intro | Cool, low saturation | Setting the scene, tentative |
| Verse | Cool blue/teal | Calm, narrative |
| Build | Warming toward orange | Tension rising |
| Drop | Hot, saturated, full palette | Peak energy |
| Breakdown | Cold blue, dim | Pull-back, contrast |
| Outro | Fading warm amber | Wind-down, resolution |

### What makes it unique

No Hue app has real-time structure detection. `StructureTracker` already
classifies `SongPhase` (VERSE, BUILD, DROP, BREAKDOWN, OUTRO, etc.) from the
live audio stream. This layer maps those phases to colour temperature shifts
that overlay the existing palette.

### How it layers

This layer **modulates the palette** rather than replacing it. The existing
colour scheme (album art, sunset, ocean, etc.) continues to drive hue
selection. The layer applies a **temperature shift** and **saturation
modulation** based on the current phase:

```
Palette colour    ←  from SyncoEngine (album art, sunset, etc.)
Temperature shift ←  EmotionalArc: verse = cool (+blue), drop = hot (+red)
Saturation        ←  EmotionalArc: verse = 0.6, drop = 1.0, breakdown = 0.4
Output colour     ←  palette hue shifted toward warm/cool by phase
```

### Design

1. Read `StructureState` from `StructureTracker` each frame
2. Map `SongPhase` to a temperature offset and saturation multiplier:
   - `VERSE` → temperature = -0.15 (cool), saturation × 0.7
   - `BUILD` → temperature ramps from -0.15 to +0.25 over the build duration
   - `DROP` → temperature = +0.30 (hot), saturation × 1.0
   - `BREAKDOWN` → temperature = -0.25 (coldest), saturation × 0.5
   - `OUTRO` → temperature = +0.10 (warm), saturation × 0.6
   - `UNKNOWN` → no shift (pass-through)
3. Apply temperature as a hue rotation in CIELAB space (warm = toward red
   axis, cool = toward blue axis), not a flat tint
4. Smooth transitions over 2–4 seconds (the structure tracker already has
   hysteresis, but the colour shift should lag slightly so it doesn't snap)
5. During a build, the temperature ramp should accelerate — the room
   *feels* the tension rising before the drop hits

### When structure is unknown

If `StructureTracker` reports `UNKNOWN` (no structure detected), the layer
passes through unchanged. This is common for ambient, classical, or
spoken-word content where there is no verse-drop structure.

### Files

- `hue/EmotionalArcLayer.kt` — phase → temperature/saturation mapping
- `hue/LightShowLayer.kt` — shared interface
- Uses: `StructureTracker.kt` (`StructureState`, `SongPhase`),
  `SyncoPalette.kt` (hue manipulation),
  `AlbumColours.kt` (CIELAB colour space for temperature shifts)

---

## Layer 3 — Phantom Stage (instruments at physical positions)

### What it does

The room becomes a stage where each **instrument group** has a fixed physical
position. Bass lives in the left corner, vocals centre, guitars right,
drums spread across the back (height axis). When a solo comes in, that part
of the room brightens. When the vocalist drops out, the centre dims. You can
see *who is playing* by which part of the room is lit.

### What makes it unique

`SpatialWaves` already maps melbank bins to physical lamp positions — a lamp
at position 0 rides the lowest frequencies, at position 1 the highest. This
layer formalises that mapping into **named stage positions** and makes it
visible as a persistent spatial layer, not just a reactive flash.

### How it layers

The existing `SyncoEngine` already uses melbank windows for per-lamp
brightness. This layer adds a **persistent positional identity** on top:

```
Stage positions   ←  computed once from room topology
  bass     → left-front (low bins, low height)
  vocals   → centre (mid bins, high chroma stability)
  guitar   → right (high-mid bins)
  drums    → spread (broadband transient bins)
  synths   → rear (high bins, high height)

Per-lamp stage role  ←  nearest stage position to each lamp
Stage activity       ←  from AnalysisFrame: which bins are active right now
Stage glow           ←  persistent low glow at each stage position (base)
Stage flash          ←  brightens when that instrument group hits (onset)
Output               ←  base stage glow + stage flash + SyncoEngine reactive
```

### Design

1. On session start, classify the room topology and assign stage positions:
   - Use the existing `SpatialWaves.positionNormalise()` to get normalised
     lamp positions
   - Assign instrument groups to positions based on frequency-mapping:
     - Sub-bass + bass bins (0–3) → leftmost/lowest position
     - Low-mid bins (4–7) → centre positions
     - Mid bins (8–11) → right positions (vocals sit here due to chroma)
     - High bins (12–15) → highest positions (cymbals, synths)
   - For a LINEAR room: left → right = low → high frequency
   - For a RING room: bass at the "front" (closest to listener), treble
     at the "back"
   - For a FIELD room: assign by both x (frequency) and height (band)
2. Each frame, read the `AnalysisFrame.melbank` to determine which bins are
   active (above a noise floor)
3. Compute a **stage glow** per lamp: a persistent low brightness (0.10–0.15)
   indicating "this is where the bassist lives"
4. Compute a **stage flash**: when the onset detector fires and the active
   bins map to a specific stage position, that position's lamps get a
   brightness boost
5. Blend with the main engine output: `output = max(base + stage_glow,
   engine_reactive + stage_flash)`
6. The stage positions are **stable for the session** — they don't shift
   between tracks. The bassist is always in the same corner.

### Visual result

During a verse with just vocals and drums: the centre of the room glows
softly (vocals) with occasional flashes at the drum positions. When the
guitar solo comes in: the right side of the room brightens dramatically.
When the bass drops: the left corner pulses. The room literally shows you
the band.

### Files

- `hue/PhantomStageLayer.kt` — stage position assignment + layer logic
- `hue/LightShowLayer.kt` — shared interface
- Uses: `SpatialWaves.kt` (position normalisation, melbank windows),
  `AudioAnalyzer.kt` (melbank bins), `SyncoEngine.kt` (onset data from
  `AnalysisFrame`)

---

## Layer 4 — Phone as Conductor (motion-controlled lighting)

### What it does

The phone's accelerometer and gyroscope become a conductor's baton. The user
holds or waves the phone, and the lights respond:

| Gesture | Effect |
|---|---|
| Tilt left/right | Colour shifts through the spatial field (left/right) |
| Tilt up/down | Brightness rises/falls across the whole room |
| Sharp flick (any axis) | All lamps "hit" — a flash in the current palette |
| Slow circular motion | Colour rotates around the room (ring topology) or along the line (linear) |
| Phone flat on table | Conductor mode auto-disables after 5 seconds (no motion) |

### What makes it unique

No Hue app uses phone motion as a lighting controller. The existing
`colourTilt` 3D axis projection (0.845x + 0.296y + 0.465z) already maps a
directional vector to room colour. This layer feeds the phone's tilt into
that axis — the phone becomes the colour controller, not a screen you tap.

### How it layers

This layer is a **modulator** — it doesn't produce its own base colour, it
shifts and augments what the other layers produced:

```
Combined output   ←  from layers 1-3 + SyncoEngine
Tilt modulation   ←  phone tilt → colour axis rotation
Flick detection   ←  accelerometer spike → whole-room flash
Circular motion   ←  gyroscope rotation → colour rotation
Output            ←  combined + motion modulation
```

### Design

1. Use `SensorManager.registerListener` for `TYPE_ACCELEROMETER` and
   `TYPE_GYROSCOPE` (both are present on every Android 12+ device)
2. Only register sensors when this layer is enabled AND light sync is
   active (battery consciousness)
3. **Tilt → colour shift**: map the phone's tilt vector to the existing
   `colourTilt` 3D axis. Tilt left = colour moves left through the spatial
   field. Tilt right = colour moves right. The existing Gaussian kernel
   diffusion makes the shift smooth across lamps.
4. **Tilt up/down → brightness**: phone face-up = normal brightness, phone
   tilted up = brightness rises up to 1.5×, phone tilted down = dims to
   0.5×. Smooth ramping over 500ms.
5. **Flick → flash**: detect a sharp acceleration spike (magnitude > 15 m/s²
   over < 100ms) that isn't part of normal holding. On detection: all lamps
   flash at full brightness in the current palette colour for 200ms, then
   decay over 1s. Rate-limited to 1 flick per 2 seconds (anti-strobe,
   `FieldSafety` also caps this).
6. **Circular motion → colour rotation**: detect sustained rotation from
   the gyroscope (angular velocity > 0.5 rad/s for > 500ms). Map the
   rotation angle to a continuous hue rotation around the room. For a RING
   topology, the colour physically travels around the lamps. For LINEAR,
   it travels left-to-right repeatedly.
7. **Auto-disable**: if no significant motion (accelerometer delta < 1 m/s²)
   for 5 seconds, the layer stops modulating and passes through. This
   prevents the phone's resting position from permanently shifting the
   colour when the user puts it down.

### Safety

- `FieldSafety` caps any flick-induced flash at 3 Hz (the existing limiter)
- The flick threshold (15 m/s²) is high enough that normal walking or
  holding won't trigger it — it requires a deliberate wrist flick
- The brightness modulation is capped at 1.5× to avoid sudden full-bright
  from a small tilt

### Files

- `hue/PhoneConductorLayer.kt` — sensor listeners + motion → lighting mapping
- `hue/LightShowLayer.kt` — shared interface
- Uses: `SpatialWaves.kt` (colour axis, room topology),
  `SyncoPalette.kt` (hue rotation), `FieldSafety.kt` (flash limiter)
- `data/AppSettings.kt` — `phoneConductorEnabled` setting
- `ui/screens/LightSyncScreen.kt` — toggle + calibration hint

### Permissions

No new manifest permissions needed. `SENSOR` access is implicit on
Android 12+ — `SensorManager.getDefaultSensor()` requires no runtime
permission. The sensors are only registered when the layer is enabled and
light sync is active.

---

## Implementation order

1. `LightShowLayer.kt` — interface + `LayerContext` (the contract)
2. `MusicDnaLayer.kt` — simplest (read scan, compute fingerprint, apply base)
3. `EmotionalArcLayer.kt` — second simplest (read structure, shift colour)
4. `PhantomStageLayer.kt` — medium (assign positions, track activity)
5. `PhoneConductorLayer.kt` — last (sensors, needs the most testing)
6. `DirectLightSync.kt` — wire the layer chain
7. `LightSyncScreen.kt` — four toggles in the UI
8. `AppSettings.kt` — four boolean settings

Each layer is independently testable — the `apply()` function is pure given
a `LayerContext`. Unit tests can feed a synthetic `AnalysisFrame`, a
`TrackScan`, and a `StructureState` and assert the output RGB.

---

## What is deliberately not in this plan

- No new DTLS transport (reuses existing `HueDtlsClient` + `HueStreamEncoder`)
- No new colour palettes (reuses existing `SyncoPalette` with hue rotation)
- No new manifest permissions (sensors are implicit on API 31+)
- No changes to `SyncoEngine.render()` — layers apply *after* the base render
- No changes to `FieldSafety` or `EffectRateLimiter` — they run after the
  layer chain, so all layers get safety limiting for free
- No changes to the HA light sync path — these layers are direct-to-bridge
  only (the HA path has no access to the analysis tap)