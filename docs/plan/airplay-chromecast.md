# AirPlay & Chromecast — Implementation Plan

> CAMusic casting: two protocols, two architectures, one coherent design.

## Executive summary

CAMusic has two playback modes today: **local** (ExoPlayer decodes on this phone)
and **remote** (`RemotePlayback` — a server-side player like MPD/foobar2000/MA owns
the queue, the phone is a remote control). AirPlay and Chromecast map to these two
modes respectively, but one of them is a fundamentally new shape.

| Protocol | Architecture in CAMusic | Library | License | GMS required? |
|---|---|---|---|---|
| **Chromecast** | `RemotePlayback` — Cast device fetches its own URL, phone is remote | `androidx.media3:media3-cast` | Apache-2.0 | **Yes** |
| **AirPlay** | New: `NetworkOutput` — phone still decodes, but streams PCM to an AirPlay receiver over the network | `airplay2-sender-cpp` (JNI) | Apache-2.0 | **No** |

Chromecast is the easier integration — media3's `CastPlayer` already implements the
`Player` interface, and the Cast device fetches media from a URL exactly like Music
Assistant does. But it requires Google Play Services, which contradicts CAMusic's
anti-corporate-surveillance stance and won't work on GrapheneOS.

AirPlay is the harder integration but the more aligned one — no Google dependency,
works on any device, local-first. The breakthrough is `airplay2-sender-cpp`: a
verified, Apache-2.0 licensed C++ AirPlay 2 sender that streams lossless ALAC to
modern Apple TV / HomePod / macOS. It's sans-I/O (no socket ownership), JNI-able
into CAMusic's existing NDK build, and ships in a real product (FXChainPlayer).

## Chromecast — `RemotePlayback` implementation

### How it works

The Cast device is a remote player. It fetches media from a URL the phone gives it,
decodes it itself, and plays to its own output. The phone is a transport remote.
This is architecturally identical to Music Assistant.

### Library

```kotlin
// build.gradle.kts
implementation("androidx.media3:media3-cast:1.10.1")
```

media3's `CastPlayer` implements the `Player` interface, so it slots into the
existing ExoPlayer-based `LocalPlayer` architecture with minimal friction.

### Design

`CastRemotePlayback` implements `RemotePlayback`:

- `setQueue(tracks, startIndex)` → build `MediaItem` list from `LocalTrack.streamUrl`,
  load onto the Cast device via `CastPlayer.setMediaItems()`
- `poll()` → read `CastPlayer` state (playing, position, duration, volume)
- `playAt()`, `pause()`, `resume()`, `next()`, `previous()`, `seekTo()` → Cast transport
- `setVolume()` → `CastPlayer.setVolume()`
- `setShuffle()`, `setRepeat()` → `CastPlayer.setShuffleModeEnabled()` / `setRepeatMode()`

### The URL problem

Cast devices can fetch URLs from the same LAN — so a Navidrome/Jellyfin/MPD stream
URL is directly castable. But:

1. **Auth headers**: Jellyfin requires an `X-Emby-Token` header. Cast's
   `MediaItem` supports `MediaMetadata` and custom HTTP headers via
   `MediaItem.Builder().setMimeType()` + the `MediaQueueItem` load request.
2. **Self-signed certs**: Cast won't trust a self-signed HTTPS cert. Use HTTP on
   LAN, or serve the Cast device a plain-HTTP stream endpoint.
3. **CORS / origin restrictions**: Cast's receiver loads via its own HTTP stack,
   so server CORS headers don't matter — it's not a browser.

### GMS gating

```kotlin
// Check at runtime — invisible on de-Googled devices
fun isCastAvailable(context: Context): Boolean {
    val pm = context.packageManager
    return runCatching {
        pm.getApplicationInfo("com.google.android.gms", 0)
        true
    }.getOrDefault(false)
}
```

The Cast button only appears when GMS is present. On GrapheneOS it's simply absent
— no crash, no error, no Google dependency loaded.

### Integration touch points

| File | Change |
|---|---|
| `audio/CastRemotePlayback.kt` | **New** — `RemotePlayback` impl wrapping `CastPlayer` |
| `audio/CastDiscovery.kt` | **New** — mDNS / Cast device discovery |
| `library/ServerConfig.kt` | Add `CAST` to `ServerKind`? **No** — Cast is an output, not a source. See below. |
| `audio/LocalPlayer.kt` | `remote` field can be set to `CastRemotePlayback` |
| `ui/screens/NowPlayingScreen.kt` | Cast button when `CastPlayer` is available |
| `app/build.gradle.kts` | Add `media3-cast` dependency |
| `AndroidManifest.xml` | Cast permissions + `CastOptionsProvider` |

### Why Cast is NOT a `ServerKind`

`ServerKind` describes a **library source** — somewhere to browse and play from.
Cast is not a library; it's an **output destination**. The user browses Navidrome,
then casts to the living room speaker. Cast is the `remote` set on `LocalPlayer`,
not a source in the picker. This mirrors how MPD works: MPD is both a source *and*
a remote, but Cast is only the remote half — the source is whatever the user
already has configured.

---

## AirPlay — `NetworkOutput` (new abstraction)

### How it works

Unlike Chromecast, AirPlay (RAOP) does **not** fetch its own media. The phone
still owns the queue, still decodes the audio — but instead of sending PCM to the
Android mixer, it **streams PCM over the network to an AirPlay receiver**.

This is an **output route**, not a remote player. It's closer to `AudioOutputs`
(USB DAC, Bluetooth, built-in speaker) than to `RemotePlayback`.

### Library: `airplay2-sender-cpp`

**Repository**: https://github.com/akustikrausch/airplay2-sender-cpp
**License**: Apache-2.0 (explicit patent grant — safe to ship)
**Language**: C++20 (uses only `std::span`, so gcc 10 / clang 14 minimum)
**Dependencies**: Mbed TLS 3.6 (fetched at configure time), ed25519 (vendored)
**Status**: Verified against Apple TV 4K + macOS in FXChainPlayer. Qt-free
standalone library landed. Sans-I/O state machine.

Why this is the right choice:

1. **AirPlay 2, not just AirPlay 1**. The old RAOP libraries (raop_play, etc.) only
   do AirPlay 1 / legacy RTSP. A 2024+ Apple TV *requires* the AP2 handshake:
   encrypted control channel, event channel + RECORD ordering, ALAC realtime
   stream, 30-second keep-alive. This library does all of it.
2. **Sender, not receiver**. Every other open-source AirPlay project (shairport-sync,
  owntone) is a *receiver*. This is one of the only *sender* libraries in existence.
3. **Sans-I/O**. The state machine owns no socket, no timer, no thread. It sends
   through a 6-method `RaopIo` interface (tcp connect/send/close, udp bind/send/close).
   This makes it trivially JNI-able — CAMusic's NDK layer can implement `RaopIo`
   with Android's `Socket` / `DatagramSocket` and drive the state machine from
   a Kotlin coroutine.
4. **Clean-room**. No Apple code, keys, or certificates. Reconstructed from
   protocol documentation. Relies on EU Directive 2009/24/EC Article 6
   (interoperability decompilation right).
5. **Apache-2.0 with patent grant**. MIT-licensed protocol code is risky for
   corporate codebases because it lacks an explicit patent grant. Apache-2.0
   has one. CAMusic is MIT, but this dependency is compatible.

### Design: the `NetworkOutput` abstraction

```kotlin
/**
 * An audio output that streams PCM over the network rather than to the
 * Android mixer. AirPlay is the first implementation.
 *
 * The phone still owns the queue and the decoder — ExoPlayer runs as
 * normal — but the audio sink is redirected from the device's DAC to
 * this output, which feeds PCM frames to a remote receiver.
 *
 * This is NOT a [RemotePlayback]: the queue, the decode and the Light
 * Sync tap all stay local. Only the output changes.
 */
interface NetworkOutput {
    /** Start streaming to the discovered device. */
    suspend fun connect(device: NetworkDevice)

    /** Stop streaming and release the session. */
    suspend fun disconnect()

    /** Whether audio is currently flowing to the remote device. */
    val connected: StateFlow<Boolean>

    /** Feed a buffer of 16-bit stereo PCM at the negotiated sample rate. */
    fun writePcm(buffer: ByteArray, offset: Int, length: Int)

    /** The remote device's volume, 0..1, or null when unsupported. */
    suspend fun setVolume(volume: Float)

    /** Name of the connected device, for the Now Playing badge. */
    val deviceName: StateFlow<String?>
}
```

### The AirPlay JNI bridge

```
app/src/main/cpp/
  airplay/                    ← airplay2-sender-cpp vendored
    airplay_crypto.h/.cpp
    raop_sender.h/.cpp
    raop_io.h
    raop_loop.h/.cpp
    ...
  airplay_jni.cpp             ← JNI bridge: implements RaopIo via Android sockets
  CMakeLists.txt              ← builds airplay_jni → libairplay_sendpin.so
```

The JNI bridge:
1. Implements `RaopIo`'s 6 methods using POSIX sockets (Android supports them).
2. Feeds PCM from ExoPlayer's `AudioProcessor` output into the sender's ring buffer.
3. Drives the sender's poll loop on a dedicated native thread.
4. Exposes `connect(ip, port, name)`, `disconnect()`, `writePcm()`, `setVolume()`
   to Kotlin via JNI.

### Tapping ExoPlayer's audio output

The key question: how does CAMusic get ExoPlayer's decoded PCM to feed the AirPlay
sender?

**Option A: `AudioProcessor` (preferred).**

media3's `AudioProcessor` chain sits between the decoder and the sink. CAMusic
already uses this — see `AudioAnalysisTap`, `VinylNoiseProcessor`, `LoFiProcessor`.
A new `AirPlayOutputProcessor` can:
- Capture the decoded PCM (16-bit stereo, the format AirPlay wants)
- Feed it to the JNI ring buffer
- Return the same PCM unchanged (pass-through — the Android mixer still works
  in parallel for local listening)

**Option B: `PlaybackCapture` (Android 10+).**

CAMusic already has `capture/PlaybackCapture.kt` using `MediaProjection` for the
Light Sync tap. This captures the system audio output. It could also feed AirPlay.
But it requires a screen capture consent dialog, which is poor UX for audio casting.

**Option A is the right choice.** It's in-process, no consent dialog, and
CAMusic already has the `AudioProcessor` infrastructure.

### AirPlay device discovery

AirPlay devices advertise via mDNS (`_raop._tcp` and `_airplay._tcp`). Android's
`NsD` (Network Service Discovery) can discover these. The `airplay2-sender-cpp`
repo notes a bundled mDNS browser is on their roadmap but not yet shipped, so
CAMusic does its own discovery on the Kotlin side:

```kotlin
// AirPlayDiscovery.kt
val nsd = NsdManager(context)
nsd.discoverServices("_raop._tcp", NsdManager.PROTOCOL_DNS_SD, callback)
```

### AirPlay 1 fallback

The library supports `--ap1` (AirPlay 1, plain RTSP) for older receivers
(shairport-sync, Apple TV 3, raw RAOP speakers). This should be automatic —
if AP2 pairing fails, fall back to AP1.

### Integration touch points

| File | Change |
|---|---|
| `audio/NetworkOutput.kt` | **New** — the output abstraction interface |
| `audio/AirPlayOutput.kt` | **New** — `NetworkOutput` impl, JNI bridge to native |
| `audio/AirPlayOutputProcessor.kt` | **New** — `AudioProcessor` that taps PCM for AirPlay |
| `audio/AirPlayDiscovery.kt` | **New** — mDNS discovery of `_raop._tcp` services |
| `cpp/airplay/` | **New** — vendored `airplay2-sender-cpp` source |
| `cpp/airplay_jni.cpp` | **New** — JNI bridge implementing `RaopIo` |
| `CMakeLists.txt` | Add airplay target → `libairplay_sendpin.so` |
| `audio/LocalPlayer.kt` | Wire `AirPlayOutputProcessor` into the audio chain when active |
| `audio/OutputMode.kt` | Add `AIRPLAY` as an output mode? Or keep separate. |
| `ui/screens/NowPlayingScreen.kt` | AirPlay button + device picker |
| `app/build.gradle.kts` | NDK already configured — add CMake target only |

### Where AirPlay sits in `OutputMode`

`OutputMode` today is: STANDARD → HIGH_RESOLUTION → PURE → DIRECT (AAudio to DAC).
AirPlay is orthogonal — it's not "more purity", it's a different destination. It
should be a **separate selector** alongside the output mode, not a rung on the
ladder. The user picks:

- **Output mode**: how the phone processes audio (Standard / Hi-res / Pure / Direct)
- **Output destination**: where the audio goes (Phone speaker / USB DAC / AirPlay device)

When AirPlay is the destination, `OutputMode.DIRECT` (AAudio exclusive) is
contradictory — there's no local DAC to be exclusive with. The UI should disable
DIRECT when AirPlay is selected, falling back to STANDARD (the processor chain
runs, including the AirPlay tap processor).

### Light Sync interaction

When AirPlay is the output, the Light Sync tap still works — the
`AirPlayOutputProcessor` is in the chain, and `AudioAnalysisTap` can sit after it.
The audio that reaches the AirPlay device is the same audio the tap analyses.
This is actually cleaner than the MA path, where the tap has to reconstruct timing
from a network stream.

---

## Implementation phases

### Phase 1: Chromecast (lower effort, higher Google dependency)

1. Add `media3-cast:1.10.1` dependency
2. Implement `CastRemotePlayback` — `RemotePlayback` wrapping `CastPlayer`
3. Implement `CastDiscovery` — device discovery via Cast SDK
4. GMS availability check — Cast button invisible without GMS
5. Wire into `LocalPlayer.remote` when a Cast device is selected
6. Handle auth headers for Jellyfin / self-signed cert workaround
7. UI: Cast button in Now Playing + device picker sheet

**Estimated effort**: ~3-5 PRs. The media3 `CastPlayer` does most of the work.

### Phase 2: AirPlay (higher effort, zero Google dependency)

1. Vendor `airplay2-sender-cpp` into `app/src/main/cpp/airplay/`
2. Build with CMake alongside existing native code (Mbed TLS fetch)
3. Implement JNI bridge (`airplay_jni.cpp`) — `RaopIo` via POSIX sockets
4. Implement `AirPlayOutput.kt` — Kotlin side of the JNI bridge
5. Implement `AirPlayOutputProcessor.kt` — `AudioProcessor` PCM tap
6. Implement `AirPlayDiscovery.kt` — mDNS `_raop._tcp` discovery
7. Wire `AirPlayOutputProcessor` into `LocalPlayer`'s audio chain
8. AirPlay 1 fallback for older receivers
9. UI: AirPlay button in Now Playing + device picker sheet
10. Device pairing flow (PIN entry for Apple TV, transient for HomePod/Mac)

**Estimated effort**: ~6-10 PRs. The JNI bridge and CMake integration are the
hard parts — CAMusic's NDK build is already set up for Oboe, so the
infrastructure exists, but Mbed TLS adds a fetch step and the C++20 requirement
needs checking against NDK r27.

### NDK r27 + C++20 compatibility

CAMusic uses NDK r27 (`27.0.12077973`). NDK r27 ships clang 18, which supports
C++20 including `std::span`. The `airplay2-sender-cpp` library requires C++20
but only uses `std::span` as the C++20 feature, so the floor is low. The existing
CMakeLists.txt uses `-std=c++17` — the AirPlay target needs `-std=c++20`.

### Mbed TLS dependency

`airplay2-sender-cpp` fetches Mbed TLS 3.6 at CMake configure time. For an
Android NDK build, this means:
- Either fetch Mbed TLS as a Gradle/CMake step (network needed once)
- Or vendor Mbed TLS into the repo (cleaner for CI, no network dependency at build time)
- Mbed TLS is Apache-2.0, compatible with CAMusic's MIT license

---

### What appears on the Apple TV screen

The AirPlay RAOP protocol carries DMAP-tagged metadata via `SET_PARAMETER`
requests. `RaopSender::setNowPlaying(title, artist, album, cover, coverMime)`
pushes this to the receiver, and the Apple TV renders its own Now Playing
screen from it: album artwork as a full-screen blurred background with a
centred art card, track title, artist and album. We control the *content*
(text + artwork), not the *layout* — the Apple TV always renders its own
Now Playing UI. This is a protocol limitation, not a library one.

1. **Chromecast stance**: The earlier question timed out. My recommendation is
   **gate it behind GMS availability** — implement it, but invisible on
   de-Googled devices. This serves users who have GMS without compromising the
   app's principles. If you'd rather skip it entirely, I'll focus solely on AirPlay.

2. **Priority order**: Chromecast first (easier, proves the casting UI) or AirPlay
   first (harder, but the one that matters for your daily-use device)?

3. **Mbed TLS handling**: vendor it in-repo (larger but CI-friendly) or fetch at
   CMake configure time (smaller repo, needs network at build)?