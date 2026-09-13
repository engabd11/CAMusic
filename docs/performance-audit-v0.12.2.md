# CAMusic v0.12.2 — Live Device Audit: Battery, DSP, Light Sync, Sendspin Sync

**Author:** Claude (AI analysis, driven over adb)
**Date:** 13–14 September 2026
**Scope:** Live audit of v0.12.2 (`versionCode 66`) on a Samsung Galaxy S22 Ultra
(SM-S908E, Android 16, 1440×3088 @ 120 Hz) with Music Assistant 2.10.3 at
`192.168.0.48:8095` (Sendspin on `:8927`), Navidrome at `192.168.0.210:4533`, Jellyfin at
`192.168.0.156:8096`, a direct-paired Hue bridge at `192.168.0.142`, and a `sendspin-cli`
7.5.0 player on a Windows PC. Every finding below was reproduced on the device; every fix
was rebuilt, reinstalled and re-measured. The fixes are in the same PR as this document.

---

## 1. Executive summary

| # | Finding | Severity | Status |
|---|---------|----------|--------|
| F1 | Every `MaApiClient.connect()` opened a new WebSocket and forgot the old one — 6 sockets to MA after one setup session | P0 | **Fixed** |
| F2 | The Oboe output stream never idled after a pause: 7 % of a core, screen off, indefinitely | P0 | **Fixed** (→ 1.4 %) |
| F3 | Media session `invalidateState()` on every 250 ms position tick, cloning artwork ×3 each time | P1 | **Fixed** (main thread 5 % → 0.9 %) |
| F4 | `MaNowPlaying`'s event-driven refresh had no background gate and parsed on the main thread | P1 | **Fixed** |
| F5 | Play button dead after a queue ended (`play()` is a no-op in `STATE_ENDED`) | P1 | **Fixed** |
| F6 | Hue bridge id hex-encoded twice on mDNS discovery → every bridge call after pairing failed hostname verification | P0 | **Fixed** + self-heal |
| F7 | Grouped playback: the phone played ~96 ms early ("unreported HAL latency" from the wrong output path) | P0 | **Fixed** |
| F8 | The phone never advertised `set_static_delay`, so MA offered it no static playback delay | P1 | **Fixed** |
| F9 | Media session retired 60 s into a pause; media keys then went to another app | P2 | **Fixed** (kept while an item remains, 30 min cap) |
| F10 | Glass backdrop blur: ~3 ms RenderThread per frame, a fifth of a core with the wave bar, invisible on today's backdrop | P2 | **User toggle**, default off |
| F11 | Emotional Arc's `structureSeen` was session-scoped despite its per-track doc | P3 | **Fixed** |
| F12 | The **debug** build had been installed as "0.12.2" (Android Studio Run) | — | Caveat, see §2 |

Not changed, with measurements: Oboe 2 ms callbacks (§6.2), light-show render at 60 fps
(§6.3), native `LOGD` in release (§6.4), auto light-sync mode following the library
(§6.5), and the Sendspin endpoint on `:8095` (§7.4).

---

## 2. Environment & methodology

### 2.1 The debug-build trap

The phone had **`app-mobile-debug.apk`** (175 MB, `DEBUGGABLE TEST_ONLY`, dexopt
`run-from-apk`) installed by Android Studio's Run. Release APKs are ~85 MB. Every number
in this document is from the **release** build (`assembleMobileRelease`, installed with
`adb install -r` — same debug signing key, data intact — then
`cmd package compile -m speed-profile -f`). Check `dumpsys package <pkg> | grep flags=`
before trusting any CPU figure.

### 2.2 Measurements used

- **Per-thread CPU**: diff of `utime+stime` from `/proc/PID/task/*/stat` over 60 s. OkHttp
  WebSocket reader threads all truncate to `8.0.48:8095/...`; which fd a thread reads is
  `run-as … cat /proc/PID/task/TID/syscall` (207 = `recvfrom`, arg 1 = fd) → `/proc/PID/fd`
  inode → `/proc/net/tcp6` (debuggable builds only).
- **Sockets**: `ss -tn | grep -c ':8095'`; uid rows in `/proc/net/tcp6`.
- **Frames**: `dumpsys gfxinfo <pkg> framestats` after `reset`; the PROFILEDATA CSV gives
  main-thread, RenderThread and GPU stages per frame. "GPU time" on this phone is mostly
  the GPU idling at its lowest clock and is **not** a usable proxy — RenderThread CPU is.
- **Network**: `dumpsys netstats --poll` then `--uid`. The foreground service keeps the
  uid in the FOREGROUND set even with the screen off.
- **Battery attribution on USB**: `dumpsys battery unplug` (reverted with `reset`).
- **UI driving**: `uiautomator dump` + `input tap`; media keys work through the lock screen
  until the session retires.
- **Music Assistant**: an independent Node WebSocket client (`auth/login` → `auth`,
  partial-chunk accumulation) for `players/all`, `config/players/dsp/*`,
  `config/players/get|save`, `players/cmd/*`.
- **PC output latency**: a chirp through PortAudio with `latency='high'`, captured on the
  Realtek "Stereo Mix" loopback (WDM-KS), cross-correlated; `currentTime` in both callbacks
  shares the QPC base.

---

## 3. Battery and resource findings

### 3.1 F1 — Music Assistant WebSocket leak

`MaApiClient.connect()` dialled a new socket without closing the previous one, and its
listener never checked `webSocket === ws`, so a stale socket's `onClosed` scheduled a
reconnect that replaced a healthy one. Every view model that needs MA calls `connect()`
in `init`, and the settings page calls it on every save and library switch.

- Observed: **6 established sockets** to `:8095` after onboarding + a few library switches.
  Only the newest was authenticated — the handshake answers through `ws`, which by then
  pointed at a newer socket — so the rest were zombies holding a thread each and pinging
  every 30 s for the life of the process.
- Reproduced deterministically on the release build: each switch to the MA library added
  one (2 → 3 → 4 → 5).
- Fix: `connect()` is a no-op when already connected/connecting to the same server with
  the same credentials; `dial()` closes the previous socket; every listener callback is
  ignored unless it is for the current socket. `HaClient` hardened the same way.
- Verified: **stays at 2** (Sendspin + API) through six library switches and the
  Speakers/Settings tabs.

### 3.2 F2 — Oboe output never idled on pause

Three compounding defects in `SendspinNativeEngine`:

1. `scheduleIdleStop()` did `removeCallbacks` + `postDelayed(5 s)` on **every** 10 ms poll
   of the empty queue, restarting its own countdown forever.
2. A pause takes the DISCARD path (`cutTail`), which never set `endOfStreamSignalled`, so
   the loop never asked for the idle stop at all.
3. After `cutTail` cleared `playbackStarted`, the loop spun in the *startup gate*
   (`sleepMs(10)`) and never reached the branch that arms the idle stop.

Net effect: a paused MA session kept the Oboe stream started (AAudio LowLatency,
96-frame / 2 ms bursts → ~500 callbacks/s rendering silence) and the `SendspinTimeline`
thread polling.

| State (release build, screen off) | Before | After |
|---|---|---|
| MA paused | **7.0 %** of a core (`AAudio_1` 4.6 %, `SendspinTimeline` 1.5 %) | **1.4 %** |

Fix: arm once (`idleStopArmed`), set `endOfStreamSignalled` on both stream-end paths, and
arm from the startup gate too. Verified: "Idle 5000ms: stopping native output + producer"
logs after a pause; both threads exit; resume restarts the stream ("Output resumed from
idle stop" → "Synchronized" in ~20 ms), including across two HA → MA TTS announcements.

### 3.3 F3 — 4 Hz media-session storm

`SendspinService.ShadePlayer` (a `SimpleBasePlayer`) used a static
`setContentPositionMs(latestPositionMs)` and the service called `invalidateState()` on
every 250 ms projected-position tick. Each invalidate rebuilt three `MediaItemData`s —
`MediaMetadata.Builder.setArtworkData` **clones** the cover bytes into each — diffed the
state, and shipped a `PlayerInfo` to every session controller over binder.

Fix: an extrapolating `PositionSupplier`, invalidation only on a re-anchor (> 600 ms
drift from the extrapolation) or a play/pause flip, and a cached playlist keyed on the
metadata and artwork identity.

| MA playing, screen off (release) | Before | After |
|---|---|---|
| Process | 23.0 % of a core | **18.1 %** |
| Main thread | 5.0 % | **0.9 %** |
| `HeapTaskDaemon` (GC) | 1.0 % | gone from the top ten |

What remains is the audio path itself: AAudio callback 6.6 %, `SendspinTimeline` 3.3 %,
FLAC decode 3.1 % — see §6.2.

### 3.4 F4 — Background MA reads

`MaNowPlaying`'s 5 s poll was gated on foreground/remote-active; the event-driven
`refresh()` (sampled to 300 ms) was not, and MA sends every client every event. Any other
MA player's activity re-read `players/all` + `player_queues/all` — this instance has 22
players — parsed into models on **`Dispatchers.Main`**, screen off. Fix: the same
`wantsLiveReads()` gate on both paths, and `MaRepository.players()/queues()` parse on
`Dispatchers.Default`.

### 3.5 F5 — Play after the queue ended

`LocalPlayer.resume()` only re-prepared on `STATE_IDLE`; media3's `play()` in
`STATE_ENDED` does nothing. Tapping Play at 2:52 / 2:52 did nothing. Fix: `seekTo(0, 0)`
first — Play means "again, from the top". Verified.

### 3.6 Now Playing rendering

- Plain seek bar: the 250 ms ticker redraws the window ~8×/s; each frame ~7.7 ms
  RenderThread. Total on the Now Playing screen, MA playing: **33 %** of a core.
- **Wave seek bar** (reads its infinite-transition phase in composition): 2 295 frames /
  30 s, **107 % of a core** (RenderThread 68 %, main 22 %). Chameleon bloom itself is
  cheap (radial gradients).
- The two 64 dp album-wash blurs were suspected and tested: pre-blurring them at decode
  time changed nothing measurable (RenderThread 66 % vs 68 % with the wave bar) — reverted.
  Wrapping them in an offscreen compositing layer made it *worse* (RT 7.7 → 12.8 ms/frame);
  HWUI does not cache `RenderEffect` output across frames.
- The **glass backdrop blur** (`glassSurface` → 24 dp `BlurEffect` on the shared layer,
  drawn by the source and every panel) was the RenderThread cost: **7.6 → 4.5 ms/frame**
  without it, ~21 % of a core with the wave bar. The only backdrop ever recorded is the
  64 dp-blurred wash under a smooth gradient, so the extra blur is a 64 → 68 dp blur of a
  featureless image: with and without, the Now Playing pills differ by **≤ 3/255 per
  channel**. Now a user setting — *Interface & Appearance → Motion & bloom → Glass blur* —
  default off, cost stated on the card.

### 3.7 Idle, nothing playing

Debug build, Navidrome backend, app backgrounded, screen off: 2.9 % of a core, 149 KB
received per 2 min — all of it MA event traffic through the leaked sockets plus the
ungated event refresh (F1, F4).

### 3.8 F9 — Session retired while paused

`IDLE_GRACE_MS = 60 s` retired the media session a minute into any pause, because MA ends
the Sendspin stream for a pause exactly as for a stop — and reports the paused phone as
`idle` on both player and queue, with the current item and elapsed kept (verified: a real
`stop` reads identically). The session now stays while MA still has an item to resume,
capped at 30 min. Verified: session present and the media Play key resumes 100 s into a
pause (previously the key went to YouTube Music).

---

## 4. DSP verification

- **Navidrome (`LocalDsp` in ExoPlayer)**: the Samsung `c2.sec.flac.decoder` hands
  ExoPlayer 16-bit PCM for a 48/24 FLAC with bit-perfect off (expected), so the EQ sits in
  the int16 processor chain. The in-app visualiser is normalised and useless as a meter, so
  the check was CPU on `ExoPlayer:Playback`: flat 418 → nine bands at −12 dB 573 → flat
  460 ticks/min. The biquad cascade only runs when bands are active, and it does.
- **Music Assistant**: set from the phone's own sheet (+7.12 dB peak at 1 kHz, Q 1, output
  −11.8 dB) → `config/players/dsp/get` on the server returned exactly that; the sheet
  re-reads it; restoring from the phone brought the server back to off/flat. MA **applies**
  it to the Sendspin stream (a −20 dB output gain was audibly quieter). MA 2.10.3 returns
  **no `dsp` key** in a Sendspin player's `streamdetails`, so the app's "what MA is doing"
  banner has nothing to show there — not fixable app-side.

---

## 5. Light sync (direct bridge, "Office full music", 3 lamps)

### 5.1 F6 — bridge id double-encoded

`HueBridgeClient`'s mDNS `onServiceResolved` hex-encoded the `bridgeid` TXT bytes, which
are already the sixteen hex characters as ASCII, so the stored id was 32 characters
(`6563…3336` for `ecb5fafffe98a536`) and `bridgeIdVerifier` could never match the
certificate's CN: **"Hostname 192.168.0.142 not verified"** on the room list, with pairing
itself having succeeded. Fix: decode as ASCII, and `normaliseBridgeId()` self-heals a
stored double-encoded id so no re-pair is needed. Unit-tested. Verified: room list loads.

### 5.2 Layers, one at a time

`adb shell setprop log.tag.LightLayers DEBUG` now makes `LayerChain` log once a second the
context every layer sees (phase, bpm, key, stems, position, energy, beat) and each layer's
mean RGB change; `DirectLightSync` logs every auto-intensity pick with its character
score. On an analysed funk album:

| Layer | Alone | Reads as |
|---|---|---|
| Music DNA | 0.06–0.08, steady | active (needs a scan; it had one) |
| Emotional arc | 0.000 through 45 s of STEADY | by design — silent until a build/drop/breakdown; its end-of-track warm fade did show |
| Phantom stage | ~0.22 with stems, ~0.23 on the band proxy, `stems=` reported correctly | active |
| Phone conductor | 0.000 still → 0.16 while moved → decays | active |

Auto intensity moved Medium ↔ Subtle only: the per-song *character* caps the rung window
(`CHAR_BAND_ANCHORS`); below roughly pop/house character the High cell is unreachable by
design. Enabling Phantom stage inserts a "Real instrument separation" row above Phone
conductor — drive the toggles by label.

### 5.3 Cost

Local playback + light sync, screen off: **58 % of a core** — `light-sync-anal` 26.5 %,
the 60 fps render loop (`STREAM_FPS`) and its `delay` timer ~18 %, ExoPlayer 7.4 %. Hue
lamps update at ≤ 25 Hz; see §6.3.

### 5.4 F11 — Emotional Arc across tracks

`structureSeen` is documented "one-way within a track" but was only reset at a session
boundary, so a quiet album after one with drops inherited the cool tint. Added
`LightShowLayer.onTrackChanged()` (default no-op; Phantom Stage keeps its per-session
positions) called from `DirectLightSync.onTrackChanged`; Emotional Arc clears the flag
there and lets the temperature ease out. Unit-tested.

---

## 6. Measured and left alone

### 6.1 Wave seek bar
A design choice with a documented cost (§3.6). The glass toggle recovers about a fifth of
a core of it.

### 6.2 Oboe 2 ms callbacks
`LowLatency` + `setBufferSizeInFrames(burst × 2)` = 96-frame callbacks even solo
(`AAudio_1` ≈ 6.6 % of a core while playing). `setFramesPerDataCallback(burst × N)` would
cut wakeups N-fold, but the sync servo's drift EMA (`/8`, `/64`) and outlier logic are
*per callback*, so its time constants would change with N; not behaviour-neutral without
re-deriving them and a listening test in a group.

### 6.3 Light-show render rate
`STREAM_FPS = 60` sends 60 DTLS frames/s to lamps that update at ≤ 25 Hz; 25–30 would
halve the render/dispatch cost with no visible change. Left as a candidate toggle.

### 6.4 Native `LOGD` in release
~200 lines/min while streaming. A runtime gate
(`__android_log_is_loggable`, re-enabled with `setprop log.tag.SendspinNative DEBUG`) is
the recommended form; not done pending a decision.

### 6.5 Auto light-sync transport
`lightSyncModeAuto` follows the library: MA → Home Assistant form even with a paired
direct bridge. The manual override in Settings → Light Sync covers it; a soft dead end.

---

## 7. Sendspin group sync

### 7.1 Setup
Phone (`up597f2ceb`, `universal_player`) leading, `PC sendspin cli` joined via
`players/cmd/group`. MA reports both `playing` at the same elapsed. Both endpoints
(`:8095/sendspin` behind the MA token, `:8927/sendspin` open) are the **same** Sendspin
server: identical `server_id`, clocks within 0.23 ms.

### 7.2 The phone, checked against the spec and the reference client
- Clock: `ClockKalmanFilter` is a port of the reference time-filter; T4 stamped at socket
  receipt; RTT ~1 ms; filter error 0 ms. Cannot be biased by tens of ms.
- Scheduling: each chunk's first sample is placed at `serverToLocal(ts)` on the HAL's own
  DAC timeline (`getTimestamp(CLOCK_MONOTONIC)`); BOOTTIME → MONOTONIC bridged at the JNI
  boundary (`bootToMonotonicUs`; the two differ by 43.7 s of suspend on this phone).
- Lock: 56 drift samples/min, mean +0.10 ms, range −0.79…+0.87 ms, zero underruns,
  `grouped=true` throughout.
- **F7**: in SYNC mode the engine subtracted `halOutputLatencyUs − outputLatencyUs`
  (109 − 13 = 96 ms) from every presentation time. `halOutputLatencyUs` came from the
  hidden `AudioManager.getOutputLatency(STREAM_MUSIC)`, which describes the *primary
  deep-buffer output*, not the MMAP stream the engine plays through (whose own
  `calculateLatencyMillis` is a stable 13 ms). The phone therefore played 96 ms early in
  every group and never solo. Removed.
- **F8**: the spec puts `set_static_delay` in the `client/state` player object (hello may
  only list volume/mute — listing it there makes MA close the socket after `client/hello`,
  found the hard way). Advertised there now; MA creates the phone's "Static playback delay"
  entry.

### 7.3 The PC, measured
Chirp through PortAudio vs the Stereo Mix loopback, group paused for silence:

| Output path | PortAudio's claimed lead | Audio actually left the mixer |
|---|---|---|
| MME, device 3 (the CLI's default) | 182 ms | **+44 / +52 / +58 ms later** than claimed |
| WASAPI, device 13 | 17–37 ms | **+84 ms later** than claimed |

The CLI schedules against `outputBufferDacTime`, which is honoured; the Windows audio
engine and Realtek driver add 50–85 ms *after* the point PortAudio can see — precisely the
"delay after the device's audio port" the spec's per-player delay is for. This is the
remaining "phone ahead": the PC is late. A 60 ms static delay was applied to the PC via
MA (`sendspin_static_delay`; positive = play earlier) for the listening test — **still
open** at the time of writing, continued in the follow-up.

### 7.4 Endpoint
The app derives `ws://host:8095/sendspin` from the MA base URL. It works (same server,
token-gated), but the MA docs reserve that path for the built-in web player and give
`:8927/sendspin` as the Sendspin server. Switching to 8927 with a fallback is part of the
follow-up.

---

## 8. HA → MA TTS announcements (phone in the background, screen off)

Two announcements, both clean on the phone: `stream/end` → announce volume 75 →
`stream/start` → "Synchronized" in 22 ms and 11 ms → music volume restored → music
`stream/start` → "Synchronized". The idle stop never got in the way (1.5 s gap < 5 s
grace). Server-side observation: MA resumed the music 5.3 s and 1.8 s past where it
paused it — the song jumps forward after a TTS; that is MA's bookkeeping, not the app.
