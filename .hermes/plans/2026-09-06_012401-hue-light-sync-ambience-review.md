# CAMusic Hue Light Sync & Ambience — Critical Review + Enhancement Plan

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** Close the remaining reactivity and scene-believability gaps in CAMusic's
direct-bridge Hue implementation, as judged against Signify's official developer
documents (Entertainment API, Light Effects Guide Book, Color Conversion, System
Performance, EDK Effect Creation).

**Architecture:** This is a review-then-enhance plan over an already excellent
pipeline. Part A hardens two protocol/physics gaps in the *music* path (gamut
hardcoding, steady-state sensor lag) with three small, testable tasks. Part B
upgrades the *ambience* path's scene depth with two perception-grounded scripts
(Fireplace multi-zone, Coastal rain) plus a lightning realism fix. Every task
follows TDD with the existing JUnit conventions (`kotlin.random.Random` with
seeds, no Robolectric, no android.util).

**Tech stack:** Kotlin, JUnit4 + Truth, Gradle 9.7.1, JDK 17
(`JAVA_HOME=C:/Users/Abdullah/AppData/Local/Temp/jdk17/jdk-17.0.20.1+1`).
Test task: `:app:testMobileDebugUnitTest`.

---

## Current context / assumptions

### What exists (verified by reading the code)

**Pipeline (music sync):** `AudioAnalysisTap` (~50 Hz) → `AnalysisFrame` →
`SyncoEngine.render()` (modes, palette, beat flash, melbank, predrop) →
`FrameDelayQueue` (lead − 100 ms pipeline) → `LayerChain` (4 optional layers) →
`FieldSafety` (WCAG 3 flashes/s + red guard) → `EffectRateLimiter`
(12.5 Hz physical ceiling) → `HueStreamEncoder` (xy+brightness, per-channel
gamuts, xy slew 0.08/frame) → pure-Kotlin `DtlsPskClient` → UDP :2100.
Remote-MA playback is covered by `ScanFrameSource` (precomputed `TrackScan`
+ playhead + `PositionSlew`). 60 Hz render loop on its own coroutine.

**Transport & connection (already conforms to the Hue docs):**

| Hue doc requirement | CAMusic status |
|---|---|
| DTLS 1.2 PSK, `TLS_PSK_WITH_AES_128_GCM_SHA256` | ✅ implemented from scratch, `HueDtlsClient.kt` |
| PSK = hex-decoded clientkey, identity = hue-application-id via `GET /auth/v1` | ✅ `HueBridgeClient.kt:304-345` |
| mDNS `_hue._tcp` primary, cloud endpoint cached for the 15-min limit, manual IP fallback, no SSDP | ✅ `HueBridgeClient.kt:128-280` |
| Signify root CA bundle + CN==bridgeid validation, no TOFU, no HTTP fallback | ✅ `createHttpClient`, `bridgeIdVerifier` |
| xy+brightness color space, 16-bit, big-endian, ≤20 ch/msg, config UUID 36 ASCII | ✅ `HueStreamEncoder.kt` |
| HueStream v2.0 header byte-exact | ✅ verified against spec example |
| Keepalive < 10 s idle timeout | ✅ 9 s |
| Continuous stream (repeat last frame, bounded skip) | ✅ 250 ms floor |
| Bridge-initiated teardown never retried; network faults retried with backoff | ✅ `reconnect()` |
| effect rate < 12.5 Hz (25 Hz Zigbee ÷ 2) | ✅ `EffectRateLimiter` |
| Strobe safety 3 Hz WCAG, red guard, brightness slower than color | ✅ `FieldSafety` |
| Match effect to sound source (bed analyser → events) | ✅ `AmbienceBedAnalyser` |

The two paths already integrate cleanly: `AmbienceSession` renders inside
`DirectLightSync.renderLoop` and brings its **own** `FieldSafety` (so music's
relaxed club-mode limiter can't leak into a thunderstorm), ownership of the
master sync switch is a tri-state (`AmbienceSyncOwnership`), and playback state
falls to `renderIdleShow` after 5 s.

**Existing test conventions** (read from `FieldSafetyTest.kt`, `AmbienceScriptTest.kt`):
plain JUnit4 + `com.google.common.truth.Truth.assertThat`, `kotlin.random.Random(seed)`
for determinism, no Robolectric, no android.* in test scope. Pure classes are
directly constructible (`SyncoEngine(channels, configType)`).

### Assumptions

1. All enhancements stay **on the existing Entertainment DTLS path**. No REST
   scene recall, no V1, no new transport. (The docs are blunt that REST is wrong
   for sustained fast updates.)
2. "Reactivity" (user's word) = perceptual sync between audio events and light
   changes, not raw frame rate — already capped at 60 fps / 12.5 Hz effect rate,
   which is correct per spec.
3. Ambience scripts keep their architecture: `schedule()` → `AmbienceEvent`
   (immutable cause) → `renderLights()` + `renderAudio()` projecting the same
   event. No second architecture.
4. The implementer has read `docs/plan/rhythm-lights-and-ambience-v2.md` only if
   told to; this plan is self-contained.

### How the codebase was judged (the Hue-doc lens)

Signify's docs that matter here, and what each one demands:

- **Entertainment API**: 50–60 Hz continuous stream; ≤20 channels; xy 12-bit /
  brightness 11-bit resolution → any per-frame change below 1/4096 is invisible
  (CAMusic already knows this: `COLOUR_QUANT`).
- **System Performance**: ~25 Zigbee msg/s budget; latency 55–125 ms by parameter
  count; clogging is silent. (Entertainment path bypasses this — MAC broadcast,
  no retries — which is why the whole design is correct.)
- **Light Effects Experience Guide Book** (the design bible):
  - §perception: brightness transitions *slower* than color; peripheral lamps
    tolerate motion, not rapid color.
  - §ambience: "saturated colors near the screen, whites near the user";
    "recreate light sources not visible on screen" (the fire should light the
    *wall opposite it*, not just itself).
  - §caution: "slow color transitions (>1 s) reveal intermediate colors — route
    through white (low saturation) or black (low brightness) instead".
  - §recipes: fireplace = flicker + occasional pop, NOT metronome; lightning =
    double-strike, position-matched.
  - §strobe: never 5–70 Hz; keep rapid brightness changes < 5 Hz.
- **Color Conversion**: out-of-gamut xy "snaps to the closest producible color";
  per-light gamut available on the light resource — "never hardcode".
- **EDK Effect Creation**: `LightSourceEffect` (virtual source, distance falloff,
  animated radius/position) is the primitive for fire/explosion; `AreaEffect`
  for hit-direction; layer+opacity mixing.

---

## Part A — Music sync: findings against the Hue docs

### Finding A1 (correctness, HIGH): hardcoded Gamut C ignores per-light gamuts

**Evidence:** `HueStreamEncoder.kt:42-46` defines only `GAMUT_C`. The doc says:
"The Hue API V2 exposes these gamuts on the API for each light resource, so you
don't need to hardcode them" and the code's own comment admits the failure mode
(`HueStreamEncoder.kt:147-155`): *clamping a Gamut A strip to C "pushes
saturated greens and blues to points it cannot actually produce, and the bridge
then snaps them somewhere unpredictable."*

`HueBridgeClient` already fetches `/resource/light` (line ~390) for the gamut
map — but the map only covers **entertainment services**, and the code builds
it from hardcoded model-id heuristics rather than the light's own `color` gamut
object. Any older bulb or colorstrip in the area renders wrong saturated colors.

**Fix:** parse `color.gamut` from the V2 light resource (fields
`red/green/blue` each `{x, y}`) and pass a per-channel triangle into
`HueStreamEncoder.gamuts`. Fall back to Gamut C only when the field is absent
(dimmable/white lights have no `color` object).

### Finding A2 (reactivity, MEDIUM-HIGH): encoder is stateless across reconnects

**Evidence:** `DirectLightSync.reconnect()` keeps the engine "so the room picks
up where it left off" but rebuilds the encoder "since its sequence numbers
belong to the old session" — and with them, `prevXy` and the xy slew state.
After a Wi-Fi blip every channel's chromaticity jumps to the current engine
state in one frame, violating the doc's smooth-dimming intent that the slew
limiter exists to provide (`XY_SLEW_MAX` comment: "the bridge does not
interpolate between frames… a big palette jump would 'pop'").

**Fix:** carry the encoder's `prevXy` map across the reconnect (same lifetime as
the engine). One-line-ish; needs a small seam because the map is private.

### Finding A3 (reactivity, MEDIUM): brightness slew floor hides sustained changes

**Evidence:** `EffectRateLimiter` blocks a channel's *reversal* within
`1/12.5 Hz = 80 ms` — correct. But combined with per-mode `briRiseRate` of 16–26
full-scale/s and the melbank floor at 0.02–0.06, a sustained level *rise* that
arrives over >80 ms is unaffected (good), while the *floor* means quiet
passages never go below 2–6% — intentional. The real gap is **xy slew interacts
with safety compression**: `FieldSafety.process` can lift the whole field
(`floor` lift) while `HueStreamEncoder.slewXy` simultaneously blocks the
chromaticity change, producing a bright-but-wrong-colored frame during
limiting. Minor, cosmetic, but it shows up on Extreme where safety is bypassed
and only the rate limiter binds — the exact rung where users push hardest.

**Fix:** none needed in the limiter. Instead, document it in
`FieldSafety.kt` header (one sentence) and skip the white-floor lift when the
red-guard is active (the two together are what produce the odd frame). Small,
testable.

### Finding A4 (reactivity, LOW, done well already — do NOT change)

The `FrameDelayQueue` (lead − 100 ms) matches the doc's measured 55 ms
(1-param) to 95 ms (2-param) latency plus ramp. `AmbienceMediaClock`'s
`LIGHT_PIPELINE_S = 0.10` uses the same constant with the same justification.
Do not "improve" these numbers.

### Part A verdict

Transport, safety, and timing are **conformant and in places more careful than
the docs require** (own limiter per ambience session, bridge-initiated teardown
distinguished from network faults, keepalive 9 s vs 10 s timeout). A1 is the
only *spec* violation; A2/A3 are quality gaps.

---

## Part B — Ambience: findings against the Light Effects Guide Book

### Finding B1 (scene depth, HIGH): Fireplace is a single virtual source

**Evidence:** the Guide Book's ambience section: "recreate the color of light
sources that illuminate the scene but are not necessarily visible on the
screen" — a fire's *visible* flames are one thing; the light they *cast on the
opposite wall and floor* is what makes a room read as "fire in the corner".
The EDK doc's `LightSourceEffect` primitive exists precisely for this (virtual
source + radius + distance falloff). A single-source script lights the fire's
own corner and leaves the rest of the room flat, which reads as "a lamp
flickering", not "a fire in the room".

**Fix:** multi-zone fireplace — hearth zone (flicker, saturated), reflection
zone (opposite wall/floor lamps, same color × lower saturation, delayed by
light-travel — a perceptual nicety, not physics), ambient zone (very low warm
wash everywhere else). Use the existing `RoomModel.heightOf` +
`axisPos` to pick zones, and `AmbienceEvent` with a long-decay envelope for
the pop that casts into the reflection zone.

### Finding B2 (scene depth, HIGH): missing "Coastal rain" — the strongest ambience archetype

**Evidence:** the Guide Book's DO list: "For atmospheric lighting use scene
colors… for lamps near to the screen, and scene lighting for lamps further
away" and the existing effect set (fireworks, thunderstorm, underwater,
fireplace, light train, aurora) has no *low, steady, melancholy* effect — the
one people leave on longest. Rain-on-a-window is the canonical example: cool
blue-grey base, slow headlight sweeps (the one perceptual "event"), occasional
distant lightning borrowed from `ThunderstormScript`. This is also the cheapest
new effect to build because it composes existing machinery.

**Fix:** new `CoastalRainScript` reusing `ThunderstormScript`'s far-strike
plumbing (via composition, not copy) at very low probability, plus a slow
`headlightSweep` event (moving warm wash across the wall zone, ~3–6 s crossing).

### Finding B3 (realism, MEDIUM): ThunderstormScript bolts are single-strike

**Evidence:** the Guide Book's lightning guidance (and every storm recording):
real lightning is overwhelmingly a **double-strike** — main flash, 60–120 ms
dark gap, restrike brighter or equal. The script's `STRIKE` envelope is
attack-hold-decay, one lobe. Single-lobe lightning reads as "camera flash",
double-lobe reads as "lightning". This is a one-event → two-event change in
`ThunderstormScript.react` (bed-reactive path) and `schedule` (synthetic path).

**Fix:** on each strike decision, with p≈0.6 schedule a second STRIKE event
`startS + 0.06 + rand*0.06`, gain ×(0.7–1.1), same origin. Both paths need it
(react for recordings, schedule for the silent/synthetic fallback).

### Finding B4 (design rule, already correct — do NOT change)

`AuroraScript` is eventless and pins "field brightness barely moves over a
minute" — exactly the Guide Book's "nothing sudden" and the strobe rule. Do not
add events to it.

---

## Step-by-step tasks

Each task is TDD: failing test → verify fail → minimal impl → verify pass →
commit. Test command everywhere:

```bash
cd "C:/Users/Abdullah/test 2/CAMusic" && JAVA_HOME="C:/Users/Abdullah/AppData/Local/Temp/jdk17/jdk-17.0.20.1+1" ./gradlew :app:testMobileDebugUnitTest --tests "<TestClass>" 
```

Expected pass output ends with `BUILD SUCCESSFUL`; expected fail output contains
`FAILED` and the assertion message.

---

### Task 1: Parse per-light gamuts from the bridge (Fix A1)

**Objective:** `HueBridgeClient` returns each entertainment channel's own gamut
triangle from the light resource's `color.gamut` object.

**Files:**
- Modify: `app/src/main/java/com/engabd/sendpin/hue/HueBridgeClient.kt` (~line 380-420, `fetchGamuts` or its equivalent)
- Test: `app/src/test/java/com/engabd/sendpin/hue/BridgeGamutParseTest.kt` (new)

**Step 1: Write failing test** — pure JSON parsing, no network:

```kotlin
package com.engabd.sendpin.hue

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BridgeGamutParseTest {
    @Test
    fun `parses color gamut from a v2 light resource`() {
        val light = """
            {"id":"abc","type":"light",
             "color":{"gamut":{"red":{"x":0.6915,"y":0.3038},
                               "green":{"x":0.17,"y":0.7},
                               "blue":{"x":0.1532,"y":0.0475}},
                      "gamut_type":"C"}}
        """.trimIndent()
        val gamut = parseLightGamut(light)
        assertThat(gamut).isNotNull()
        assertThat(gamut!!.size).isEqualTo(3)
        assertThat(gamut[0].first).isWithin(1e-4f).of(0.6915f)
        assertThat(gamut[2].second).isWithin(1e-4f).of(0.0475f)
    }

    @Test
    fun `returns null when light has no color object (white lights)`() {
        val light = """{"id":"abc","type":"light","on":{"on":false}}"""
        assertThat(parseLightGamut(light)).isNull()
    }

    @Test
    fun `returns null when gamut object is missing`() {
        val light = """{"id":"abc","type":"light","color":{}}"""
        assertThat(parseLightGamut(light)).isNull()
    }
}
```

**Step 2: Run to verify failure**

```bash
cd "C:/Users/Abdullah/test 2/CAMusic" && JAVA_HOME="C:/Users/Abdullah/AppData/Local/Temp/jdk17/jdk-17.0.20.1+1" ./gradlew :app:testMobileDebugUnitTest --tests "com.engabd.sendpin.hue.BridgeGamutParseTest"
```
Expected: `FAILED` — `unresolved reference: parseLightGamut`.

**Step 3: Implement** — top-level function in `HueBridgeClient.kt` (file already
imports `kotlinx.serialization.json.*` — add `jsonObject`, `jsonPrimitive`,
`contentOrNull`, `floatOrNull` if missing):

```kotlin
/**
 * The light's own colour gamut triangle from its V2 resource, or null when the
 * light does not publish one (white/dimmable lights have no `color` object).
 *
 * Philips: "The Hue API V2 exposes these gamuts on the API for each light
 * resource, so you don't need to hardcode them." Clamping a Gamut A strip to
 * the Gamut C triangle pushes saturated greens and blues to points the bulb
 * cannot produce, and the bridge then snaps them somewhere unpredictable —
 * which is why the per-light triangle wins over any fallback model guess.
 */
internal fun parseLightGamut(lightJson: String): List<Pair<Float, Float>>? {
    val json = Json { ignoreUnknownKeys = true }
    val obj = runCatching { json.parseToJsonElement(lightJson).jsonObject }.getOrNull()
        ?: return null
    val gamut = obj["color"]?.jsonObject?.get("gamut")?.jsonObject ?: return null
    fun corner(name: String): Pair<Float, Float>? {
        val c = gamut[name]?.jsonObject ?: return null
        val x = c["x"]?.jsonPrimitive?.floatOrNull ?: return null
        val y = c["y"]?.jsonPrimitive?.floatOrNull ?: return null
        return x to y
    }
    val red = corner("red") ?: return null
    val green = corner("green") ?: return null
    val blue = corner("blue") ?: return null
    return listOf(red, green, blue)
}
```

Then in the gamut-map builder (~line 380-420): where a channel's gamut is
currently chosen by model-id heuristic, first try `parseLightGamut` on that
light's resource body and fall back to the heuristic only when it returns null.

**Step 4: Run to verify pass** — same command. Expected: `BUILD SUCCESSFUL`, 3 tests passed.

**Step 5: Commit**

```bash
cd "C:/Users/Abdullah/test 2/CAMusic" && git add app/src/main/java/com/engabd/sendpin/hue/HueBridgeClient.kt app/src/test/java/com/engabd/sendpin/hue/BridgeGamutParseTest.kt && git commit -m "hue: parse per-light gamut from the v2 light resource"
```

---

### Task 2: Wire parsed gamuts into the encoder (completes A1)

**Objective:** `HueStreamEncoder.gamuts` receives per-channel triangles parsed
from the bridge instead of model-id guesses.

**Files:**
- Modify: `app/src/main/java/com/engabd/sendpin/hue/DirectLightSync.kt` (session start, where `HueStreamEncoder` is constructed — search `HueStreamEncoder(`)
- Test: `app/src/test/java/com/engabd/sendpin/hue/GamutWiringTest.kt` (new)

**Step 1: Write failing test** — the seam is the mapping function from
`(channels, parsed-light-gamuts)` to the encoder's `Map<Int, List<Pair<Float,Float>>>`:

```kotlin
package com.engabd.sendpin.hue

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GamutWiringTest {
    @Test
    fun `channel gamuts prefer the light's own gamut and fall back to C`() {
        val channels = listOf(
            EntertainmentChannel(channelId = 0, position = Vec3(0f, 0f, 0f)),
            EntertainmentChannel(channelId = 1, position = Vec3(1f, 0f, 0f)),
        )
        val parsed = mapOf("light-a" to listOf(0.7f to 0.3f, 0.2f to 0.7f, 0.1f to 0.05f))
        val serviceToLight = mapOf("ent-0" to "light-a")   // ch0's entertainment service -> light-a
        val serviceByChannel = mapOf(0 to "ent-0")         // ch1 has none
        val gamuts = channelGamuts(channels, serviceByChannel, serviceToLight, parsed)
        assertThat(gamuts[0]!![0].first).isWithin(1e-4f).of(0.7f)   // own gamut
        assertThat(gamuts[1]).isEqualTo(GAMUT_C)                    // fallback
    }
}
```

(If `EntertainmentChannel` has required fields beyond these, fill them with
`0`/`""` defaults as the existing tests do — see `SyncoEngineTest.kt` for the
exact constructor shape.)

**Step 2: Run to verify failure** — same command pattern, class
`com.engabd.sendpin.hue.GamutWiringTest`.
Expected: `FAILED` — `unresolved reference: channelGamuts`.

**Step 3: Implement** — top-level function in `HueStreamEncoder.kt`:

```kotlin
/**
 * Per-channel gamut triangles for the encoder: the light's own parsed gamut
 * where the bridge publishes one, Gamut C otherwise (current bulbs; only
 * approximate for older hardware — but the parsed map above is now the norm,
 * not the fallback).
 */
internal fun channelGamuts(
    channels: List<EntertainmentChannel>,
    serviceByChannel: Map<Int, String>,
    serviceToLight: Map<String, String>,
    parsedGamuts: Map<String, List<Pair<Float, Float>>>,
): Map<Int, List<Pair<Float, Float>>> = channels.associate { ch ->
    val light = serviceByChannel[ch.channelId]?.let { serviceToLight[it] }
    val own = light?.let { parsedGamuts[it] }
    ch.channelId to (own ?: GAMUT_C)
}
```

At the `HueStreamEncoder(` construction site in `DirectLightSync.kt`, replace
the heuristic-derived map with `channelGamuts(...)`, using the service ids the
session start already resolves when it builds `channels`.

**Step 4: Run to verify pass.** Expected: `BUILD SUCCESSFUL`.

**Step 5: Run the full hue suite** (regression — this touches the render path):

```bash
cd "C:/Users/Abdullah/test 2/CAMusic" && JAVA_HOME="C:/Users/Abdullah/AppData/Local/Temp/jdk17/jdk-17.0.20.1+1" ./gradlew :app:testMobileDebugUnitTest --tests "com.engabd.sendpin.hue.*"
```
Expected: `BUILD SUCCESSFUL`, zero failures across the 31 existing test classes.

**Step 6: Commit**

```bash
cd "C:/Users/Abdullah/test 2/CAMusic" && git add -A && git commit -m "hue: feed per-light gamuts into the stream encoder"
```

---

### Task 3: Preserve encoder xy state across reconnects (Fix A2)

**Objective:** a DTLS reconnect no longer lets chromaticity jump.

**Files:**
- Modify: `app/src/main/java/com/engabd/sendpin/hue/HueStreamEncoder.kt` (add `snapshotXy()` / `restoreXy()`)
- Modify: `app/src/main/java/com/engabd/sendpin/hue/DirectLightSync.kt` (`reconnect()` — keep old encoder's snapshot, restore into new)
- Test: `app/src/test/java/com/engabd/sendpin/hue/HueStreamEncoderSlewTest.kt` (extend existing file if present; otherwise new)

**Step 1: Write failing test:**

```kotlin
package com.engabd.sendpin.hue

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HueStreamEncoderSlewTest {
    private val configId = "1a8d99cc-967b-44f2-9202-43f976c0fa6b"

    @Test
    fun `slew state survives a snapshot-restore round trip`() {
        val a = HueStreamEncoder(configId)
        // Walk far from the start so slew is engaged and state is non-trivial.
        var xy = 0.1f to 0.7f
        repeat(20) {
            val packets = a.buildPackets(mapOf(0 to Triple(0.9f, 0.9f, 0.9f)))
            assertThat(packets).hasSize(1)
            xy = xy // encoder holds its own prevXy internally
        }
        val snapshot = a.snapshotXy()
        assertThat(snapshot).isNotEmpty()

        val b = HueStreamEncoder(configId)
        b.restoreXy(snapshot)
        // A fresh encoder without restore would slew from zero distance;
        // with restore it must continue from where a left off, so the very
        // next frame's movement is bounded identically.
        val restored = b.buildPackets(mapOf(0 to Triple(0.9f, 0.9f, 0.9f)))
        val fresh = HueStreamEncoder(configId).buildPackets(mapOf(0 to Triple(0.9f, 0.9f, 0.9f)))
        assertThat(restored).isEqualTo(fresh) // same input -> same bytes is NOT the assertion
        // The real assertion: the restored encoder's stored state equals a's.
        assertThat(b.snapshotXy()).isEqualTo(a.snapshotXy())
    }
}
```

**Step 2: Run to verify failure** — `unresolved reference: snapshotXy`.

**Step 3: Implement** in `HueStreamEncoder`:

```kotlin
/** Copy of the per-channel slew state, for surviving a reconnect. */
fun snapshotXy(): Map<Int, Pair<Float, Float>> = prevXy.toMap()

/** Restore slew state from a previous encoder's snapshot. */
fun restoreXy(state: Map<Int, Pair<Float, Float>>) {
    prevXy.clear()
    prevXy.putAll(state)
}
```

In `DirectLightSync.reconnect()`, where the encoder is rebuilt, keep the old
instance's snapshot first:

```kotlin
val carryXy = encoder?.snapshotXy()
// ... rebuild encoder ...
carryXy?.let { encoder?.restoreXy(it) }
```

**Step 4: Run to verify pass**, then full hue suite (regression).
**Step 5: Commit**

```bash
cd "C:/Users/Abdullah/test 2/CAMusic" && git add -A && git commit -m "hue: carry encoder xy slew state across reconnects"
```

---

### Task 4: FieldSafety red-guard + white-floor interaction (Fix A3)

**Objective:** when the red guard is actively desaturating, skip the white
floor lift (they compose into a bright-but-wrong-chromaticity frame).

**Files:**
- Modify: `app/src/main/java/com/engabd/sendpin/hue/FieldSafety.kt` (`process`, the `floor` computation ~line 198, and `applyRedGuard` ordering)
- Test: `app/src/test/java/com/engabd/sendpin/hue/FieldSafetyTest.kt` (extend)

**Step 1: Write failing test** (append to existing class, following its conventions):

```kotlin
@Test
fun `white floor lift is suppressed while the red guard is desaturating`() {
    val safety = FieldSafety()
    // Drive a strongly red, hard-swinging field to engage both mechanisms.
    var up = true
    var last: Map<Int, Rgb> = emptyMap()
    repeat(40) {
        val v = if (up) 1.0f else 0.15f
        last = safety.process(mapOf(0 to Rgb(v, 0.05f, 0.05f)), 1f / 60f)
        up = !up
    }
    // Guard active: redness high, so output must not be a neutral lift
    // (equal channels) — it must keep the red dominance the guard preserves.
    val (r, g, b) = last.getValue(0)
    assertThat(r).isGreaterThan(g)
    assertThat(r).isGreaterThan(b)
}
```

**Step 2: Run to verify failure.** Expected: `FAILED` — r is not greater (the
lift added white, equalizing channels).

**Step 3: Implement** — in `process`, compute the guard *before* the floor and
suppress the lift when the guard will engage:

```kotlin
val out = HashMap<Int, Rgb>(colors.size)
val rednessNow = fieldRedness(colors)          // moved up from applyRedGuard
val guardActive = rednessNow >= RED_SATURATION &&
    max(0, flashes.size - MAX_RED_FLASHES_PER_S) > 0
for ((cid, c) in colors) {
    var r = c.first * gain
    var g = c.second * gain
    var b = c.third * gain
    if (!guardActive) {                        // lift only when the guard is quiet
        val lift = floor - max(r, max(g, b))
        if (lift > 0f) { r += lift; g += lift; b += lift }
    }
    out[cid] = Rgb(min(1f, r), min(1f, g), min(1f, b))
}
trackFlash(fieldBrightness(out))
return applyRedGuardDrive(out, rednessNow)     // existing guard logic, passed the value
```

(Refactor `applyRedGuard` to accept the precomputed redness as
`applyRedGuardDrive(colors, redness)` — behaviour unchanged, DRY.)

Add one sentence to the class KDoc: *"When the red guard is desaturating a
swinging red field, the dark-floor lift is suppressed — lifting toward neutral
white while desaturating toward white produced a frame that was both brighter
and chromatically wrong."*

**Step 4: Run to verify pass**, then full `com.engabd.sendpin.hue.*` suite.
**Step 5: Commit**

```bash
cd "C:/Users/Abdullah/test 2/CAMusic" && git add -A && git commit -m "hue: suppress white-floor lift while the red guard is active"
```

---

### Task 5: Fireplace multi-zone rendering (Fix B1)

**Objective:** the fire casts light into a reflection zone and an ambient wash,
using the EDK `LightSourceEffect` model (virtual source + distance falloff).

**Files:**
- Modify: `app/src/main/java/com/engabd/sendpin/hue/ambience/scripts/FireplaceScript.kt`
- Test: `app/src/test/java/com/engabd/sendpin/hue/ambience/AmbienceScriptTest.kt` (extend; it already constructs `RoomModel`s — copy its helper)

**Step 1: Write failing tests** (append to `AmbienceScriptTest.kt`, using its
existing room-builder):

```kotlin
@Test
fun `fireplace lights the reflection zone behind the hearth`() {
    val script = FireplaceScript()
    val room = roomOf( // helper already in this test class; hearth at +x
        ids = listOf(1, 2),
        positions = mapOf(1 to Vec3(0.9f, 0.5f, 0.1f), 2 to Vec3(0.1f, 0.5f, 0.1f)),
    )
    script.bind(room, AmbienceParams())
    val out = HashMap<Int, Rgb>()
    script.renderLights(10.0, arrayOfNulls(0), 0, out)
    // Lamp 2 is across the room from the hearth: it must receive cast light
    // (non-zero, warm-dominant), not stay dark.
    val cast = out.getValue(2)
    assertThat(maxOf(cast.first, cast.second, cast.third)).isGreaterThan(0.02f)
    assertThat(cast.first).isGreaterThan(cast.second)
}

@Test
fun `fireplace pop casts into the reflection zone with a slow tail`() {
    val script = FireplaceScript()
    val room = roomOf(ids = listOf(1, 2), positions = mapOf(1 to Vec3(0.9f, 0.5f, 0.1f), 2 to Vec3(0.1f, 0.5f, 0.1f)))
    script.bind(room, AmbienceParams())
    val events = mutableListOf<AmbienceEvent>()
    script.schedule(0.0, 30.0) { events.add(it) }
    val pops = events.filter { it.kind == AmbienceEvent.POP }
    assertThat(pops).isNotEmpty()
    val pop = pops.first()
    // The pop's envelope must outlive the hearth flash: a slow decay is what
    // makes the *reflection* read (light has to arrive and linger).
    assertThat(pop.env.decayTauS).isAtLeast(0.25f)
}
```

**Step 2: Run to verify failure** — cast light assertion fails (lamp 2 ≈ 0) and/or
`decayTauS` below bound.

**Step 3: Implement** — restructure `renderLights` into three zones (keep the
existing flicker + pop logic for the hearth lamp unchanged):

```kotlin
// Zone pick, computed in bind():
//   hearth   = lamp(s) nearest the chosen corner (max along dominant axis)
//   reflect  = lamps far from the hearth on the same axis (the wall opposite)
//   ambient  = everything else
// Cast light = hearth flicker level * falloff(gap), falloff = 1/(1+gap*k),
// warm-tinted, arriving through a slow one-pole (~120 ms) so the reflection
// lags the flame slightly — how real bounced light behaves.
```

Concrete additions:

```kotlin
private var hearthId: Int = -1
private val reflectIds = mutableListOf<Int>()
private val reflectFilter = OnePole()   // reuse ambience/Dsp.kt's OnePole

// in bind(): hearthId = argmax(axisPos); reflectIds = ids sorted by gap-from-hearth
//   take those in the far half; reflectFilter.setCutoff(1.3f) (≈120 ms at 60 Hz)
```

In `renderLights`, after computing the hearth lamp's flicker level `fl`:

```kotlin
val gap = room.gap(hearthId, id).coerceAtLeast(0.05f)
val falloff = 1f / (1f + gap * 2.2f)
val cast = reflectFilter.lp(fl) * falloff * 0.55f
// warm cast light, blended under whatever ambient level the lamp already gets
out[id] = Rgb(base.first * level + cast * 1.00f,
              base.second * level + cast * 0.55f,
              base.third  * level + cast * 0.22f)
```

For the pop envelope: give `AmbienceEvent.POP` a `decayTauS` of 0.35 (was
~0.12) **for the light envelope only** — audio keeps its own shape (the event's
audio derives independently; see the `AmbienceEvent` KDoc contract).

**Step 4: Run to verify pass**, then the whole ambience package:

```bash
cd "C:/Users/Abdullah/test 2/CAMusic" && JAVA_HOME="C:/Users/Abdullah/AppData/Local/Temp/jdk17/jdk-17.0.20.1+1" ./gradlew :app:testMobileDebugUnitTest --tests "com.engabd.sendpin.hue.ambience.*"
```
Expected: `BUILD SUCCESSFUL`.

**Step 5: Commit**

```bash
cd "C:/Users/Abdullah/test 2/CAMusic" && git add -A && git commit -m "ambience: fireplace casts light into a reflection zone (LightSourceEffect model)"
```

---

### Task 6: Thunderstorm double-strike (Fix B3)

**Objective:** most strikes are followed by a restrike 60–120 ms later.

**Files:**
- Modify: `app/src/main/java/com/engabd/sendpin/hue/ambience/scripts/ThunderstormScript.kt` (both `schedule` and `react` paths)
- Test: `app/src/test/java/com/engabd/sendpin/hue/ambience/AmbienceReactionTest.kt` (extend)

**Step 1: Write failing tests:**

```kotlin
@Test
fun `synthetic storms restrike most strikes`() {
    val script = ThunderstormScript()
    val room = roomOf(ids = listOf(1, 2, 3), positions = defaultThreeLamps())
    script.bind(room, AmbienceParams())
    val events = mutableListOf<AmbienceEvent>()
    script.schedule(0.0, 600.0) { events.add(it) }   // long window: many strikes
    val strikes = events.filter { it.kind == AmbienceEvent.STRIKE }
        .sortedBy { it.startS }
    assertThat(strikes.size).isAtLeast(4)
    // Count strikes with a partner within 200 ms: must be the majority.
    val withRestrike = strikes.count { s ->
        strikes.any { o -> o !== s && abs(o.startS - s.startS) < 0.20 &&
            (o.gain - s.gain) < s.gain * 0.5f }   // restrike is same-family gain
    }
    assertThat(withRestrike).isGreaterThan(strikes.size / 2)
}

@Test
fun `bed-reactive storms restrike too`() {
    // Use the same fake-bed harness AmbienceReactionTest already provides;
    // drive one onset, collect events, assert a second STRIKE within 200 ms
    // for a majority of repeated onsets.
}
```

**Step 2: Run to verify failure** — restrike counts ≈ 0.

**Step 3: Implement** — one helper inside `ThunderstormScript`, called from
both paths wherever a strike is currently emitted:

```kotlin
private fun emitStrikeWithRestrike(
    base: AmbienceEvent,
    rand: Random,
    emit: (AmbienceEvent) -> Unit,
) {
    emit(base)
    // Real lightning is a double-strike (Light Effects Guide Book): main
    // flash, 60-120 ms gap, restrike at similar or higher level. p = 0.6.
    if (rand.nextFloat() < 0.6f) {
        emit(base.copy(
            startS = base.startS + 0.06 + rand.nextDouble() * 0.06,
            gain = base.gain * (0.7f + rand.nextFloat() * 0.4f),
        ))
    }
}
```

(`AmbienceEvent` is a class, not data class — if `copy` is unavailable, construct
a new `AmbienceEvent` with the same fields and the two changed ones; that is the
only difference.)

Use the script's existing seeded RNG — do **not** create `Random(System.nanoTime())`,
which would break determinism that `AmbienceScriptTest` relies on.

**Step 4: Run to verify pass**, then ambience package + `FieldSafetyTest`
(the restrike doubles flash density — confirm the limiter still bounds it; if a
test now trips the 3 Hz budget that is the safety working, and the assertion
tolerances in `AmbienceReactionTest` may need the documented adjustment).

**Step 5: Commit**

```bash
cd "C:/Users/Abdullah/test 2/CAMusic" && git add -A && git commit -m "ambience: thunderstorm restrikes (double-flash lightning)"
```

---

### Task 7: Coastal rain ambience effect (Fix B2)

**Objective:** new `AmbienceEffect.COASTAL_RAIN` — cool blue-grey base, slow
headlight sweeps, rare distant strikes.

**Files:**
- Modify: `app/src/main/java/com/engabd/sendpin/hue/ambience/Ambience.kt` (enum + `scriptFor` in `AmbienceScripts.kt`)
- Create: `app/src/main/java/com/engabd/sendpin/hue/ambience/scripts/CoastalRainScript.kt`
- Test: `app/src/test/java/com/engabd/sendpin/hue/ambience/CoastalRainTest.kt` (new)

**Step 1: Write failing test:**

```kotlin
package com.engabd.sendpin.hue.ambience

import com.engabd.sendpin.hue.Rgb
import com.engabd.sendpin.hue.Vec3
import com.engabd.sendpin.hue.ambience.scripts.CoastalRainScript
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.max

class CoastalRainTest {
    private fun room() = RoomModel(listOf(
        // three lamps across the room; constructor shape per AmbienceScriptTest
    ))

    @Test
    fun `base is cool, dim and steady`() {
        val script = CoastalRainScript()
        script.bind(room(), AmbienceParams())
        val out = HashMap<Int, Rgb>()
        script.renderLights(5.0, arrayOfNulls(0), 0, out)
        for ((_, c) in out) {
            val level = max(c.first, max(c.second, c.third))
            assertThat(level).isAtMost(0.35f)          // dim room
            assertThat(c.second).isAtLeast(c.first)     // no warm cast
        }
    }

    @Test
    fun `headlight sweeps travel the room slowly`() {
        val script = CoastalRainScript()
        script.bind(room(), AmbienceParams())
        val events = mutableListOf<AmbienceEvent>()
        script.schedule(0.0, 120.0) { events.add(it) }
        val sweeps = events.filter { it.kind == AmbienceEvent.SWEEP }
        assertThat(sweeps).isAtLeast(2)                 // roughly one per 20-60 s
        for (s in sweeps) assertThat(s.env.attackS).isAtLeast(1.0f)  // slow onset
    }

    @Test
    fun `distant lightning is rare and never bright`() {
        val script = CoastalRainScript()
        script.bind(room(), AmbienceParams())
        val events = mutableListOf<AmbienceEvent>()
        script.schedule(0.0, 600.0) { events.add(it) }
        val strikes = events.filter { it.kind == AmbienceEvent.STRIKE }
        assertThat(strikes.size).isAtMost(3)            // rare
        for (s in strikes) assertThat(s.gain).isAtMost(0.35f)  // and dim
    }
}
```

If `AmbienceEvent.SWEEP` does not exist yet, add it beside `STRIKE`/`POP` in
`Ambience.kt`'s companion (Task 7 step 3).

**Step 2: Run to verify failure** — `unresolved reference: CoastalRainScript`.

**Step 3: Implement.** Enum entry:

```kotlin
COASTAL_RAIN(
    "coastal_rain", "Coastal rain",
    "Cool grey rain on the glass, headlights sweeping the wall now and then.",
),
```

`scriptFor` branch: `AmbienceEffect.COASTAL_RAIN -> CoastalRainScript()`.

Script skeleton (full file; the audio path is deliberately minimal — rain hiss
band-passed, matching the light's calm):

```kotlin
package com.engabd.sendpin.hue.ambience.scripts

import com.engabd.sendpin.hue.Rgb
import com.engabd.sendpin.hue.ambience.AmbienceEffect
import com.engabd.sendpin.hue.ambience.AmbienceEvent
import com.engabd.sendpin.hue.ambience.AmbienceParams
import com.engabd.sendpin.hue.ambience.AmbienceScript
import com.engabd.sendpin.hue.ambience.OnePole
import com.engabd.sendpin.hue.ambience.PinkNoise
import com.engabd.sendpin.hue.ambience.RoomModel
import com.engabd.sendpin.hue.ambience.Svf
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Cool grey rain on the glass, headlights sweeping the wall now and then.
 *
 * The low steady one — the Guide Book's "scene lighting for lamps further away"
 * pushed to its conclusion: the base field is a cool near-neutral wash with a
 * slight blue lean, brightness keyed to each lamp's synthetic height (higher
 * lamps slightly brighter, like sky through a tall window). The only *events*
 * are slow warm headlight sweeps crossing the wall every 20-60 s (attack >= 1 s
 * so nothing sudden ever happens), and rare distant strikes borrowed from the
 * storm's own vocabulary but capped at a third of full brightness.
 */
class CoastalRainScript : AmbienceScript {

    override val effect = AmbienceEffect.COASTAL_RAIN
    private lateinit var room: RoomModel
    @Volatile private var params = AmbienceParams()
    private var rand = Random(0x5EED_1101L)

    // Rain hiss for the audio path.
    private val hiss = PinkNoise(0x11C0)
    private val hissHp = Svf()
    private var voiced = false

    override fun bind(room: RoomModel, params: AmbienceParams) {
        this.room = room
        this.params = params
        this.rand = Random(0x5EED_1101L)
    }

    override fun retune(params: AmbienceParams) { this.params = params }

    override fun schedule(fromS: Double, toS: Double, emit: (AmbienceEvent) -> Unit) {
        var t = maxOf(fromS, nextSweepS)
        while (t < toS) {
            emitSweep(t)
            t += SWEEP_MIN_S + rand.nextDouble() * (SWEEP_MAX_S - SWEEP_MIN_S)
        }
        nextSweepS = t
        // Rare distant strike: keep a cursor the same way.
        var s = maxOf(fromS, nextStrikeS)
        while (s < toS) {
            emitStrike(s)
            s += STRIKE_MIN_GAP_S + rand.nextDouble() * 240.0
        }
        nextStrikeS = s
    }

    private var nextSweepS = 0.0
    private var nextStrikeS = 0.0

    private fun emitSweep(t: Double) {
        val from = rand.nextFloat()
        emit(AmbienceEvent(
            kind = AmbienceEvent.SWEEP,
            startS = t,
            env = Envelope(attackS = 1.2f, holdS = (2f + rand.nextFloat() * 2f), decayTauS = 1.5f),
            gain = 0.10f + rand.nextFloat() * 0.05f,
            origin = room.centre(),
            azimuth = from,
            colour = Rgb(1.00f, 0.72f, 0.45f),  // headlights: warm against the cool base
            seed = rand.nextLong(),
            soundS = 0f,                        // headlights are silent
        ))
    }

    private fun emitStrike(t: Double) {
        val az = rand.nextFloat()
        emit(AmbienceEvent(
            kind = AmbienceEvent.STRIKE,
            startS = t,
            env = Envelope(attackS = 0.03f, holdS = 0.08f, decayTauS = 0.35f),
            gain = 0.18f + rand.nextFloat() * 0.15f,
            origin = room.centre(),
            azimuth = az,
            colour = Rgb(0.82f, 0.86f, 1.00f),  // cold, far
            timbre = 2.5f + rand.nextFloat() * 2f,  // 2.5-4.5 km: distant
            seed = rand.nextLong(),
            soundS = 4f,                        // delayed rumble arrives with the distance
        ))
    }

    override fun renderLights(
        tS: Double, live: Array<AmbienceEvent?>, n: Int, out: MutableMap<Int, Rgb>,
    ) {
        val intensity = params.intensity.coerceIn(0f, 1f)
        val t = tS.toFloat()
        for (id in room.ids) {
            val p = room.positions[id] ?: room.centre()
            val h = room.heightOf(id)
            // Slow breathing base: cool, blue-leaning, slightly brighter high.
            val base = 0.10f + 0.06f * intensity + 0.05f * h
                + 0.015f * sin(2f * PI.toFloat() * (t * 0.05f + p.x))
            out[id] = Rgb(base * 0.82f, base * 0.92f, base * 1.00f)
        }
        renderEventsLights(tS, live, n, out)
    }

    /** Shared event projection: sweeps as travelling warm wash, strikes as cold flash. */
    private fun renderEventsLights(
        tS: Double, live: Array<AmbienceEvent?>, n: Int, out: MutableMap<Int, Rgb>,
    ) {
        for (i in 0 until n) {
            val e = live[i] ?: continue
            val age = e.ageAt(tS)
            if (age < 0f) continue
            val lvl = e.levelAt(tS)
            if (lvl <= 0f) continue
            for (id in room.ids) {
                val p = room.positions[id] ?: room.centre()
                // Sweep: warm wash whose centre walks across the room over the
                // event's life; falloff on distance from the moving centre.
                val centre = e.azimuth + (p.x * 0f)  // azimuth is the start; movement below
                val travel = (age / (e.env.attackS + e.env.holdS)).coerceIn(0f, 1f)
                val headX = (e.azimuth + travel * 0.9f) % 1f
                val d = kotlin.math.abs(p.x - headX)
                val influence = (1f - (d / 0.45f).coerceIn(0f, 1f)) * lvl
                val (r, g, b) = out.getValue(id)
                out[id] = Rgb(
                    r + e.colour.first * influence,
                    g + e.colour.second * influence * 0.8f,
                    b + e.colour.third * influence * 0.6f,
                )
            }
        }
    }

    override fun renderAudio(
        out: FloatArray, frames: Int, startS: Double, sampleRate: Int,
        live: Array<AmbienceEvent?>, n: Int,
    ) {
        if (!voiced) { hissHp.set(900f, 0.9f, sampleRate); voiced = true }
        val level = 0.04f + 0.03f * params.intensity.coerceIn(0f, 1f)
        var i = 0
        while (i < frames) {
            val src = hissHp.hp(hiss.next())
            out[i * 2] += src * level
            out[i * 2 + 1] += src * level * 0.92f
            i++
        }
    }

    private companion object {
        const val SWEEP_MIN_S = 20.0
        const val SWEEP_MAX_S = 60.0
        const val STRIKE_MIN_GAP_S = 240.0
    }
}
```

**Note to implementer:** this skeleton is the shape, not gospel — the room
event-projection helper may already exist in the shared `AmbienceScript`
plumbing (check `AmbienceSession.renderLights` and the other scripts for a
`renderEventsLights`-style helper; DRY — use the shared one if present). The
tests, not the skeleton, are the contract.

**Step 4: Run to verify pass**, then full ambience + hue suites.
**Step 5: Commit**

```bash
cd "C:/Users/Abdullah/test 2/CAMusic" && git add -A && git commit -m "ambience: coastal rain effect (cool base, headlight sweeps, rare distant strikes)"
```

---

### Task 8: Docs + verification sweep

**Objective:** record the review verdict and the doc-grounding so the next
change doesn't re-litigate it.

**Files:**
- Modify: `docs/plan/rhythm-lights-and-ambience-v2.md` (append a "Hue-spec audit" section)

**Step 1:** Append:

```markdown
## Hue-spec audit (2026-09, against the official developer-program docs)

Transport/timing/safety: conformant. Verified against Entertainment API (DTLS
1.2 PSK, 50-60 Hz, <=20 ch, 10 s idle), System Performance (effect rate
< 12.5 Hz enforced by EffectRateLimiter; keepalive 9 s vs 10 s timeout), Using
HTTPS (Signify CA bundle + CN==bridgeid, no TOFU), Color Conversion (per-light
gamuts now parsed from /resource/light, Task 1-2; xy+brightness colorspace).

Perception rules from the Light Effects Guide Book now applied:
- brightness transitions slower than colour (FieldSafety + EffectRateLimiter — pre-existing);
- fireplace casts into a reflection zone (LightSourceEffect model, Task 5);
- lightning double-strikes (Task 6);
- coastal rain added as the low steady ambience archetype (Task 7);
- red-guard + white-floor interaction fixed (Task 4).

Explicitly NOT changed, on purpose:
- FrameDelayQueue's 100 ms LIGHT_PIPELINE_MS (matches the doc's 55-95 ms measurements);
- AuroraScript's eventless design (the Guide Book's "nothing sudden");
- the 60 fps render / 12.5 Hz effect-rate ceiling (hardware fact).
```

**Step 2:** Run the **entire** hue test tree one last time:

```bash
cd "C:/Users/Abdullah/test 2/CAMusic" && JAVA_HOME="C:/Users/Abdullah/AppData/Local/Temp/jdk17/jdk-17.0.20.1+1" ./gradlew :app:testMobileDebugUnitTest --tests "com.engabd.sendpin.hue.*"
```
Expected: `BUILD SUCCESSFUL`, all 31+ classes green.

**Step 3:** Commit

```bash
cd "C:/Users/Abdullah/test 2/CAMusic" && git add docs/plan/rhythm-lights-and-ambience-v2.md && git commit -m "docs: hue-spec audit for light sync and ambience"
```

---

## Tests / validation summary

- Per-task TDD: failing test → run (expect `FAILED` + reason) → minimal impl →
  run (expect `BUILD SUCCESSFUL`) → commit. Commands and expected outputs are
  inline in every task.
- Regression gates: after Tasks 2, 3, 4 run the full
  `com.engabd.sendpin.hue.*` suite (31 existing classes must stay green);
  after Tasks 5-7 run `com.engabd.sendpin.hue.ambience.*`.
- Physical-device smoke (manual, once at the end, not per task): start music
  sync on a saturated-color track → colors should be noticeably more saturated
  on any pre-Gamut-C bulbs; force a Wi-Fi drop mid-song (airplane toggle on the
  router) → no color pop on recovery; Fireplace → opposite wall visibly lit;
  Thunderstorm → most flashes double; Coastal rain → calm, occasional sweep.

## Risks, tradeoffs, open questions

**Risks**

1. *Task 5-7 change perceived feel, not just correctness.* The Guide Book
   recipes are directional. Mitigation: each script keeps its existing seed so
   A/B comparison is possible; every change is behind tests that pin the
   *structure* (zones exist, restrikes exist) not exact brightness values.
2. *Task 6 doubles flash density in storms.* `FieldSafety` bounds it (3 Hz),
   but `AmbienceReactionTest` tolerances may need adjustment — expected, and
   the test edit must be justified in the commit message, not silent.
3. *Task 1-2 touch session startup.* If the light resource fetch happens on a
   different code path than assumed (search `HueStreamEncoder(` and
   `/resource/light` in `HueBridgeClient`), the mapping function stays pure and
   only the wiring line moves — the tests still hold.
4. *Restrike determinism:* the restrike RNG must come from the script's own
   seeded stream or `AmbienceScriptTest`'s pinned-seed assumptions break.

**Tradeoffs**

- Per-light gamut parsing adds one JSON parse per light at session start
  (microseconds, once) vs permanently wrong saturated colors on old hardware.
- Carrying xy slew state across reconnects means the first frame after a
  reconnect is *slow to move* if the room state diverged during the outage —
  accepted; a one-frame pop is worse.
- Coastal rain adds one more enum entry and script; audio is deliberately
  minimal (hiss only). Richer rain audio is YAGNI until someone asks.

**Open questions (ask Abdullah before Tasks 5-7, not 1-4)**

1. Fireplace: is there a preferred corner, or should the hearth pick the corner
   nearest the strongest lamp? (Plan assumes: pick by `axisPos` argmax, stable
   per session.)
2. Coastal rain: should the headlight sweeps also *sound* (doppler-ish pass)?
   Plan says no (silence is the point) but it's a taste call.
3. Should Thunderstorm II (`THUNDERSTORM_2`, the far-off storm) restrike at a
   *lower* rate than the near storm (far lightning often single)? Plan: yes,
   p=0.3 there — one-line change, but it's taste.
