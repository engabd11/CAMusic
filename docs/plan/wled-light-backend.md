# WLED as a second Light Sync backend — Implementation Plan

> Same 60 Hz show, a second wire. Hue Entertainment gated behind an official
> bridge and Hue-branded bulbs; WLED is the open, DIY-LED standard that runs on
> a $5 ESP8266/ESP32 and any addressable strip. This plan generalises Light
> Sync's transport so WLED becomes the second backend without touching the
> show itself.

> **Paused after Phase 0.** Light Sync stays Hue-only for now — the next
> priority is `docs/plan/server-mdns-discovery.md` (automatic discovery of
> library servers) instead. The `LightBridge` extraction below is done and
> costs nothing to leave idle; picking this back up later means starting at
> Phase 1 (the WLED transport itself), not redoing Phase 0.

## Executive summary

Light Sync today is one pipeline with two halves that are already cleanly
split, even though nothing currently depends on the split:

1. **The show** — `SyncoEngine.render(frame, dt, beatgrid, structure, gesture)`
   in `hue/SyncoEngine.kt:1090` takes an `AnalysisFrame` and returns
   `Map<Int, Rgb>`: normalised 0..1 RGB keyed by an abstract channel id. It
   knows nothing about Hue. Position-aware effects (`SpatialWaves.kt`) work
   off `roomPositions: Map<Int, Vec3>` and a `RoomTopology` (LINE / RING /
   CLUSTER / …), both derived from channel positions — not from anything
   Hue-specific either.
2. **The wire** — `HueStreamEncoder.buildPackets()` (`hue/HueStreamEncoder.kt`)
   turns that same `Map<Int, Triple<Float,Float,Float>>` into HueStream v2
   datagrams (gamut-clamped xy+brightness, 36-byte config UUID, ≤20 channels
   per packet), and `DirectLightSync.kt` (`hue/DirectLightSync.kt`) owns the
   Hue-specific session lifecycle around it: mDNS/CLIP v2 bridge discovery
   (`HueBridgeClient`), DTLS-PSK handshake and keepalive (`HueDtlsClient`),
   entertainment-area fetch (`EntertainmentConfig`/`EntertainmentChannel`),
   and the bridge's "one streaming client at a time" semantics.

Nothing about (1) needs to change. This plan extracts (2) behind a small
`LightBridge` interface, ports the existing Hue code onto it with **no
behaviour change**, and adds a `WledLightBridge` as the second
implementation. WLED's realtime protocol is UDP, unencrypted, and needs no
pairing beyond knowing the device's IP — which fits CAMusic's "everything
happens on your own network" posture even more directly than Hue's DTLS
session does.

| | Hue | WLED |
|---|---|---|
| Discovery | mDNS + Philips cloud fallback (`HueBridgeClient`) | mDNS `_wled._tcp.local` (`NsdManager`, same mechanism `AirPlayDiscovery` already uses) |
| Pairing | Link-button POST, returns `username` + `clientkey` (PSK) | None — realtime UDP has no auth. First frame just starts driving the strip. |
| Session | DTLS-PSK, port 2100, `action:start`/`action:stop` on the bridge, ~10s keepalive | Connectionless. "Session" is local-only: stop sending, optionally send a timeout byte. |
| Wire format | HueStream v2 binary, xy+brightness, ≤20 channels/packet | WLED DRGB: `[0x02 protocol][timeout s][R G B]×N`, one packet, any LED count |
| Addressing | Bridge → entertainment area → up to 20 lights, each with an xy position from the Hue app | One or more WLED controllers, each driving a strip of N pixels; position comes from how the user physically laid the strip out |
| Colour space | xy chromaticity, gamut-clamped per bulb | Raw sRGB, no gamut correction needed (LEDs render what they're sent) |

## Design

### 1. The `LightBridge` interface

New file: `hue/LightBridge.kt`.

```kotlin
/**
 * Whatever turns [SyncoEngine]'s per-channel RGB into light on a wire.
 *
 * The show (SyncoEngine + the layer chain + FieldSafety) never sees this —
 * DirectLightSync renders once per frame and hands the same
 * Map<Int, Rgb> to whichever bridge is active. A bridge owns everything
 * downstream of that map: transport, wire format, session lifecycle,
 * reconnect policy.
 */
interface LightBridge {
    /** Bridge-specific room description: channel ids, positions, topology hint. */
    suspend fun fetchRoom(): LightRoom

    /** Claim the device(s) for streaming. Throws on failure — DirectLightSync maps to `_error`. */
    suspend fun start(room: LightRoom)

    /** Release the device(s). Idempotent. */
    suspend fun stop()

    /** Send one rendered frame. Called from the 60 Hz render loop — must not block on I/O retries. */
    fun send(colors: Map<Int, Rgb>)

    /** Resend the last frame — see DirectLightSync's keepalive loop. No-op where the wire doesn't need it. */
    fun keepalive()

    /** True if a fault means "someone else took the device" (never auto-retried) vs. transient. */
    fun isExclusivityConflict(e: Exception): Boolean
}

data class LightRoom(
    val channels: List<LightChannel>,
    val topologyHint: RoomTopology? = null,
)

data class LightChannel(
    val id: Int,
    val position: Vec3,
)
```

`Rgb`, `Vec3` and `RoomTopology` already exist (`SyncoEngine.kt`,
`SpatialWaves.kt`) and are bridge-agnostic today in fact, not just in
principle — reused as-is.

### 2. `HueLightBridge` — port, not rewrite

`HueBridgeClient`, `HueDtlsClient`, `HueStreamEncoder` and the Hue-specific
slices of `DirectLightSync` (entertainment-area fetch, DTLS handshake,
`action:start`/`action:stop`, the 9s keepalive, the `HueStreamBusyException`
→ "someone else has the area" handling) move behind `HueLightBridge :
LightBridge` with their internals untouched. `DirectLightSync` keeps its
`bridgeClient`/`entertainmentConfigs` StateFlow surface (the Light Sync
settings screen reads it directly) by delegating to the bridge rather than
inlining the calls.

This phase is a pure refactor: same wire bytes, same reconnect/backoff
constants, same `HueStreamBusyException` semantics. It should ship on its
own, covered by the existing Hue unit tests (`HueStreamEncoder`,
`HueDtlsClient` framing tests already exist under `androidTest`/unit tests —
verify none of them reach into `DirectLightSync` internals that move) with
**zero observable behaviour change** — that's the gate for merging it before
any WLED code lands.

### 3. `WledLightBridge` — the new implementation

New files under `hue/wled/`:

- **`WledDiscovery.kt`** — `NsdManager.discoverServices("_wled._tcp",
  NsdManager.PROTOCOL_DNS_SD, …)`, the same pattern `AirPlayDiscovery.kt`
  already uses for `_raop._tcp`. Falls back to manual IP entry (WLED devices
  don't always advertise reliably on every router).
- **`WledClient.kt`** — HTTP calls to the device's JSON API (`GET
  /json/info` for LED count, name, firmware version; `GET /json/state` to
  read/restore brightness and on/off state so CAMusic can hand control back
  cleanly on `stop()`).
- **`WledUdpClient.kt`** — opens a `DatagramSocket`, sends realtime frames.
  DRGB packet: `byteArrayOf(0x02, timeoutSeconds) + ByteArray(n*3) {R,G,B...}`
  to UDP port 21324. No handshake — the first packet takes over the strip in
  "realtime" mode, and WLED reverts to its own effects automatically
  `timeoutSeconds` after the last packet (used as the built-in "stop"; an
  explicit `0xFF`-timeout-byte "exit realtime now" packet on `stop()` for
  the case where the reversion delay would look like a stuck light).
- **`WledLightBridge.kt`** implements `LightBridge`:
  - `fetchRoom()` → one `LightChannel` per configured segment (see §4 for
    how segments get positions).
  - `start()` → confirm reachability (`GET /json/info`), nothing to claim.
  - `send(colors)` → gamma-correct (LEDs are linear-ish; Hue's sRGB gamma
    expansion in `HueStreamEncoder.gamExpand` does not apply — WLED wants
    roughly straight sRGB bytes) and hand the ordered RGB bytes to
    `WledUdpClient`.
  - `isExclusivityConflict()` → always false. WLED has no bridge-level
    exclusivity; two senders would just fight over the strip, which is a
    user configuration error, not a protocol event to special-case.
  - Multiple WLED controllers in one room (a strip behind the TV *and* a
    strip along a shelf) are multiple channels on the *same* bridge instance
    — `send()` groups them by target IP and fires one UDP packet per device
    per frame. This is what makes `SpatialWaves`' room-shape effects work
    across more than one physical strip without SyncoEngine knowing there's
    more than one wire.

### 4. Room/segment configuration

Hue has an "entertainment area" the user builds in the Hue app, complete
with xy positions per bulb. WLED has no equivalent concept, so CAMusic has
to ask for the layout it would otherwise get from the bridge. Keep this
deliberately simple for v1, matching the existing "auto-classified topology"
already used for Hue's own `classifyTopology`:

- Settings → Lights → Add WLED device: enter/discover IP, fetch LED count
  from `/json/info`.
- One followup screen: "How is this strip arranged?" — Line (left-to-right
  across a wall/soundbar), Ring (wraps a room or a shelf), Single point
  (one fixture, e.g. a lamp with a WLED controller). This directly produces
  a `RoomTopology` and even positions along it — the same shape
  `classifyTopology`/`normalizePositions` already compute for Hue, just
  supplied by the user instead of read from the bridge.
- Each 3-pixel run (or a user-chosen pixel stride, for a long strip) becomes
  one `LightChannel`, so a 60-LED strip doesn't need 60 individual
  positions — it's downsampled the way `HueStreamEncoder.MAX_CHANNELS = 20`
  already caps Hue areas, keeping `SyncoEngine`'s channel count in the range
  it's tuned for.
- Persisted in `AppSettings` alongside the existing `hue*` keys: `wledDevices`
  (JSON list of `{name, host, ledCount, topology, pixelStride}`), following
  the same `pref {}`/DataStore pattern already at `AppSettings.kt:1639`.
  No PSK/app-key equivalent to encrypt — only the device's LAN IP, which
  doesn't need `Crypto.encrypt` the way `hueAppKey`/`hueClientKey` do.

### 5. Backend selection

v1 scope: **one active light backend at a time**, selected in Settings →
Lights, same place the Hue bridge is configured today — not because running
both is architecturally hard (`DirectLightSync` already renders once and
could fan the same frame out to two `LightBridge`s), but because verifying
two live wire protocols against real hardware in one PR is more risk than
this plan needs to take on. Fan-out to multiple simultaneous bridges is a
natural v2 once both are independently solid.

`DirectLightSync` gains one field, `private var bridge: LightBridge`, chosen
from `AppSettings.lightBackend` ("hue" | "wled") at `start()`. Everything
from `SyncoEngine` construction onward — the layer chain, `FieldSafety`,
`AutoIntensityPicker`, Rhythm Lights' gate, saved shows, ambience effects —
is unchanged: they all already terminate at "produce a `Map<Int, Rgb>`",
which is exactly the boundary this plan draws.

### 6. Safety

`FieldSafety.kt`'s WCAG flash-rate budget operates on the rendered RGB
stream before it reaches the encoder, so it applies to WLED unmodified —
worth calling out explicitly since an ungated, fast-updating LED strip is
exactly the kind of device that budget exists for, and WLED's ability to
run well past 60 Hz makes it more important here than for Hue's own
12.5 Hz-per-channel relay ceiling, not less.

## Integration touch points

| File | Change |
|---|---|
| `hue/LightBridge.kt` | **New** — the interface + `LightRoom`/`LightChannel` |
| `hue/HueLightBridge.kt` | **New** — wraps existing `HueBridgeClient`/`HueDtlsClient`/`HueStreamEncoder` behind `LightBridge`, behaviour-preserving |
| `hue/DirectLightSync.kt` | Delegate the Hue-specific block in `start()`/`stop()`/keepalive to `bridge: LightBridge`; add backend selection |
| `hue/wled/WledDiscovery.kt` | **New** — mDNS `_wled._tcp` discovery |
| `hue/wled/WledClient.kt` | **New** — `/json/info` + `/json/state` HTTP calls |
| `hue/wled/WledUdpClient.kt` | **New** — DRGB realtime UDP framing |
| `hue/wled/WledLightBridge.kt` | **New** — `LightBridge` impl |
| `data/AppSettings.kt` | Add `lightBackend`, `wledDevices` (mirrors the `hue*` keys at line ~1639) |
| `ui/screens/` (Lights/Settings) | Backend picker; "Add WLED device" flow (discover/manual IP → LED count → topology) alongside the existing Hue pairing screen |
| `docs/architecture-decision.md` | Note the bridge abstraction alongside the existing "why direct to the bridge" rationale |
| `README.md` | Light Sync section gains a WLED row once it ships |

## Implementation phases

### Phase 0 — Extract `LightBridge`, Hue-only (refactor, no new feature)

1. Define `LightBridge`/`LightRoom`/`LightChannel` in `hue/LightBridge.kt`.
2. Move the Hue-specific blocks of `DirectLightSync.start()`/`stop()`/
   `keepaliveLoop()` into `HueLightBridge`, preserving every constant
   (`KEEPALIVE_INTERVAL_MS`, `RECONNECT_ATTEMPTS`, `SEND_FAILURES_BEFORE_RECONNECT`, …).
3. `DirectLightSync` calls through `bridge` instead of `bridgeClient`/`dtls`/
   `encoder` directly; `bridgeClient` stays exposed as a property (delegating
   to `HueLightBridge.bridgeClient`) so `entertainmentConfigs`/
   `refreshEntertainmentConfigs()` keep working for the existing settings UI.
4. Run the full existing test suite plus a manual pass against a real Hue
   bridge — this phase must be indistinguishable from today's build.

**Gate**: merge only once Phase 0 is confirmed behaviour-identical. Nothing
in Phase 1+ should require touching `HueLightBridge` again.

### Phase 1 — WLED transport

1. `WledUdpClient` + a unit test asserting DRGB packet bytes for a known
   RGB map (mirrors the existing `HueStreamEncoder` framing tests).
2. `WledClient` (`/json/info`, `/json/state`) + `WledDiscovery`.
3. `WledLightBridge` implementing `LightBridge`.
4. Manual verification against a real WLED controller (or the WLED
   simulator — `github.com/Aircoookie/WLED` ships one) for frame rate,
   colour accuracy and the realtime-timeout revert behaviour.

### Phase 2 — Room configuration + settings UI

1. `AppSettings.wledDevices`/`lightBackend`.
2. "Add WLED device" screen (discover-or-manual-IP → LED count → topology
   picker) alongside the existing Hue setup wizard step.
3. Backend picker in Settings → Lights.
4. Onboarding: offer WLED alongside Hue in the Light Sync setup step.

### Phase 3 — Wire-up and polish

1. `DirectLightSync` backend selection at `start()`.
2. Confirm Rhythm Lights, Saved Shows, Auto Intensity, ambience effects and
   the creative layers (Music DNA, Emotional Arc, Phantom Stage, Phone as
   Conductor) all work unmodified against a WLED-backed session — they
   should, since none of them import anything from `hue/HueStreamEncoder.kt`
   or `HueDtlsClient.kt`, but this is the point to actually verify it rather
   than assume it.
3. README + `docs/architecture-decision.md` updates.

**Estimated effort**: Phase 0 ~2-3 PRs (careful refactor, no visible
change). Phase 1 ~2 PRs. Phase 2 ~2-3 PRs (UI-heavy). Phase 3 ~1-2 PRs. WLED
hardware (an ESP8266/ESP32 + an addressable strip, ~$15-25) is needed for
real verification the way a Hue bridge was needed for the original
Entertainment work — the simulator covers protocol correctness but not
timing/flicker behaviour on real hardware.

## Open questions

1. **Gamma/colour correction for WLED.** Hue's encoder gamma-expands sRGB
   before converting to xy because the bridge's own dimming curve expects
   linear-ish input; WLED strips take raw sRGB bytes directly, but different
   LED chipsets (WS2812B vs SK6812 vs APA102) render slightly different
   whites. Worth a "warmth" trim in the WLED device settings rather than
   trying to auto-detect chipset from `/json/info`.
2. **Multi-controller sync.** Sending one UDP packet per device per frame
   from the same render loop keeps them phase-aligned in practice (LAN
   latency is sub-millisecond), but a stretch goal is WLED's own multicast
   sync protocol (UDP port 21324 broadcast) if independent per-device
   packets prove visibly unsynced on a real multi-strip room.
3. **Segment-level WLED features** (WLED supports multiple segments per
   controller with independent effects) are out of scope for v1 — CAMusic
   treats one WLED controller as one strip of N pixels in realtime mode,
   the same simplification it makes for a Hue "channel" being one bulb.

## Status (2026-09-14)

- **Phase 0: done, pending verification.** `hue/LightBridge.kt` (the
  `LightBridge` interface, `SendOutcome`, `LightRoom`) and
  `hue/HueLightBridge.kt` (wrapping `HueBridgeClient`/`DtlsPskClient`/
  `HueStreamEncoder`) are in. `DirectLightSync` now renders through
  `bridge: LightBridge` instead of touching `dtls`/`encoder`/`bridgeClient`
  directly — `start()`, `cleanup()`, `emitFrame()`, `reconnectBridge()` (was
  `reconnect()`) and `keepaliveLoop()` all go through the interface. The
  `bridgeClient` property stays on `DirectLightSync` (delegating to
  `HueLightBridge.bridgeClient`) for the Light Sync settings screen's
  pairing/area-picker flow, which is unrelated to the streaming session.
- **One intentional deviation from strict byte-for-byte behaviour, kept:**
  `HueLightBridge.closeSession()` wraps `dtls?.close()` in a try/catch
  (logging on failure); the original `cleanup()` called it unguarded, so an
  exception there would have skipped the rest of cleanup (including
  releasing the wake/Wi-Fi locks). This is strictly more defensive, not a
  behaviour narrowing.
- **Two real bugs found on a follow-up review (`/code-review`, max effort)
  and fixed since the note above was first written:**
  1. `DirectLightSync.emitFrame()` reset `sendFailures = 0` unconditionally
     after every packet, including one whose send had just failed and
     hadn't yet reached `SEND_FAILURES_BEFORE_RECONNECT` — wiping the
     increment out before a second consecutive failure could ever be
     counted. This pre-dates the `LightBridge` refactor (it's the same
     placement the original inline code used) but the refactor's own doc
     comment asserted this exact control flow was "unchanged," restating
     the bug with false confidence rather than catching it. Fixed: the
     reset now happens only in the `SendOutcome.Ok` branch. Before this
     fix, a sustained real network fault (not a bridge-initiated
     revocation) froze the light show silently, with no reconnect attempt
     and no `_error` shown, until the user stopped and restarted Light
     Sync by hand.
  2. `HueLightBridge.reconnect()` used the `configId` field `fetchRoom()`
     cached at session start instead of re-reading
     `AppSettings.hueEntertainmentConfigId` fresh on every attempt, unlike
     the original `reconnect()` it replaced. A reconnect can run for up to
     ~2 minutes (14 attempts); a user who changed the selected
     entertainment area in Settings during that window would have every
     subsequent attempt keep targeting the stale area. Fixed: re-reads the
     setting fresh each attempt, and keeps the cached field in sync
     afterward so `closeSession()`/`encode()` address whichever area the
     reconnect actually landed on.
- **One more thing the second review pass found, checked against the
  original code, and confirmed is *not* new**: `reconnectBridge()` (in
  `DirectLightSync`) passes `LightRoom(channels)` into `bridge.reconnect()`,
  where `channels` is the class field `fetchRoom()` populated once at
  session start and never refreshed — so even with fix 2 above making
  `configId` correct, a reconnect that lands on a *different* entertainment
  area (per the same "user switched areas mid-reconnect" scenario) still
  builds that area's stream encoder from the *old* area's channel/gamut
  list. Checked directly against `origin/master`'s pre-refactor
  `reconnect()`: it has the identical shape — a freshly re-read `configId`
  alongside the same never-refetched `channels` field. Not a regression
  from this refactor, and not fixed here — a proper fix means deciding
  whether a reconnect should re-fetch the entertainment configuration over
  HTTPS (a new failure mode inside a path that exists specifically to
  survive network trouble) or something else, which is a real design
  question the original app never had to answer either, not a Phase 0
  cleanup. Left as a known, narrow, self-healing (a full stop/start already
  re-fetches everything correctly) limitation inherited as-is.
- **A third review pass (the same `/code-review`, max effort) found one more
  gap in fix 2, and it was fixed**: `HueLightBridge.reconnect()` updated the
  `configId` field only *after* the DTLS handshake succeeded, not right
  after the HTTP `action:start` claim did. If a reconnect attempt's HTTP
  claim on a (possibly newly-selected) area succeeded but the DTLS connect
  right after it then failed — a plausible split, since the two use
  different transports — and every later attempt also failed, `configId`
  never advanced past whatever it was before this attempt. The eventual
  give-up path's `closeSession()` would then call `stopStream` on the
  *wrong* area, leaving the area actually claimed this attempt marked
  in-use on the bridge with no remaining code path to release it. Fixed:
  `configId` now updates immediately once `startStream` succeeds, before
  the DTLS connect is even attempted.
- **A fourth review pass found the same field's last real gap, and it was
  fixed**: `configId` was never *cleared*, only ever set — so a value left
  over from a previous, successfully-run session could survive into a new
  one whose own `fetchRoom()` then failed before setting a fresh value (a
  bridge/host change in Settings, or the paired area having been deleted
  since). `closeSession()`'s cleanup would still see a non-blank `configId`
  and call `stopStream` with a leftover id against a host/appKey it has
  nothing to do with — caught by that method's own try/catch, so not a
  crash, but a wrong, wasted API call rather than none at all. Fixed:
  `fetchRoom()` clears `configId` up front rather than only setting it on
  success, and `closeSession()` clears it again at the end — so the field
  never carries meaning past the session that set it.
- **The reviewer's broader point, noted rather than acted on**: four
  point-fixes to the same field (this one included) is itself a signal that
  `configId` being an implicit mutable side-channel shared across
  `fetchRoom`/`openSession`/`reconnect`/`closeSession` — rather than an
  explicit value threaded through `LightBridge`'s interface — is what kept
  producing one narrow bug per edge case instead of closing the whole
  family at once (e.g. `fetchRoom()`/`reconnect()` returning the claimed id
  explicitly, with `DirectLightSync` passing it into `closeSession(id)`).
  That is a real design improvement worth making, but it changes
  `LightBridge`'s interface shape — exactly the kind of change that most
  wants a real build and a real bridge to validate before landing, neither
  of which this environment can do. Left as a follow-up rather than
  attempted here.
- **A fifth review pass — fixed**: `HueLightBridge.send()`'s `DtlsPeerClosed`
  branch built `SendOutcome.Revoked`'s message as `e.message ?: "The bridge
  revoked the stream…"`. Since `DtlsPeerClosed.message` is never actually
  null (`HueDtlsClient.kt` always builds one, e.g. `"bridge closed the
  stream (close_notify)"`), the fallback never ran — every revocation
  surfaced that raw protocol string on the Light Sync screen's error banner
  instead of the original fixed, friendly one the pre-refactor code always
  used there (it only put `e.message` in the *log* line, never in
  `_error.value`). Fixed by dropping the `?:` fallback and always using the
  fixed string; the raw message still goes to `Log.w`. Believed currently
  unreachable in practice — nothing in `HueDtlsClient.kt`'s `DtlsPskClient`
  actually throws `DtlsPeerClosed` today, per a repo-wide check — but the
  branch exists for when that detection is wired up, and carrying the bug
  forward silently would have meant it ships live the moment it is.
- **Two edge cases surfaced by the same pass, matched against
  `origin/master`, and left as pre-existing rather than fixed:**
  1. A reconnect that fails on one Hue entertainment area and then succeeds
     on a *different* one (only possible if the user changes the selected
     area in Settings between two failed attempts within the same ~2-minute
     retry window) claims the new area via `action:start` without ever
     releasing the first one — `closeSession()` only ever stops whichever
     area is current by the time it runs. Confirmed present in
     `origin/master`'s own `reconnect()` before this refactor touched it, for
     the same reason: it re-reads the entertainment-area setting fresh on
     every attempt (by design — see the fix earlier in this list) without
     ever stopping the area an earlier attempt in the same loop already
     claimed. A real fix means tracking every area claimed across the retry
     loop and releasing the ones not ultimately used, which is new
     behaviour beyond what the app has ever done, not a Phase 0 cleanup.
  2. The same "different area" case leaves `reconnectBridge()`'s
     `LightRoom(channels)` — `channels` being the `DirectLightSync` field
     `fetchRoom()` set once at session start and never refreshed — pointing
     at the *original* area's channel/gamut list even once the reconnect
     lands on a new one, so the rebuilt encoder clamps colours to the wrong
     bulbs. This is the same root cause already recorded further up this
     list ("checked against `origin/master`... confirmed is *not* new") —
     noted again here because a later review pass re-found it independently
     from the `configId` angle rather than the `channels` one.
  Both require the same rare precondition (switching Hue areas *during* an
  active network-fault recovery) and both self-heal on the next ordinary
  stop/start, which re-fetches everything fresh. Worth fixing together with
  the `configId`-threading redesign noted above, not as one-off patches.
- **One more, judged not worth changing**: replacing the render loop's old
  per-tick `val enc = encoder ?: continue; val client = dtls ?: continue`
  with the single `sessionOpen` flag narrows a check that used to read two
  fields atomically-enough for the loop's purposes into one that can go
  stale a moment before `bridge.encode()`/`bridge.send()` actually run, if a
  concurrent `stop()`/revocation-triggered `cleanup()` lands in that gap —
  producing an occasional "Encode failed"/"Send failed" log line on
  ordinary shutdown instead of a silent `continue`. Functionally harmless
  (`running` is already false by then, so the loop exits right after) and
  not a new class of race: the original `enc`/`client` locals were snapshot
  at the same point and could just as easily go stale before `emitFrame`'s
  own `client.send(packet)` ran, throwing from a socket `close()` had just
  raced shut and landing in the same generic, rate-limited log line by a
  different path. Left alone rather than adding synchronization for a
  cosmetic difference in which log line an already-benign race produces.
- **A pure efficiency observation, not acted on**: `start()` now reads the
  same `AppSettings` values up to three times across `DirectLightSync`'s own
  guard, `fetchRoom()`, and `openSession()` (11 DataStore reads per `start()`
  versus 5 before), since each of those three methods independently reads
  what it needs rather than one read being threaded through. Harmless in
  practice — Light Sync `start()`/`stop()` are user-initiated, not a hot
  loop, and DataStore reads are cheap — so left alone rather than
  restructuring the validation flow to save a few reads on an infrequent
  call.
- **Not yet done, and the actual gate on relying on this**: verification
  against a real Hue bridge, and a run of the existing unit test suite.
  Neither could be done from the environment this was written in — no
  Android SDK/NDK is installed and the network policy does not reach
  Google's Maven repository, so `./gradlew :app:testMobileDebugUnitTest`
  itself could not be run here, on this branch or on an unmodified
  checkout. The change has been through two rounds of manual review (an
  initial pass tracing every removed `dtls`/`encoder` reference to its
  replacement, then a `/code-review` pass at max effort that caught the two
  bugs above) but neither substitutes for a real build and a real bridge.
- **Phase 1+ (WLED itself) not started.**
