# CAMusic — Focus Feature Implementation Plan

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** Implement the seven focus areas Master Abdullah selected: (1) Wear OS companion, (2) finish true 24-bit bit-perfect AAudio output without conflicting with existing high-resolution/exclusive-output toggles, (3) rhythm tiles/note highway game, (4) improve "listen to other devices" so it can capture the phone's own audio output like Samsung Music Sync, (5) sonic similarity across local libraries, (6) simple/advanced settings split + onboarding polish, and (7) ambient visual shows on Android TV and webOS screens plus user-correctable album-art colour palettes persisted into offline scans.

**Architecture:** Each feature is a vertical slice that reuses existing infrastructure: `AudioAnalyzer`/`AnalysisFrame` for the rhythm game, `DirectLightSync`/`SyncoEngine` for ambient TV/webOS visuals, `TrackScanStore` for user colour overrides, `SendspinNativeOutput`/Oboe C++ engine for the 24-bit path, `MediaProjection`/`AudioPlaybackCapture` for output capture, `Media3`/`MediaSession` for Wear OS, and the existing `MusicSource` + `TrackScan` data for local sonic similarity.

**Tech Stack:** Jetpack Compose/Material3 (phone/TV), media3/ExoPlayer, Oboe/AAudio NDK, Wear OS SDK, Hue Entertainment DTLS, Kotlin coroutines, Room/DataStore.

---

## How to use this plan

- Each numbered section is one focus area.
- Within each section, tasks are bite-sized (2–15 minutes of focused work).
- File paths are relative to `app/src/main/java/com/engabd/sendpin/` unless otherwise stated.
- Run `./gradlew :app:testMobileDebugUnitTest` after each commit unless a different test command is given.
- The app already uses `mobile` and `tv` product flavors; Wear OS will add a `wear` flavor.

---

## 1. Wear OS companion

A Wear OS tile/app that shows now-playing metadata and transport controls over the existing `MediaSession`. The phone app already exposes a media session via `LocalPlaybackService`/`RemoteSessionPlayer`; Wear OS can use `androidx.wear.media`/`MediaController` to talk to it without a custom socket.

### Task 1.1: Create the `:wear` module skeleton

**Objective:** Add a Wear OS application module that shares the core media session contract.

**Files:**
- Create: `wear/build.gradle.kts`
- Create: `wear/src/main/AndroidManifest.xml`
- Create: `wear/src/main/java/com/engabd/sendpin/wear/WearMusicApplication.kt`
- Modify: `settings.gradle.kts`
- Modify: `build.gradle.kts` (if needed for a Wear OS plugin alias)

**Step 1: Add the module to settings**

```kotlin
// settings.gradle.kts
include(":app", ":baselineprofile", ":wear")
```

**Step 2: Create `wear/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.engabd.sendpin.wear"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.engabd.sendpin"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation("androidx.wear.compose:compose-material:1.4.0")
    implementation("androidx.wear.compose:compose-foundation:1.4.0")
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.lifecycle.runtime.compose)
}
```

Use the project's actual BOM/library aliases; adjust version to match `libs.versions.toml`.

**Step 3: Wear manifest**

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-feature android:name="android.hardware.type.watch" />
    <application
        android:name=".WearMusicApplication"
        android:label="CAMusic"
        android:icon="@mipmap/ic_launcher">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:theme="@android:style/Theme.DeviceDefault">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

**Step 4: Empty application class**

```kotlin
package com.engabd.sendpin.wear

import android.app.Application

class WearMusicApplication : Application()
```

**Verification:** `./gradlew :wear:assembleDebug` compiles.

---

### Task 1.2: Discover and bind to the phone's MediaSession

**Objective:** Let the Wear app find and control CAMusic's existing session.

**Files:**
- Create: `wear/src/main/java/com/engabd/sendpin/wear/SessionConnection.kt`
- Create: `wear/src/main/java/com/engabd/sendpin/wear/MainActivity.kt`

**Step 1: SessionConnection helper**

```kotlin
package com.engabd.sendpin.wear

import android.content.ComponentName
import android.content.Context
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SessionConnection(context: Context) {
    private val _controller = MutableStateFlow<MediaController?>(null)
    val controller: StateFlow<MediaController?> = _controller

    private val future: ListenableFuture<MediaController>

    init {
        val token = SessionToken(context, ComponentName("com.engabd.sendpin", "com.engabd.sendpin.service.LocalPlaybackService"))
        future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            _controller.value = try { future.get() } catch (_: Exception) { null }
        }, MoreExecutors.directExecutor())
    }

    fun release() {
        _controller.value?.release()
        _controller.value = null
        future.cancel(false)
    }
}
```

Verify the exact service class name from the phone manifest; adjust if `RemoteSessionPlayer` is the session owner instead.

**Step 2: Minimal MainActivity**

```kotlin
package com.engabd.sendpin.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.MaterialTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val connection = SessionConnection(this)
        setContent {
            MaterialTheme {
                val controller = connection.controller.collectAsStateWithLifecycle().value
                Text("Connected: ${controller != null}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
```

Add the missing `import androidx.compose.material3.Text` or use Wear's `Text`.

**Verification:** Build succeeds; no runtime test possible without a watch emulator.

---

### Task 1.3: Wear Now Playing UI (artwork + transport)

**Objective:** Render cover, title, artist, and a play/pause + skip row suitable for a round watch.

**Files:**
- Create: `wear/src/main/java/com/engabd/sendpin/wear/WearNowPlaying.kt`
- Modify: `wear/src/main/java/com/engabd/sendpin/wear/MainActivity.kt`

**Step 1: Observe metadata and playback state**

```kotlin
@Composable
fun WearNowPlaying(controller: MediaController?) {
    val metadata by remember(controller) {
        controller ?: return@remember flowOf(null)
        callbackFlow {
            val listener = object : MediaController.Listener {
                override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                    trySend(mediaMetadata)
                }
            }
            controller.addListener(listener)
            trySend(controller.mediaMetadata)
            awaitClose { controller.removeListener(listener) }
        }
    }.collectAsStateWithLifecycle(initialValue = null)

    val isPlaying by remember(controller) {
        controller ?: return@remember flowOf(false)
        callbackFlow {
            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) { trySend(isPlaying) }
            }
            controller.addListener(listener)
            trySend(controller.isPlaying)
            awaitClose { controller.removeListener(listener) }
        }
    }.collectAsStateWithLifecycle(initialValue = false)

    // layout below
}
```

Use Coil to load artwork. On a small screen, show title/artist above the controls and a circular play button.

**Step 2: Transport buttons**

```kotlin
Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    CompactButton(onClick = { controller?.seekToPreviousMediaItem() }, imageVector = Icons.Default.SkipPrevious)
    CompactButton(onClick = { if (isPlaying) controller?.pause() else controller?.play() }, imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow)
    CompactButton(onClick = { controller?.seekToNextMediaItem() }, imageVector = Icons.Default.SkipNext)
}
```

**Step 3: Volume buttons (optional)**

Add `+` / `-` buttons that call `controller?.setVolume(volume +/- 0.1f)` if volume control is supported by the session.

**Verification:** `./gradlew :wear:assembleDebug` succeeds; lint check `./gradlew :wear:lintDebug` has no new errors.

---

### Task 1.4: Add a Wear OS tile for quick transport

**Objective:** Surface play/pause and next on the watch face without opening the app.

**Files:**
- Create: `wear/src/main/java/com/engabd/sendpin/wear/tile/MusicTileService.kt`
- Modify: `wear/src/main/AndroidManifest.xml`

**Step 1: Tile service skeleton**

```kotlin
package com.engabd.sendpin.wear.tile

import androidx.wear.tiles.TileService
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.LayoutElementBuilders
import androidx.wear.tiles.ResourceBuilders
import androidx.wear.tiles.request.ResourcesRequest
import androidx.wear.tiles.request.TileRequest
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

class MusicTileService : TileService() {
    override fun onTileRequest(requestParams: TileRequest): ListenableFuture<TileBuilders.Tile> {
        val play = LayoutElementBuilders.Builder()
            .setPrimaryChip(
                LayoutElementBuilders.PrimaryChip.Builder()
                    .setText(LayoutElementBuilders.Text.Builder().setText("Play / Pause").build())
                    .setClickable(...)
                    .build()
            )
            .build()
        return Futures.immediateFuture(TileBuilders.Tile.Builder().setResourcesVersion("1").setTimeline(play).build())
    }

    override fun onResourcesRequest(requestParams: ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(ResourceBuilders.Resources.Builder().build())
}
```

**Step 2: Register service in manifest**

```xml
<service android:name=".tile.MusicTileService"
    android:permission="com.google.android.wearable.permission.BIND_TILE_PROVIDER"
    android:exported="true">
    <intent-filter>
        <action android:name="androidx.wear.tiles.action.BIND_TILE_PROVIDER" />
    </intent-filter>
    <meta-data android:name="androidx.wear.tiles.PREVIEW" android:resource="@drawable/tile_preview" />
</service>
```

**Verification:** `./gradlew :wear:assembleDebug` succeeds.

---

### Task 1.5: Phone-side verification that MediaSession exposes enough

**Objective:** Confirm the existing session publishes metadata, artwork, and transport. Most of this is already done; the task is to test and document any gap.

**Files:**
- Inspect: `service/LocalPlaybackService.kt`
- Inspect: `service/RemoteSessionPlayer.kt`
- Test: `app/src/test/java/com/engabd/sendpin/service/MediaSessionExportTest.kt` (create if missing)

**Step 1: Read the existing service and verify it sets**
- `setMediaMetadata` with title, artist, album, artwork
- `setPlaybackState` with correct actions (`ACTION_PLAY_PAUSE`, `ACTION_SKIP_TO_NEXT`, `ACTION_SKIP_TO_PREVIOUS`, `ACTION_SEEK_TO`)
- `setSessionActivity` pointing back to the app

If any action is missing, add it.

**Step 2: Add a unit test if the service can be instantiated in Robolectric; otherwise document manual verification steps.**

Manual verification: install the Wear app on an emulator, pair via ADB, and confirm controls work.

**Verification:** `./gradlew :app:testMobileDebugUnitTest` passes; no regression in existing media notification tests.

---

## 2. True 24-bit bit-perfect AAudio output

**Important:** The phone app already has:
- **High-resolution output** toggle (`settings.bitPerfect24Bit`) — keeps decoder float output so 24-bit files are not truncated to 16 by media3.
- **Exclusive output** toggle (`settings.exclusiveOutput`) — disables this app's processors and asks Android to carry the source rate/depth.
- **SendspinNativeEngine** — the MA path, hardcoded to 16-bit int16 Oboe output (`OUTPUT_BIT_DEPTH = 16`).
- **LocalPlayer** — the local-library ExoPlayer path, already respects `bitPerfect`/`exclusive`.
- A signal-path card explaining all of this.

This feature must **not** remove or rename those toggles. It adds a **new, deeper** option: an AAudio `I24`/float exclusive stream in the native C++ engine for the local player, and optionally extends the Sendspin path to advertise and render 24-bit when the native engine can do so.

### Task 2.1: Verify there is no conflict with existing toggles

**Objective:** Confirm the new 24-bit toggle is additive and orthogonal.

**Files:**
- Inspect: `data/AppSettings.kt` around `PREFER_HI_RES`, `BIT_PERFECT_24BIT`, `EXCLUSIVE_OUTPUT`
- Inspect: `audio/ExclusiveOutput.kt`
- Inspect: `audio/SignalPath.kt`

**Decision matrix:**

| Existing high-res | Existing exclusive | New 24-bit AAudio | Result |
|---|---|---|---|
| Off | Off | Off | Current default: media3 int16 sink, all processors active |
| On | Off | Off | Current high-res: float sink, no app processors, 24-bit preserved through media3 |
| Off/On | On | Off | Current exclusive: no app processors, source rate/depth requested, still media3-managed |
| Off/On | Off/On | **On** | **New path**: AAudio exclusive/float/I24, bypassing media3 AudioTrack; only volume survives |

The new toggle is named **"Bit-perfect AAudio output"** and lives directly under the existing **Exclusive output** toggle in `AudioSettings.kt`. It is disabled unless exclusive output is on, to avoid confusion.

**Verification:** Document the matrix in `docs/bit-perfect-aaudio.md` (create) and review it against `ExclusiveOutput.kt` to ensure wording matches.

---

### Task 2.2: Add the new setting

**Objective:** Add `bitPerfectAaudio` boolean to DataStore, gated behind `exclusiveOutput`.

**Files:**
- Modify: `data/AppSettings.kt`
- Modify: `ui/screens/settings/AudioSettings.kt`

**Step 1: In `AppSettings.kt` companion**

```kotlin
private val BIT_PERFECT_AAUDIO = booleanPreferencesKey("bit_perfect_aaudio")
```

Add flow and setter:

```kotlin
val bitPerfectAaudio: Flow<Boolean> = pref { it[BIT_PERFECT_AAUDIO] ?: false }
suspend fun setBitPerfectAaudio(value: Boolean) = context.dataStore.edit { it[BIT_PERFECT_AAUDIO] = value }
```

**Step 2: Add the UI toggle in `AudioSettings.kt` `OutputCard`, after the existing Exclusive output toggle**

```kotlin
CardDivider()
ToggleRow(
    title = "Bit-perfect AAudio output",
    subtitle = "Bypass media3 and send 24-bit PCM straight to a USB DAC",
    checked = bitPerfectAaudio && exclusiveOutput,
    accent = accent,
    info = "Requires Exclusive output above. Uses Android's low-latency AAudio API directly " +
        "with a 24-bit or 32-bit float stream, so the file reaches the DAC without being " +
        "resampled or requantised by the normal mixer. " +
        "Only works on the library this phone decodes itself; Music Assistant playback " +
        "stays 16-bit because the native Sendspin engine is int16. " +
        "Turning this on disables the equaliser, Light Sync analysis and sound modes, same as Exclusive output.",
) { on ->
    scope.launch {
        if (on && !exclusiveOutput) {
            settings.setExclusiveOutput(true)
        }
        settings.setBitPerfectAaudio(on)
    }
}
```

**Verification:** UI builds; the toggle state persists across process restarts.

---

### Task 2.3: Create the native AAudio output path

**Objective:** Add a small C++ class that opens an AAudio stream with `I24` or `Float` exclusive sharing mode.

**Files:**
- Create: `app/src/main/cpp/aaudio_bitperfect_output.h`
- Create: `app/src/main/cpp/aaudio_bitperfect_output.cpp`
- Modify: `app/src/main/cpp/CMakeLists.txt`
- Modify: `app/src/main/cpp/sendspin_output_jni.cpp` (add new JNI methods)

**Step 1: Header**

```cpp
#pragma once

#include <aaudio/AAudio.h>
#include <atomic>
#include <cstdint>
#include <mutex>
#include <vector>

namespace sendspin {

class AAudioBitperfectOutput {
public:
    AAudioBitperfectOutput();
    ~AAudioBitperfectOutput();

    bool open(int32_t sampleRate, int32_t channels, bool useFloat);
    void close();

    // PCM data is interleaved, native endian. For I24, samples are packed 3-byte little-endian.
    // For float, samples are 32-bit IEEE float.
    int32_t write(const uint8_t* data, int32_t bytes);

    bool isOpen() const { return stream_ != nullptr; }
    int32_t deviceId() const;

private:
    AAudioStream* stream_ = nullptr;
    int32_t sampleRate_ = 48000;
    int32_t channels_ = 2;
    bool floatMode_ = false;
    std::mutex writeMutex_;
    std::vector<uint8_t> conversionBuffer_;

    static aaudio_data_callback_result_t callback(AAudioStream* stream, void* userData, void* audioData, int32_t numFrames);
};

} // namespace sendspin
```

**Step 2: Implementation**

Open with `AAUDIO_FORMAT_PCM_I24_PACKED` if `useFloat == false`, otherwise `AAUDIO_FORMAT_PCM_FLOAT`. Request `AAUDIO_SHARING_MODE_EXCLUSIVE`, fall back to shared if it fails. Use `AAUDIO_PERFORMANCE_MODE_NONE` for bit-perfect (low-latency forces resampling on some devices).

Keep a small ring buffer and a callback to satisfy the AAudio thread. For the first version, implement a blocking write path: if the ring is full, sleep a millisecond.

**Step 3: JNI bridge**

Add functions in `sendspin_output_jni.cpp`:

```cpp
extern "C" JNIEXPORT jlong JNICALL
Java_com_engabd_sendpin_audio_AaudioBitperfectOutput_nativeOpen(...)
```

Expose: `nativeOpen(sampleRate, channels, useFloat) -> long handle`, `nativeWrite(handle, bytes) -> int written`, `nativeClose(handle)`.

**Verification:** `./gradlew :app:assembleMobileDebug` links the new object files.

---

### Task 2.4: Create `AaudioBitperfectOutput` Kotlin wrapper

**Objective:** Provide a Kotlin class that the local player can drop in as a custom audio sink.

**Files:**
- Create: `app/src/main/java/com/engabd/sendpin/audio/AaudioBitperfectOutput.kt`

**Step 1: Kotlin wrapper**

```kotlin
package com.engabd.sendpin.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * Sends decoded PCM directly to an AAudio stream, bypassing media3's AudioTrack path.
 *
 * Implements [AudioProcessor] only to fit into ExoPlayer's render chain at the end;
 * it consumes float or 24-bit packed PCM and forwards it to the native AAudio output.
 */
@OptIn(UnstableApi::class)
class AaudioBitperfectOutput : BaseAudioProcessor() {

    private var nativeHandle: Long = 0L
    private var pendingOutputFormat: AudioProcessor.AudioFormat? = null

    fun open(sampleRate: Int, channels: Int, useFloat: Boolean): Boolean {
        nativeHandle = nativeOpen(sampleRate, channels, useFloat)
        return nativeHandle != 0L
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        pendingOutputFormat = inputAudioFormat
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        if (nativeHandle == 0L) {
            // Pass-through if not opened
            inputBuffer.position(inputBuffer.limit())
            return
        }
        val arr = ByteArray(inputBuffer.remaining())
        inputBuffer.get(arr)
        nativeWrite(nativeHandle, arr)
    }

    override fun onFlush() {}
    override fun onReset() { nativeClose(nativeHandle); nativeHandle = 0L }

    private external fun nativeOpen(sampleRate: Int, channels: Int, useFloat: Boolean): Long
    private external fun nativeWrite(handle: Long, bytes: ByteArray): Int
    private external fun nativeClose(handle: Long)

    companion object { init { System.loadLibrary("sendpin") } }
}
```

**Verification:** `./gradlew :app:testMobileDebugUnitTest` passes; no runtime test possible without a USB DAC.

---

### Task 2.5: Wire the local player to use AAudio when bit-perfect is on

**Objective:** In `LocalPlayer.buildPlayer`, insert `AaudioBitperfectOutput` as the final audio processor when `bitPerfectAaudio && exclusive`.

**Files:**
- Modify: `app/src/main/java/com/engabd/sendpin/audio/LocalPlayer.kt`
- Modify: `app/src/main/java/com/engabd/sendpin/audio/TapRenderersFactory.kt` (if needed)

**Step 1: Read the existing player build flow**

Find where `setEnableAudioFloatOutput(bitPerfect || exclusive)` and `TapRenderersFactory` are configured. Add a branch: if `bitPerfectAaudio` is true, do **not** use the normal media3 sink; instead configure a custom renderer that ends at `AaudioBitperfectOutput`.

The simplest path: add a boolean constructor parameter to `TapRenderersFactory` and, when true, return a `MediaCodecAudioRenderer` whose sink is a custom `AudioSink` wrapping the AAudio output. This is complex; a smaller first step is to make `AaudioBitperfectOutput` the last processor in the chain and rely on media3 to build a sink, but that still goes through `DefaultAudioSink`.

**Recommended minimal approach:**
- Add `bitPerfectAaudio` to `LocalPlayer` constructor parameters.
- When true, build an `ExoPlayer` with a custom `RenderersFactory` that produces a sink directly to `AaudioBitperfectOutput`, bypassing `DefaultAudioSink`.

Document that this is a v1; v2 can use a full custom renderer.

**Step 2: Update `SignalPath`**

When AAudio bit-perfect is engaged, set `SignalPath.onExclusive(true)` and report a new stage: "AAudio exclusive I24" or "AAudio exclusive float".

Add to `SignalPath`:

```kotlin
fun onAaudioBitperfect(enabled: Boolean, format: Stage?) {
    _state.value = _state.value.copy(
        exclusive = enabled || _state.value.exclusive,
        sink = format ?: Stage()
    )
}
```

**Verification:** `./gradlew :app:assembleMobileDebug` succeeds; existing local-player tests still pass.

---

### Task 2.6: Add tests and documentation

**Objective:** Unit-test the pure Kotlin decisions; integration tests require hardware.

**Files:**
- Create: `app/src/test/java/com/engabd/sendpin/audio/ExclusiveOutputPolicyTest.kt` (if not present)
- Modify: `docs/bit-perfect-aaudio.md`

**Step 1: Test policy matrix**

```kotlin
@Test
fun `bitPerfectAaudio implies exclusive`() {
    // The setting can be on only when exclusive is already on.
    assertTrue(ExclusiveOutput.disables.isNotEmpty())
}
```

**Step 2: Document the matrix and the limitation**

Create `docs/bit-perfect-aaudio.md` explaining:
- Existing toggles are unchanged.
- New toggle is for local library only.
- Requires exclusive output + a USB DAC.
- Disables EQ/Light Sync/sound modes.
- Music Assistant stays 16-bit.

**Verification:** `./gradlew :app:testMobileDebugUnitTest` passes.

---

## 3. Rhythm Tiles / Note Highway game

The app already produces `AnalysisFrame` with `beat`, `bassBeat`, `midBeat`, `melbank`, `chroma`, `tempoBpm`, and `beatStrength`. The rhythm game consumes this stream.

### Task 3.1: Design the game data model

**Objective:** Define the notes the game will show and how they are generated from `AnalysisFrame`.

**Files:**
- Create: `app/src/main/java/com/engabd/sendpin/game/RhythmGame.kt`

**Step 1: Note model**

```kotlin
data class GameNote(
    val lane: Int,              // 0..3
    val triggerTimeMs: Long,    // when the note should be hit
    val kind: NoteKind,         // KICK, SNARE, HAT, MELODY
    val intensity: Float,       // 0..1
)

enum class NoteKind { KICK, SNARE, HAT, MELODY }
```

**Step 2: Note generator from analysis**

```kotlin
class NoteGenerator(private val lanes: Int = 4) {
    private val pending = ArrayDeque<GameNote>()

    fun onFrame(frame: AnalysisFrame, positionMs: Long, lookAheadMs: Long = 2000L): List<GameNote> {
        val now = positionMs
        // Bass → lane 0, Beat → lane 1, Mid → lane 2, high melbank → lane 3
        if (frame.bassBeat) addNote(0, now + lookAheadMs, NoteKind.KICK, frame.bassStrength)
        if (frame.beat && !frame.bassBeat) addNote(1, now + lookAheadMs, NoteKind.SNARE, frame.beatStrength)
        if (frame.midBeat) addNote(2, now + lookAheadMs, NoteKind.HAT, frame.midStrength)
        val topBin = frame.melbank.withIndex().maxByOrNull { it.value }?.index ?: -1
        if (topBin >= 8 && frame.energy > 0.15f) addNote(3, now + lookAheadMs, NoteKind.MELODY, frame.energy)

        // Expire old notes
        while (pending.isNotEmpty() && pending.first().triggerTimeMs < now - 500L) pending.removeFirst()
        return pending.filter { it.triggerTimeMs in now..(now + lookAheadMs) }
    }

    private fun addNote(lane: Int, timeMs: Long, kind: NoteKind, intensity: Float) {
        // Avoid duplicate notes in same lane within 120 ms
        if (pending.any { it.lane == lane && kotlin.math.abs(it.triggerTimeMs - timeMs) < 120L }) return
        pending.addLast(GameNote(lane, timeMs, kind, intensity))
    }
}
```

**Verification:** Add unit test `NoteGeneratorTest` that feeds synthetic `AnalysisFrame` objects and checks lane assignment and deduplication.

---

### Task 3.2: Build the game screen

**Objective:** A full-screen Compose game that renders falling notes and a hit line, with lights reacting to accuracy.

**Files:**
- Create: `app/src/main/java/com/engabd/sendpin/ui/screens/RhythmGameScreen.kt`
- Create: `app/src/main/java/com/engabd/sendpin/ui/viewmodel/RhythmGameViewModel.kt`

**Step 1: ViewModel**

```kotlin
class RhythmGameViewModel(app: Application) : AndroidViewModel(app) {
    private val generator = NoteGenerator()
    private val _notes = MutableStateFlow<List<GameNote>>(emptyList())
    val notes: StateFlow<List<GameNote>> = _notes

    private val _score = MutableStateFlow(0)
    val score: StateFlow<Int> = _score

    fun onFrame(frame: AnalysisFrame, positionMs: Long) {
        _notes.value = generator.onFrame(frame, positionMs)
    }

    fun tap(lane: Int, positionMs: Long) {
        val target = _notes.value.minByOrNull { kotlin.math.abs(it.triggerTimeMs - positionMs) } ?: return
        if (target.lane != lane) return
        val delta = kotlin.math.abs(target.triggerTimeMs - positionMs)
        val points = when {
            delta < 50L -> 100
            delta < 120L -> 50
            delta < 200L -> 25
            else -> 0
        }
        if (points > 0) {
            _score.value += points
            // remove the note
            _notes.value = _notes.value.filter { it !== target }
            onHit(points, lane)
        }
    }

    private fun onHit(points: Int, lane: Int) {
        // Inject a light event: DirectLightSync.receiveGameHit(lane, points)
    }
}
```

**Step 2: Screen composable**

Render 4 lanes as vertical tracks. Notes fall from top to a hit line near the bottom. Use `detectTapGestures` per lane. While the game runs, keep the screen on and route audio frames from the active analysis tap (`AudioAnalysisTap.frames`) to the view model.

**Verification:** `./gradlew :app:assembleMobileDebug` succeeds; the screen does not crash on open.

---

### Task 3.3: Light reaction to hits

**Objective:** When the player hits a note, flash the corresponding side of the Hue room.

**Files:**
- Modify: `app/src/main/java/com/engabd/sendpin/hue/DirectLightSync.kt`

**Step 1: Add a game-event overlay**

```kotlin
@Volatile
private var gameOverlay: Map<Int, Rgb>? = null
private var gameOverlayExpiryMs: Long = 0L

fun receiveGameHit(lane: Int, points: Int, positions: Map<Int, Vec3>) {
    val color = when (lane) {
        0 -> Rgb(1f, 0.2f, 0.2f) // red kick
        1 -> Rgb(0.2f, 0.6f, 1f) // blue snare
        2 -> Rgb(1f, 0.8f, 0.1f) // yellow hat
        else -> Rgb(0.5f, 1f, 0.3f) // green melody
    }
    val sideX = (lane / 3f) * 2f - 1f // map lane 0..3 to left..right
    val affected = positions.filter { (_, p) -> kotlin.math.abs(p.x - sideX) < 0.4f }.keys
    val scaled = color.copy(first = color.first * (points / 100f))
    gameOverlay = affected.associateWith { scaled }
    gameOverlayExpiryMs = System.currentTimeMillis() + 150L
}
```

In the 60 Hz render loop, blend `gameOverlay` on top of the engine output if not expired.

**Verification:** Unit test the overlay blending in isolation with a fake `DirectLightSync` helper.

---

### Task 3.4: Entry point and settings

**Objective:** Add a "Rhythm Game" button in the Now Playing overflow / Light Sync screen.

**Files:**
- Modify: `app/src/main/java/com/engabd/sendpin/ui/App.kt`
- Modify: `app/src/main/java/com/engabd/sendpin/ui/screens/NowPlayingScreen.kt` or `LightSyncScreen.kt`

**Step 1: Add navigation route**

```kotlin
sealed class Screen(val route: String) {
    // ... existing routes ...
    data object RhythmGame : Screen("rhythm_game")
}
```

**Step 2: Add a button**

In the Now Playing options sheet or Light Sync screen, add a chip: "Play with lights".

**Verification:** The screen opens from Now Playing and from Light Sync.

---

## 4. Capture the phone's own audio output (Samsung Music Sync style)

The app already has `PlaybackCapture` using `MediaProjection` + `AudioPlaybackCaptureConfiguration`, which captures **other apps' audio** when they allow it. The user wants to capture **this phone's own audio output**, including CAMusic's own playback, so the lights react to whatever sound the phone produces without needing another source.

The platform does **not** let an app capture its own audio directly via `AudioPlaybackCaptureConfiguration` without `MediaProjection`, and even with projection it captures other apps' playback, not the final mixed output stream that includes itself. The practical way to achieve "lights follow everything this phone plays" is to:
1. Keep the existing MediaProjection capture for other apps.
2. When CAMusic itself is playing, route a copy of the PCM that is already going to the analysis tap (`AudioAnalysisTap`) into the light engine, so the lights react to CAMusic's own audio natively.
3. Provide a single user-facing toggle: "Listen to this phone" that selects the right feed automatically.

This is the cleanest, conflict-free design.

### Task 4.1: Define a unified "phone audio feed" selector

**Objective:** Add a setting that chooses between "Other apps" (MediaProjection) and "This app" (internal tap).

**Files:**
- Modify: `data/AppSettings.kt`
- Modify: `ui/screens/LightSyncScreen.kt`

**Step 1: Add setting**

```kotlin
private val PHONE_AUDIO_FEED = stringPreferencesKey("phone_audio_feed")
val phoneAudioFeed: Flow<String> = pref { it[PHONE_AUDIO_FEED] ?: "auto" }
suspend fun setPhoneAudioFeed(value: String) { context.dataStore.edit { it[PHONE_AUDIO_FEED] = value } }
```

Allowed values: `"auto"`, `"internal"`, `"projection"`.

**Step 2: Add UI in DirectLightSyncScreen**

After the existing "Listen to other apps" toggle, add a row:

```kotlin
FieldLabel("When this phone is playing")
SegmentedToggleRow(
    labels = listOf("Auto", "Use this app", "Use microphone/screen"),
    selectedIndex = listOf("auto", "internal", "projection").indexOf(feed),
) { i -> scope.launch { settings.setPhoneAudioFeed(values[i]) } }
Note("Auto uses the internal tap when CAMusic is playing, and MediaProjection otherwise.")
```

**Verification:** UI builds and setting persists.

---

### Task 4.2: Route internal playback into the light engine

**Objective:** When CAMusic is the active player, feed `AudioAnalysisTap.frames` to `DirectLightSync`.

**Files:**
- Inspect: `app/src/main/java/com/engabd/sendpin/hue/DirectLightSync.kt`
- Inspect: `app/src/main/java/com/engabd/sendpin/service/PlaybackOwner.kt`
- Modify: `app/src/main/java/com/engabd/sendpin/hue/LightSyncFeed.kt` (or create)

**Step 1: Create a feed abstraction**

```kotlin
sealed class LightSyncFeed {
    data object None : LightSyncFeed()
    data class Internal(val tap: AudioAnalysisTap) : LightSyncFeed()
    data class Projection(val tap: AudioAnalysisTap) : LightSyncFeed()
}
```

**Step 2: In `DirectLightSync`, consume the chosen feed**

The existing `renderLoop` already calls `engine.render(frame)` from a chosen source. Add a branch:

```kotlin
private fun chooseFeed(): LightSyncFeed {
    val feed = settings.phoneAudioFeed.first()
    val localPlaying = PlaybackOwner.localPlaying.value
    return when {
        feed == "internal" && localPlaying -> LightSyncFeed.Internal(localPlayerTap)
        feed == "projection" -> LightSyncFeed.Projection(PlaybackCapture.tap)
        localPlaying -> LightSyncFeed.Internal(localPlayerTap) // auto
        PlaybackCapture.running.value -> LightSyncFeed.Projection(PlaybackCapture.tap)
        else -> LightSyncFeed.None
    }
}
```

**Verification:** `./gradlew :app:testMobileDebugUnitTest` passes.

---

### Task 4.3: Improve the MediaProjection capture UX

**Objective:** Make it clearer what is happening when other apps block capture.

**Files:**
- Modify: `app/src/main/java/com/engabd/sendpin/capture/CaptureConsentActivity.kt` (if exists)
- Modify: `app/src/main/java/com/engabd/sendpin/ui/screens/LightSyncScreen.kt`

**Step 1: Add an onboarding hint**

When the user first taps "Listen to other apps", show a dialog:

```
Some apps (YouTube, YouTube Music, most DRM-protected players) block audio capture.
If the lights stay dark while music is audible, that app has opted out.
Spotify, podcast apps and local players usually work.
```

**Step 2: Better blocked-state UI**

When `PlaybackCapture.state == BLOCKED`, show a pill: "This app blocks capture" instead of "Listening".

**Verification:** Manual UI test; unit test `CaptureSilenceWatchdog` already exists and should still pass.

---

## 5. Sonic similarity across local libraries

Music Assistant already provides `similarTracks` and `sonicTextSearch` for MA-tracked items. The local libraries (Navidrome/Subsonic/Jellyfin/Emby/Plex/local files) have no similarity engine. We will build one from existing `TrackScan` features (bpm, key, melbank reference, intensity profile) and on-device embeddings.

### Task 5.1: Define the similarity model

**Objective:** Create a data class and distance function that compare two tracks using scan features.

**Files:**
- Create: `app/src/main/java/com/engabd/sendpin/audio/SonicSimilarity.kt`

**Step 1: Feature vector**

```kotlin
data class SonicFingerprint(
    val bpm: Float,
    val keyTonic: Int?,          // 0..11 or null
    val keyMode: String?,        // "MAJOR" / "MINOR"
    val energy: Float,           // average intensity profile value
    val dynamics: Float,         // from IntensityProfile
    val spectralCentroid: Float, // average melbankRef weighted by bin center frequency
)
```

**Step 2: Distance**

```kotlin
object SonicSimilarity {
    fun distance(a: SonicFingerprint, b: SonicFingerprint): Float {
        var d = 0f
        d += tempoDistance(a.bpm, b.bpm) * 0.30f
        d += keyDistance(a.keyTonic, a.keyMode, b.keyTonic, b.keyMode) * 0.25f
        d += kotlin.math.abs(a.energy - b.energy) * 0.20f
        d += kotlin.math.abs(a.dynamics - b.dynamics) * 0.15f
        d += kotlin.math.abs(a.spectralCentroid - b.spectralCentroid) * 0.10f
        return d.coerceIn(0f, 1f)
    }

    private fun tempoDistance(a: Float, b: Float): Float {
        val ratio = max(a, b) / max(1f, min(a, b))
        val octaveCorrected = if (ratio > 1.5f) ratio / 2f else ratio
        return kotlin.math.abs(octaveCorrected - 1f).coerceIn(0f, 1f)
    }

    private fun keyDistance(t1: Int?, m1: String?, t2: Int?, m2: String?): Float {
        if (t1 == null || t2 == null) return 0.5f
        val tonicDiff = min(abs(t1 - t2), 12 - abs(t1 - t2))
        val modePenalty = if (m1 == m2) 0f else 0.2f
        return (tonicDiff / 6f + modePenalty).coerceIn(0f, 1f)
    }
}
```

**Verification:** Create `SonicSimilarityTest` with known similar/dissimilar cases.

---

### Task 5.2: Index local tracks

**Objective:** Build a lightweight in-memory index of fingerprints for tracks that have scans.

**Files:**
- Create: `app/src/main/java/com/engabd/sendpin/audio/LocalSonicIndex.kt`
- Modify: `app/src/main/java/com/engabd/sendpin/audio/TrackScanRepository.kt`

**Step 1: Index**

```kotlin
class LocalSonicIndex(
    private val scanStore: TrackScanStore,
    private val sources: MusicSources,
) {
    private val index = mutableMapOf<String, SonicFingerprint>()

    suspend fun rebuild() {
        index.clear()
        // Enumerate all local tracks via each MusicSource
        for (source in sources.allLocal()) {
            val tracks = source.tracks(limit = 10_000)
            for (track in tracks) {
                val key = scanKey(track)
                val scan = scanStore.load(key) ?: continue
                index[key] = fingerprintFromScan(track, scan)
            }
        }
    }

    fun findSimilar(to: MaItem, limit: Int = 20): List<Pair<MaItem, Float>> {
        val targetKey = scanKey(to)
        val target = index[targetKey] ?: return emptyList()
        return index.entries
            .filter { it.key != targetKey }
            .map { (key, fp) -> sources.findByKey(key) to SonicSimilarity.distance(target, fp) }
            .filter { it.first != null }
            .map { it.first!! to it.second }
            .sortedBy { it.second }
            .take(limit)
    }
}
```

**Step 2: Hook rebuild after scans complete**

In `TrackScanRepository`, after a batch scan finishes, call `LocalSonicIndex.rebuild()`.

**Verification:** Unit test with a fake `TrackScanStore` and a fake `MusicSources`.

---

### Task 5.3: UI for local similar tracks

**Objective:** In the Now Playing "similar" panel, fall back to local sonic similarity when MA has none.

**Files:**
- Modify: `app/src/main/java/com/engabd/sendpin/ui/viewmodel/NowPlayingViewModel.kt`

**Step 1: Add local fallback in `loadSimilar()`**

```kotlin
fun loadSimilar() {
    val item = currentItem.value ?: return
    _similar.value = Load.Loading
    viewModelScope.launch {
        val maSimilar = runCatching { repo.similarTracks(item.itemId, item.provider) }.getOrNull()
        val localSimilar = if (maSimilar.isNullOrEmpty()) {
            localSonicIndex.findSimilar(item, limit = 20)
                .map { (track, distance) -> MaSimilarTrack(..., score = 1f - distance) }
        } else null
        _similar.value = Load.Ready(maSimilar ?: localSimilar ?: emptyList())
    }
}
```

**Verification:** `./gradlew :app:testMobileDebugUnitTest` passes; manual check that similar tracks appear when MA returns none.

---

## 6. Simple/Advanced settings split + onboarding polish

### Task 6.1: Add a "Simple" vs "Advanced" settings mode

**Objective:** Hide advanced toggles by default.

**Files:**
- Modify: `data/AppSettings.kt`
- Modify: `ui/screens/SettingsScreen.kt`
- Modify: `ui/screens/settings/AudioSettings.kt`, `LightSyncSettings.kt`, etc.

**Step 1: Setting**

```kotlin
private val ADVANCED_SETTINGS = booleanPreferencesKey("advanced_settings")
val advancedSettings: Flow<Boolean> = pref { it[ADVANCED_SETTINGS] ?: false }
suspend fun setAdvancedSettings(value: Boolean) { context.dataStore.edit { it[ADVANCED_SETTINGS] = value } }
```

**Step 2: Use it**

In `SettingsScreen.kt`, add a switch at the top: "Advanced settings". Pass the value down to each section. Each section hides rows that are not essential:
- Audio: keep Output device, High-resolution output, ReplayGain visible; hide Exclusive output, Bit-perfect AAudio, Output sample rate, signal path details unless advanced.
- Light Sync: keep master toggle and area; hide creative layers, speaker offset, capture mode unless advanced.

**Verification:** `./gradlew :app:assembleMobileDebug` succeeds; toggling advanced shows/hides rows.

---

### Task 6.2: Improve onboarding

**Objective:** Add a 3-page onboarding that explains servers, Light Sync, and output choices.

**Files:**
- Create: `app/src/main/java/com/engabd/sendpin/ui/screens/OnboardingScreen.kt` (or extend existing)
- Modify: `data/AppSettings.kt` — add `onboardingShown` key if missing
- Modify: `ui/App.kt` — route to onboarding on first launch

**Step 1: Pages**

1. Welcome + "Add a music server" button (shows the server picker).
2. "Light Sync" — explains direct Hue bridge vs Home Assistant, with a demo button that pulses the screen.
3. "Sound" — explains output device and high-resolution output, with a "Start listening" button.

**Step 2: First-launch routing**

```kotlin
val onboardingShown by settings.onboardingShown.collectAsStateWithLifecycle(initialValue = false)
if (!onboardingShown) navController.navigate("onboarding")
```

**Verification:** `./gradlew :app:assembleMobileDebug` succeeds; deleting app data triggers onboarding.

---

## 7. Ambient visual shows on Android TV / webOS + user-correctable album colours

This combines two requests: (a) show the ambient light show visually on the TV screen, and (b) let users override album-art colours and persist the override into the offline scan so future plays use the corrected palette.

### 7.1 TV/webOS ambient visual show

### Task 7.1.1: TV ambient background renderer

**Objective:** On `TvNowPlayingScreen`, when Light Sync is active, render a large ambient visual behind the artwork.

**Files:**
- Create: `app/src/tv/java/com/engabd/sendpin/tv/screens/TvAmbientBackground.kt`
- Modify: `app/src/tv/java/com/engabd/sendpin/tv/screens/TvNowPlayingScreen.kt`

**Step 1: Compose background**

```kotlin
@Composable
fun TvAmbientBackground(engine: SyncoEngine?, palette: Palette?) {
    val t = rememberInfiniteTransition(label = "ambient")
    val phase by t.animateFloat(initialValue = 0f, targetValue = 1f, animationSpec = infiniteRepeatable(tween(20_000, easing = LinearEasing)))
    val colors = palette?.colors ?: SyncoPalette.getPalette(ColorScheme.SUNSET).colors
    val brush = Brush.sweepGradient(
        colors = colors.map { Color(it.first, it.second, it.third) },
        center = Offset(0.5f, 0.5f)
    )
    Box(Modifier.fillMaxSize().background(brush).alpha(0.35f))
}
```

**Step 2: Wire into TV Now Playing**

Collect the current `Palette` from a small holder updated by `DirectLightSync`. Place `TvAmbientBackground` as the screen's bottom layer.

**Verification:** `./gradlew :app:assembleTvDebug` succeeds.

---

### Task 7.1.2: webOS ambient background

**Objective:** In the webOS app, render a canvas visual that follows the current light palette.

**Files:**
- Modify: `webos/js/app.js`
- Modify: `webos/index.html` (add a canvas element)

**Step 1: WebSocket or HTTP polling from webOS to phone**

The webOS app already communicates with the phone. Add a lightweight endpoint or WebSocket message that publishes the current palette and mode. Reuse the existing local HTTP server if one exists; otherwise add a simple UDP broadcast of palette + mode.

**Step 2: Canvas renderer**

```javascript
function drawAmbient(palette, mode) {
    const ctx = document.getElementById('ambientCanvas').getContext('2d');
    const grad = ctx.createLinearGradient(0, 0, canvas.width, canvas.height);
    palette.forEach((c, i) => grad.addColorStop(i / palette.length, c));
    ctx.fillStyle = grad;
    ctx.fillRect(0, 0, canvas.width, canvas.height);
}
```

Run at 10 fps, not 60, to save CPU.

**Verification:** Build webOS app with `ares-package`; verify canvas appears in simulator.

---

### 7.2 User-correctable album colours

### Task 7.2.1: Define the override storage model

**Objective:** Store user-chosen colours keyed by album (or by track when no album art).

**Files:**
- Modify: `app/src/main/java/com/engabd/sendpin/audio/TrackScan.kt`
- Modify: `app/src/main/java/com/engabd/sendpin/audio/TrackScanStore.kt`

**Step 1: Add override fields to `TrackScan`**

```kotlin
data class TrackScan(
    // ... existing fields ...
    /**
     * User-override album-art colours. When present, the light show uses these
     * instead of extracting from the bitmap. Persists across plays.
     */
    val userPalette: List<Rgb>? = null,
    val userPaletteWeights: List<Float>? = null,
)
```

**Step 2: Persist and load**

Extend `TrackScanStore.write()`/`read()` to include the optional user palette. Use format version bump (FORMAT = 4 → 5) and keep backward reader for older files.

**Verification:** Unit test `TrackScanStoreTest` updated to round-trip a scan with `userPalette`.

---

### Task 7.2.2: UI to edit the palette

**Objective:** On Now Playing, long-press the album art (or a Light Sync menu) to open a colour editor.

**Files:**
- Create: `app/src/main/java/com/engabd/sendpin/ui/screens/AlbumColourEditor.kt`
- Modify: `app/src/main/java/com/engabd/sendpin/ui/screens/NowPlayingScreen.kt`
- Modify: `app/src/main/java/com/engabd/sendpin/ui/screens/LightSyncScreen.kt`

**Step 1: Editor UI**

Show:
- The currently extracted colours as chips.
- A "+" chip to add a custom colour.
- A reset button: "Use extracted colours again".
- Weights slider per colour (only for `ALBUM_ART_V2`).

Use a colour picker composable (the system one or a small HSV wheel).

**Step 2: Save action**

```kotlin
fun saveUserPalette(item: MaItem, colors: List<Rgb>, weights: List<Float>?) {
    val key = scanKey(item)
    val existing = scanStore.load(key)
    val updated = (existing ?: fallbackScanFor(item)).copy(userPalette = colors, userPaletteWeights = weights)
    scanStore.save(key, updated)
    // Notify DirectLightSync to re-adopt the palette
    DirectLightSync.clearPaletteCache(key)
}
```

**Verification:** `./gradlew :app:assembleMobileDebug` succeeds; unit test the save/load path.

---

### Task 7.2.3: Make the light engine prefer user palette

**Objective:** In `DirectLightSync`, when extracting album colours, first check the scan's `userPalette`.

**Files:**
- Modify: `app/src/main/java/com/engabd/sendpin/hue/DirectLightSync.kt`

**Step 1: Lookup order**

When a new track starts:
1. Load its scan from `TrackScanStore`.
2. If `scan.userPalette` is present, use it.
3. Otherwise, extract from the downloaded bitmap as today.
4. Cache the result in `lastAlbumColours`.

**Step 2: Provide a reset path**

In the editor, "Reset to auto" sets `userPalette = null` and re-extracts from the bitmap.

**Verification:** Unit test `AlbumColoursSeedingTest` updated to assert user palette wins.

---

## Cross-cutting tasks

### Task 8.1: Add plan metadata and run baseline tests

**Objective:** Ensure the plan is tracked and the baseline is green before any feature work.

**Files:**
- Create: `.hermes/plans/2026-09-03_camusic-focus-features.md` (this file)

**Step 1: Run baseline tests**

```bash
cd /home/dash/CAMusic
./gradlew :app:testMobileDebugUnitTest
```

Expected: all existing tests pass.

**Step 2: Create feature branches**

For each focus area, create a branch from `master`:

```bash
git checkout -b feat/wear-os-companion
git checkout -b feat/bit-perfect-aaudio
git checkout -b feat/rhythm-game
git checkout -b feat/phone-output-capture
git checkout -b feat/local-sonic-similarity
git checkout -b feat/simple-advanced-onboarding
git checkout -b feat/tv-webos-ambient-user-colours
```

These can be developed independently and merged in order.

---

## Risks and tradeoffs

| Risk | Mitigation |
|---|---|
| 24-bit AAudio requires real hardware to verify | Document that it is hardware-dependent; fall back to existing exclusive output. |
| Rhythm game timing depends on `AnalysisFrame` latency | Use the same `AudioLead`/position logic the light engine uses; do not rely on UI position. |
| Wear OS increases APK size | Use dynamic delivery (`dist:wear`) or a separate Wear build variant. |
| Local sonic similarity index is expensive to rebuild | Rebuild only after batch scans; keep it in memory and lazy. |
| User palette storage increases scan file size | The override is optional and small (a few RGB triples). |
| TV/webOS ambient visuals compete with video rendering | Render at low opacity and 10 fps; provide an off toggle. |

---

## Suggested implementation order

The plan above has **7 focus-area sections**. Section 7 contains two related sub-features, so the schedule below lists them separately while keeping the same 7-section structure.

1. **Baseline tests + branch setup**
2. **Simple/Advanced settings + onboarding** (small, improves every user)
3. **User-correctable album colours** (solves a real user pain, reuses existing scan store — part of section 7)
4. **Phone output capture** (mostly wiring existing taps)
5. **Sonic similarity** (pure Kotlin, testable)
6. **Rhythm game** (fun, demoable, builds on capture)
7a. **TV/webOS ambient visuals** (parallel to above — part of section 7)
7b. **24-bit AAudio** (riskiest; leave until hardware is available — section 2)
7c. **Wear OS** (largest new surface; do last — section 1)

---

## Notes on existing features that must not be broken

- **High-resolution output toggle (`bitPerfect24Bit`)**: unchanged. The new "Bit-perfect AAudio output" is a separate, deeper path.
- **Exclusive output toggle (`exclusiveOutput`)**: unchanged. The new AAudio path requires it and auto-enables it if needed.
- **Sendspin native engine**: remains 16-bit int16. The 24-bit path is only for the local ExoPlayer path.
- **MediaProjection / capture other apps**: remains for other apps. The new "internal" feed lets CAMusic listen to itself.
- **Album colour extraction (`AlbumColours.kt`)**: remains the default. User overrides live in `TrackScan.userPalette` and are applied after extraction.

---

*Plan prepared after inspecting the current CAMusic master at v0.11.5, including the native Oboe engine, SignalPath, FormatNegotiator, ExclusiveOutput, DirectLightSync, TrackScanStore, and existing TV/webOS source trees.*
