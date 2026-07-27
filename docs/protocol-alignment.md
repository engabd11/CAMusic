# Sendspin protocol — alignment target (current spec)

Derived from **github.com/Sendspin/spec** (`connection.md`, `messaging.md`) + the Music Assistant
Sendspin provider docs, on 2026-07-27. This is the exact target the `protocol/` layer is being
re-aligned to. Items marked **(confirm on wire)** need a live device↔MA `/sendspin` capture and/or a
read of `Sendspin/sendspin-go` before implementation, because the spec is a technical preview.

The current code implements an **older Resonate draft** (plain-WS JSON `client/hello`, NTP,
`_mass._tcp`, `stream/end` per track, `player@v1` only, no encryption). Below is what it must become.

## 1. Discovery (done)

- This app is a **player** and initiates the connection → **client-initiated** discovery: browse
  `_sendspin-server._tcp.local.` (recommended port **8927**). TXT records: `path` (e.g. `/sendspin`),
  optional `name`. Connect to `ws://<host>:<port><path>`.
- The reciprocal `_sendspin._tcp` / **8928** is *server-initiated* (server connects to a device that
  advertises itself). A later version may also advertise `_sendspin._tcp` so MA can push to us.
- `MaDiscovery.kt` is updated accordingly.

## 2. Identity (Curve25519, not UUID)

- `client_id` MUST be the device's **static Curve25519 public key**, base64url, **43 chars**
  (32 bytes, no padding). `PlayerIdentity.getPlayerId()` currently returns a UUID — replace with a
  persisted Curve25519 keypair (store the private key in EncryptedSharedPreferences / Keystore-wrapped).
- The server has its own static key (`server_id`); with KKpsk2 both static keys are known to each
  other after the init exchange.

## 3. Handshake sequence

WebSocket connect → then:

1. **`client/init`** — TEXT frame, JSON: `client_id` (base64url Curve25519 pubkey), `version` = 1,
   `suite` = one of `25519_ChaChaPoly_SHA256` (software) or `25519_AESGCM_SHA256` (hw-accelerated).
2. **`server/init`** — TEXT frame, JSON: `server_id` (base64url pubkey), `version` = 1.
3. **Noise KKpsk2 handshake.** The **server is the Noise initiator, the client (this app) is the
   responder**, regardless of who opened the WebSocket. The **prologue** mixed into the Noise state on
   both sides is the exact bytes of `client/init` **concatenated with** the exact bytes of
   `server/init`. Handshake messages are carried as `noise/handshake` with `data` = base64url of the
   Noise bytes — **message 1 payload** = `psk_id` (string), **message 2 payload** = `{}`.
   **(confirm on wire:** whether `noise/handshake` rides as TEXT frames or as binary type-0 frames,
   and exact base64 vs raw.)**
4. After a successful Noise handshake, the transport switches to **encrypted binary frames** and
   continues with **`server/hello`** → **`client/hello`** → **`server/activate`**.

### Pairing

- Methods: `pairing_psk`, `dynamic_pin`, `static_pin`. The client advertises `supported_pair_methods`
  and `unpaired_access { enabled: bool }` in `client/hello`; the server picks `selected_pair_method`
  in `server/activate`. **(confirm on wire:** MA's default method + the PIN UX / where the PSK is
  derived. For a phone, `dynamic_pin` (show/enter a code) is the likely default.)**

## 4. Post-handshake framing (binary, with a type byte)

Every frame after the handshake is a Noise-encrypted **binary** WebSocket message whose first
plaintext byte is a `uint8` message type:

| Type | Meaning |
|---|---|
| `0` | JSON control message (UTF-8 body) |
| `2`–`3` | Fragmentation frames |
| `4`–`7` | **Player** role, message slots 0–3 (audio) |
| `8`–`11` | Artwork role |
| `12`–`15` | Source role |
| `16`–`23` | Visualizer role |
| `192`–`255` | Application-specific |

So control JSON (`server/hello`, `stream/start`, `client/time`, …) is sent as **binary type-0**, not
a WS text frame. Player **audio** is types 4–7 with **server-relative timestamps carried in the
decrypted plaintext**. **(confirm on wire:** the exact audio header layout after the type byte —
timestamp width/endianness, sequence, codec/format indication vs `stream/start`.)**

## 5. Message field names (from messaging.md)

- `client/init`: `client_id`, `version`, `suite`
- `server/init`: `server_id`, `version`
- `noise/handshake`: `data` (base64url)
- `server/hello`: `name`
- `client/hello`: `name`, `device_info?{product_name?,manufacturer?,software_version?,mac_address?}`,
  `trust_level`('user'|'none'), `supported_roles[]`, `player@v1_support?`, `source@v1_support?`,
  `artwork@v1_support?`, `visualizer@v1_support?`, `supported_pair_methods?[]`,
  `unpaired_access{enabled}`
- `server/activate`: `activities[]`('playback'|'pairing'|'management'), `active_roles?[]`,
  `selected_pair_method?`('dynamic_pin'|'pairing_psk'|'static_pin')
- `client/time`: `client_transmitted` (µs)
- `server/time`: `client_transmitted`, `server_received`, `server_transmitted` (client measures its
  own receive time `t4` locally → NTP-style 4 points)
- `client/state`: `available`(bool), `player?`, `source?`
- `server/state`: `metadata?`, `controller?`, `color?`
- `stream/start`: `server_transmitted`, `player?`, `artwork?`, `visualizer?`
- `stream/request-format`: `player?`, `artwork?`, `visualizer?`
- `stream/clear`: `server_transmitted`, `roles?[]`
- `stream/end`: `server_transmitted`, `roles?[]` (per-role; NOT sent between tracks during gapless)
- `group/update`: `playback_state?`('playing'|'stopped'), `group_id?`, `group_name?`

**(confirm on wire:** the `player`/`player@v1_support` sub-object schema — sample rate, bit depth,
codec, channels, buffer capacity — vs the current `AudioFormatSpec {codec,channels,sampleRate,bitDepth}`.)**

## 6. Clock sync

- `client/time { client_transmitted }` → `server/time { client_transmitted, server_received,
  server_transmitted }`; client records `t4` (local receive). Offset ≈
  `((server_received − client_transmitted) + (server_transmitted − t4)) / 2`.
- Spec uses a **Kalman filter** (time-filter) rather than a raw average; don't report
  `client/state.available = true` until the filter has converged. The current EMA is a placeholder →
  upgrade to a Kalman/robust filter.

## 7. Roles

- Implement **`player`** (audio out) first; add **`controller`** (browse/search/queue/transport) for
  M2. `metadata`/`artwork` are for on-device display; `visualizer`/`color` are the light-sync hooks
  (the HA integration is the natural consumer of those, not this app).

## 8. M0 task list (protocol)

1. Discovery → spec (**done**).
2. `Identity`: persisted Curve25519 keypair; `client_id` = base64url pubkey.
3. Choose a Noise lib (Noise-Java / Cacophony) — verify **KKpsk2** + PSK + both cipher suites; else a
   focused KKpsk2 impl. Add JVM handshake-vector tests.
4. `Messages.kt` → the field names in §5 (sub-objects as typed models where known, `JsonObject?`
   placeholders where **(confirm on wire)**). JVM serialization round-trip tests.
5. `SendspinTransport`: WS connect → init exchange → Noise → binary type-0 JSON + type-4 audio;
   fragmentation (types 2–3).
6. `ClockSync`: 4-point exchange + a proper filter; gate `available`.
7. Wire `PlayerViewModel` / `SendspinService` to the new transport; keep the audio engine (Oboe/AAudio
   I24 + libFLAC) behind `stream/start` format.
