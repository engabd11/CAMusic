# Plan: Route Sendspin Audio Through ExoPlayer

## Status: Proposed

## Problem

The Sendspin (MA) player path uses a hand-built `MediaCodec` → `AudioTrack` pipeline
that manages everything manually — codec lifecycle, audio focus, gapless transitions,
clock scheduling. It works, but:

- **No Light Sync for MA playback.** The `AudioAnalysisTap` sits inside ExoPlayer's
  render chain (via `TapRenderersFactory`). The Sendspin path bypasses ExoPlayer
  entirely, so direct Hue Bridge sync cannot see MA audio. Only the HA → syncoV2
  path works for MA, and that has HA's WebSocket latency in the loop.
- **No ReplayGain on the MA path.** The local path applies ReplayGain in
  `LocalPlayer.applyGain()`; the MA path doesn't.
- **No media3-session integration.** The MA path uses a manual
  `MediaSessionCompat` and `AudioManager` for audio focus. The local path gets
  this for free from ExoPlayer.
- **Fragile gapless.** `StreamContinuity` hand-manages codec reuse and track
  boundaries. ExoPlayer's built-in gapless is battle-tested.
- **Maintenance burden.** Every codec lifecycle issue, every AudioTrack underrun,
  every focus duck is hand-debugged.

The local player (Navidrome/Jellyfin/downloads) uses ExoPlayer and works flawlessly.
Both paths should use the same player framework.

## Goal

Route Sendspin audio through ExoPlayer via a custom `DataSource`, so the MA player
gains all of ExoPlayer's capabilities — and the `AudioAnalysisTap` can see MA
playback, giving it the same tight light sync that local playback has today.

## Architecture

### Current state

```
MA WebSocket binary frame
  → SendspinClient (protocol)
  → SendspinAudioEngine
    → MediaCodec (manual lifecycle)
    → AudioTrack (MODE_STREAM, manual scheduling)
    → ClockSync / Kalman filter (blocks until scheduled time)
```

Light Sync for this path: **not connected**. The `AudioAnalysisTap` only sees
ExoPlayer's render chain, which is the local player.

### Proposed state

```
MA WebSocket binary frame
  → SendspinClient (protocol, unchanged)
  → SendspinDataSource (new — wraps WebSocket frames as a DataSource)
  → ExoPlayer (ProgressiveMediaSource + SendspinDataSource)
    → TapRenderersFactory (already in place)
      → AudioAnalysisTap → Hue Bridge DTLS (direct)
    → AudioTrack (ExoPlayer manages)
  Clock sync: DataSource.read() blocks until Kalman filter says "go"
```

This is a single new `DataSource` + a thin `MediaSource` adapter. Everything
else is ExoPlayer infrastructure that already exists in the app.

## Key design decisions

### 1. Clock sync via pre-buffering in DataSource

The Sendspin protocol schedules each frame against a server clock. The current
engine uses `Thread.sleep()` to wait for the scheduled time, then writes to
AudioTrack. ExoPlayer has no concept of "play at server time X."

**Solution:** `SendspinDataSource.read()` blocks until the Kalman filter says the
current frame is due. ExoPlayer receives data only when it's time to play it. The
sync is transparent to ExoPlayer — it just sees data arriving at the right rate.

```
SendspinDataSource:
  open(DataSpec)    → start buffering WebSocket frames
  read(buf, off, len) → block until next frame is due (per ClockSync),
                        then copy frame bytes into buf
  close()           → stream/end or stream/clear
```

This preserves the multi-room sync accuracy. The Kalman filter's offset and drift
calculations are unchanged; only the scheduling mechanism moves from
`awaitFrameTime()` inside `SendspinAudioEngine` to `read()` inside the
DataSource.

### 2. Stream lifecycle → MediaItem per track

Each Sendspin `stream/start` / `stream/end` cycle is one track. The DataSource
models this as:

- `stream/start` → `open()` the DataSource, synthesize a FLAC stream header
  from the `codec_header` field, begin feeding frames
- Audio frames → `read()` returns bytes, blocking until scheduled time
- `stream/end` → signal end-of-stream, ExoPlayer transitions to next item
- `stream/clear` (seek) → `close()` the current DataSource, prepare a new one

Track changes become ExoPlayer `MediaItem` transitions, which gives us gapless
for free (ExoPlayer's built-in gapless, replacing `StreamContinuity`).

### 3. Codec header handling

The `stream/start` message carries a `codec_header` (base64-encoded STREAMINFO
for FLAC, or Opus headers). The DataSource synthesises a proper FLAC/Opus stream
header from this data before the raw frame data, so ExoPlayer's extractors can
parse the stream.

`FormatNegotiator` already advertises codecs. The `SendspinDataSource` reads the
negotiated format and reports it via `DataSpec` or `Resolver` so ExoPlayer picks
the right extractor (FLAC, Opus, or raw PCM).

### 4. AudioAnalysisTap and Light Sync

`TapRenderersFactory` already installs `AudioAnalysisTap` into ExoPlayer's render
chain for the local player. Once MA audio flows through the same ExoPlayer
instance (or a second one sharing the same `RenderersFactory`), the tap sees it
automatically.

This gives MA playback:
- **Direct Hue Bridge sync** (the "Known Limitation" goes away)
- **AudioLeadProbe** latency compensation
- **TrackScanner** pre-scan (next track analysed before it starts)
- **Stereo pan, vocal shimmer, fireworks** — all the syncoV2 effects

### 5. What stays the same

- **ClockSync and Kalman filter** — unchanged, still the authoritative time source
- **SendspinClient** — WebSocket protocol unchanged, still handles `stream/start`,
  `stream/end`, `stream/clear`, `client/hello`, time sync
- **FormatNegotiation** — unchanged, still advertises codecs to MA
- **The native AAudio I24 path** — remains a future bit-perfect output option,
  complementing rather than replacing ExoPlayer

## Implementation plan

### Phase 1: SendspinDataSource (core)

Create `SendspinDataSource` implementing `com.google.android.exoplayer2.upstream.DataSource`:

- `open(DataSpec)` — begin buffering WebSocket frames
- `read(byte[], int, int)` — block until scheduled time (per ClockSync),
  then copy frame bytes. Returns `C.RESULT_END_OF_INPUT` on `stream/end`
- `getUri()` — return a `sendspin://` URI for logging
- Close handling for `stream/clear` (seek) — invalidate and re-prepare

Create `SendspinMediaSource` wrapping `ProgressiveMediaSource`:

- Build `MediaItem` with the `SendspinDataSource.Factory`
- Handle `stream/start` codec header synthesis
- Handle `stream/clear` (seek) by creating a new source

### Phase 2: Clock sync adapter

Extract the clock-scheduling logic from `SendspinAudioEngine.awaitFrameTime()`
into the DataSource's `read()` method. The Kalman filter and `ClockSync` class
remain unchanged — they're pure math with no Android dependencies.

### Phase 3: Player integration

Replace `SendspinAudioEngine` + `Playback` with a second ExoPlayer instance
(or reuse the local player with careful queue management). Wire:
- `TapRenderersFactory` for the analysis tap
- `media3-session` for the notification (merge with `SendspinService`)
- Audio focus handling (automatic from ExoPlayer)

### Phase 4: Test and validate

- Sync accuracy: measure MA speaker group latency before and after, target ≤10ms
- Light sync: verify direct Hue Bridge sync works on MA playback
- Gapless: verify track transitions on MA path
- Audio focus: verify ducking, pause on call, resume
- Bit-perfect: verify 24-bit float output still works

### Phase 5: Clean up

- Remove `SendspinAudioEngine` (the old MediaCodec + AudioTrack path)
- Remove `StreamContinuity` (hand-built gapless, replaced by ExoPlayer's)
- Remove manual `AudioManager` focus handling in `Playback`
- Update `SendspinService` notification to use `media3-session`

## Trade-offs

| Factor | Current (MediaCodec+AudioTrack) | After (ExoPlayer) |
|--------|----------------------------------|--------------------|
| **Light Sync (direct Hue)** | ❌ Not connected | ✅ Same as local path |
| **Light Sync (HA → syncoV2)** | ✅ Works | ✅ Still works |
| **Clock sync accuracy** | ✅ Kalman filter, tested | ✅ Same filter, moved to DataSource |
| **Gapless** | ⚠️ Hand-built, fragile | ✅ ExoPlayer built-in |
| **ReplayGain** | ❌ Not on MA path | ✅ Same applyGain() path |
| **Audio focus** | ⚠️ Manual AudioManager | ✅ Automatic |
| **Media session** | ⚠️ Manual MediaSessionCompat | ✅ media3-session |
| **Codec lifecycle** | ⚠️ Manual, error-prone | ✅ ExoPlayer manages |
| **24-bit output** | ✅ ENCODING_PCM_FLOAT | ✅ Same via RenderersFactory |
| **Bit-perfect (I24)** | 🔲 Written, not compiled | 🔲 Still future phase |
| **Maintenance burden** | High | Low (ExoPlayer is battle-tested) |

## Risks and mitigations

1. **Clock sync regression.** The DataSource `read()` must block at the right time
   with microsecond precision. Mitigation: reuse the existing Kalman filter and
   `awaitFrameTime()` logic verbatim, just move it from the engine thread to the
   DataSource's read thread.

2. **Latency increase.** ExoPlayer has its own buffering that may add latency.
   Mitigation: set a low buffer duration (`ExoPlayer.Builder.setLoadControl()`)
   and measure end-to-end latency before and after.

3. **Stream/clear (seek) handling.** ExoPlayer expects seekable sources; Sendspin
   streams are non-seekable. Mitigation: `stream/clear` drops the current source
   and creates a new one — the same pattern ExoPlayer uses for live streams.

4. **Codec header synthesis.** FLAC needs STREAMINFO before audio frames. The
   `codec_header` field carries this. Mitigation: prepend the decoded header bytes
   in the DataSource before the first frame data.

5. **Two ExoPlayer instances.** Running both Sendspin and local playback through
   ExoPlayer means two instances sharing audio focus. Mitigation: the current code
   already stops one when the other starts (`setBackend`); ExoPlayer's
   `setHandleAudioBFocus` handles this automatically.

## What this enables (future)

Once MA audio flows through ExoPlayer, these become straightforward:

- **Direct Hue Bridge sync from MA playback** — the roadmap item becomes trivial,
  since the `AudioAnalysisTap` already sits in ExoPlayer's render chain
- **Android Auto** — `media3-session` is already a dependency; the `MediaSession`
  already exists for the local player; extending it for MA is a manifest declaration
  and a browse tree
- **Unified notification** — one `PlayerNotificationManager` for both backends
  instead of the current two separate notification channels