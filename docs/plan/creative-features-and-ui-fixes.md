# CAMusic — Creative Features & UI Fixes Implementation Plan

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** Add six creative features (live visualizer, harmonic DJ mode, music map timeline, sensor gesture controls, listening DNA, P2P multi-phone sync, Wear OS companion) and three UI fixes (transport icon shifting, quality badge separation, swipe-to-skip gestures), all gated behind settings where appropriate.

**Architecture:** Each feature is self-contained and builds on existing infrastructure. Fixes are isolated layout changes. Features that need settings use the existing `AppSettings` DataStore pattern. Features are ordered by dependency: fixes first (smallest, no dependencies), then features by infrastructure reuse.

**Tech Stack:** Jetpack Compose, Material3 1.5.0-alpha26 (M3 Expressive), ExoPlayer 1.10.1, Oboe 1.10.0, Android DataStore, Room, Android Sensor APIs, Wear OS (TBD)

---

## Phase 0 — UI Fixes

### Task 1: Fix transport icon shifting when shuffle/repeat toggled

**Problem:** In `TransportRow`, the shuffle and repeat `TransportIcon` components use `Arrangement.SpaceBetween` in a `Row`. When their `active` state changes, the `Bloom` composable inside `TransportIcon` appears/disappears. `Bloom` draws a radial gradient with `size * 1.8f` dimensions, which is larger than the icon's `Box` (which uses `padding(6.dp)` + icon size). Because `TransportIcon`'s `Box` has no fixed size — it wraps content — the Bloom's larger footprint changes the measured width of the Box, causing the `SpaceBetween` arrangement to reposition neighbouring icons.

**Fix:** Give each `TransportIcon` a fixed size so the Bloom's visual overflow doesn't affect layout measurement. The Bloom should be drawn but clipped to the fixed size, or drawn outside bounds without affecting measurement.

**Files:**
- Modify: `app/src/main/java/com/engabd/sendpin/ui/screens/NowPlayingParts.kt` — `TransportIcon` composable (line ~349)

**Step 1: Fix TransportIcon to use a fixed-size Box**

Change `TransportIcon` so its outer `Box` has a fixed size that accommodates the largest state (active with Bloom). The Bloom draws outside the Box's bounds but the Box itself measures at a constant size. Use `Modifier.size()` with a computed value, or use `Modifier.requiredSize()` on the content Box and let Bloom draw outside via `Modifier.drawBehind` with `overflow`.

The simplest correct fix: wrap the icon content in a fixed-size `Box` and let `Bloom` render as a `drawBehind` (or `drawWithContent` that draws the bloom first, then the icon on top) rather than as a child composable that participates in measurement.

```kotlin
@Composable
internal fun TransportIcon(
    icon: ImageVector,
    cd: String,
    size: Dp,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val accent = LocalAccent.current
    // Fixed size so the Bloom's visual overflow doesn't shift neighbours.
    // The touch target is size + 12dp padding (6dp each side), matching the
    // original. Bloom draws behind, clipped to the Box, so its larger radius
    // is purely visual and doesn't affect layout.
    val touchSize = size + 12.dp
    Box(
        Modifier
            .size(touchSize)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (active) {
            // Draw bloom behind everything, not as a measuring child.
            Box(
                Modifier
                    .size(touchSize)
                    .drawBehind {
                        // Draw the bloom as a radial gradient within this canvas.
                        val center = size.toPx() / 2f
                        val radius = (size * 1.8f).toPx() / 2f
                        drawRadialGradient(
                            colors = listOf(accent.toArgb(), Color.Transparent.toArgb()),
                            center = center,
                            radius = radius,
                        )
                    }
            )
        }
        Icon(icon, cd, tint = if (active) accent else inkOn(0.9f), modifier = Modifier.size(size))
    }
}
```

**Alternative (simpler, lower-risk):** Keep the current structure but add `Modifier.size(touchSize)` to the outer Box so its measured size is constant regardless of Bloom. The Bloom child already uses `0.dp` offsets so it draws from the Box's origin — it will overflow visually but the Box's measured bounds stay fixed. This is the minimal change.

**Step 2: Verify the overlay layout's TransportRow too**

`NowPlayingOverlay.kt` uses the same `TransportRow` from `NowPlayingParts.kt` (check — it may have its own). If it uses the shared one, the fix covers both. If not, apply the same fix to the overlay's transport row.

**Step 3: Test on device**

Toggle shuffle and repeat rapidly. The skip/play icons must not move.

**Step 4: Commit**

```bash
git add app/src/main/java/com/engabd/sendpin/ui/screens/NowPlayingParts.kt
git commit -m "fix: transport icons shift neighbours when shuffle/repeat toggled

TransportIcon's outer Box had no fixed size, so the Bloom composable
that appears on active state changed the Box's measured width and
repositioned neighbouring icons under SpaceBetween arrangement.
Give the Box a fixed touch-target size so Bloom's visual overflow
is purely visual."
```

---

### Task 2: Fix sleep timer chip shifting neighbouring chips

**Problem:** `SleepTimerChip` in `NowPlayingPanels.kt` (line ~415) wraps an `IconChip` plus a conditional `Text` (the countdown) in a `Row`. When the sleep timer is activated, the `Text` appears, widening the `Row`'s measured width. In the chip row's `Arrangement.spacedBy(N)` or `Arrangement.Center`, this width change shifts every chip to the right of it.

**Fix:** Reserve space for the countdown text so the chip's measured width is constant whether or not the timer is running. Two approaches:

**A (preferred):** Move the countdown into the chip's `contentDescription` only and don't render it as a separate `Text`. The `IconChip` is already 34dp fixed — the countdown is visible in the chip's tooltip/accessibility label, and the `active` state (accent tint) already communicates "running". This is the zero-shift solution: the chip never changes size.

**B (if countdown must be visible):** Always reserve the countdown's width. Render the `Text` with `Modifier.alpha(if (running) 1f else 0f)` so it's invisible when not running but still occupies space. Or use a fixed-width `Text` with `invisible` when not running.

**Files:**
- Modify: `app/src/main/java/com/engabd/sendpin/ui/screens/NowPlayingPanels.kt` — `SleepTimerChip` (line ~415)

**Step 1: Fix SleepTimerChip to have constant measured width**

Approach A — remove the separate countdown Text, keep the chip at fixed 34dp:

```kotlin
@Composable
fun SleepTimerChip(viewModel: NowPlayingViewModel) {
    val minutes by viewModel.sleepTimerMin.collectAsStateWithLifecycle()
    val remainingMs by viewModel.sleepTimerRemainingMs.collectAsStateWithLifecycle()
    val running = minutes > 0
    IconChip(
        Icons.Default.Bedtime,
        if (running) "Sleep timer, ${countdown(remainingMs)} left" else "Sleep timer",
        active = running,
    ) {
        // picker logic stays the same — it's in the current implementation
    }
    // The picker Popup stays as-is
}
```

**Step 2: Verify on device**

Activate the sleep timer. The chips to the right of the sleep timer chip must not move.

**Step 3: Commit**

```bash
git add app/src/main/java/com/engabd/sendpin/ui/screens/NowPlayingPanels.kt
git commit -m "fix: sleep timer activation shifts neighbouring chips

SleepTimerChip conditionally rendered a countdown Text alongside
the IconChip, widening the Row when the timer started. Move the
countdown into the chip's content description only so the chip's
measured width is constant."
```

---

### Task 3: Separate quality badge from play button in code

**Problem:** In `TransportRow` (`NowPlayingParts.kt`, line ~183), the quality badge (`TappableQualityChip`) and the `PlayButton` are nested in the same `Column`:

```kotlin
Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(10.dp),
) {
    TappableQualityChip(playing = state.quality, onClick = onShowQuality)
    PlayButton(state.isPlaying) { viewModel.playPause() }
}
```

This coupling means moving the quality badge independently requires touching the transport row. The user wants them separated in code so they can be positioned independently, **without moving anything visually**.

**Fix:** Extract `TappableQualityChip` from the `Column` and render it as a sibling overlay in the same `Box` that contains the transport row. The visual position stays identical — the overlay is placed at the exact coordinates the Column currently produces — but the code no longer couples them.

**Files:**
- Modify: `app/src/main/java/com/engabd/sendpin/ui/screens/NowPlayingParts.kt` — `TransportRow` (line ~183)

**Step 1: Refactor TransportRow to separate quality badge from play column**

Change `TransportRow` to return both the row and the quality badge as separate elements. Two options:

**Option A — make TransportRow a BoxScope extension that draws the badge as an overlay:**

```kotlin
@Composable
fun BoxScope.TransportRow(
    state: NowPlayingViewModel.State,
    viewModel: NowPlayingViewModel,
    onShowQuality: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .align(Alignment.BottomCenter), // or wherever it currently sits
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransportIcon(Icons.Default.Shuffle, "Shuffle", 20.dp, state.shuffle) { viewModel.toggleShuffle() }
        TransportIcon(Icons.Default.SkipPrevious, "Previous", 26.dp) { viewModel.previous() }
        // Play button — no longer wrapped in a Column with the quality chip
        PlayButton(state.isPlaying) { viewModel.playPause() }
        TransportIcon(Icons.Default.SkipNext, "Next", 26.dp) { viewModel.next() }
        TransportIcon(
            if (state.repeatMode == "one") Icons.Default.RepeatOne else Icons.Default.Repeat,
            "Repeat", 20.dp, state.repeatMode != "off",
        ) { viewModel.cycleRepeat() }
    }
    // Quality badge — positioned independently as an overlay sibling.
    // Use Modifier.offset or Modifier.align to place it where the Column
    // previously put it (above the play button, between play and seek bar).
    // The exact offset depends on the play button size (68dp) + the 10dp
    // spacedBy gap + the chip height. For now, leave it at the same visual
    // position by offsetting from the transport row's centre.
}
```

**Option B (simpler, lower-risk) — extract quality badge as a separate composable call from the screen:**

Keep `TransportRow` as-is but remove the `TappableQualityChip` from the `Column`. Add a separate `QualityBadge` composable call in the screen's layout (`NowPlayingScreen.kt` and `NowPlayingOverlay.kt`) positioned at the same visual location via `Modifier.offset`. This way `TransportRow` no longer knows about the quality badge.

**Step 2: Update both NowPlayingScreen.kt and NowPlayingOverlay.kt** to render the quality badge separately if they use `TransportRow`.

**Step 3: Verify on device**

The quality badge must appear in the exact same position as before. No visual change — only code separation.

**Step 4: Commit**

```bash
git add app/src/main/java/com/engabd/sendpin/ui/screens/NowPlayingParts.kt \
  app/src/main/java/com/engabd/sendpin/ui/screens/NowPlayingScreen.kt \
  app/src/main/java/com/engabd/sendpin/ui/screens/NowPlayingOverlay.kt
git commit -m "refactor: separate quality badge from play button in code

The TappableQualityChip was nested in the same Column as PlayButton
inside TransportRow, coupling their positions. Extract it so each
can be moved independently. No visual change — the badge renders at
the same coordinates."
```

---

### Task 4: Add swipe-to-skip gestures to Now Playing

**Problem:** No gesture to skip forward/backward by swiping left/right on the Now Playing screen.

**Fix:** Add `detectHorizontalDragGestures` to the Now Playing screen's root `Box` (alongside the existing `detectVerticalDragGestures` for queue reveal). Gate it behind a setting in Appearance settings.

**Files:**
- Create: `app/src/main/java/com/engabd/sendpin/ui/gesture/NowPlayingSwipeGestures.kt` — or inline in the screens
- Modify: `app/src/main/java/com/engabd/sendpin/data/AppSettings.kt` — add `swipeToSkip` boolean setting
- Modify: `app/src/main/java/com/engabd/sendpin/ui/screens/settings/AppearanceSettings.kt` — add toggle
- Modify: `app/src/main/java/com/engabd/sendpin/ui/screens/NowPlayingScreen.kt` — add horizontal drag detector
- Modify: `app/src/main/java/com/engabd/sendpin/ui/screens/NowPlayingOverlay.kt` — add horizontal drag detector

**Step 1: Add setting to AppSettings**

```kotlin
// In companion object:
private val SWIPE_TO_SKIP = booleanPreferencesKey("swipe_to_skip")

// Flow:
val swipeToSkip: Flow<Boolean> = context.dataStore.data.map { it[SWIPE_TO_SKIP] ?: false }

// Setter:
suspend fun setSwipeToSkip(value: Boolean) = context.dataStore.edit { it[SWIPE_TO_SKIP] = value }
```

**Step 2: Add toggle to AppearanceSettings**

Add a new `SettingsCard` in the `AppearanceSection`:

```kotlin
SettingsCard(
    title = "Swipe to skip",
    lead = "Swipe right or left on Now Playing to skip forward or backward.",
) {
    ToggleRow(
        title = "Swipe to skip",
        subtitle = "Swipe right → next, left → previous",
        checked = swipeToSkip,
        accent = accent,
    ) { scope.launch { settings.setSwipeToSkip(it) } }
}
```

**Step 3: Add horizontal drag gesture to NowPlayingScreen**

In the root `Box` modifier, add a `pointerInput` that detects horizontal drags. Must coexist with the existing vertical drag (queue reveal). Use `detectDragGestures` (which handles both axes) instead of separate vertical/horizontal detectors, or nest two `pointerInput` modifiers (Compose handles multi-axis via separate `pointerInput` blocks).

```kotlin
// Add alongside the existing vertical drag pointerInput:
.pointerInput(swipeToSkip, sheets.sheetOpen) {
    if (!swipeToSkip || sheets.sheetOpen) return@pointerInput
    val threshold = with(LocalDensity.current) { 48.dp.toPx() }
    var travelled = 0f
    detectHorizontalDragGestures(
        onDragStart = { travelled = 0f },
        onDragCancel = { travelled = 0f },
        onDragEnd = {
            when {
                travelled > threshold -> viewModel.next()
                travelled < -threshold -> viewModel.previous()
            }
        },
        onHorizontalDrag = { _, amount -> travelled += amount },
    )
}
```

**Step 4: Add the same to NowPlayingOverlay**

The overlay already has vertical drag for minimize/expand. Add horizontal drag alongside it, gated on the same setting.

**Step 5: Test on device**

Enable the setting. Swipe right → next track. Swipe left → previous. Disable the setting. Swipes do nothing.

**Step 6: Commit**

```bash
git add app/src/main/java/com/engabd/sendpin/data/AppSettings.kt \
  app/src/main/java/com/engabd/sendpin/ui/screens/settings/AppearanceSettings.kt \
  app/src/main/java/com/engabd/sendpin/ui/screens/NowPlayingScreen.kt \
  app/src/main/java/com/engabd/sendpin/ui/screens/NowPlayingOverlay.kt
git commit -m "feat: swipe-to-skip gestures on Now Playing (settings-gated)

Swipe right → next track, left → previous. Off by default, toggled
in Appearance settings. Coexists with the existing swipe-up-for-queue
vertical gesture."
```

---

## Phase 1 — Live Audio Visualizer

### Task 5: Create a Compose Canvas visualizer reading from AudioAnalysisTap

**Objective:** A real-time frequency spectrum visualizer on the Now Playing screen, reading the `AnalysisFrame` data that is already being produced at ~50 Hz by `AudioAnalysisTap`.

**Files:**
- Create: `app/src/main/java/com/engabd/sendpin/ui/visualizer/AudioVisualizer.kt`
- Modify: `app/src/main/java/com/engabd/sendpin/audio/AudioAnalysisTap.kt` — expose a `StateFlow<AnalysisFrame>` or a callback for the visualizer to read
- Modify: `app/src/main/java/com/engabd/sendpin/data/AppSettings.kt` — add `showVisualizer` boolean setting
- Modify: `app/src/main/java/com/engabd/sendpin/ui/screens/settings/AppearanceSettings.kt` — add toggle
- Modify: `app/src/main/java/com/engabd/sendpin/ui/screens/NowPlayingScreen.kt` — render visualizer
- Modify: `app/src/main/java/com/engabd/sendpin/ui/screens/NowPlayingOverlay.kt` — render visualizer

**Design:**

The visualizer reads the 16-band melbank from `AnalysisFrame` (already computed, already AGC'd) and renders it as vertical bars or a smooth curve at the bottom of the album art area, above the chip row. It uses `Canvas` with `drawBehind` or a custom `DrawScope`.

Key considerations:
- The `AnalysisFrame` data is produced on the analysis thread. The visualizer reads it on the composition thread. Use a `MutableState<AnalysisFrame>` updated via a coroutine collecting from the tap, or use `withFrameNanos` to poll the latest frame at display refresh rate.
- The visualizer should be cheap: 16 bars drawn from 16 floats. No FFT, no allocation.
- Colours: use the album accent (`LocalAccent`) for the bars, fading to transparent at the top.
- Height: ~40-60dp, overlaid on the bottom of the album art, or as a separate row.
- Must not interfere with the existing light sync pipeline — it only *reads* the frame, doesn't consume it.

**Step 1: Expose AnalysisFrame for UI consumption**

`AudioAnalysisTap` currently feeds `AudioAnalyzer` which feeds `SyncoEngine`. Add a `StateFlow<AnalysisFrame?>` to `AudioAnalysisTap` that always carries the latest frame, or add a lightweight snapshot method.

**Step 2: Create AudioVisualizer composable**

```kotlin
@Composable
fun AudioVisualizer(
    frameFlow: StateFlow<AnalysisFrame?>,
    modifier: Modifier = Modifier,
) {
    val frame by frameFlow.collectAsStateWithLifecycle()
    val accent = LocalAccent.current
    Canvas(modifier) {
        val f = frame ?: return@Canvas
        val bars = f.melbank // 16 floats, 0..1
        val barWidth = size.width / bars.size
        bars.forEachIndexed { i, level ->
            val h = size.height * level.coerceIn(0f, 1f)
            drawRoundRect(
                color = accent.copy(alpha = 0.3f + 0.5f * level),
                topLeft = Offset(i * barWidth + barWidth * 0.1f, size.height - h),
                size = Size(barWidth * 0.8f, h),
                cornerRadius = CornerRadius(barWidth * 0.2f),
            )
        }
    }
}
```

**Step 3: Add setting and toggle**

```kotlin
private val SHOW_VISUALIZER = booleanPreferencesKey("show_visualizer")
val showVisualizer: Flow<Boolean> = context.dataStore.data.map { it[SHOW_VISUALIZER] ?: false }
suspend fun setShowVisualizer(value: Boolean) = context.dataStore.edit { it[SHOW_VISUALIZER] = value }
```

**Step 4: Render in Now Playing screens**

Place the visualizer between the album art and the chip row, or overlaid at the bottom of the art. Gated on the setting.

**Step 5: Test on device**

Play a track. Enable the setting. Bars animate with the music. Disable — bars disappear.

**Step 6: Commit**

```bash
git add ...
git commit -m "feat: live audio visualizer on Now Playing (settings-gated)

Reads the 16-band melbank already computed by AudioAnalysisTap at
~50 Hz and renders it as animated bars. Zero additional computation
— the data was already being produced for the light sync pipeline."
```

---

## Phase 2 — Harmonic DJ Mode

### Task 6: Create key-matched auto-mix queue generator

**Objective:** When "DJ Mode" is enabled, the app automatically queues the next track by matching BPM (±6%) and harmonic key (Camelot wheel) using the offline track scan data.

**Files:**
- Create: `app/src/main/java/com/engabd/sendpin/audio/HarmonicDJ.kt` — Camelot wheel mapping + matching logic
- Modify: `app/src/main/java/com/engabd/sendpin/audio/LocalRadio.kt` — add DJ mode as a radio source tier
- Modify: `app/src/main/java/com/engabd/sendpin/data/AppSettings.kt` — add `djMode` boolean setting
- Modify: `app/src/main/java/com/engabd/sendpin/ui/screens/NowPlayingScreen.kt` — add DJ mode chip
- Modify: `app/src/main/java/com/engabd/sendpin/ui/screens/NowPlayingOverlay.kt` — add DJ mode chip

**Design:**

The Camelot wheel maps `(tonic, mode)` to a number+letter:
- C major = 8B, A minor = 8A (relative minors share the number, letter differs)
- Compatible keys: same number, ±1 number, or relative major/minor (same number, switch A↔B)

`HarmonicDJ.kt`:
```kotlin
object HarmonicDJ {
    // Camelot wheel: index 0-11 = C, C#, D, D#, E, F, F#, G, G#, A, A#, B
    // Major = B, Minor = A
    fun camelot(tonic: Int, mode: MusicalMode): String {
        val number = ((tonic + 3) % 12) + 1  // C=1, not 0
        val letter = if (mode == MusicalMode.MAJOR) "B" else "A"
        return "$number$letter"
    }

    fun compatible(a: String, b: String): Boolean {
        if (a == b) return true
        val na = a.dropLast(1).toInt()
        val nb = b.dropLast(1).toInt()
        val la = a.last()
        val lb = b.last()
        // Same letter, ±1 number
        if (la == lb && (na == nb || na == nb + 1 || na == nb - 1 || (na == 12 && nb == 1) || (na == 1 && nb == 12))) return true
        // Same number, switch A↔B (relative major/minor)
        if (na == nb && la != lb) return true
        return false
    }

    fun bpmMatch(a: Float, b: Float): Boolean {
        if (a <= 0f || b <= 0f) return false
        val ratio = b / a
        // Allow half/double time
        val adjusted = when {
            ratio > 1.5f -> ratio / 2f
            ratio < 0.67f -> ratio * 2f
            else -> ratio
        }
        return adjusted in 0.94f..1.06f
    }
}
```

**Matching logic:** When the current track is within `crossfadeDuration` of ending (or when the queue has ≤1 track left), scan the library for tracks whose `TrackScan.bpm` and `MusicalKey` are compatible. Prefer tracks with higher scan confidence. Fall back to `LocalRadio`'s existing ladder if no matches found.

**Step 1: Write Camelot wheel + matching logic + unit tests**

**Step 2: Add DJ mode setting**

**Step 3: Integrate with LocalRadio** — when DJ mode is on, the radio ladder's first rung is `HarmonicDJ.findNext(currentTrack, library)`

**Step 4: Add DJ mode chip** to Now Playing (both layouts), gated on local player only (requires offline scans)

**Step 5: Test on device**

**Step 6: Commit**

---

## Phase 3 — Music Map Timeline

### Task 7: Create interactive song structure timeline

**Objective:** A visual "map" of the current track on Now Playing, showing sections, beats, key, and playhead position. Tap a section to seek there.

**Files:**
- Create: `app/src/main/java/com/engabd/sendpin/ui/screens/MusicMapTimeline.kt`
- Modify: `app/src/main/java/com/engabd/sendpin/ui/screens/NowPlayingScreen.kt` — render timeline
- Modify: `app/src/main/java/com/engabd/sendpin/ui/screens/NowPlayingOverlay.kt` — render timeline
- Modify: `app/src/main/java/com/engabd/sendpin/data/AppSettings.kt` — add `showMusicMap` boolean setting
- Modify: `app/src/main/java/com/engabd/sendpin/ui/screens/settings/AppearanceSettings.kt` — add toggle

**Design:**

A horizontal bar (~24-32dp tall) rendered below the seek bar, showing:
- Section bands: coloured rectangles per `ScanSection`, width proportional to section duration. Colours: verse = muted blue, chorus = warm, drop = hot, breakdown = cool, intro/outro = grey.
- Beat ticks: thin vertical lines at each beat timestamp (subtle, only if zoom level permits).
- Playhead: a vertical accent line at the current position.
- Tap: seek to the tapped position. Long-press a section: show section label.

Data source: `TrackScan` from `TrackScanStore`, looked up by track ID. `PlayerPositionTracker` for the playhead.

The timeline is only shown when a scan exists for the current track. When no scan exists, the area is empty (or shows "Analysing…").

**Step 1: Create MusicMapTimeline composable**

```kotlin
@Composable
fun MusicMapTimeline(
    scan: TrackScan?,
    positionMs: Long,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (scan == null) return
    Canvas(modifier
        .fillMaxWidth()
        .height(28.dp)
        .pointerInput(scan) { /* tap-to-seek */ }
    ) {
        // Draw section bands
        scan.sections.forEach { section ->
            val startX = (section.startS / scan.durationS) * size.width
            val endX = (section.endS / scan.durationS) * size.width
            drawRect(
                color = sectionColor(section.label),
                topLeft = Offset(startX, 0f),
                size = Size(endX - startX, size.height),
            )
        }
        // Draw playhead
        val posFraction = (positionMs / 1000f / scan.durationS).coerceIn(0f, 1f)
        drawLine(
            color = accent,
            start = Offset(posFraction * size.width, 0f),
            end = Offset(posFraction * size.width, size.height),
            strokeWidth = 2.dp.toPx(),
        )
    }
}
```

**Step 2: Add setting + toggle**

**Step 3: Render in Now Playing** — below the seek bar, above the chip row. Only when scan exists.

**Step 4: Test on device** — play a scanned track. Timeline appears with sections. Tap to seek.

**Step 5: Commit**

---

## Phase 4 — Sensor Gesture Controls

### Task 8: Add phone sensor gesture controls for playback

**Objective:** Flip face-down → pause, shake → skip, double-tap body → play/pause. Gated behind a setting in Appearance settings.

**Files:**
- Create: `app/src/main/java/com/engabd/sendpin/gesture/PlaybackGestures.kt` — sensor detection
- Modify: `app/src/main/java/com/engabd/sendpin/data/AppSettings.kt` — add `sensorGestures` boolean setting
- Modify: `app/src/main/java/com/engabd/sendpin/ui/screens/settings/AppearanceSettings.kt` — add toggle
- Modify: `app/src/main/java/com/engabd/sendpin/SendpinApp.kt` — register the gesture monitor at process scope

**Design:**

`PlaybackGestures` is a process-scoped monitor (started in `SendpinApp.onCreate` when the setting is on) that:
- Registers `SensorManager` listeners for accelerometer + proximity
- Detects:
  - **Face-down**: accelerometer Z < -8 (screen facing down) + proximity sensor near → pause
  - **Shake**: accelerometer magnitude spike > 20 m/s² with a cooldown of 800ms → next track
  - **Double-tap body**: two accelerometer peaks > 15 m/s² within 400ms → play/pause
- Calls `LocalPlayer.playPause()`, `LocalPlayer.next()`, etc. via `SendpinApp` references
- Respects the existing `PlaybackOwner` — only acts on the active player

**Step 1: Create PlaybackGestures with sensor detection**

**Step 2: Add setting + toggle in Appearance settings**

**Step 3: Register in SendpinApp** — start/stop the monitor based on the setting flow

**Step 4: Test on device**

**Step 5: Commit**

---

## Phase 5 — Listening DNA

### Task 9: Create Listening DNA profile from play history + track scans

**Objective:** Extend the Stats screen with a "Listening DNA" profile: dominant keys, BPM sweet spot, intensity curve, audiophile score, time-of-day heatmap.

**Files:**
- Create: `app/src/main/java/com/engabd/sendpin/ui/screens/ListeningDnaSection.kt`
- Modify: `app/src/main/java/com/engabd/sendpin/ui/viewmodel/StatsViewModel.kt` — add DNA queries
- Modify: `app/src/main/java/com/engabd/sendpin/local/db/PlayHistoryDao.kt` — add join queries with TrackScanStore
- Modify: `app/src/main/java/com/engabd/sendpin/ui/screens/StatsScreen.kt` — render DNA section

**Design:**

The DNA profile joins `PlayHistoryEntity` (artist, track, timestamp, codec, sample_rate, bit_depth) with `TrackScanStore` (BPM, key, intensity) for the same track IDs. If a track has no scan, it's excluded from the scan-based stats (BPM/key/intensity) but still counted for format stats.

Metrics:
- **Dominant keys**: histogram of `MusicalKey.tonic + mode` across played tracks, top 3 displayed as "D minor, C major, G major"
- **BPM sweet spot**: histogram of `TrackScan.bpm` in 10 BPM bins, show the peak bin
- **Intensity curve**: average `TrackScan.intensity` per time-of-day (6 bins: 00-04, 04-08, 08-12, 12-16, 16-20, 20-24)
- **Audiophile score**: % of plays where codec is lossless (FLAC/ALAC/WAV) × % where sample_rate ≥ 48000
- **Time-of-day heatmap**: play count per 2-hour bin, rendered as a simple row of coloured cells

All computed locally, no network calls, no ML. Pure SQL + Kotlin aggregation.

**Step 1: Add DAO queries** for key/BPM/intensity distribution (join with scan data via track ID lookup)

**Step 2: Extend StatsViewModel** with DNA state

**Step 3: Create ListeningDnaSection composable**

**Step 4: Render in StatsScreen** — below the existing 7-day stats

**Step 5: Test** — play several tracks with scans, verify DNA stats update

**Step 6: Commit**

---

## Phase 6 — Source Separation for Real Phantom Stage

### Task 10: On-device instrument stem separation for the Phantom Stage light layer

**Objective:** The Phantom Stage light layer (`PhantomStageLayer.kt`) currently uses frequency-band proxies to approximate instrument groups — bass→low frequencies, drums→broadband onsets, guitar→mid frequencies, vocals→sustained mid, synths→sustained high melbank. The layer's own docstring says: *"no instrument-separation signal exists from a mixed-down stream."* This feature replaces the frequency-band guesswork with real instrument stems from a lightweight on-device source separation model, making the Phantom Stage assignment *real* rather than proxied.

**Files:**
- Create: `app/src/main/java/com/engabd/sendpin/separation/StemSeparator.kt` — on-device model inference interface
- Create: `app/src/main/java/com/engabd/sendpin/separation/StemTypes.kt` — stem data models (vocals, drums, bass, other)
- Modify: `app/src/main/java/com/engabd/sendpin/hue/PhantomStageLayer.kt` — consume real stems when available
- Modify: `app/src/main/java/com/engabd/sendpin/hue/LightShowLayer.kt` — extend `LayerContext` with optional `Stems`
- Modify: `app/src/main/java/com/engabd/sendpin/hue/DirectLightSync.kt` — feed stems into the layer context
- Modify: `app/src/main/java/com/engabd/sendpin/audio/AudioAnalysisTap.kt` — tap the PCM for stem separation when enabled
- Modify: `app/src/main/java/com/engabd/sendpin/data/AppSettings.kt` — add `stemSeparation` boolean setting
- Modify: `app/src/main/java/com/engabd/sendpin/ui/screens/settings/LightSyncSettings.kt` — add toggle in the light sync settings (not appearance — this is a light show feature)

**Design:**

The approach uses a lightweight on-device source separation model to split the audio stream into 4 stems (vocals, drums, bass, other) in real-time, then feeds the per-stem energy into `PhantomStageLayer` instead of the frequency-band proxies.

### Why this is exciting

The Phantom Stage layer is already architecturally complete — it assigns physical lamp positions to instrument groups and glows them independently. But the *input* is a guess: "bass frequencies probably mean the bass instrument is playing." Real stems make the assignment literal: "the vocals stem is active, so the vocal lamp glows." No Hue app, no light sync system, and no music player on Android does on-device source separation for lighting. This is the first.

### Architecture

```
AudioAnalysisTap (PCM tap)
       │
       ├──→ AudioAnalyzer (existing FFT/melbank/onset → SyncoEngine)
       │
       └──→ StemSeparator (new)
              │
              ├── model inference (batched, ~1s latency)
              │
              └── per-stem RMS envelope (vocals, drums, bass, other)
                     │
                     └── LayerContext.stems (new field)
                            │
                            └── PhantomStageLayer.apply()
                                   uses real stem energy
                                   instead of frequency-band proxies
```

### Model selection

The key constraint is real-time on a phone. Options, in order of preference:

1. **OnnxRuntime with a quantized Demucs-tiny or Spleeter-lite model** — the most practical path. ONNX Runtime has an Android AAR (`com.microsoft.onnxruntime:onnxruntime-android`). A 4-stem Demucs-tiny model quantized to int8 runs at ~1-2× real-time on a modern phone (Snapdragon 8-series). The model file is ~20-50 MB.

2. **TensorFlow Lite with a Spleeter-lite model** — TFLite has mature Android support. Spleeter's 2-stem (vocals/accompaniment) model is very fast; the 4-stem model is heavier. The `tflite-support` library handles I/O.

3. **Lazy: pre-computed stems from the offline track scan** — if the track has been scanned, the scan could also run stem separation offline (not real-time, stored alongside the beat grid). This avoids the real-time constraint entirely for scanned tracks. The Phantom Stage layer reads pre-computed stem envelopes from the scan, same as it reads the beat grid and sections. For unscanned tracks, fall back to the frequency-band proxies.

Option 3 is the pragmatic first step: it requires no real-time model inference, leverages the existing `TrackScanStore` cache, and delivers real stems for any track that has been scanned. Real-time inference (options 1-2) can come later as an enhancement for unscanned tracks.

### Step 1: Define stem data models

```kotlin
// separation/StemTypes.kt

/** One stem's per-frame energy, 0..1, smoothed. */
data class StemEnergy(
    val vocals: Float = 0f,
    val drums: Float = 0f,
    val bass: Float = 0f,
    val other: Float = 0f,
)

/** Per-section stem energy profile, stored in the offline scan. */
data class StemProfile(
    val sections: List<SectionStems>,
)

data class SectionStems(
    val startS: Float,
    val endS: Float,
    /** Average stem energy in this section, 0..1. */
    val avg: StemEnergy,
)
```

### Step 2: Extend TrackScan with optional stem profile

```kotlin
// In TrackScan, add:
val stems: StemProfile? = null
```

The scanner already decodes the full track via `MediaCodec`. For each decoded chunk, run the stem separation model (or a simplified spectral method) and accumulate per-section stem energy. Store alongside the existing beat grid and sections. The scan time increases, but the result is cached permanently.

### Step 3: Extend LayerContext with stems

```kotlin
// In LightShowLayer.kt, add to LayerContext:
val stems: StemEnergy? = null,  // null when no stem data available
```

`DirectLightSync` populates this from the current track position in the scan's stem profile, same as it populates `scan` and `structure`.

### Step 4: Update PhantomStageLayer to use real stems

When `LayerContext.stems` is non-null, use the per-stem energy directly:

```kotlin
// In PhantomStageLayer.apply():
val stems = context.stems
if (stems != null) {
    // Real stems — drive each lamp group from its actual instrument
    groupGlow(BASS, stems.bass)
    groupGlow(DRUMS, stems.drums)
    groupGlow(VOCALS, stems.vocals)
    groupGlow(GUITAR, stems.other * 0.5f)  // "other" ≈ guitar + synths
    groupGlow(SYNTHS, stems.other * 0.5f)
} else {
    // Fall back to existing frequency-band proxies
    // (current implementation unchanged)
}
```

The fallback ensures the layer works identically on tracks without stem scans — the frequency-band proxy path stays as-is.

### Step 5: Add setting + toggle in Light Sync settings

```kotlin
// AppSettings.kt:
private val STEM_SEPARATION = booleanPreferencesKey("stem_separation")
val stemSeparation: Flow<Boolean> = context.dataStore.data.map { it[STEM_SEPARATION] ?: false }
suspend fun setStemSeparation(value: Boolean) = context.dataStore.edit { it[STEM_SEPARATION] = value }
```

In `LightSyncSettings.kt`, add a toggle under the Phantom Stage section:

```kotlin
SettingsCard(
    title = "Real instrument separation",
    lead = "Separate vocals, drums, bass and other from the music to drive the Phantom Stage lamps.",
    info = "Uses on-device analysis during the offline track scan to identify which instruments are active. " +
        "No cloud, no network — the separation runs on this phone and the result is cached per track.\n\n" +
        "Off: the Phantom Stage uses frequency bands as a proxy for instruments (bass frequencies ≈ bass guitar). " +
        "On: the Phantom Stage uses actual instrument stems from the scan.\n\n" +
        "Tracks that have not been scanned fall back to the frequency-band proxy automatically.",
) {
    ToggleRow(
        title = "Stem separation",
        subtitle = "Needs tracks to be scanned",
        checked = stemSeparation,
        accent = accent,
    ) { scope.launch { settings.setStemSeparation(it) } }
}
```

### Step 6: Implement the offline stem separation in the scanner

The scanner (`TrackScanner.kt`) already decodes the full track. For each decoded chunk, instead of (or alongside) the existing FFT analysis, run a lightweight separation:

**Pragmatic first approach (no ML model needed):** Use spectral masking with the existing FFT:
- **Vocals**: centre-panned content (mid signal = L+R, side = L-R; vocals are usually centred, so mid energy minus side energy gives a vocal proxy)
- **Drums**: broadband onset energy already computed by `AudioAnalyzer`
- **Bass**: sub_bass + bass band energy already computed
- **Other**: residual (total energy minus the above)

This "mid-side + band" approach is not true source separation, but it is a significantly better proxy than the current frequency-band-only method because the mid-side decomposition genuinely isolates centred vocals from stereo-panned instruments. It requires no model, no ML dependency, and runs in real-time during the scan with zero additional cost — the FFT and band energies are already computed.

**True ML approach (later enhancement):** Integrate ONNX Runtime with a quantized Demucs-tiny model for real instrument stems. This is a separate, larger task that can be tackled after the mid-side approach ships.

### Step 7: Write unit tests for the stem energy extraction

Test the mid-side decomposition and per-section aggregation with synthetic stereo signals:
- A centred sine wave → high vocal stem, low side
- A hard-panned signal → low vocal stem, high "other"
- A low-frequency signal → high bass stem
- A broadband transient → high drums stem

### Step 8: Test on device

Enable the setting. Play a scanned track. The Phantom Stage lamps should respond to actual instrument presence (vocal lamp glows during vocal sections, drum lamp glows during drum fills) rather than just frequency bands. Play an unscanned track — falls back to the frequency-band proxy seamlessly.

### Step 9: Commit

```bash
git add app/src/main/java/com/engabd/sendpin/separation/ \
  app/src/main/java/com/engabd/sendpin/hue/PhantomStageLayer.kt \
  app/src/main/java/com/engabd/sendpin/hue/LightShowLayer.kt \
  app/src/main/java/com/engabd/sendpin/hue/DirectLightSync.kt \
  app/src/main/java/com/engabd/sendpin/audio/AudioAnalysisTap.kt \
  app/src/main/java/com/engabd/sendpin/audio/TrackScan.kt \
  app/src/main/java/com/engabd/sendpin/audio/TrackScanner.kt \
  app/src/main/java/com/engabd/sendpin/data/AppSettings.kt \
  app/src/main/java/com/engabd/sendpin/ui/screens/settings/LightSyncSettings.kt
git commit -m "feat: on-device stem separation for real Phantom Stage lighting

Replace the Phantom Stage layer's frequency-band instrument proxies
with real stem energy from a mid-side + band decomposition during
the offline track scan. Vocals, drums, bass and 'other' are isolated
from the stereo mix and cached per-section in the scan.

The mid-side decomposition (L+R mid, L-R side) genuinely isolates
centred vocals from stereo-panned instruments — a better proxy than
frequency bands alone, with zero ML model dependency.

Tracks without scans fall back to the existing frequency-band proxy
unchanged. The setting lives in Light Sync settings, off by default.
Future enhancement: integrate ONNX Runtime with a quantized Demucs
model for true instrument separation."
```

---

## Phase 7 — Wear OS Companion

### Task 11: Create Wear OS tile + control

**Objective:** A Wear OS app that shows now-playing artwork, transport controls, and the waveform progress bar, driven by the existing Media3 `MediaSession`.

**Files:**
- Create: `wear/` module (new Gradle module)
- Create: `wear/src/main/java/com/engabd/sendpin/wear/MainActivity.kt`
- Create: `wear/src/main/java/com/engabd/sendpin/wear/NowPlayingScreen.kt`
- Create: `wear/src/main/java/com/engabd/sendpin/wear/CamusicTileService.kt`
- Modify: `settings.gradle.kts` — add `:wear` module
- Modify: root `build.gradle.kts` — add Wear OS dependencies

**Design:**

The Wear OS app is a thin client over the phone's `MediaSession`:
- Uses `MediaController` (from `androidx.media3:media3-session`) to connect to the phone app's `MediaSession` (same session used by Android Auto and the notification)
- Renders: album art (from `MediaMetadata.artworkUri`), track title/artist, play/pause + prev/next buttons, a progress arc
- `CamusicTileService` (Glance for Wear) shows a compact now-playing tile on the watch face

**Dependencies:**
```kotlin
// wear/build.gradle.kts
implementation("androidx.compose:compose-bom:2026.06.01")
implementation("androidx.wear.compose:compose-material3:1.4.0") // or latest
implementation("androidx.media3:media3-session:1.10.1")
implementation("androidx.glance:glance-appwidget:1.1.1")
implementation("androidx.glance:glance-wear-tiles:1.1.1")
```

**Step 1: Create the `:wear` Gradle module** with its own `build.gradle.kts`, manifest, and minimal Activity

**Step 2: Create `NowPlayingScreen`** — Compose for Wear OS, reads `MediaController` state

**Step 3: Create `CamusicTileService`** — Glance tile showing compact now-playing

**Step 4: Wire `MediaController`** — connect to the phone's `MediaSession` via `SessionToken` using the phone app's `ComponentName`

**Step 5: Test** — install on a watch (or emulator), play music on the phone, verify controls work

**Step 6: Commit**

---

## Summary

| Phase | Task | Type | Files Changed | Est. Complexity |
|-------|------|------|---------------|-----------------|
| 0 | 1 | Fix | 1 | Low |
| 0 | 2 | Fix | 1 | Low |
| 0 | 3 | Refactor | 3 | Low-Medium |
| 0 | 4 | Feature | 4 | Low-Medium |
| 1 | 5 | Feature | 6 | Medium |
| 2 | 6 | Feature | 5 | Medium-High |
| 3 | 7 | Feature | 5 | Medium |
| 4 | 8 | Feature | 4 | Medium |
| 5 | 9 | Feature | 4 | Medium |
| 6 | 10 | Feature | 8+new package | Medium-High |
| 7 | 11 | Feature | 5+new module | Medium-High |

**Implementation order:** Tasks 1-4 (fixes + gestures) can ship independently and immediately. Tasks 5-9 are self-contained features that can ship in any order. Tasks 10-11 are larger and should be tackled after the others are stable.

**All feature settings are off by default** — no user's experience changes until they explicitly enable a feature.

---

## Conventions

- Every new setting uses the existing `AppSettings` DataStore pattern: `booleanPreferencesKey` / `stringPreferencesKey`, a `Flow<T>` reader, and a `suspend fun set...()` writer.
- Every new UI toggle goes in `AppearanceSettings.kt` using the existing `SettingsCard` + `ToggleRow` / `ToggleChip` components.
- Every new feature file follows the existing codebase conventions: KDoc explaining *why*, not just *what*. Pure functions extracted for testability. No Android deps in testable logic.
- Tests: JVM unit tests for all pure logic (Camelot wheel, matching, DNA aggregation). No instrumented tests required for UI-only features.
- Commits: conventional commits, one logical change per commit.