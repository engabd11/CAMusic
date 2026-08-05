# Direct Light Sync Gap Analysis: syncoV2 vs CAMusic

## Executive Summary

syncoV2 (Home Assistant integration) is a **production-complete** music visualization system with ~7000 lines of Python code across analysis, effects, and coordination. CAMusic's direct mode is a **minimal viable implementation** (~2000 lines Kotlin) that proves the concept but lacks the sophisticated musical intelligence that makes syncoV2's output feel like a choreographed show rather than a reactive visualization.

**Key architectural difference**: syncoV2 uses **scheduled playback** — it pre-analyzes entire tracks, builds a beat grid with section markers (verse/chorus/drop), and schedules lighting events ahead of time. CAMusic's direct mode is purely **reactive** — it analyzes audio frames in real-time and responds to what just happened, not what's coming.

---

## What's Missing from CAMusic Direct Mode

### 1. **Tempo PLL & Beat Grid Locking** (HIGH PRIORITY)

**syncoV2**: Runs a phase-locked loop that locks onto the track's tempo grid. Once locked, it knows when the next beat/downbeat/bar is *before* it happens. This enables:
- Firing wavefronts that peak exactly on the beat (not reacting after)
- Scheduling color jumps to land on bar boundaries
- Anticipating drops and builds

**CAMusic**: No tempo detection, no beat grid. Every reaction is retrospective — the beat arrived, the analyzer detected it, the lights respond ~100-200ms later.

**Files to port**: 
- `custom_components/hue_music_sync/audio/tempo.py` (~600 lines)
- `custom_components/hue_music_sync/timing.py` (~200 lines)

---

### 2. **Song Structure Detection** (HIGH PRIORITY)

**syncoV2**: Identifies verse, chorus, build, drop, breakdown sections by analyzing energy contours, beat density, and spectral balance across the entire track. The engine uses this to:
- Boost brightness on drops
- Desaturate colors during builds (tension)
- Apply different intensity rungs per section (Auto mode)

**CAMusic**: No structure awareness. Every moment is treated identically — a drop and a verse get the same reaction if they have similar instantaneous loudness.

**Files to port**:
- `custom_components/hue_music_sync/audio/structure.py` (~400 lines)
- `custom_components/hue_music_sync/audio/phrase.py` (~200 lines)

---

### 3. **Auto Intensity Picker** (MEDIUM PRIORITY)

**syncoV2**: Analyzes the track's character (tempo, beat density, bass weight, dynamic range) and assigns it a "rung band" — which intensity levels it can reach. A lofi track tops out at Medium; a metal track can reach Extreme. Within that band, the song's moment-to-moment energy moves it up/down.

**CAMusic**: Manual intensity selection only. User picks Subtle/Medium/High/Intense/Extreme and it stays there.

**Why it matters**: Auto prevents a chill track from looking boring (stuck on Subtle) and a banger from being overwhelming (stuck on Extreme). It's the difference between "the lights match the song's vibe" and "the lights are just loud."

**Files to port**:
- `custom_components/hue_music_sync/audio/liveliness.py` (~300 lines)
- `custom_components/hue_music_sync/coordinator.py::AreaSettings::auto_intensity` logic

---

### 4. **Scheduled Beat Wavefronts** (HIGH PRIORITY)

**syncoV2**: When a beat is detected in analysis, it's scheduled to fire at the exact musical position. The wavefront renderer calculates when to launch the wave so it peaks across the room at the beat timestamp, accounting for:
- Bridge pipeline latency (~100ms)
- Room acoustic delay
- User timing offset

**CAMusic**: Instant reaction. Beat detected → render frame → send. The wave (if enabled) sweeps continuously, not locked to musical time.

**Files to port**:
- `custom_components/hue_music_sync/effects/spatial.py` (~400 lines) — wavefront math
- `custom_components/hue_music_sync/audio/playback_clock.py` (~250 lines) — scheduled event queue

---

### 5. **Pre-Analyzed Track Maps** (MEDIUM PRIORITY)

**syncoV2**: Can pre-analyze entire library tracks (via Navidrome/Music Assistant integration). Stores:
- Beat timestamps (every kick, snare, downbeat)
- Section boundaries (verse starts at 0:45, chorus at 1:15, drop at 2:30)
- Tempo curve (BPM changes)
- Recommended intensity rung per section

During playback, uses this map instead of real-time analysis. Benefits:
- Perfect accuracy from first beat (no 20-second "settling" period)
- Zero CPU during playback (just reading timestamps)
- Works with any player (even ones that can't provide live audio tap)

**CAMusic**: Real-time analysis only. First 20 seconds of every track are "warmup" while the analyzer calibrates.

**Files to port**:
- `custom_components/hue_music_sync/library/base.py` (~500 lines)
- `custom_components/hue_music_sync/library/ma_backend.py` (~300 lines)
- `custom_components/hue_music_sync/library/subsonic_backend.py` (~400 lines)

---

### 6. **Advanced Tunables UI** (LOW PRIORITY)

**syncoV2**: Card has "Advanced" toggle revealing live tunable knobs:
- Bass gain (how much kicks affect bass-role lights)
- Wave speed
- Color saturation
- Beat threshold
- etc. (12 total)

**CAMusic**: Fixed parameters per intensity mode. No live adjustment.

**Files to port**:
- `custom_components/hue_music_sync/const.py::TUNABLE_DEFS`
- UI: Add advanced section to `DirectLightSyncScreen` similar to HA screen

---

### 7. **Movies & Fireworks Effects** (LOW PRIORITY)

**syncoV2**: Three effects:
- **Music**: Full beat choreography (default)
- **Movies**: Calm, no flashing, brightness follows energy, warm color drift
- **Fireworks**: Bursts on big beats with rapid fade

**CAMusic**: Only Music effect implemented. `SyncEffect` enum has all three, but `SyncoEngine.render()` only has music path.

**Files to port**:
- `custom_components/hue_music_sync/effects/fireworks.py` (~150 lines)
- `custom_components/hue_music_sync/effects/modes.py::render_movies` (~100 lines)

---

### 8. **Album Art Color Extraction (V2 with population weights)** (MEDIUM PRIORITY)

**syncoV2**: Album art v2 extracts colors with population weights — a 90% green / 10% red cover spends 90% of time green, 10% red. CAMusic's `AlbumPalette.kt` does k-means extraction but treats all colors equally.

**Files to port**:
- `custom_components/hue_music_sync/color/palette_v2.py` (~200 lines)

---

### 9. **Event Salience Precision Gates** (ALREADY PORTED ✓)

**syncoV2**: Flash amplitude scales with absolute loudness (salience). Narrow onsets (vocals) are muted; broadband (drums) flash full. Width gate prevents sustained tones from triggering flashes.

**CAMusic**: Already implemented in `SyncoEngine.kt` — `eventGates()` function with `salience_gamma`, `width_min`, `width_soft`.

---

### 10. **Highlight Selection** (ALREADY PORTED ✓)

**syncoV2**: Beats are ranked against recent ~24 beats. Only top 30% (configurable by mode) trigger full brightness flash.

**CAMusic**: Already implemented — `beatHighlight()` in `SyncoEngine.kt`.

---

## Implementation Priority

### Phase 1: Core Musical Intelligence (2-3 days)
1. **Tempo PLL** — enables scheduled wavefronts
2. **Beat grid locking** — fire events on exact musical positions
3. **Scheduled wavefronts** — spatial choreography that peaks on the beat

These three are interdependent and transform the show from "reactive" to "choreographed."

### Phase 2: Song Awareness (1-2 days)
4. **Song structure detection** — verse/chorus/drop recognition
5. **Auto intensity picker** — song-character-based rung selection
6. **Pre-analyzed track maps** — library analysis + cached beat timestamps

### Phase 3: Polish & Features (1 day)
7. **Movies/Fireworks effects** — alternate effect modes
8. **Album art v2** — population-weighted color extraction
9. **Advanced tunables UI** — live parameter adjustment

---

## UI Bug Fix: Vertical Text on Connected Bridge

**Location**: `app/src/main/java/com/engabd/sendpin/ui/screens/LightSyncScreen.kt`, line 669

**Issue**: When bridge is connected and entertainment areas are loaded, area names render vertically when the chip width is constrained.

**Fix**: Add `overflow = TextOverflow.Ellipsis` to the `Text` in `AreaChip`:

```kotlin
Text(
    name,
    color = tint,
    style = MaterialTheme.typography.labelLarge,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,  // ADD THIS
)
```

---

## Code Quality Notes

### syncoV2 Strengths to Emulate
- **Extensive test coverage** — every module has unit tests
- **Typed constants** — all magic numbers are named constants in `const.py`
- **Docstrings** — every public function explains its purpose, inputs, outputs
- **Gradual enhancement** — fallback paths (metadata → track map → live audio)

### CAMusic Direct Mode Strengths
- **Pure Kotlin** — no external dependencies (DTLS, FFT, analysis all ported)
- **Real-time rendering** — 60 FPS render loop with measured `dt`
- **Keepalive handling** — bridge alert polling + automatic reconnection

### CAMusic Weaknesses to Address
- **No tests** — `AudioAnalyzer`, `SyncoEngine`, `DtlsPskClient` have zero unit tests
- **Magic numbers** — `FRAME_STALE_NANOS = 250_000_000L` should be a named constant with explanation
- **No fallback** — if DTLS handshake fails, no graceful degradation

---

## File-by-File Porting Guide

### Phase 1 Files
```
custom_components/hue_music_sync/audio/tempo.py          → app/src/main/java/com/engabd/sendpin/audio/TempoTracker.kt
custom_components/hue_music_sync/audio/beat_grid.py      → app/src/main/java/com/engabd/sendpin/audio/BeatGrid.kt
custom_components/hue_music_sync/timing.py               → app/src/main/java/com/engabd/sendpin/hue/TimingScheduler.kt
custom_components/hue_music_sync/effects/spatial.py      → app/src/main/java/com/engabd/sendpin/hue/SpatialWavefronts.kt (extend existing)
```

### Phase 2 Files
```
custom_components/hue_music_sync/audio/structure.py      → app/src/main/java/com/engabd/sendpin/audio/SongStructure.kt
custom_components/hue_music_sync/audio/liveliness.py     → app/src/main/java/com/engabd/sendpin/audio/IntensityPicker.kt
custom_components/hue_music_sync/library/base.py         → app/src/main/java/com/engabd/sendpin/library/TrackAnalyzer.kt
custom_components/hue_music_sync/library/subsonic_backend.py → app/src/main/java/com/engabd/sendpin/subsonic/SubsonicAnalyzer.kt
```

### Phase 3 Files
```
custom_components/hue_music_sync/effects/fireworks.py    → app/src/main/java/com/engabd/sendpin/hue/FireworksEffect.kt
custom_components/hue_music_sync/color/palette_v2.py     → app/src/main/java/com/engabd/sendpin/ui/design/AlbumPaletteV2.kt (extend existing)
custom_components/hue_music_sync/const.py (TUNABLE_DEFS) → app/src/main/java/com/engabd/sendpin/hue/SyncoEngine.kt (add tunable params)
```

---

## Testing Strategy

### Unit Tests (JVM)
- `TempoTrackerTest` — BPM detection accuracy on known-tempo tracks
- `BeatGridTest` — downbeat/bar alignment
- `SyncoEngineTest` — render output for known input frames
- `AudioAnalyzerTest` — SuperFlux onset detection, melbank output

### Integration Tests (On-Device)
- DTLS handshake with physical Hue Bridge
- Entertainment area channel mapping
- Audio tap → analysis → render → bridge pipeline latency measurement

### Visual Validation
- Side-by-side video: syncoV2 (HA) vs CAMusic (direct) on same song
- Verify wavefront timing, color jumps, highlight selection match

---

## Conclusion

syncoV2 is a **composition engine** — it knows the song's structure, tempo, and character, and choreographs the lights accordingly. CAMusic direct mode is a **reactive visualizer** — it responds to what just happened but can't anticipate.

The gap is bridgeable in 4-6 days of focused porting. Start with tempo PLL and beat grid locking — those two unlock scheduled wavefronts, which is the single biggest visual improvement.
