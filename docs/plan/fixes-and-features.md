# CAMusic v0.12 Implementation Plan: Fixes and Features

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** Fix seven bugs found in the v0.11.1 codebase review, decompose three god-object files, and add fifteen new features ranging from achievable polish to moonshot differentiators.

**Architecture:** Each fix is isolated to the file or subsystem it affects. Each feature is self-contained and builds on existing infrastructure: the `MusicSource` adapter pattern, the `LocalDsp` biquad chain, the `AudioAnalysisTap`, the ambience event system, the `SetBuilder`, and the `ShowPreset` genre-rule system. Features are ordered by dependency: fixes first, then features by infrastructure reuse.

**Tech Stack:** Jetpack Compose, Material3 1.5.0-alpha26 (M3 Expressive), ExoPlayer 1.10.1, Oboe 1.10.0, Android DataStore, Room, Android Sensor APIs, Android Haptic APIs.

---

## Phase 1: Bug Fixes

### Task 1: Bounds-check DTLS packet parsing in `HueDtlsClient`

**Problem:** `splitRecords` and `splitHandshake` in `HueDtlsClient.kt` parse `length` and `fragLen` fields from incoming DTLS datagrams and pass them directly to `copyOfRange` without validating that they fit within the remaining buffer. A malformed or malicious packet could throw `IndexOutOfBoundsException` in the receive path, crashing the light-sync loop.

**Files:**
- Modify: `app/src/main/java/com/engabd/sendpin/hue/HueDtlsClient.kt` (around the `splitRecords` and `splitHandshake` methods)

**Step 1: Bound the length in `splitRecords`**

Before the `copyOfRange` call, clamp the length to the remaining data:

```kotlin
val actualLen = minOf(length, data.size - off - 13)
if (actualLen < 0) return records  // fragment header extends past buffer
val fragment = data.copyOfRange(off + 13, off + 13 + actualLen)
```

**Step 2: Bound `fragLen` in `splitHandshake`**

Same pattern: clamp `fragLen` to remaining data before `copyOfRange`. The `length` field parsed at the top of the loop should also be validated against the buffer size to prevent reading a header past the end.

**Step 3: Write a test**

Add a test in `HueDtlsClientTest` (new file or existing) that feeds a truncated buffer where the `length` field claims more bytes than present. Verify it returns the records parsed so far without throwing.

**Step 4: Run tests**

```bash
./gradlew :app:testMobileDebugUnitTest --tests "*HueDtlsClient*"
```

**Step 5: Commit**

```bash
git add app/src/main/java/com/engabd/sendpin/hue/HueDtlsClient.kt
git add app/src/test/java/com/engabd/sendpin/hue/HueDtlsClientTest.kt
git commit -m "fix: bound-check DTLS packet lengths before copyOfRange"
```

---

### Task 2: Fix stale `OboeRenderer` reference in `SendspinNativeOutput` class doc

**Problem:** The class doc on `SendspinNativeOutput.kt` line 17 references `OboeRenderer`, which no longer exists. The text was written for the old ExoPlayer-based engine and was not updated when `SendspinNativeEngine` replaced it.

**Files:**
- Modify: `app/src/main/java/com/engabd/sendpin/audio/SendspinNativeOutput.kt` (line 17)

**Step 1: Update the doc**

Replace the `OboeRenderer` reference with an accurate description: the producer is `SendspinNativeEngine`'s decode thread, which feeds PCM into the native ring via `write()`.

**Step 2: Commit**

```bash
git add app/src/main/java/com/engabd/sendpin/audio/SendspinNativeOutput.kt
git commit -m "fix: update stale OboeRenderer reference in SendspinNativeOutput doc"
```

---

### Task 3: Fix `setGrouped` interface doc to match implementation

**Problem:** The `SendspinPlaybackEngine` interface doc for `setGrouped` describes stream restart behaviour (calling `startNativeOutput()` and `onFlush()`). The implementation in `SendspinNativeEngine` deliberately does not do this: every stream is already scheduled on the server timeline, so restarting would silence the leader. The interface doc should describe what the implementation does, not what an earlier version did.

**Files:**
- Modify: `app/src/main/java/com/engabd/sendpin/audio/SendspinPlaybackEngine.kt` (the `setGrouped` interface method doc)

**Step 1: Update the interface doc**

Describe the actual behaviour: `setGrouped` sets the grouped flag only and does not touch playback. Document why: every stream is already on the server timeline, so a restart would silence the leader.

**Step 2: Commit**

```bash
git add app/src/main/java/com/engabd/sendpin/audio/SendspinPlaybackEngine.kt
git commit -m "fix: align setGrouped interface doc with implementation behaviour"
```

---

### Task 4: Add `RECEIVER_NOT_EXPORTED` flag to `DrivingMode` car receiver

**Problem:** `DrivingMode.carReceiver` is registered via `app.registerReceiver` without the `Context.RECEIVER_NOT_EXPORTED` flag. On API 33+ (Android 13+), registering a receiver for a non-system broadcast without an exported flag throws `SecurityException` or logs a warning. The broadcast is `ACTION_ACL_CONNECTED`, which is a system broadcast deliverable only to runtime receivers on modern Android.

**Files:**
- Modify: `app/src/main/java/com/engabd/sendpin/service/DrivingMode.kt` (the `registerReceiver` call)

**Step 1: Add the flag**

Change the `registerReceiver` call to pass `Context.RECEIVER_NOT_EXPORTED`:

```kotlin
app.registerReceiver(carReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
```

**Step 2: Verify on a device if possible**

This needs a car Bluetooth connection or a mock intent. At minimum, verify the app does not crash on launch on an API 33+ device.

**Step 3: Commit**

```bash
git add app/src/main/java/com/engabd/sendpin/service/DrivingMode.kt
git commit -m "fix: add RECEIVER_NOT_EXPORTED flag to DrivingMode car receiver"
```

---

### Task 5: Make `CarLibraryBridge.sourceCache` thread-safe

**Problem:** `CarLibraryBridge.sourceCache` is a plain `mutableMapOf` accessed from suspend functions. These likely run on `Dispatchers.Main`, but the contract is not guaranteed. A `ConcurrentHashMap` makes the thread safety explicit.

**Files:**
- Modify: `app/src/main/java/com/engabd/sendpin/car/CarLibraryBridge.kt`

**Step 1: Replace the map**

Change `private val sourceCache = mutableMapOf<...>()` to `private val sourceCache = java.util.concurrent.ConcurrentHashMap<...>()`.

**Step 2: Commit**

```bash
git add app/src/main/java/com/engabd/sendpin/car/CarLibraryBridge.kt
git commit -m "fix: make CarLibraryBridge source cache thread-safe"
```

---

### Task 6: Remove `SyncoEngine.render()` per-frame `HashMap` allocation

**Problem:** `SyncoEngine.render()` creates a `HashMap<Int, Rgb>()` every frame at 60 Hz. That is 60 map allocations per second producing garbage for the GC. The `LayerChain` already solved this with double-buffering; the engine itself did not.

**Files:**
- Modify: `app/src/main/java/com/engabd/sendpin/hue/SyncoEngine.kt` (the `render()` method)

**Step 1: Pre-allocate the output map**

Add a `private val renderOut = HashMap<Int, Rgb>()` field. At the top of `render()`, call `renderOut.clear()` instead of allocating a new map. The `DirectLightSync` caller reads the map within the same frame, so clearing and reusing is safe.

**Step 2: Run existing tests**

```bash
./gradlew :app:testMobileDebugUnitTest --tests "*SyncoEngine*"
```

**Step 3: Commit**

```bash
git add app/src/main/java/com/engabd/sendpin/hue/SyncoEngine.kt
git commit -m "perf: reuse render output map instead of allocating per frame"
```

---

### Task 7: Increase `HueDtlsClient.recv()` buffer to handle full DTLS records

**Problem:** The receive buffer is 4096 bytes. DTLS records can be up to 16384 bytes per RFC 6347. Hue entertainment frames are small (~156 bytes for 20 channels), so this works in practice, but a larger bridge message would be silently truncated.

**Files:**
- Modify: `app/src/main/java/com/engabd/sendpin/hue/HueDtlsClient.kt` (the `recv()` buffer size)

**Step 1: Increase the buffer**

Change the receive buffer from 4096 to 16384 bytes.

**Step 2: Commit**

```bash
git add app/src/main/java/com/engabd/sendpin/hue/HueDtlsClient.kt
git commit -m "fix: increase DTLS receive buffer to handle full-size records"
```

---

## Phase 2: Architecture Decomposition

### Task 8: Extract `OutputRouter` from `SendspinNativeEngine`

**Problem:** `SendspinNativeEngine` is ~1450 lines with 30+ volatile fields. It combines codec management, timing policy, device routing, idle power management, and audio analysis tap feeding. The device-change and routing logic is a self-contained concern that can be extracted.

**Files:**
- Create: `app/src/main/java/com/engabd/sendpin/audio/OutputRouter.kt`
- Modify: `app/src/main/java/com/engabd/sendpin/audio/SendspinNativeEngine.kt`

**Step 1: Extract the `AudioDeviceCallback`, route-change handling, and reopen logic**

Move all device callback registration, unregistration, route-change handling, and output-reopen logic into `OutputRouter`. The router exposes a `reopen()` method and a `onDeviceChanged` callback that `SendspinNativeEngine` registers for.

**Step 2: Verify compilation and tests**

```bash
./gradlew :app:compileMobileDebugKotlin
./gradlew :app:testMobileDebugUnitTest --tests "*Sendspin*"
```

**Step 3: Commit**

```bash
git add app/src/main/java/com/engabd/sendpin/audio/OutputRouter.kt
git add app/src/main/java/com/engabd/sendpin/audio/SendspinNativeEngine.kt
git commit -m "refactor: extract OutputRouter from SendspinNativeEngine"
```

---

### Task 9: Split `AppSettings` by domain

**Problem:** `AppSettings` is ~1700 lines with ~100+ keys covering audio, driving, light sync, servers, appearance, and behaviour. It is a god class.

**Files:**
- Create: `app/src/main/java/com/engabd/sendpin/data/AudioSettings.kt`
- Create: `app/src/main/java/com/engabd/sendpin/data/DrivingSettingsStore.kt`
- Create: `app/src/main/java/com/engabd/sendpin/data/LightSyncSettingsStore.kt`
- Create: `app/src/main/java/com/engabd/sendpin/data/ServerSettings.kt`
- Modify: `app/src/main/java/com/engabd/sendpin/data/AppSettings.kt`

**Step 1: Extract domain-specific settings classes**

Each new class holds a `DataStore<Preferences>` reference and exposes only its domain's keys. `AppSettings` becomes a facade that delegates to these. The public API stays the same; the internal organisation changes.

**Step 2: Verify compilation and tests**

```bash
./gradlew :app:compileMobileDebugKotlin
./gradlew :app:testMobileDebugUnitTest
```

**Step 3: Commit**

```bash
git add app/src/main/java/com/engabd/sendpin/data/
git commit -m "refactor: split AppSettings into domain-specific stores"
```

---

### Task 10: Decompose `LibraryViewModel` into focused components

**Problem:** `LibraryViewModel` is ~2900 lines handling backend switching, browse stack, shelf loading, search, playlist CRUD, downloads state, radio mode, server config, and playback routing.

**Files:**
- Create: `app/src/main/java/com/engabd/sendpin/ma/LibraryBrowser.kt`
- Create: `app/src/main/java/com/engabd/sendpin/ma/LibrarySearch.kt`
- Create: `app/src/main/java/com/engabd/sendpin/ma/LibraryPlaylistManager.kt`
- Modify: `app/src/main/java/com/engabd/sendpin/ma/LibraryViewModel.kt`

**Step 1: Extract browse, search, and playlist logic**

Each extracted class takes a `MusicSource?` reference and exposes suspend functions. `LibraryViewModel` becomes a thin facade that delegates to these and holds the `StateFlow` the UI collects.

**Step 2: Verify compilation**

```bash
./gradlew :app:compileMobileDebugKotlin
```

**Step 3: Commit**

```bash
git add app/src/main/java/com/engabd/sendpin/ma/
git commit -m "refactor: decompose LibraryViewModel into focused components"
```

---

## Phase 3: New Features

### Task 11: Vinyl surface noise DSP layer

**Concept:** A DSP layer that adds subtle, configurable vinyl surface noise to digital playback. The audio equivalent of a film grain filter. Includes crackle, dust pops, and low-end rumble. Toggleable with intensity control. Sits in the existing `LocalDsp` `AudioProcessor` chain ahead of the analysis tap, so the light show reacts to what is actually heard.

**Files:**
- Create: `app/src/main/java/com/engabd/sendpin/audio/VinylNoiseProcessor.kt`
- Create: `app/src/test/java/com/engabd/sendpin/audio/VinylNoiseProcessorTest.kt`
- Modify: `app/src/main/java/com/engabd/sendpin/audio/LocalPlayer.kt` (add to processor chain)
- Modify: `app/src/main/java/com/engabd/sendpin/data/AppSettings.kt` (or `AudioSettings.kt` if Task 9 landed) (add `vinylNoiseEnabled` and `vinylNoiseIntensity` keys)
- Modify: `app/src/main/java/com/engabd/sendpin/ui/screens/settings/AudioSettings.kt` (add toggle and slider)

**Step 1: Implement the noise generator**

`VinylNoiseProcessor` extends `BaseAudioProcessor`. It generates:

- **Crackle:** sparse random impulses, Poisson-distributed in time, amplitude shaped by a one-pole decay. The rate scales with intensity.
- **Dust pops:** occasional sharper impulses, less frequent than crackle, with a faster decay.
- **Low-end rumble:** filtered white noise through a low-pass at ~50 Hz, very quiet (at most 3% of full scale at max intensity).

All three are additive into the output buffer. The processor is zero-latency (no lookahead). Like `LocalDsp`, it takes effect at the start of the next buffer and reads a `@Volatile` config snapshot.

**Step 2: Write tests**

Test that: the output equals the input when disabled; crackle rate scales with intensity; rumble stays below the amplitude ceiling; no allocation on the hot path.

**Step 3: Add to the processor chain**

In `LocalPlayer`, insert `VinylNoiseProcessor` after `LocalDsp` and before `AudioAnalysisTap`. This means the light show reacts to the vinyl-treated audio, not the raw file.

**Step 4: Add settings**

Add a toggle and intensity slider under Settings > Playback & audio. When vinyl noise is on, the EQ and vinyl noise share the same section.

**Step 5: Commit**

```bash
git add app/src/main/java/com/engabd/sendpin/audio/VinylNoiseProcessor.kt
git add app/src/test/java/com/engabd/sendpin/audio/VinylNoiseProcessorTest.kt
git add app/src/main/java/com/engabd/sendpin/audio/LocalPlayer.kt
git add app/src/main/java/com/engabd/sendpin/data/AppSettings.kt
git add app/src/main/java/com/engabd/sendpin/ui/screens/settings/AudioSettings.kt
git commit -m "feat: vinyl surface noise DSP layer with crackle, pops, and rumble"
```

---

### Task 12: Lo-fi music mode

**Concept:** A mode that makes any music sound like lo-fi: bitcrusher (sample rate and bit depth reduction), warm saturation, vinyl noise (reuses Task 11), and a gentle low-pass to roll off highs. The whole chain is one `AudioProcessor` with a single intensity slider. At low intensity it adds warmth; at high intensity it is full lo-fi hip-hop. Sits in the `LocalDsp` chain alongside the vinyl noise.

**Files:**
- Create: `app/src/main/java/com/engabd/sendpin/audio/LoFiProcessor.kt`
- Create: `app/src/test/java/com/engabd/sendpin/audio/LoFiProcessorTest.kt`
- Modify: `app/src/main/java/com/engabd/sendpin/audio/LocalPlayer.kt` (add to processor chain)
- Modify: `app/src/main/java/com/engabd/sendpin/data/AppSettings.kt` (or `AudioSettings.kt`) (add `loFiEnabled` and `loFiIntensity` keys)
- Modify: `app/src/main/java/com/engabd/sendpin/ui/screens/settings/AudioSettings.kt` (add toggle and slider)

**Step 1: Implement the processor**

`LoFiProcessor` extends `BaseAudioProcessor`. The signal chain inside it:

1. **Bit depth reduction:** quantise each sample to N bits, where N ranges from 16 (transparent) down to 6 (crushed). The `intensity` slider maps to bit depth: 0 = 16 (off), 1.0 = 6. The reduction is `floor(x * levels) / levels` where `levels = 2^bits`.
2. **Sample rate reduction (decimation):** hold each sample for `decimate` samples, where `decimate` ranges from 1 (transparent) to 4 (strong downsampling aliasing). The `intensity` slider maps to decimation factor.
3. **Warm saturation:** a soft clipper (`tanh`-shaped) with a drive that scales with intensity. Adds harmonic content, the opposite of the bitcrusher's subtraction.
4. **Low-pass:** a one-pole IIR low-pass at a frequency that drops with intensity (from 20 kHz down to 3 kHz). Removes the harshness the bitcrusher adds.
5. **Vinyl noise:** delegates to `VinylNoiseProcessor` when both are enabled, or includes its own lightweight crackle if vinyl noise is off. This avoids double-running the vinyl processor when both modes are on: if both are enabled, the lo-fi processor's crackle stage is skipped and `VinylNoiseProcessor` in the chain handles it.

**Step 2: Write tests**

Test that: the output equals the input when disabled; bit depth reduction produces the expected quantisation steps; decimation produces the expected hold pattern; saturation is bounded; low-pass attenuates high frequencies; no allocation on the hot path.

**Step 3: Add to the processor chain**

In `LocalPlayer`, insert `LoFiProcessor` after `VinylNoiseProcessor` and before `AudioAnalysisTap`. Order matters: lo-fi processing changes the signal the light show sees.

**Step 4: Add settings**

Add a toggle and intensity slider under Settings > Playback & audio, in a new "Sound modes" subsection alongside vinyl noise. When both vinyl and lo-fi are on, the settings note that vinyl crackle is shared.

**Step 5: Commit**

```bash
git add app/src/main/java/com/engabd/sendpin/audio/LoFiProcessor.kt
git add app/src/test/java/com/engabd/sendpin/audio/LoFiProcessorTest.kt
git add app/src/main/java/com/engabd/sendpin/audio/LocalPlayer.kt
git add app/src/main/java/com/engabd/sendpin/data/AppSettings.kt
git add app/src/main/java/com/engabd/sendpin/ui/screens/settings/AudioSettings.kt
git commit -m "feat: lo-fi music mode with bitcrusher, saturation, and low-pass"
```

---

### Task 13: Hue sunrise alarm clock

**Concept:** An alarm that starts with a simulated sunrise: warm colours rising over 10 minutes, paired with an aurora ambience bed that fades in. Uses the existing ambience system to drive lights and the existing `AmbienceClipPlayer` for audio. No other alarm app can drive a Hue entertainment area.

**Files:**
- Create: `app/src/main/java/com/engabd/sendpin/alarm/SunriseAlarm.kt`
- Create: `app/src/main/java/com/engabd/sendpin/alarm/AlarmReceiver.kt`
- Create: `app/src/main/java/com/engabd/sendpin/alarm/AlarmScheduler.kt`
- Create: `app/src/main/java/com/engabd/sendpin/ui/screens/AlarmScreen.kt`
- Modify: `app/src/main/AndroidManifest.xml` (register `AlarmReceiver`)
- Modify: `app/src/main/java/com/engabd/sendpin/data/AppSettings.kt` (alarm time, enabled, light duration)
- Modify: `app/src/main/java/com/engabd/sendpin/ui/screens/SettingsScreen.kt` (add alarm section)
- Modify: `app/src/main/java/com/engabd/sendpin/ui/App.kt` (add alarm route)

**Step 1: Implement `SunriseAlarm`**

`SunriseAlarm` uses `AlarmManager.setAlarmClock` for exact delivery (with `setExactAndAllowWhileIdle` fallback). On trigger:

1. Start the `EffectsService` with the Aurora ambience at zero intensity.
2. Ramp the Hue brightness from 0 to the user's ceiling over the configured duration (default 10 minutes).
3. Ramp the aurora ambience audio from 0 to a gentle volume.
4. After the ramp, optionally start playing music from the active library (a "wake-up playlist" setting).

The sunrise colour sequence: deep red at 0%, warm orange at 30%, soft yellow at 60%, warm white at 100%. This follows the natural sunrise spectrum.

**Step 2: Add the alarm UI**

A time picker in Settings, with toggles for: light sunrise on/off, audio ambience on/off, music after sunrise on/off, and a day-of-week selector.

**Step 3: Add the `AlarmReceiver`**

A `BroadcastReceiver` that starts the `EffectsService` and the sunrise ramp. Uses `RECEIVER_NOT_EXPORTED`.

**Step 4: Test the alarm logic**

Test the ramp schedule and the colour sequence computation. The `SunriseAlarm` colour function should be pure and testable.

**Step 5: Commit**

```bash
git add app/src/main/java/com/engabd/sendpin/alarm/
git add app/src/main/java/com/engabd/sendpin/ui/screens/AlarmScreen.kt
git add app/src/main/AndroidManifest.xml
git commit -m "feat: Hue sunrise alarm with aurora ambience and music wake-up"
```

---

### Task 14: DJ transition bridges

**Concept:** When the Set Builder or auto-queue moves between two tracks with incompatible keys or very different tempos, insert a short ambience bridge: a thunderclap, a swell, or a beatless pad. The ambience engine already exists; this is a new scheduling layer on top of it.

**Files:**
- Create: `app/src/main/java/com/engabd/sendpin/audio/TransitionBridge.kt`
- Create: `app/src/test/java/com/engabd/sendpin/audio/TransitionBridgeTest.kt`
- Modify: `app/src/main/java/com/engabd/sendpin/audio/LocalPlayer.kt` (hook into track transitions)
- Modify: `app/src/main/java/com/engabd/sendpin/data/AppSettings.kt` (`transitionBridgesEnabled`)

**Step 1: Implement the transition detector**

`TransitionBridge.shouldBridge(prev, next)` returns a `BridgeType` (THUNDERCLAP, SWELL, PAD, NONE) based on:
- Key compatibility (Camelot): if keys are incompatible and both are tonal, a pad bridge.
- Tempo gap: if BPM difference > 20% and both are rhythmic, a swell bridge.
- Energy cliff: if energy drops > 0.4, a thunderclap bridge.
- Otherwise: NONE.

**Step 2: Implement the bridge playback**

When a bridge is selected, the `LocalPlayer` inserts a short (2-5 second) ambience event between tracks. The ambience audio is rendered through the existing `AmbienceClipPlayer` and the light event through `DirectLightSync`. The bridge is not a track in the queue; it is a gap effect.

**Step 3: Write tests**

Test the transition detector: compatible keys produce no bridge, large tempo gaps produce swells, large energy drops produce thunderclaps.

**Step 4: Add a setting**

Under Settings > Playback & behavior. Off by default.

**Step 5: Commit**

```bash
git add app/src/main/java/com/engabd/sendpin/audio/TransitionBridge.kt
git add app/src/test/java/com/engabd/sendpin/audio/TransitionBridgeTest.kt
git commit -m "feat: DJ transition bridges between incompatible tracks"
```

---

### Task 15: Hue lullaby sleep timer

**Concept:** Extends the existing sleep timer into a light-and-sound wind-down. Over the configured duration (default 20 minutes), gradually dim Hue lights from current brightness to zero, shift ambience from active (fireplace) to calm (aurora) to off. A ritual, not a timer.

**Files:**
- Create: `app/src/main/java/com/engabd/sendpin/audio/LullabyController.kt`
- Modify: `app/src/main/java/com/engabd/sendpin/ui/screens/NowPlayingParts.kt` (extend sleep timer UI)
- Modify: `app/src/main/java/com/engabd/sendpin/data/AppSettings.kt` (`lullabyMode`)

**Step 1: Implement the ramp controller**

`LullabyController` takes the current Hue brightness and ambience effect, and over the duration:
- Minutes 0-50%: keep current ambience, slowly dim brightness by 30%.
- Minutes 50-80%: switch to aurora ambience, dim to 15%.
- Minutes 80-100%: fade aurora audio to zero, dim lights to 0, stop the show.

**Step 2: Extend the sleep timer UI**

Add a "Lullaby" toggle next to the sleep timer. When on, the sleep timer uses `LullabyController` instead of a simple stop-at-time.

**Step 3: Commit**

```bash
git add app/src/main/java/com/engabd/sendpin/audio/LullabyController.kt
git add app/src/main/java/com/engabd/sendpin/ui/screens/NowPlayingParts.kt
git commit -m "feat: Hue lullaby sleep timer with gradual light and ambience wind-down"
```

---

### Task 16: Stem-separation solo mode

**Concept:** The Phantom Stage light layer already does on-device stem separation (bass, vocals, guitar, synths, drums). Add a "solo" mode that mutes everything except one isolated stem, so the listener can hear just the drums, just the vocals, just the bass. The separation already runs; this is a new output routing.

**Files:**
- Create: `app/src/main/java/com/engabd/sendpin/audio/StemSoloProcessor.kt`
- Modify: `app/src/main/java/com/engabd/sendpin/audio/LocalPlayer.kt` (add to processor chain)
- Modify: `app/src/main/java/com/engabd/sendpin/data/AppSettings.kt` (`stemSoloMode`: none/bass/vocals/drums/guitar/synths)
- Modify: `app/src/main/java/com/engabd/sendpin/ui/screens/NowPlayingParts.kt` (add stem solo selector)

**Step 1: Implement the stem solo processor**

`StemSoloProcessor` extends `BaseAudioProcessor`. When a stem is selected, it applies a bandpass or set of filters matching the frequency characteristics of that stem:

- **Bass:** low-pass at ~200 Hz.
- **Vocals:** bandpass 300 Hz to 3 kHz (the existing mid-presence range the sustain layer uses).
- **Drums:** high-pass at ~3 kHz plus broadband transient detection (everything else attenuated).
- **Guitar:** bandpass 500 Hz to 2 kHz.
- **Synths:** high-pass at ~2 kHz.

When real stems are available (from `SectionStems`), use the actual separated channels instead of frequency filtering.

**Step 2: Add the UI**

A chip row on Now Playing, visible when stem separation data is available. Tapping a stem solos it; tapping it again returns to full mix.

**Step 3: Commit**

```bash
git add app/src/main/java/com/engabd/sendpin/audio/StemSoloProcessor.kt
git add app/src/main/java/com/engabd/sendpin/audio/LocalPlayer.kt
git add app/src/main/java/com/engabd/sendpin/ui/screens/NowPlayingParts.kt
git commit -m "feat: stem-separation solo mode for isolated instrument listening"
```

---

### Task 17: Adaptive EQ profiles by genre

**Concept:** The EQ and genre detection both exist. When a genre-tagged track starts, automatically switch to a genre-appropriate EQ curve. The ShowPreset genre-rule system already proves the pattern; apply it to EQ presets too.

**Files:**
- Create: `app/src/main/java/com/engabd/sendpin/audio/EqPreset.kt`
- Create: `app/src/test/java/com/engabd/sendpin/audio/EqPresetTest.kt`
- Modify: `app/src/main/java/com/engabd/sendpin/data/AppSettings.kt` (`eqGenreRules`)
- Modify: `app/src/main/java/com/engabd/sendpin/audio/LocalPlayer.kt` (apply preset on track change)
- Modify: `app/src/main/java/com/engabd/sendpin/ui/screens/settings/AudioSettings.kt` (genre-to-preset editor)

**Step 1: Implement `EqPreset`**

Same `@Serializable` pattern as `ShowPreset`. Fields: `id`, `name`, `bands: List<LocalDsp.Band>`, `preampDb`, `autoPreamp`. Starter presets:

- **Bass boost:** +6 dB at 62 Hz, +4 dB at 125 Hz, Q 1.0.
- **Vocal forward:** +3 dB at 1 kHz, +2 dB at 2 kHz, -2 dB at 125 Hz.
- **Flat:** all zero (passthrough).
- **Electronic:** +4 dB at 62 Hz, +2 dB at 8 kHz, -2 dB at 250 Hz.
- **Classical:** +2 dB at 31 Hz, +1 dB at 16 kHz, flat elsewhere.

**Step 2: Implement genre rules**

Same `GenrePresetRule` matching as `ShowPreset`: case-insensitive, bidirectional substring, first-rule-wins. A track with no genre changes nothing.

**Step 3: Apply on track change**

When `LocalPlayer` sets a new track, read the genre from the `MaItem`, look up the EQ preset via the genre rules, and call `LocalDsp.setConfig()`.

**Step 4: Write tests**

Test genre matching, preset application, and the "no genre changes nothing" invariant.

**Step 5: Commit**

```bash
git add app/src/main/java/com/engabd/sendpin/audio/EqPreset.kt
git add app/src/test/java/com/engabd/sendpin/audio/EqPresetTest.kt
git commit -m "feat: adaptive EQ profiles with genre-based auto-switching"
```

---

### Task 18: Listening journal

**Concept:** A daily auto-generated note from the day's listening. Natural-language summary: "You started with jazz at 8 AM, moved to electronic during work, ended with ambient. Most played: 'Blue in Green' (3 times). Key of the day: D minor." Exportable as a text file.

**Files:**
- Create: `app/src/main/java/com/engabd/sendpin/audio/ListeningJournal.kt`
- Create: `app/src/test/java/com/engabd/sendpin/audio/ListeningJournalTest.kt`
- Modify: `app/src/main/java/com/engabd/sendpin/ui/screens/StatsScreen.kt` (add journal tab)
- Modify: `app/src/main/java/com/engabd/sendpin/ui/viewmodel/StatsViewModel.kt` (expose journal data)

**Step 1: Implement the journal generator**

`ListeningJournal.generate(plays: List<PlayHistoryEntity>, scans: Map<String, TrackScan>): String`. Pure function, testable. Produces:

- Time-of-day summary (morning/afternoon/evening/night, based on first and last play timestamps).
- Most played track and artist.
- Genre breakdown (top 3).
- Key of the day (most common key across played tracks).
- Tempo average.
- Total listening time.

The summary reads as a narrative, not a table.

**Step 2: Write tests**

Test with various play histories: empty, single track, diverse day, late-night-only.

**Step 3: Add the UI**

A "Journal" tab on the Stats screen. Shows today's entry and the last 7 days. Each entry is scrollable text with a share button.

**Step 4: Commit**

```bash
git add app/src/main/java/com/engabd/sendpin/audio/ListeningJournal.kt
git add app/src/test/java/com/engabd/sendpin/audio/ListeningJournalTest.kt
git add app/src/main/java/com/engabd/sendpin/ui/screens/StatsScreen.kt
git add app/src/main/java/com/engabd/sendpin/ui/viewmodel/StatsViewModel.kt
git commit -m "feat: auto-generated listening journal with daily narrative summary"
```

---

### Task 19: Hue colour palette editor for albums

**Concept:** Let users override album-art colour extraction per album. Pick which colours from the cover drive the room, and save the override alongside the track scan. An album with a great cover that extracts poorly becomes fixable.

**Files:**
- Create: `app/src/main/java/com/engabd/sendpin/hue/AlbumColourOverride.kt`
- Modify: `app/src/main/java/com/engabd/sendpin/hue/AlbumColours.kt` (check overrides first)
- Modify: `app/src/main/java/com/engabd/sendpin/ui/screens/LightSyncScreen.kt` (colour picker)
- Modify: `app/src/main/java/com/engabd/sendpin/data/AppSettings.kt` (store overrides)

**Step 1: Implement the override store**

`AlbumColourOverride` is a `@Serializable` map from album ID to a list of `Rgb` colours. Stored as a JSON blob in DataStore, same pattern as `showPresets`.

**Step 2: Integrate into `AlbumColours`**

When extracting colours for an album, check the override map first. If an override exists, use it instead of the k-means extraction.

**Step 3: Add the UI**

On the Light Sync screen, a "Customise colours" button when an album is playing. Shows the extracted palette and lets the user pick which colours to keep, adjust their weights, or pick new colours from the cover art.

**Step 4: Commit**

```bash
git add app/src/main/java/com/engabd/sendpin/hue/AlbumColourOverride.kt
git add app/src/main/java/com/engabd/sendpin/hue/AlbumColours.kt
git add app/src/main/java/com/engabd/sendpin/ui/screens/LightSyncScreen.kt
git commit -m "feat: per-album Hue colour palette editor with overrides"
```

---

### Task 20: Mood-driven auto-mix

**Concept:** The Set Builder already knows tempo, key, and energy. Extend it to read the room's ambient noise level through the microphone. A party getting louder shifts the auto-queue toward higher energy. Late and quiet winds down. The microphone never records: it reads dB levels in real-time and discards the audio immediately.

**Files:**
- Create: `app/src/main/java/com/engabd/sendpin/audio/AmbientNoiseMonitor.kt`
- Modify: `app/src/main/java/com/engabd/sendpin/audio/LocalRadio.kt` (factor noise level into track selection)
- Modify: `app/src/main/java/com/engabd/sendpin/data/AppSettings.kt` (`moodMixEnabled`)
- Modify: `app/src/main/AndroidManifest.xml` (RECORD_AUDIO is already declared for MediaProjection; this uses it for mic level only)

**Step 1: Implement `AmbientNoiseMonitor`**

Uses `AudioRecord` with `MediaRecorder.AudioSource.MIC` at a low sample rate (8 kHz, mono, 16-bit). Reads short buffers, computes RMS, and publishes a `StateFlow<Float>` for the current noise level. The audio is never stored. The monitor runs only when mood-mix is enabled and playback is active.

**Step 2: Integrate into `LocalRadio`**

When selecting the next track for continuous play, factor the ambient noise level into the energy target: high noise level biases toward higher-energy tracks, low noise level toward lower-energy tracks. The existing energy-based ranking in `LocalRadio` already has the structure.

**Step 3: Add a setting**

Under Settings > Playback & behavior. Off by default. When on, requests the microphone permission at the point it is turned on.

**Step 4: Commit**

```bash
git add app/src/main/java/com/engabd/sendpin/audio/AmbientNoiseMonitor.kt
git add app/src/main/java/com/engabd/sendpin/audio/LocalRadio.kt
git commit -m "feat: mood-driven auto-mix using ambient noise level"
```

---

### Task 21: Listening room: phone-to-phone sync

**Concept:** Two CAMusic phones on the same network sync playback via the existing Kalman filter. Two people in different rooms, same album, in sync, each with their own Hue show. The protocol already exists for MA; extending it phone-to-phone is a natural fit.

**Files:**
- Create: `app/src/main/java/com/engabd/sendpin/p2p/PhoneSyncServer.kt`
- Create: `app/src/main/java/com/engabd/sendpin/p2p/PhoneSyncClient.kt`
- Modify: `app/src/main/java/com/engabd/sendpin/protocol/SendspinClient.kt` (reuse clock sync)
- Modify: `app/src/main/java/com/engabd/sendpin/ui/screens/SpeakersScreen.kt` (add "Sync with phone" option)
- Modify: `app/src/main/java/com/engabd/sendpin/data/AppSettings.kt` (`phoneSyncEnabled`)

**Step 1: Implement the sync server**

`PhoneSyncServer` runs a lightweight WebSocket server (using `okhttp3.WebSocketServer` or `java.net.ServerSocket`). It publishes the current playback state (track ID, position, play/pause) and accepts join requests. Clock sync uses the same four-point exchange as the Sendspin protocol.

**Step 2: Implement the sync client**

`PhoneSyncClient` discovers sync servers on the local network (mDNS or manual IP), joins, and aligns its playback to the leader's clock. The follower's `LocalPlayer` starts at the leader's position adjusted for the measured offset.

**Step 3: Add the UI**

On the Speakers screen, a "Sync with another phone" option. Shows discoverable phones on the network. One phone is the leader; the other follows.

**Step 4: Commit**

```bash
git add app/src/main/java/com/engabd/sendpin/p2p/
git commit -m "feat: phone-to-phone listening sync via local WebSocket"
```

---

### Task 22: Collaborative queue via QR code

**Concept:** A QR code on the Now Playing screen that, when scanned by another phone, opens a local web page where friends add tracks to your queue. No account, no app install, just a WebSocket server on the phone. The MA protocol already handles queues; this is a web frontend for it.

**Files:**
- Create: `app/src/main/java/com/engabd/sendpin/p2p/QueueServer.kt`
- Create: `app/src/main/assets/web/queue.html` (minimal web UI)
- Modify: `app/src/main/java/com/engabd/sendpin/ui/screens/NowPlayingParts.kt` (QR display)
- Modify: `app/src/main/java/com/engabd/sendpin/data/AppSettings.kt` (`collabQueueEnabled`)

**Step 1: Implement `QueueServer`**

A lightweight HTTP + WebSocket server on the phone (using `NanoHTTPD` or `okhttp3` mock server). Serves a web page with a search field and a list of the current queue. When a friend adds a track, the server calls `LibraryViewModel.addToQueue()`.

**Step 2: Generate the QR code**

On Now Playing, a "Share queue" button that shows a QR code encoding the server URL (e.g., `http://192.168.0.42:8080`). The QR is generated locally using a pure-Kotlin QR encoder.

**Step 3: Commit**

```bash
git add app/src/main/java/com/engabd/sendpin/p2p/QueueServer.kt
git add app/src/main/assets/web/queue.html
git add app/src/main/java/com/engabd/sendpin/ui/screens/NowPlayingParts.kt
git commit -m "feat: collaborative queue via QR code and local web server"
```

---

### Task 23: On-device acoustic similarity

**Concept:** For servers without `similarSongs` (local files, Subsonic), compute similarity from the existing track scan data (tempo, key, energy, spectral characteristics) using cosine similarity over feature vectors. No cloud, no API, pure on-device.

**Files:**
- Create: `app/src/main/java/com/engabd/sendpin/audio/AcousticSimilarity.kt`
- Create: `app/src/test/java/com/engabd/sendpin/audio/AcousticSimilarityTest.kt`
- Modify: `app/src/main/java/com/engabd/sendpin/library/MusicSource.kt` (default `similarSongs` uses scan data)
- Modify: `app/src/main/java/com/engabd/sendpin/ui/screens/AlbumDetailScreen.kt` (show "Similar" shelf for all sources)

**Step 1: Implement the similarity function**

`AcousticSimilarity.featureVector(scan: TrackScan): FloatArray` produces a normalised feature vector from the scan data: `[bpm_normalised, key_chroma_12_bins, mean_energy, section_count, spectral_centroid]`. `similarity(a, b)` is cosine similarity.

**Step 2: Write tests**

Test that identical scans produce similarity 1.0, transposed tracks produce high similarity, and unrelated tracks produce low similarity.

**Step 3: Integrate into the library**

When `MusicSource.similarSongs()` returns empty (no server support), the library falls back to scanning the local download/scan database for the most similar tracks by feature vector.

**Step 4: Commit**

```bash
git add app/src/main/java/com/engabd/sendpin/audio/AcousticSimilarity.kt
git add app/src/test/java/com/engabd/sendpin/audio/AcousticSimilarityTest.kt
git commit -m "feat: on-device acoustic similarity from track scan feature vectors"
```

---

### Task 24: Music-driven phone haptics

**Concept:** Map the beat grid and onset detection to the phone's haptic actuator. Not a notification vibration: a continuous, musical haptic track that pulses with the beat. Android's `VibrationEffect` supports amplitudes 1 to 255, and the onset envelope is already computed.

**Files:**
- Create: `app/src/main/java/com/engabd/sendpin/audio/HapticEngine.kt`
- Modify: `app/src/main/java/com/engabd/sendpin/audio/AudioAnalysisTap.kt` (feed haptic engine)
- Modify: `app/src/main/java/com/engabd/sendpin/data/AppSettings.kt` (`hapticsEnabled`, `hapticIntensity`)
- Modify: `app/src/main/java/com/engabd/sendpin/ui/screens/NowPlayingParts.kt` (haptic toggle)

**Step 1: Implement `HapticEngine`**

`HapticEngine` takes beat and onset data from the analysis tap and drives `VibratorManager`. On each beat, it fires a short `VibrationEffect.createOneShot` with amplitude proportional to the beat strength. On onsets, a lighter pulse. The haptic engine throttles to at most 10 Hz to avoid continuous buzzing.

**Step 2: Integrate with the analysis tap**

The `AudioAnalysisTap` already computes onset envelopes and beat positions. Add a callback that feeds these to `HapticEngine` at the analysis frame rate.

**Step 3: Add the UI**

A haptics toggle on Now Playing, near the sleep timer. Intensity slider in Settings.

**Step 4: Commit**

```bash
git add app/src/main/java/com/engabd/sendpin/audio/HapticEngine.kt
git add app/src/main/java/com/engabd/sendpin/audio/AudioAnalysisTap.kt
git commit -m "feat: music-driven phone haptics from beat and onset detection"
```

---

### Task 25: Audio-reactive live wallpaper

**Concept:** A live wallpaper that reacts to whatever is playing. Same FFT, same colour extraction. Makes CAMusic the only app whose home screen presence matches its room presence.

**Files:**
- Create: `app/src/main/java/com/engabd/sendpin/wallpaper/MusicWallpaperService.kt`
- Create: `app/src/main/res/xml/wallpaper.xml`
- Modify: `app/src/main/AndroidManifest.xml` (register `WallpaperService`)
- Modify: `app/src/main/java/com/engabd/sendpin/audio/AudioAnalysisTap.kt` (publish spectrum to a shared flow)

**Step 1: Implement `MusicWallpaperService`**

Extends `WallpaperService`. The wallpaper renders a simplified version of the `AudioVisualizer` spectrum using Canvas. It subscribes to a `StateFlow` published by `AudioAnalysisTap` for the current spectrum data. When nothing is playing, it renders a slow ambient drift using the album colour palette.

**Step 2: Add the wallpaper XML**

A `wallpaper.xml` metadata file declaring the wallpaper and its settings activity.

**Step 3: Register in the manifest**

Add the `WallpaperService` to the manifest with the `BIND_WALLPAPER` permission.

**Step 4: Commit**

```bash
git add app/src/main/java/com/engabd/sendpin/wallpaper/
git add app/src/main/res/xml/wallpaper.xml
git add app/src/main/AndroidManifest.xml
git commit -m "feat: audio-reactive live wallpaper from FFT and colour extraction"
```

---

## Task Summary

| # | Type | Title | Phase |
|---|---|---|---|
| 1 | fix | Bounds-check DTLS packet parsing | 1 |
| 2 | fix | Stale OboeRenderer doc reference | 1 |
| 3 | fix | setGrouped interface doc | 1 |
| 4 | fix | RECEIVER_NOT_EXPORTED on car receiver | 1 |
| 5 | fix | Thread-safe CarLibraryBridge cache | 1 |
| 6 | perf | Reuse render output map | 1 |
| 7 | fix | DTLS receive buffer size | 1 |
| 8 | refactor | Extract OutputRouter | 2 |
| 9 | refactor | Split AppSettings by domain | 2 |
| 10 | refactor | Decompose LibraryViewModel | 2 |
| 11 | feat | Vinyl surface noise DSP | 3 |
| 12 | feat | Lo-fi music mode | 3 |
| 13 | feat | Hue sunrise alarm clock | 3 |
| 14 | feat | DJ transition bridges | 3 |
| 15 | feat | Hue lullaby sleep timer | 3 |
| 16 | feat | Stem-separation solo mode | 3 |
| 17 | feat | Adaptive EQ profiles by genre | 3 |
| 18 | feat | Listening journal | 3 |
| 19 | feat | Hue colour palette editor | 3 |
| 20 | feat | Mood-driven auto-mix | 3 |
| 21 | feat | Phone-to-phone sync | 3 |
| 22 | feat | Collaborative queue via QR | 3 |
| 23 | feat | On-device acoustic similarity | 3 |
| 24 | feat | Music-driven phone haptics | 3 |
| 25 | feat | Audio-reactive live wallpaper | 3 |

## Design Principles

- **DRY:** Every feature reuses existing infrastructure. The `LocalDsp` chain, the ambience event system, the `SetBuilder`, the `ShowPreset` genre-rule pattern, and the `AudioAnalysisTap` are all foundations a new feature builds on rather than reimplements.
- **YAGNI:** Each feature implements only what is described. No "flexibility" for future requirements.
- **TDD:** Every task that produces code includes a test step. Pure logic is extracted into testable, Android-free objects.
- **Honest defaults:** Every feature is off by default. Nothing changes the sound or the lights until the listener turns it on.

## Verification

After each phase:
```bash
./gradlew :app:testMobileDebugUnitTest
./gradlew :app:lintMobileDebug
./gradlew :app:assembleMobileDebug
```

After all phases:
```bash
./gradlew :app:testMobileDebugUnitTest :app:lintMobileDebug :app:assembleMobileDebug
./gradlew :app:testTvDebugUnitTest :app:lintTvDebug :app:assembleTvDebug
```

Judge anything about how the app feels on a release build:
```bash
./gradlew :app:assembleMobileRelease
```