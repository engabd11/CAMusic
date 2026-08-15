# Plan: Sendspin Player Overhaul — ExoPlayer + Oboe Native Output

## Status: Shipped (v0.8.8, PR #52)

Everything below is implemented — `SendspinExoEngine`, `OboeAudioSink`,
`SendspinNativeOutput` and the `src/main/cpp` output engine, with
`externalNativeBuild` enabled and Oboe 1.10.0 as a real dependency. The
`SendspinService` notification moved to media3-session alongside it.

The headline consequence landed too: because the `AudioAnalysisTap` sits in
ExoPlayer's render chain, direct Hue Bridge light sync now works for Music
Assistant playback as well as local. See `SendpinApp.activeLightSyncSource`.

Read the rest as the record of *why* it was built this way, not as work
outstanding.

## Why now

The Sendspin (MA) player path uses a hand-built `MediaCodec` → `AudioTrack`
pipeline that manages everything manually. It works, but it falls short of what
Massdroid (the other native Sendspin client) delivers — and it blocks several
features that should be table stakes:

1. **No Light Sync for MA playback.** The `AudioAnalysisTap` sits inside
   ExoPlayer's render chain (via `TapRenderersFactory`). The Sendspin path
   bypasses ExoPlayer entirely, so direct Hue Bridge sync cannot see MA audio.
   Only the HA → syncoV2 path works for MA, with HA's WebSocket latency in the
   loop.

2. **JVM GC pauses cause underruns.** The current engine writes decoded PCM to
   `AudioTrack` from a JVM thread using `Thread.sleep()` for scheduling. ART
   garbage collection can pause this thread, causing audible micro-drops. The
   buffer is intentionally shallow (for sync accuracy), which means zero
   tolerance for jitter.

3. **No sample-accurate timeline alignment.** The Kalman filter corrects the
   *schedule* (when to write), but the output itself has no per-chunk drift
   correction. Over a long stream, clock drift between the client and server
   accumulates — Massdroid corrects this inside the audio callback using
   resampling (run slightly fast/slow to converge). CAMusic does not.

4. **No solo mode.** When you're the only speaker, the full sync machinery
   (server-clock scheduling, headroom delays) adds unnecessary latency for no
   benefit. Massdroid has a separate `SendspinDirectEngine` that anchors to
   "now" and starts playback instantly. CAMusic uses the same engine for both.

5. **No dynamic range processing.** Massdroid's native output has a soft-knee
   compressor, noise-shaped TPDF dither for 16-bit, and gain ramping that
   prevents clicks on mute/unmute. CAMusic's output is bit-exact but raw.

6. **No audio focus loss survival.** When audio focus is lost (call, alarm,
   another app), Massdroid fades to silence, holds the buffer read position,
   and fades back in from exactly where it stopped. CAMusic drops the stream
   and has to reconnect.

7. **No ReplayGain on the MA path.** The local path applies ReplayGain in
   `LocalPlayer.applyGain()`; the MA path doesn't.

8. **Fragile gapless.** `StreamContinuity` hand-manages codec reuse and track
   boundaries. ExoPlayer's built-in gapless is battle-tested.

9. **No media3-session integration.** The MA path uses manual
   `MediaSessionCompat` and `AudioManager` for audio focus. The local path gets
   this for free from ExoPlayer.

10. **High maintenance burden.** Every codec lifecycle issue, every AudioTrack
    underrun, every focus duck is hand-debugged in `SendspinAudioEngine.kt`
    (920 lines of monolithic code doing everything).

The local player (Navidrome/Jellyfin/downloads) uses ExoPlayer and works
flawlessly. Both paths should use the same player framework, and the output
path should be GC-immune.

## Goal

Bring the Sendspin player to production quality in one upgrade:

- **ExoPlayer** for decode, queue management, media session, audio focus,
  gapless, ReplayGain, and the `AudioAnalysisTap` integration that makes
  direct Hue Bridge sync work on MA playback
- **Oboe native output** (the C++ code already exists in `cpp/`) for GC-immune
  real-time audio with per-chunk timeline alignment, dynamic range compression,
  and proper audio focus loss handling
- **Direct/Sync mode split** so solo playback starts instantly without clock
  sync, and grouped playback uses the full Kalman-filtered server-clock schedule
- **Unify the player architecture** — one framework for both local and MA
  playback, one media session, one notification

## Massdroid comparison

Massdroid is the reference Sendspin client. Here's how its architecture maps
to what we need:

| Component | Massdroid | CAMusic (current) | CAMusic (after this plan) |
|---|---|---|---|
| **Decode** | `MediaCodec` (same as us) | `MediaCodec` (manual lifecycle) | ExoPlayer (automatic) |
| **Output** | Oboe real-time callback (C++) | `AudioTrack` from JVM thread | Oboe real-time callback (C++) |
| **Sync scheduling** | `SendspinSyncEngine` + `ClockSynchronizer` (Kalman, NTP-style) | `SendspinAudioEngine.awaitFrameTime()` + `ClockSync` (Kalman) | ExoPlayer DataSource + `ClockSynchronizer` (upgraded from Massdroid) |
| **Solo mode** | `SendspinDirectEngine` — anchors to now, no sync overhead | Same engine, no mode split | `SendspinDirectDataSource` — anchors to now |
| **Grouped mode** | `SendspinSyncEngine` — server-clock phase, 200ms headroom | Same engine, always uses server clock | `SendspinSyncDataSource` — server-clock phase |
| **Timeline alignment** | Per-chunk snap/resample/deadzone inside Oboe callback | None (schedule-based only) | Per-chunk snap/resample/deadzone inside Oboe callback |
| **Dynamic range** | Compressor + dither + soft-clip + gain ramp in C++ | None | Compressor + dither + soft-clip + gain ramp in C++ |
| **Audio focus loss** | Freeze/unfreeze with fade, holds buffer position | Drops stream, must reconnect | Freeze/unfreeze with fade, holds buffer position |
| **Light Sync** | Not applicable (no Hue integration) | HA path only (with latency) | Direct Hue Bridge via AudioAnalysisTap |
| **Media session** | media3-session | Manual `MediaSessionCompat` | media3-session (shared with local) |
| **Gapless** | Via Oboe buffer continuity | Hand-built `StreamContinuity` | ExoPlayer built-in |
| **ReplayGain** | Not visible in source | Local path only | Both paths |

## Architecture

### Current state

```
MA WebSocket binary frame
  → SendspinClient (protocol)
  → SendspinAudioEngine (monolithic 920 lines)
    → MediaCodec (manual lifecycle)
    → AudioTrack (MODE_STREAM, JVM thread, Thread.sleep scheduling)
    → ClockSync / Kalman filter
    → StreamContinuity (hand-built gapless)

Light Sync: NOT CONNECTED to this path.
Audio focus: manual AudioManager calls.
Media session: manual MediaSessionCompat.
```

### Target state

```
MA WebSocket binary frame
  → SendspinClient (protocol, unchanged)
  → SendspinDataSource (new — wraps WebSocket frames as ExoPlayer DataSource)
    → ClockSynchronizer (upgraded from Massdroid: NTP-style, adaptive forgetting,
        drift significance gating, startup rejection backoff, soft reset)
    → SendspinSyncDataSource or SendspinDirectDataSource
        (mode split — sync uses server clock, direct anchors to now)
  → ExoPlayer (ProgressiveMediaSource + SendspinDataSource)
    → TapRenderersFactory (already in place)
      → AudioAnalysisTap → Hue Bridge DTLS (direct)
    → OboeRenderer (new — bridges ExoPlayer decode to Oboe native output)
      → Oboe real-time callback (C++ — gc-immune)
        → Lock-free SPSC ring buffer (4s deep, absorbs JVM pauses)
        → Per-chunk timeline alignment (snap/resample/deadzone)
        → Dynamic range compressor (4 levels, soft-knee)
        → TPDF dither (16-bit) / passthrough (24-bit)
        → Gain ramping (fast mute, slow volume)
        → Freeze/unfreeze on audio focus loss
```

This is **one upgrade**: ExoPlayer for decode/queue/session, Oboe for output,
mode split for solo vs grouped, upgraded clock sync, and dynamic range
processing. No phases, no "future bit-perfect phase" — we do it all.

## Key design decisions

### 1. ExoPlayer for decode, Oboe for output

ExoPlayer handles everything *up to* the final PCM output: codec lifecycle,
gapless transitions, media session, audio focus, ReplayGain, and the
`AudioAnalysisTap` for Light Sync. But ExoPlayer writes to `AudioTrack` on a
JVM thread, which means GC pauses can cause underruns and there's no
per-chunk timeline alignment.

The `OboeRenderer` is a custom ExoPlayer `Renderer` that replaces
`MediaCodecAudioRenderer`'s output stage. Instead of writing PCM to
`AudioTrack`, it writes to a lock-free SPSC ring buffer. The Oboe real-time
callback on the SCHED_FIFO HAL thread reads from this ring buffer, applies
timeline alignment and dynamic range processing, and writes to the DAC.

This gives us:
- **ExoPlayer's strengths**: decode, queue, session, focus, gapless, ReplayGain
- **Oboe's strengths**: GC-immune output, sample-accurate sync, no-click
  volume changes, freeze/unfreeze on focus loss

### 2. Clock sync via DataSource pre-buffering

The Sendspin protocol schedules each frame against a server clock. ExoPlayer
has no concept of "play at server time X."

**Solution:** The `SendspinDataSource.read()` method blocks until the
`ClockSynchronizer` says the current frame is due. ExoPlayer receives data only
when it's time to play it. The sync is transparent to ExoPlayer — it just sees
data arriving at the right rate.

```
SendspinDataSource:
  open(DataSpec)    → start buffering WebSocket frames
  read(buf, off, len) → block until next frame is due (per ClockSynchronizer),
                        then copy frame bytes into buf
  close()           → stream/end or stream/clear
```

This preserves multi-room sync accuracy. The Kalman filter's offset and drift
calculations are unchanged; only the scheduling mechanism moves from
`awaitFrameTime()` to `read()`.

### 3. Direct/Sync mode split

Massdroid separates solo and grouped playback into two engines:

- **SendspinDirectDataSource** (solo mode): Anchors the first post-flush frame
  to `now + outputLatency + 60ms`. No clock dependency, no headroom, no late-drop
  machinery. Playback starts instantly. Seeks respond instantly. The `read()`
  method never blocks on clock sync — it returns data as soon as it's decoded.

- **SendspinSyncDataSource** (grouped mode): Uses the full
  `ClockSynchronizer` to schedule every frame at `serverTs + 200ms headroom`
  (matching the sendspin-js reference implementation's phase). Has the startup
  gate (waits for clock convergence before playing), late-drop (discards frames
  too far behind), and per-chunk timeline alignment in the Oboe callback.

The mode is selected at `stream/start` based on whether the player is in a
group. Switching modes mid-stream is handled by creating a new DataSource.

### 4. Upgraded ClockSynchronizer (from Massdroid)

Port Massdroid's `ClockSynchronizer` to replace the current `ClockSync`. Key
improvements over our current implementation:

- **NTP-style two-dimensional Kalman filter** — tracks both offset and drift
  rate between client and server, with algebraic inverse matching the
  sendspin-js reference (`time-filter.ts`)
- **Adaptive forgetting** — when a residual exceeds `2σ`, all covariances are
  inflated by the forget factor (1.1²), allowing rapid recovery from network
  disruptions without losing the accumulated filter state
- **Drift significance gating** — the drift term is only used when
  `drift² > 4 × driftCovariance` (statistically significant), preventing noise
  amplification when drift is too small to measure reliably
- **Startup rejection backoff** — when RTT-gated samples are rejected during
  cold start (RTT > 150ms), the request interval ramps from 300ms to 3s instead
  of hammering the server at 3 requests/second
- **Soft reset** — preserves drift across reconnections while reseeding offset
  from a fresh measurement, avoiding the full cold-start convergence period
- **`isReadyForPlaybackStart()`** — requires ≥8 samples with ≤5ms error before
  allowing grouped playback to start, preventing out-of-sync startup

The `serverToLocalUs()` and `localToServerUs()` methods are algebraic inverses
that exactly match the sendspin-js reference implementation.

### 5. Oboe native output engine (from `cpp/`)

The C++ code in `cpp/audio_engine.cpp` and `cpp/flac_decoder.cpp` already
exists but is deliberately not compiled. This plan activates it, but refactored
to match Massdroid's architecture:

**Current `cpp/` code**: A complete but unactivated FLAC decoder + AAudio
output path that bypasses the Android mixer for true 24-bit I24 output.

**What we're adding** (ported from Massdroid's `sendspin_output_engine.cpp`):

- **Lock-free SPSC ring buffer**: 4-second buffer between the JVM decode thread
  (producer) and the Oboe callback (consumer). If the JVM GC pauses, the ring
  absorbs it — no audible dropout.

- **Per-chunk timeline alignment**: Every decoded PCM chunk carries its intended
  `CLOCK_MONOTONIC` presentation time. The Oboe callback compares "when will
  this sample leave the DAC?" vs "when should it play?" and corrects in
  real-time:
  - **Snap** (≥50ms drift): skip/insert frames instantly — inaudible while muted
  - **Resample** (1–50ms drift): run at slightly off 1.0× playback rate — smooth,
    click-free convergence
  - **Deadzone** (<1ms drift): pass through at exactly 1.0×

- **Dynamic range processing**:
  - Soft-knee compressor with 4 levels (off/soft/medium/hard)
  - Upward expansion (leveler) for quiet passages
  - Log-domain gain smoothing (no zipper noise)
  - Soft-clip ceiling for compressor overshoot

- **Noise-shaped TPDF dither**: Applied automatically when the output format is
  16-bit. Bypassed for 24-bit and float output.

- **Gain ramping**: Fast mute/unmute (5ms), slow volume changes (50ms). Zero
  clicks on any state transition.

- **Freeze/unfreeze**: On audio focus loss, fades to silence over 50ms, holds
  the ring buffer read position, and fades back in from exactly where it stopped
  when focus returns. The 4-second ring buffer means even a brief focus loss
  doesn't require a reconnect.

### 6. Stream lifecycle → MediaItem per track

Each Sendspin `stream/start` / `stream/end` cycle is one track. The DataSource
models this as:

- `stream/start` → `open()` the DataSource, synthesize a FLAC stream header
  from the `codec_header` field, begin feeding frames
- Audio frames → `read()` returns bytes, blocking until scheduled time
- `stream/end` → signal end-of-input, ExoPlayer transitions to next item
- `stream/clear` (seek) → `close()` the current DataSource, prepare a new one

Track changes become ExoPlayer `MediaItem` transitions, which gives us gapless
for free.

### 7. Codec header handling

The `stream/start` message carries a `codec_header` (base64-encoded STREAMINFO
for FLAC, or Opus headers). The DataSource synthesises a proper FLAC/Opus stream
header from this data before the raw frame data, so ExoPlayer's extractors can
parse the stream.

`FormatNegotiator` already advertises codecs. The `SendspinDataSource` reads the
negotiated format and reports it via `DataSpec` so ExoPlayer picks the right
extractor (FLAC, Opus, or raw PCM).

### 8. AudioAnalysisTap and Light Sync

`TapRenderersFactory` already installs `AudioAnalysisTap` into ExoPlayer's
render chain for the local player. Once MA audio flows through ExoPlayer, the
tap sees it automatically.

This gives MA playback:
- **Direct Hue Bridge sync** (the "Known Limitation" goes away entirely)
- **AudioLeadProbe** latency compensation
- **TrackScanner** pre-scan (next track analysed before it starts)
- **Stereo pan, vocal shimmer, fireworks** — all syncoV2 effects

### 9. What stays the same

- **SendspinClient** — WebSocket protocol unchanged, still handles
  `stream/start`, `stream/end`, `stream/clear`, `client/hello`, time sync
- **FormatNegotiation** — unchanged, still advertises codecs to MA
- **The Kalman filter math** — unchanged, just upgraded to the NTP-style version
  from Massdroid with adaptive forgetting and drift gating

## Implementation plan

### Step 1: Port ClockSynchronizer from Massdroid

Replace `ClockSync.kt` with the `ClockSynchronizer` class from Massdroid
(`sfortis/massdroid_native`). This is a pure-Kotlin class with no Android
dependencies beyond `System.nanoTime()`.

Key additions over our current `ClockSync`:
- `processTimeResponse()` with NTP-style 4-timestamp handling
- Adaptive forgetting for large residuals
- Drift significance gating
- Startup rejection backoff
- `isReadyForPlaybackStart()` gate
- `softReset()` with drift preservation
- `serverToLocalUs()` / `localToServerUs()` matching sendspin-js reference

### Step 2: Create SendspinDataSource + mode split

Create the ExoPlayer DataSource:

- `SendspinDataSource`: abstract base implementing
  `com.google.android.exoplayer2.upstream.DataSource`. Handles the WebSocket
  frame queue, codec header synthesis, and stream lifecycle (`open`, `read`,
  `close`).
- `SendspinSyncDataSource`: extends `SendspinDataSource`. In `read()`, blocks
  until `ClockSynchronizer.serverToLocalUs(frameTimestamp) + headroom` arrives.
  Has the startup gate (waits for clock convergence) and late-drop.
- `SendspinDirectDataSource`: extends `SendspinDataSource`. In `read()`, returns
  data immediately after a short 60ms headroom. No clock dependency. Anchors to
  `now` on every flush.

The mode is selected in `SendspinCoordinator` based on whether the player is
in a group.

### Step 3: Create SendspinMediaSource

Thin wrapper around `ProgressiveMediaSource` that:
- Builds a `MediaItem` with the `SendspinDataSource.Factory`
- Handles `stream/start` codec header synthesis
- Handles `stream/clear` (seek) by invalidating and re-preparing
- Reports the format from `FormatNegotiator` so ExoPlayer picks the right
  extractor

### Step 4: Port and activate Oboe native output

The C++ code in `cpp/` already exists but needs to be refactored to match
Massdroid's `SendspinOutputEngine` architecture. Port from Massdroid:

- Lock-free SPSC ring buffer (JVM producer, Oboe consumer)
- Per-chunk timeline alignment (snap/resample/deadzone)
- Dynamic range compressor with 4 levels
- TPDF dither for 16-bit output
- Gain ramping (fast mute, slow volume)
- Freeze/unfreeze on audio focus loss
- DAC presentation time alignment (matches `serverToLocalUs()` output)

Update `app/build.gradle.kts` to compile the NDK code (remove the disable flag).

### Step 5: Create OboeRenderer

Custom ExoPlayer `Renderer` that bridges ExoPlayer's decode output to the Oboe
native output:

- Receives decoded PCM from ExoPlayer's codec pipeline
- Writes to the SPSC ring buffer (producer side)
- The Oboe callback reads from the ring buffer (consumer side)
- Each chunk carries its `CLOCK_MONOTONIC` presentation time for timeline
  alignment
- On audio focus loss, signals the native layer to freeze (fade out, hold
  position). On focus return, signals unfreeze (fade in, resume).

This replaces `MediaCodecAudioRenderer`'s `AudioTrack` output with the Oboe
path. The `AudioAnalysisTap` still sits in the render chain *before* this
renderer, so it sees decoded PCM before the ring buffer — Light Sync works.

### Step 6: Wire ExoPlayer for Sendspin playback

Create a second ExoPlayer instance (or reuse the local player with careful
queue management) configured with:

- `TapRenderersFactory` for the analysis tap
- `OboeRenderer` for native output
- `SendspinMediaSource` as the media source
- `media3-session` for the notification (merge with `SendspinService`)
- Audio focus handling (automatic from ExoPlayer)

Wire `SendspinClient`'s protocol messages to the new player:
- `stream/start` → create `SendspinSyncDataSource` or `SendspinDirectDataSource`,
  set as ExoPlayer's current media source
- Binary frames → feed to the DataSource's frame queue
- `stream/end` → signal end-of-input
- `stream/clear` → close DataSource, prepare new one
- Time sync responses → feed to `ClockSynchronizer`

### Step 7: Remove the old pipeline

Delete:
- `SendspinAudioEngine.kt` — the monolithic MediaCodec + AudioTrack engine
- `StreamContinuity` — hand-built gapless, replaced by ExoPlayer's
- `ClockSync.kt` — replaced by `ClockSynchronizer`
- Manual `AudioManager` focus handling in `Playback.kt`
- Manual `MediaSessionCompat` in `SendspinService`

Update `SendspinService` notification to use `media3-session`.

### Step 8: Test and validate

- **Sync accuracy**: Measure MA speaker group latency before and after, target
  ≤10ms vs Massdroid reference
- **Solo mode**: Verify instant start, instant seek, no clock dependency
- **Grouped mode**: Verify startup gate (wait for convergence), late-drop,
  per-chunk alignment
- **Light Sync**: Verify direct Hue Bridge sync works on MA playback (the
  "Known Limitation" is gone)
- **Gapless**: Verify track transitions on MA path
- **Audio focus**: Verify ducking, pause on call, resume with freeze/unfreeze
- **Dynamic range**: Verify compressor levels, dither, gain ramping
- **24-bit output**: Verify ENCODING_PCM_FLOAT and ENCODING_PCM_24BIT_PACKED
  still work through the Oboe path
- **Audio focus loss recovery**: Verify freeze/unfreeze with buffer preservation
- **ReplayGain**: Verify `applyGain()` works on MA path
- **Media session**: Verify unified notification for both backends

## Trade-offs

| Factor | Current (MediaCodec+AudioTrack) | After (ExoPlayer+Oboe) |
|--------|----------------------------------|------------------------|
| **Light Sync (direct Hue)** | ❌ Not connected | ✅ Same as local path |
| **Light Sync (HA → syncoV2)** | ✅ Works | ✅ Still works |
| **Clock sync accuracy** | ✅ Kalman filter, tested | ✅ Upgraded NTP-style Kalman with drift gating |
| **Timeline alignment** | ❌ Schedule-only, no per-chunk correction | ✅ Per-chunk snap/resample/deadzone |
| **Solo mode latency** | ⚠️ Same engine, server-clock headroom | ✅ Anchors to now, starts instantly |
| **Grouped sync** | ✅ Works | ✅ Same + per-chunk correction |
| **GC immunity** | ❌ JVM thread, GC pauses cause underruns | ✅ Oboe SCHED_FIFO callback, ring buffer absorbs GC |
| **Gapless** | ⚠️ Hand-built, fragile | ✅ ExoPlayer built-in |
| **ReplayGain** | ❌ Not on MA path | ✅ Same applyGain() path |
| **Dynamic range** | ❌ None | ✅ Compressor + dither + soft-clip |
| **Audio focus loss** | ❌ Drops stream, must reconnect | ✅ Freeze/unfreeze with buffer preservation |
| **Audio focus** | ⚠️ Manual AudioManager | ✅ Automatic via ExoPlayer |
| **Media session** | ⚠️ Manual MediaSessionCompat | ✅ media3-session |
| **Codec lifecycle** | ⚠️ Manual, error-prone | ✅ ExoPlayer manages |
| **24-bit output** | ✅ ENCODING_PCM_FLOAT | ✅ Same + Oboe I24 path |
| **Bit-perfect (I24)** | 🔲 Written, not compiled | ✅ Activated |
| **Maintenance burden** | High (920-line monolith) | Low (ExoPlayer battle-tested + focused C++ output) |

## Risks and mitigations

1. **Clock sync regression.** The DataSource `read()` must block at the right
   time with microsecond precision. Mitigation: port Massdroid's proven
   `ClockSynchronizer` verbatim (it matches the sendspin-js reference
   implementation), and the Oboe callback adds per-chunk correction on top.

2. **Latency increase from ExoPlayer buffering.** ExoPlayer has its own buffer
   that may add latency. Mitigation: set a low buffer duration
   (`ExoPlayer.Builder.setLoadControl()`), and the Oboe ring buffer's
   per-chunk alignment corrects for any drift.

3. **Stream/clear (seek) handling.** ExoPlayer expects seekable sources;
   Sendspin streams are non-seekable. Mitigation: `stream/clear` drops the
   current source and creates a new one — the same pattern ExoPlayer uses for
   live streams.

4. **Codec header synthesis.** FLAC needs STREAMINFO before audio frames. The
   `codec_header` field carries this. Mitigation: prepend the decoded header
   bytes in the DataSource before the first frame data.

5. **Two ExoPlayer instances.** Running both Sendspin and local playback through
   ExoPlayer means two instances sharing audio focus. Mitigation: the current
   code already stops one when the other starts (`setBackend`);
   ExoPlayer's `setHandleAudioBFocus` handles this automatically.

6. **NDK build complexity.** The Oboe native code adds NDK build time and
   platform considerations (arm64-v8a, armeabi-v7a, x86_64). Mitigation:
   Massdroid already ships this across all architectures; we can reuse their
   CMake configuration and CI setup.

7. **Oboe callback thread safety.** The real-time callback must never block or
   allocate. Mitigation: the SPSC ring buffer is lock-free by design; the C++
   callback does no allocation, no JVM calls, no locks.

## What this enables (future, not blocked)

Once the Sendspin player uses ExoPlayer + Oboe:

- **Direct Hue Bridge sync from MA playback** — trivial, since
  `AudioAnalysisTap` already sits in ExoPlayer's render chain
- **Android Auto** — `media3-session` is already a dependency; extending the
  `MediaSession` for MA is a manifest declaration and a browse tree
- **Unified notification** — one `PlayerNotificationManager` for both backends
  instead of two separate notification channels
- **Equalizer** — ExoPlayer supports audio effects via `AudioProcessor`; adding
  an EQ is a matter of inserting a processor into the render chain
- **Crossfade** — ExoPlayer supports crossfade transitions between `MediaItem`s