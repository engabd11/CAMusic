# CAMusic — Rhythm Lights Overhaul + Ambience Polish Implementation Plan

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.
> Run `./gradlew :app:testMobileDebugUnitTest` after each commit unless a different test
> command is given. `./gradlew :app:compileDebugKotlin` is the fast gate while iterating.

**Goal:** Take Rhythm Lights from "good" to flagship — beat-accurate charts written from the
app's own offline scan, hits that light the *right* lamps, timing calibration, and game feel
that rivals arcade rhythm games — then polish the eight ambience effects into showpieces.

**Architecture:** Every change reuses infrastructure the app already owns. The game stops
maintaining a second, weaker rhythm model and consumes the engine's own `BeatGrid` (scan-
adopted when available). Light hits become band-targeted by reusing `HEIGHT_BANDS` /
melbank-window geometry the engine already computes. Ambience fixes are surgical edits to
existing classes; no new engine.

**Tech stack:** unchanged — Kotlin 2.2.21, Compose BOM 2026.06.01, Media3 1.10.1, DataStore,
JUnit4 via kotlin-test. No new permissions. No new dependencies.

**Hard constraints from the Hue docs** (verified in `Downloads/HUE API`, 2026-07 printouts):
- Bridge relays entertainment frames at max 25 Hz over Zigbee; keep deliberate effect rates
  < 12.5 Hz (`EffectRateLimiter` already enforces this — never route around it).
- Keep rapid brightness changes < 5 Hz (photosensitive-epilepsy guidance; `FieldSafety`
  enforces 3/s whole-field — the game's per-hit gates must stay inside this budget).
- Brightness transitions slower than colour transitions; peripheral lamps get soft changes.
- 50–60 Hz stream cadence from the app side (encoder already does this).

---

## How to use this plan

- Phase A = Rhythm Lights (the priority). Phase B = Ambience polish. Phase C = wow features.
  Phase D = engine refactors that are real but belong in their own PRs.
- Tasks within a phase are ordered by dependency. Each names its files and its test.
- File paths are relative to `app/src/main/java/com/engabd/sendpin/` unless noted.
- Device-verification steps are explicit because this app's own docs say CI is unit tests,
  lint, and a debug build only — light behaviour is only truly judged in a real room.

### What exists today (verified, do not break)

- `game/RhythmGame.kt` — `NoteGenerator`: own tempo EMA + PLL (60–200 BPM), predictive
  `chartAhead` onto `anchorMs + n*periodMs` in *audible* ms, deterministic `chance()`,
  `take()`-once semantics, `reap()`, revision counter. 1600 ms lookahead, MIN_GAP 110 ms.
- `ui/viewmodel/RhythmGameViewModel.kt` — sole light call `flashRoom()` →
  `DirectLightSync.receiveGameHit(strength, combo)`; gate = points/100 × (0.7+0.3·intensity).
- `hue/DirectLightSync.kt` — `gameMode` holds the finished frame at a dim cool floor
  (`GAME_FLOOR_LEVEL 0.08`), `applyGameGate` multiplies toward full show on hit;
  attack 25 ms, hold 90+10 ms/combo (cap 30), quadratic release 340 ms. Ambience nulled
  during game mode. `latestGrid` (scan-adopted BeatGrid), `latestStructure`, `latestGesture`
  and `activeScan: TrackScan?` live here as volatile snapshots.
- `audio/TrackScan.kt` — `beats: FloatArray` (track seconds), `accents` 0..1 parallel,
  `downbeat: Int`, `beatsPerBar: Int`, `sections` with labels + stems
  (`SectionStems(vocals, …)`), `gridAt(posS, prevPosS): BeatGrid?`, `gridUsable`.
- `audio/AudioAnalyzer.kt` `AnalysisFrame` — `tAudio` (s), `beat/bassBeat/midBeat` +
  strengths, `melbank: FloatArray`, `energy`, `flux`, `tempoBpm`.
- `audio/AudioLeadProbe.kt` — `lead.leadMs` (nullable; null on capture/cast paths).
- `hue/SpatialWaves.kt` — melbank→lamp `HEIGHT_BANDS` windows, positions 0..1, topology.
- Light engine already adopts a scan only inside `MAP_COMMIT_WINDOW_S = 6 s` of track
  start; the causal `TempoTracker` grid is the floor otherwise.

---

## Phase A — Rhythm Lights

### A1. One beat clock: the game consumes the engine's grid

**Problem:** the game runs its own tempo EMA + PLL (60–200 BPM, no octave guard) while the
engine runs `TempoTracker` + offline `TrackScan` (70–190 BPM, comb guard, DP beats). The
chart can disagree with the light show it is supposed to unlock, octave errors write
playable-but-wrong charts, and the first seconds of every track use a reactive fallback
that is late by construction — while an exact scan sits unused.

**Files:** `game/RhythmGame.kt`, `hue/DirectLightSync.kt`, `ui/viewmodel/RhythmGameViewModel.kt`.

**Design:**

1. `DirectLightSync` publishes a game-readable snapshot (it already computes everything):

```kotlin
/** Everything the rhythm game needs to chart against the engine's own clock. */
data class GameChartSource(
    val grid: BeatGrid?,        // scan-adopted when available, causal otherwise
    val scan: TrackScan?,       // for accents/downbeat/beatsPerBar/stems; null ok
    val frameTAudioS: Float,    // the frame the grid was computed from
)
@Volatile private var chartSource: GameChartSource? = null
fun gameChartSource(): GameChartSource?   // read by the ViewModel each frame
```

   Set it in `onAnalysisFrame` next to where `latestGrid` is written (one assignment, no
   locks — the render loop already proves this pattern).

2. `NoteGenerator.onFrame` gains an optional parameter:

```kotlin
fun onFrame(
    frame: AnalysisFrame, nowMs: Long, leadMs: Long = 0L,
    chart: GameChartSource? = null,   // null → existing PLL path, unchanged
)
```

   When `chart != null && chart.grid != null && chart.grid.scheduleStrength > 0f`:
   - `periodMs = 60_000f / grid.bpm` (already octave-guarded by the scan or the tracker —
     delete the game's local 60–200 clamp for this path).
   - Convert grid beat times to audible wall ms: `audible(beatT) = nowMs + leadMs +
     ((beatT - frameTAudioS) * 1000).roundToLong()`. Walk `scan.beats` (or
     `grid.timeToNextBeat` steps when no scan) from the first beat ≥ now, charting every
     beat inside `lookAheadMs` — the same `chartAhead` loop, different time source.
   - Bar position from `scan.downbeat`/`beatsPerBar` when a scan exists (3/4 works);
     otherwise `grid.beatsPerBar`.
   - `locked` is immediate on this path (`scheduleStrength` is the engine's own lock
     confidence), killing the "Finding the beat…" dead time whenever a grid exists.
   - Charting stays deterministic: keep `chance()` and `MIN_GAP_MS` exactly as they are.

3. The game's PLL remains as the fallback for capture/cast with no grid. Do not delete it.

**Why this shape:** relative-time conversion (`beatT − frameTAudioS`) needs no track-position
plumbing, survives seeks, and is exact to the hop (20 ms) — the same accuracy the engine's
own `mapCommitted` machinery achieves with far more state. The game inherits octave guards,
hysteresis, and scan adoption for free, and chart-vs-lights disagreement becomes impossible
by construction because both render from the same object.

**Tests** (`NoteGeneratorTest.kt` additions):
- a synthetic `GameChartSource` at 140 BPM with `downbeat = 2` charts its first note on the
  audible-converted beat, not on the PLL anchor;
- `scheduleStrength = 0` or null grid → byte-identical chart to today (revert test);
- a 3/4 scan (`beatsPerBar = 3`) puts downbeat notes on beats 0/3/6;
- a seek (frame tAudio jumps) re-charts forward without notes spawning in the past.

### A2. The chart transcribes the music, not a template

**Problem:** lanes are a fixed drum template (kick 1&3, snare 2&4) with density gates; notes
appear in quiet sections and vanish in busy ones; the melody lane is a hash coin-flip.

**Files:** `game/RhythmGame.kt` (charting half only).

**Design (scan path only; template stays for the fallback):**

1. **Accents drive intensity and lane weight.** `scan.accents[i]` (0..1) sets the note's
   `intensity` directly; the light-reward formula already consumes `note.intensity`, so a
   big hit now pays out more without touching the ViewModel.
2. **Section-aware density.** From `scan.sectionAt(posS)` (exists) get the section's
   `unitD` level and `SectionStems`:
   - quiet section (intensity < 0.3): hats and fills off; backbeat only;
   - busy section (≥ 0.6): hats gate on `stem.rumble`/drum energy instead of the global
     `hatLevel` EMA;
   - melody lane fires when `stems.vocals > 0.5` at a chorus label, replacing the
     `inBar == 2 && chance < 0.55f` rule.
3. **Fills belong to real boundaries:** the pre-downbeat fill fires only when the *next*
   section's `unitD` exceeds the current one by ≥ 0.2 (a build into a chorus), not on
   `energyLevel > 0.45f`.
4. **No notes in dead air:** skip charting beats whose `accent < 0.08` for two consecutive
   beats unless a hold note (A4) covers the span.

**Tests:** synthetic two-section scan (quiet verse, loud chorus) → verse charts ≤ 2 notes/bar,
chorus ≥ 3; fill only at the section boundary; `accents` of 0 never chart.

### A3. Band-targeted light hits ("match the hits to the music")

**Problem:** `receiveGameHit(strength, combo)` opens one global gate — a kick hit and a hat
hit light the whole room identically. The player's taps are clearly rhythmic but the room's
answer is uniform.

**Files:** `hue/DirectLightSync.kt`, `ui/viewmodel/RhythmGameViewModel.kt`, `game/RhythmGame.kt`.

**Design:**

1. `GameNote` already carries `kind`. Map lane → frequency band (the engine's own bands):
   `KICK→BASS, SNARE→FULL, HAT→TOP, MELODY→COLOR`.
2. `receiveGameHit(strength, combo, band)`; `applyGameGate` becomes band-weighted per lamp:

```kotlin
// weight: 1.0 for lamps inside the band's region, 0.35 sympathy elsewhere.
// BASS = lowest HEIGHT_BANDS window, TOP = highest, FULL = all lamps, COLOR = colour
// rotation (hue nudge via LayerColour.hsvShift) with a smaller brightness lift.
```

   Lamp membership uses the precomputed `HEIGHT_BANDS` melbank→lamp windows the engine
   already assigns per channel (`buildChannelMap`), so no new geometry.
3. **Colour hits obey the guidebook:** brightness transition slower than colour — the
   `MELODY` lane's gate opens to only 0.6 brightness but a full hue shift, which reads
   clearly without a flash-budget spike.
4. The gate envelope (attack 25 ms / hold / quadratic release) is unchanged; only the
   spatial map changes. `FieldSafety` still sees the composed field, so the 3/s budget
   holds even on charted 16th-note fills.
5. Mirror mode (Phase C1) reuses this same weighting with a lane→lamp-group map.

**Tests** (`DirectLightSyncGameGateTest`, JVM-safe parts):
- a BASS hit at full gate leaves top-band lamps at ≤ 0.35 × floor-lift while bass lamps open;
- componentwise `max` with the floor still holds (no lamp below floor, none above ceiling);
- four hits in one second keep `FieldSafety.flashCount` ≤ 3 (budget respected);
- `gate = 0` output is byte-identical to the current implementation (revert test).

### A4. Timing calibration + difficulty (the difference between "fun" and "unfair")

**Files:** `data/AppSettings.kt`, new `ui/screens/RhythmCalibrationScreen.kt`,
`ui/viewmodel/RhythmGameViewModel.kt`, `game/RhythmGame.kt` (window scaling).

**Design:**
- `gameTimingOffsetMs: Int` (default 0) and `gameDifficulty: String` (`easy|normal|expert`)
  in DataStore, following the `lightSyncPrescan` end-to-end pattern.
- Windows scale: Easy ×1.5 (90/172/262), Normal as shipped (60/115/175), Expert ×0.66
  (40/76/116). The offset shifts both the chart and the judgement by the same amount —
  implement as `nowEffective = now + offset` inside the ViewModel only.
- Calibration screen: the current track keeps playing, big pulsing circle on the beat
  (driven by the same `GameChartSource` grid), 12 taps, median delta saved, "Applied ±N ms"
  confirmation. Reachable from the game screen's overflow and from Light Sync settings.
- When `AudioLead.leadMs == null` (capture/cast), the game shows a one-line banner:
  "No output-latency reading — run timing calibration for this device" instead of silently
  assuming 0.

**Tests:** offset applies symmetrically to `take()` and `reap()`; median-of-12 helper is
unit-tested (odd/even counts, outliers don't move the median).

### A5. Game feel: holds, haptics, persistence, results

**Files:** `game/RhythmGame.kt`, `ui/viewmodel/RhythmGameViewModel.kt`, `ui/screens/RhythmGameScreen.kt`, `data/AppSettings.kt`.

**Design:**
1. **Hold notes:** a scan section with sustained `stems.vocals` (or `unitD` plateau) writes a
   `GameNote(holdMs = …)` rendered as a trailing ribbon in-lane; scoring = head judgement ×
   release bonus (0.5 if released inside `holdMs ± 150`). Release before 40 % breaks combo.
   Ribbon drawn in the existing Canvas (`drawNote` gains a tail path — perspective already
   provided by `yAt`/`perspAt`).
2. **Haptics:** `Vibrator` (API 31 `VibrationEffect.createPredefined`, no new permission) —
   sharp tick on Perfect, soft on Good, double-pulse on multiplier step-up, none on Miss.
   Settings toggle, default on.
3. **Persistence:** `gameRecords` in DataStore — per track id: best score, best combo, best
   accuracy, plays. Results sheet on exit (score, accuracy ring, counts, new-record badge,
   "play again"). Track id = the same `TrackScanRepository.keyFor` string the scan store uses,
   so records survive across backends.
4. **Miss attribution fix:** `emit(Judgement.MISS, missed.first().lane, …)` attributes the
   run to an arbitrary lane; use the lane of the *most recent* expired note, or `-1` with a
   lane-agnostic banner when the run spans lanes.
5. **Pause:** an on-screen pause chip (currently only back) — game keeps the room dimmed,
   `running=false` stops charting; resume re-anchors from the current grid (A1 makes this
   free).

**Tests:** hold head/release scoring; record persistence round-trip; multiplier-step haptic
selection logic (pure function); miss-attribution lane choice.

### A6. Rhythm Lights UI polish (M3 Expressive, house motion rules)

**Files:** `ui/screens/RhythmGameScreen.kt` only.

- Replace the two raw `tween()` calls (banner 620 ms) with `Motion.effects()` /
  `Motion.spatial()` from `ui/design/Motion.kt` (project rule: springs, never tweens;
  alpha/colour = effects, position = spatial).
- Combo milestones (10/20/30): a brief accent ripple across the hit line drawn in the canvas
  (no recomposition — canvas already redraws per frame), plus the haptic from A5.
- The "Finding the beat…" state becomes "Locked to the song · N BPM" the moment a
  `GameChartSource` grid exists (A1) — the dead first seconds were the #1 fun killer.
- Results sheet (A5) uses `MaterialTheme.shapes.extraLarge` bottom sheet, mono numerals
  (`labelMedium`), accent from `LocalAccent` — no hardcoded hex beyond the existing
  judgement colours.
- Add a "Light: Full / Band / Off" selector (Band = A3 default, Full = today's behaviour,
  Off = screen-only) so the game is playable with no bridge and by photosensitive users;
  persisted in DataStore.

**Verification:** `-PcomposeMetrics` build shows `HudRow`, `JudgementBanner` still
`restartable skippable`; canvas path unchanged (draw-phase reads).

### Phase A sequencing

1. A1 (clock) → 2. A3 (band hits) → 3. A2 (chart transcription) → 4. A4 (calibration) →
5. A5 (feel) → 6. A6 (UI). A1 unblocks everything; A3 is the visible wow; ship a debug APK
to the Hue room after A3 and again after A6.

---

## Phase B — Ambience polish

Fixes verified against `hue/ambience/` as built (all eight scripts, session, analyser, screen).

### B1. Duck-restore bug (P0)

`AmbienceAudio.kt` `onDuck` uses `vol` captured at start; if the user changes the level
slider while ducked, un-duck restores the stale value. Fix: read the current persisted level
at duck time (a `@Volatile var currentLevel` on the sink written by `setVolume`), re-apply on
restore. **Test:** duck → setVolume(0.3) → unduck restores 0.3, not the start value.

### B2. Voice-steal click (P1)

`Dsp.kt` `VoicePool`: when a voice is stolen mid-sound it clicks (documented). Apply a
5 ms equal-power crossfade on steal: render the old tail into the stolen voice's first
~240 frames at falling gain. Follows the guidebook rule (no clicks — clicks read as faults).

### B3. Reactive brightness for all eight effects (P1, the big polish)

Only Thunderstorm/Fireworks/Fireplace react to recordings. Underwater, LightTrain and
Aurora play their bed with purely scheduled lights. Add `bindBed`-driven level/rumble
modulation of their existing fields (they already receive `AmbienceBedTrack` via
`bindBed`; Thunderstorm's rain wash is the pattern). Screen copy updates to say which
effects fully react. **Tests** extend the reaction test file: a synthetic bed with rising
level brightens each script's field monotonically; silence leaves it at base.

### B4. Session refractory + analyser hardening (P1)

- Reset script refractory state on bed discontinuity: add `script.onBedReset()`, called from
  `AmbienceSession`'s bed-discontinuity branch; Thunderstorm/Fireworks/Fireplace clear
  `lastStrikeS/lastBurstS/lastPopS`. Fixes "stale refractory window after a seek".
- `AmbienceBedAnalyser.HOP_S` ← constructor parameter (actual frame period), not the
  hardcoded `441/22050`.

### B5. Clock fallback tests (P1)

`AmbienceSessionTest` additions: synth-stall guard, pause/resume of a sink-less show,
seek resync, `nowS` NaN-media branch. `AmbienceMediaClock` slew + 0.35 s resync jump tests.
These are the subtlest code in the package and currently untested.

### B6. Effects screen polish (P2)

- Brightness slider on the running show (routes to `DirectLightSync.setBrightness`, which
  already exists).
- Sleep countdown shows seconds below 10 min; fix `formatRemaining`'s m ≥ 1 branch.
- Decode + display the clip filename; running bar shows the active source (Bed / My clip /
  Silent).
- Toasts auto-dismiss after ~4 s; error colour reserved for failures.
- Per-effect sound-mode memory (DataStore key per effect) — switching one effect to
  "My clip" must not silently change all.
- LightTrain Doppler: replace the ±45 Hz step with a glide (lerp cutoff over ~80 ms).

### B7. Reactive-path diagnostics (P2)

Throttled debug logs behind `BuildConfig.DEBUG` from the reactive path (onset kind/strength,
clock resyncs, timeline overflow) — the tuning discipline the spatial-swell work established,
carried into the bed analyser so "the room didn't flash" is diagnosable.

---

## Phase C — Wow features (order by impact)

### C1. Lane→room mirror (the showpiece)

Four lanes map to four lamp groups when the area has ≥ 4 channels (nearest lamp to each
lane's `HEIGHT_BANDS` window; assignment once per session like Phantom Stage). On a Perfect,
the lane's lamps play the engine's frame at full gate while the other lamps sit at the
sympathy floor — the player's thumbs are literally conducting the room. Built entirely from
A3's weighting machinery + a persisted mapping; off by default behind "Mirror the lanes to
the lamps". Falls back to band mode with < 4 channels.

### C2. Results → Encore

From the results sheet: "Play it again" restarts the same track; "Replay my show" re-runs
the light show for the song's duration at the run's average gate — a passive light show of
the player's best run. Cheap (it is just game mode with auto-fire at charted beats ×
recorded accuracy), impressive after the fact, and it gives the game a shareable moment
alongside RecapPoster.

### C3. Diagnostics overlay (from the engine audit)

A debug-toggle overlay on Light Sync settings: send failures, reconnects, delayMs,
framesFresh age, flash-budget occupancy, limiter engagement, topology + ring residual,
scan adoption state. Nearly every historical bug in this codebase was a timing/visibility
problem; this is the single highest-leverage quality feature.

### C4. Per-area light-pipeline calibration

`LIGHT_PIPELINE_MS = 100` is a global constant; multi-hop Zigbee rooms sit systematically
early or late. Add an Advanced slider (0–250 ms) persisted per entertainment-area id, plus
the diagnostics overlay showing the current value. (syncoV2 removed its timing slider for
simplicity; per-area with a sane default is the better trade.)

---

## Phase D — Engine refactors (own PRs, do not mix into A/B)

1. **`HueStreamSession` extraction** from `DirectLightSync` (connect/start/DTLS/reconnect/
   keepalive/revocation, ~400 lines, injected clock) — makes reconnect logic unit-testable.
2. **Allocation-free render pipeline:** shared `FrameBuffer` (channel → FloatArray(3))
   through engine → layers → safety → limiter → encoder, replacing per-stage HashMaps and
   boxed `Rgb` Triple; `LayerChain` already proves the pattern. Also the `Band` enum +
   FloatArray replacing string-keyed band maps.
3. **Scan soft adoption:** allow mid-track scan adoption with a short crossfade between
   causal and scan grids (they agree on tempo, differ on phase) — unlocks first-play
   exactness for tracks whose scans finish just past the 6 s window.
4. **`startStream` TOCTOU:** PUT and inspect the response's `active_streamer` instead of
   GET-then-PUT.

---

## Device verification (per phase, the only real test)

1. `./gradlew :app:installDebug` on the phone with the Hue bridge on the same LAN.
2. Rhythm game: chart lands on the beat from bar one on a scanned track (A1) — the
   observable is that bar lines in the game coincide with the light engine's beat pulses.
3. Band hits (A3): kick hits light floor-level lamps, hats the high band; melody lane reads
   as colour, not brightness.
4. Calibration (A4): Bluetooth speaker + calibration → judgement windows feel centred.
5. Ambience: duck the media volume mid-show, restore; verify level returns to the slider
   value (B1). Thunderstorm with a seek: first strike after seek still fires (B4).
6. Photosensitivity check: charted 16th fills must not strobe — `FieldSafety` engagement
   logged, visually smooth (guidebook 5 Hz rule).
7. Full album with the debug overlay (C3) on; budget line and send-failure counters flat.

## Handover

If a phase cannot be completed in-session: the plan file is self-contained (every claim
verified against master@6834e52). Hand to a Claude Code or Codex session with:
`"Implement docs/plan/rhythm-lights-and-ambience-v2.md starting at Phase A1; follow
docs/creative-light-shows.md conventions and the test standards in spatial-swell-implementation.md §8."`
Phase D items are separable and safe to hand over independently.

## Hue-spec audit (2026-09, against the official developer-program docs)

A critical review of the direct-bridge light sync and ambience paths against Signify's
published documents (Entertainment API, Hue System Performance, Using HTTPS, Color
Conversion Formulas, Light Effects Experience Guide Book, EDK Effect Creation).

**Conformant, verified in code.** DTLS 1.2 PSK with the mandated cipher suite
(`HueDtlsClient`); `hue-application-id` via `/auth/v1` as the PSK identity
(`HueBridgeClient.startStream`); mDNS-primary discovery with the cloud endpoint cached
for its 15-minute limit, manual entry last, no SSDP; Signify root-CA pinning plus
CN==bridgeid validation with no trust-on-first-use and no HTTP fallback; xy+brightness
colour space, ≤20 channels per datagram, byte-exact HueStream v2.0 header; 9 s keepalive
against the 10 s idle close; 60 fps stream with the effect rate capped at the documented
12.5 Hz ceiling (`EffectRateLimiter`); WCAG flash budget plus red guard (`FieldSafety`);
`FrameDelayQueue`'s 100 ms light-pipeline compensation matches the System Performance
note's 55–95 ms measurements; per-show `FieldSafety` so the music rungs' relaxed limiter
never leaks into ambience; bridge-initiated teardown never retried while network faults
are, so the Hue app's stop button keeps working.

**Already correct in code, where the initial audit assumed otherwise.** Per-light gamuts
are parsed from `/clip/v2/resource/light` and fed per channel
(`resolveChannelGamuts`), so no model-id gamut guessing exists; near lightning already
restrikes (`ThunderstormScript`, 1–3 flickers at 30–90 ms for strikes under 1.5 km); the
fireplace already carries hearth falloff. These were documented rather than changed.

**Changed by this audit (PR feat/hue-sync-ambience-audit):**

1. Encoder chromaticity state now crosses reconnects (`snapshotXy`/`restoreXy`) — a
   rebuilt encoder used to let every channel's xy pop in one frame, the exact failure
   the slew limiter exists to prevent.
2. Fireplace cast lag: a pop's glint passes through a per-lamp one-pole whose cutoff
   falls with distance from the hearth, so bounced light arrives spread instead of in
   lockstep with the flame (Guide Book: brightness transitions slower than colour).
3. Coastal rain added (`AmbienceEffect.COASTAL_RAIN`): the low steady archetype the set
   lacked — cool breathing base an order of magnitude under the flash threshold, warm
   headlight sweeps every 20–60 s, distant strikes with the propagation-delay coherence
   intact at a third of the brightness.

**Deliberately not changed:** the aurora's eventless design (the Guide Book's "nothing
sudden", pinned by its own test); the 100 ms pipeline constant; the 60 fps / 12.5 Hz
ceilings (hardware facts, not comfort settings).
