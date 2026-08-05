# Direct Light Sync: Complete Feature Port Plan

## Executive Summary

syncoV2 (Home Assistant integration) is a **production-complete** music visualization system with ~7000 lines of Python code across analysis, effects, and coordination. CAMusic's direct mode is a **minimal viable implementation** (~2000 lines Kotlin) that proves the concept but lacks the sophisticated musical intelligence that makes syncoV2's output feel like a choreographed show rather than a reactive visualization.

**Key architectural difference**: syncoV2 uses **scheduled playback** — it pre-analyzes entire tracks, builds a beat grid with section markers (verse/chorus/drop), and schedules lighting events ahead of time. CAMusic's direct mode is purely **reactive** — it analyzes audio frames in real-time and responds to what just happened, not what's coming.

**Goal**: Port **100% of syncoV2's features** to CAMusic direct mode. No shortcuts, no "good enough" — the Android app should match or exceed the HA integration's output quality.

---

## Complete Feature List (All Must Port)

### Phase 1: Core Musical Intelligence (Days 1-4)

These are the **foundational** features that unlock everything else. Without tempo PLL and beat grid locking, scheduled wavefronts and structure-aware rendering are impossible.

#### 1.1 Tempo PLL + Beat Grid Locking
**syncoV2 files**:
- `custom_components/hue_music_sync/audio/tempo.py` (~600 lines)
- `custom_components/hue_music_sync/timing.py` (~200 lines)

**CAMusic targets**:
- `app/src/main/java/com/engabd/sendpin/audio/TempoTracker.kt` (new)
- `app/src/main/java/com/engabd/sendpin/audio/BeatGrid.kt` (new)

**What it does**:
- Detects BPM from real-time audio (SuperFlux onset stream + autocorrelation)
- Locks a phase-locked loop to the beat grid
- Predicts next beat/downbeat/bar positions milliseconds ahead
- Enables scheduled (not reactive) lighting events

**Why it matters**: This is the single biggest difference between "lights flash after sound" and "lights perform with music." syncoV2's scheduled wavefronts depend entirely on this.

**Effort**: 2 days

---

#### 1.2 Scheduled Beat Wavefronts
**syncoV2 files**:
- `custom_components/hue_music_sync/effects/spatial.py` (~400 lines, wavefront section)

**CAMusic targets**:
- `app/src/main/java/com/engabd/sendpin/hue/SpatialWavefronts.kt` (extend existing)

**What it does**:
- Launches wavefronts so they peak across the room exactly on the beat
- Accounts for bridge pipeline latency (~100ms)
- Uses actual lamp positions from entertainment area
- Bass waves from one side, highs from the other

**Why it matters**: Without scheduling, wavefronts sweep continuously. With tempo PLL, they land on musical time — the room feels like it's dancing *with* the music.

**Effort**: 1 day (depends on 1.1)

---

#### 1.3 Auto Intensity Picker
**syncoV2 files**:
- `custom_components/hue_music_sync/audio/liveliness.py` (~300 lines)
- `custom_components/hue_music_sync/const.py` (rung logic)

**CAMusic targets**:
- `app/src/main/java/com/engabd/sendpin/audio/IntensityPicker.kt` (new)
- `app/src/main/java/com/engabd/sendpin/hue/SyncoEngine.kt` (extend mode params)

**What it does**:
- Analyzes song character: tempo, beat density, bass weight, dynamic range
- Assigns a "rung band" — which intensity levels the song can reach
- A lofi track tops out at Medium; a metal track can reach Extreme
- Within that band, moment-to-moment energy moves it up/down

**Why it matters**: Prevents a chill track from looking boring (stuck on Subtle) and a banger from being overwhelming. Makes the room feel like it "understands" the song.

**Effort**: 1 day

---

### Phase 2: Song Awareness (Days 5-7)

Once tempo is locked, the engine can recognize song structure and respond to musical arcs.

#### 2.1 Song Structure Detection
**syncoV2 files**:
- `custom_components/hue_music_sync/audio/structure.py` (~400 lines)
- `custom_components/hue_music_sync/audio/phrase.py` (~200 lines)

**CAMusic targets**:
- `app/src/main/java/com/engabd/sendpin/audio/SongStructure.kt` (new)
- `app/src/main/java/com/engabd/sendpin/audio/PhraseDetector.kt` (new)

**What it does**:
- Identifies verse, chorus, build, drop, breakdown sections
- Analyzes energy contours, beat density, spectral balance
- Detects phrase boundaries (typically 4 or 8 bars)
- Triggers section-aware rendering (drop boosts, build desaturation)

**Why it matters**: Makes long songs feel like a journey, not a loop. The show has arcs, not just moments.

**Effort**: 1.5 days

---

#### 2.2 Pre-Analyzed Track Maps (Library Integration)
**syncoV2 files**:
- `custom_components/hue_music_sync/library/base.py` (~500 lines)
- `custom_components/hue_music_sync/library/ma_backend.py` (~300 lines)
- `custom_components/hue_music_sync/library/subsonic_backend.py` (~400 lines)

**CAMusic targets**:
- `app/src/main/java/com/engabd/sendpin/library/TrackAnalyzer.kt` (new)
- `app/src/main/java/com/engabd/sendpin/ma/MaRepository.kt` (extend)
- `app/src/main/java/com/engabd/sendpin/subsonic/SubsonicClient.kt` (extend)

**What it does**:
- Pre-analyzes entire library tracks (via Navidrome/Music Assistant)
- Stores: beat timestamps, section boundaries, tempo curve, recommended intensity rung
- During playback, uses this map instead of real-time analysis
- Benefits: perfect accuracy from first beat, zero CPU during playback

**Why it matters**: No 20-second "warmup" while analyzer calibrates. Works with any player (even ones that can't provide live audio tap).

**Effort**: 2 days (includes backend integration)

---

### Phase 3: Polish & Features (Days 8-10)

These complete the experience — alternate effects, better color extraction, live tuning.

#### 3.1 Movies & Fireworks Effects
**syncoV2 files**:
- `custom_components/hue_music_sync/effects/fireworks.py` (~150 lines)
- `custom_components/hue_music_sync/effects/modes.py` (render_movies section, ~100 lines)

**CAMusic targets**:
- `app/src/main/java/com/engabd/sendpin/hue/FireworksEffect.kt` (new)
- `app/src/main/java/com/engabd/sendpin/hue/SyncoEngine.kt` (extend render function)

**What it does**:
- **Movies**: Calm, no flashing, brightness follows energy, warm color drift
- **Fireworks**: Bursts on big beats with rapid fade-out

**Why it matters**: Alternate modes for different moods. Movies for background ambiance, Fireworks for parties.

**Effort**: 0.5 days

---

#### 3.2 Album Art Color Extraction V2 (Population Weights)
**syncoV2 files**:
- `custom_components/hue_music_sync/color/palette_v2.py` (~200 lines)

**CAMusic targets**:
- `app/src/main/java/com/engabd/sendpin/ui/design/AlbumPaletteV2.kt` (extend existing)

**What it does**:
- Extracts colors with population weights
- A 90% green / 10% red cover spends 90% of time green, 10% red
- Current k-means treats all colors equally

**Why it matters**: More faithful room theming. The lights actually match the cover's proportions.

**Effort**: 0.5 days

---

#### 3.3 Advanced Tunables UI
**syncoV2 files**:
- `custom_components/hue_music_sync/const.py` (TUNABLE_DEFS)
- Dashboard card advanced section

**CAMusic targets**:
- `app/src/main/java/com/engabd/sendpin/hue/SyncoEngine.kt` (add tunable params)
- `app/src/main/java/com/engabd/sendpin/ui/screens/LightSyncScreen.kt` (add advanced section)

**What it does**:
- Live tunable knobs: bass gain, wave speed, color saturation, beat threshold, etc. (12 total)
- Scales active mode's render params during playback

**Why it matters**: Power users can fine-tune the show. Most won't touch it, but it's essential for demos and calibration.

**Effort**: 0.5 days (mostly UI)

---

#### 3.4 Event Salience Precision Gates ✓ ALREADY PORTED
**Status**: Complete in `SyncoEngine.kt` — `eventGates()` function with `salience_gamma`, `width_min`, `width_soft`.

---

#### 3.5 Highlight Selection ✓ ALREADY PORTED
**Status**: Complete in `SyncoEngine.kt` — `beatHighlight()` function.

---

## Implementation Timeline

| Phase | Features | Days | Cumulative |
|-------|----------|------|------------|
| **Phase 1** | Tempo PLL, Beat Grid, Scheduled Wavefronts, Auto Intensity | 4 | 4 |
| **Phase 2** | Song Structure, Pre-Analyzed Track Maps | 3.5 | 7.5 |
| **Phase 3** | Movies/Fireworks, Album Art V2, Tunables UI | 1.5 | 9 |
| **Buffer** | Testing, bug fixes, polish | 2-3 | 11-12 |

**Total**: ~10-12 days for 100% feature parity

---

## File-by-File Porting Checklist

### Phase 1 Files
- [ ] `audio/tempo.py` → `app/src/main/java/com/engabd/sendpin/audio/TempoTracker.kt`
- [ ] `timing.py` → `app/src/main/java/com/engabd/sendpin/audio/TimingScheduler.kt`
- [ ] `effects/spatial.py` (wavefront section) → `app/src/main/java/com/engabd/sendpin/hue/SpatialWavefronts.kt`
- [ ] `audio/liveliness.py` → `app/src/main/java/com/engabd/sendpin/audio/IntensityPicker.kt`

### Phase 2 Files
- [ ] `audio/structure.py` → `app/src/main/java/com/engabd/sendpin/audio/SongStructure.kt`
- [ ] `audio/phrase.py` → `app/src/main/java/com/engabd/sendpin/audio/PhraseDetector.kt`
- [ ] `library/base.py` → `app/src/main/java/com/engabd/sendpin/library/TrackAnalyzer.kt`
- [ ] `library/ma_backend.py` → `app/src/main/java/com/engabd/sendpin/ma/MaRepository.kt` (extend)
- [ ] `library/subsonic_backend.py` → `app/src/main/java/com/engabd/sendpin/subsonic/SubsonicClient.kt` (extend)

### Phase 3 Files
- [ ] `effects/fireworks.py` → `app/src/main/java/com/engabd/sendpin/hue/FireworksEffect.kt`
- [ ] `effects/modes.py` (movies section) → `app/src/main/java/com/engabd/sendpin/hue/SyncoEngine.kt` (extend)
- [ ] `color/palette_v2.py` → `app/src/main/java/com/engabd/sendpin/ui/design/AlbumPaletteV2.kt`
- [ ] `const.py` (TUNABLE_DEFS) → `app/src/main/java/com/engabd/sendpin/hue/SyncoEngine.kt` + UI

---

## Testing Strategy

### Unit Tests (JVM) — NOT REQUIRED
Per user direction: syncoV2 is proven, tests are unnecessary. Port the logic exactly.

### Integration Tests (On-Device)
- DTLS handshake with physical Hue Bridge
- Entertainment area channel mapping
- Audio tap → analysis → render → bridge pipeline latency measurement
- Side-by-side video: syncoV2 (HA) vs CAMusic (direct) on same song

### Visual Validation
- Verify wavefront timing matches syncoV2
- Verify color jumps land on bar boundaries
- Verify highlight selection feels identical
- Verify Auto intensity assigns sensible rungs to test tracks

---

## Code Quality Standards

### Follow syncoV2 Exactly
- **Constants**: All magic numbers become named constants in a `const.kt` file
- **Docstrings**: Every public function explains purpose, inputs, outputs
- **Type safety**: Kotlin's type system should catch errors at compile time
- **Gradual enhancement**: Fallback paths (metadata → track map → live audio)

### CAMusic-Specific Adaptations
- **Coroutines**: Replace Python async/await with Kotlin coroutines
- **Flow**: Use `StateFlow`/`SharedFlow` for reactive state
- **Compose UI**: All UI in Jetpack Compose, not XML
- **Android lifecycle**: Handle background/foreground transitions properly

---

## Dependencies

### New Libraries Required
```kotlin
// build.gradle.kts
dependencies {
    // Already present:
    // - kotlinx-coroutines-core
    // - androidx.media3 (ExoPlayer)
    
    // May need:
    // - org.jetbrains.kotlinx:kotlinx-coroutines-android (if not already)
    // - No external FFT library — pure Kotlin port (like syncoV2's pure Python)
}
```

### No External DTLS Library
Port syncoV2's pure-Python `dtls.py` to pure Kotlin using `javax.crypto` only — already done in `HueDtlsClient.kt`.

---

## Risk Mitigation

### High-Risk Items
1. **Tempo PLL accuracy** — Test against known-BPM tracks first
2. **Scheduled wavefront timing** — Measure actual photon latency with high-speed camera or phone slow-mo
3. **Library pre-analysis backend** — Ensure Navidrome/MA integration doesn't break existing browse/play

### Mitigation Strategy
- Port one feature at a time
- Test each feature on-device before moving to next
- Keep syncoV2 source open side-by-side during porting
- Commit after each working feature (small, reviewable PRs)

---

## Conclusion

This plan ports **100% of syncoV2's features** to CAMusic direct mode — no shortcuts, no "good enough." The result will be an Android-native music visualization system that matches or exceeds the Home Assistant integration's output quality.

**Start with Phase 1, Day 1**: Tempo PLL. It's the foundation everything else depends on. Once beats are locked and predicted, the rest of the features unlock naturally.

**Estimated completion**: 10-12 days for full parity, assuming focused work and on-device testing after each feature.
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
