# Sendspin protocol — what the app speaks, and what Music Assistant does with it

**Updated 2026-09-14** against the published specification
(sendspin-audio.com/build/spec), `aiosendspin` 9.1.1 (client *and* server, the version
Music Assistant 2.10.x pins), the official MA mobile app, and a live MA 2.10.3. The
earlier version of this file said MA's provider did not use the encrypted protocol; that
was true of `aiosendspin` 6.0.5 (MA ≤ 2.9) and stopped being true with 2.10.

The implementation lives in `protocol/` (`SendspinClient`, `Messages`, `ActivationPolicy`,
`ManagementHandler`) and `protocol/noise/` (X25519, the Noise KKpsk2 responder, transport
framing, keys and PSKs, the pairing token, `PairingStore`).

## The session

```
ws://<ma-host>:8927/sendspin                      plain ws://, no auth (MA's documented endpoint)
client/init {client_id, version:1, suite}         cleartext text frame
server/init {server_id, version:1}                cleartext
noise/handshake {data}   (message 1, server→)     cleartext; payload names the psk_id
noise/handshake {data}   (message 2, →server)     cleartext; payload "{}"
── every frame from here is a WebSocket binary frame holding a Noise ciphertext ──
server/hello {name}
client/hello {name, device_info, supported_roles, player@v1_support,
              trust_level, supported_pair_methods, unpaired_access}
server/activate {activities, active_roles?, pairing?}
client/time ⇄ server/time                          200 ms bursts until the filter is ready
client/state {available:true, player:{volume, muted, static_delay_ms,
              required_lead_time_ms, min_buffer_ms, supported_commands}}
stream/start → binary type 4 chunks → stream/clear / stream/end …
```

- **Identity.** `client_id` is the base64url X25519 public key of a keypair the phone
  generates once (`PairingStore`, `sendspin_pairing` prefs). It is also the Music
  Assistant player id, so a new key is a new player in MA — which is what "Register
  again as a new player" is for.
- **Noise.** `Noise_KKpsk2_25519_AESGCM_SHA256`; the server is the initiator whoever
  opened the socket. The prologue is the exact bytes of `client/init` + `server/init`.
  Message 1's payload carries the `psk_id`; the phone looks it up and mixes that PSK in
  before message 2. Both suites are implemented; AES-GCM is picked because every phone
  accelerates it. X25519 is hand-rolled (RFC 7748 vectors in `X25519Test`) because the
  platform's `XDH` only exists from API 33.
- **Framing.** Decrypted plaintext starts with a type byte: `0` JSON, `4` audio
  (`[4][int64 BE µs][codec data]`, the same header as before), `2`/`3` fragments of a
  message larger than 65 518 bytes (a 24/192 PCM chunk is). Frames are decrypted on
  OkHttp's reader thread in wire order, and every send goes through one lock, because
  the transport counters are the replay protection.
- **PSK categories.** *Sentinel* (a published constant — an unpaired "guest" session),
  the phone's own *Pairing PSK* (only during a pairing), or a *long-term* record from an
  earlier pairing. Which one matched bounds what `server/activate` may declare —
  `ActivationPolicy` is the spec's table, with its ordered rejections
  (`pairing_required` before `unauthorized`) and `pair/abort method_not_supported`.
- **Pairing.** Only the Pairing PSK method (the one the spec requires; the PIN methods
  are optional and need a CPace PAKE). The operator pastes the phone's token
  (`SP:0…`, Settings › This phone › Pairing and trust) into MA's player *Setup*. MA
  re-handshakes onto the Pairing PSK, sends `server/activate {activities:[pairing]}`,
  the phone answers `client/pair-finalize {long_term_psk}`, persists the record only on
  `server/pair-finalize`, and MA re-handshakes again onto the new long-term PSK, after
  which the hello says `trust_level: user`. Measured at 170 ms end to end.
- **Re-handshake.** Same state machine, prologue = the previous handshake hash, message
  2 still under the old keys; handled on the socket thread under the send lock so
  nothing of ours goes out under the old keys after it. Then `server/hello` →
  `client/hello` → `server/activate` again, and the full player state is re-sent.
- **Management.** With `management` in the activities (long-term paired only), the
  server may list/add/remove records and read/patch the pairing config; every request
  gets one `management/result`. No PIN methods, so `open-pairing-window` is `invalid`
  and the PIN objects are absent from the config. `server/unpair` drops the record,
  says goodbye `unpaired`, and reconnects — MA expects the device to "reconnect as
  unpaired".
- **State.** `available: true` goes out once, when the clock filter is fit to schedule
  against (`isReadyForPlaybackStart`, ≈100 ms with a persisted seed, ≈1.6 s cold),
  bounded by the server's 5 s initial-state window. It is *not* reset to false on a
  reconnect: the server answers `available: false` by moving the player to a solo
  stopped group. `required_lead_time_ms` = 400 and `min_buffer_ms` = 250 come from the
  engine's real needs (`SendspinNativeEngine.REQUIRED_LEAD_TIME_MS`); with them the
  first chunk lands with ~70 ms to spare instead of a trimmed intro. No undefined
  fields (the old `state: "synchronized"` is gone); no periodic re-report — volume,
  mute, trim and timing changes each send the full player object.
- **Streams.** A `stream/start` on the open stream reconfigures without clearing
  buffers (`SendspinPlaybackEngine.reconfigure`); re-sent chunks (at or behind the last
  enqueued timestamp) are dropped. A stream that had to start on the local anchor is
  re-gated onto the server timeline the moment the filter converges (one-shot resync).

## The legacy dialect (MA ≤ 2.9, `aiosendspin` 6.0.5)

Picked from MA's unauthenticated `/info`: `schema_version` ≥ 45 (MA 2.10+) is
encrypted, below is legacy, unknown is encrypted. The same gate the official app uses;
never probed, never downgraded after a failed handshake. Legacy is the pre-spec shape
exactly — `client/hello` first with `client_id` and `version` inside, answered by a
`server/hello` carrying `active_roles`, text frames for JSON, raw binary for audio — and
**must not** carry `trust_level`, `supported_pair_methods` or `unpaired_access`: see
the quirks.

## Endpoints

`ws://host:8927/sendspin` first (what the MA docs give for clients; no auth). MA's
`:8095/sendspin` is an authenticated proxy to the same server (`auth` + token, then
`auth_ok`, then the identical protocol) and is the fallback when only the web port is
reachable. Both are the same server object and clock.

## Music Assistant behaviours worth knowing (2.10.3)

- **Guest access is auto-approved.** A client advertising `unpaired_access.enabled`
  gets `trust_unpaired` on first contact (`_auto_trust_guest_access`); the first
  `server/activate` may carry no roles and a second one a few ms later carries them.
- **`sendspin/pair_web_player`** (how the official app pairs silently through the MA
  login) only works for clients MA classes as web players — `device_info.product_name`
  in "Mobile Application" / "Web Browser" / "Web Player" / "PWA", or manufacturer
  "Music Assistant" — and those players are hidden, private and not exposed to Home
  Assistant. CAMusic stays a room player, so it pairs by token instead.
- **Downgrade protection bites legacy clients.** A cleartext hello that carries
  `unpaired_access` is *trusted* like a guest, and MA then refuses that client id
  unencrypted for ever after ("Rejecting unencrypted connection claiming known
  client"). Hence the legacy hello is the bare pre-spec shape.
- **A phone that loses its half of a pairing** cannot reconnect while MA still holds
  its half (the handshake fails on an unknown `psk_id`); the fix is MA's *Unpair*,
  which is why the app offers no "forget pairings" button.
- `allow_legacy_clients` (hidden, default on, "temporary") is what still admits the
  cleartext dialect; MA shows such a player as "connected without encryption".
- MA sends `set_static_delay 0` right after every initial state; the client's echo
  guard keeps a locally negative trim from being erased by it.

## Verification recipe

- `./gradlew :app:testMobileDebugUnitTest --tests 'com.engabd.sendpin.protocol.*'` —
  RFC 7748 vectors, a KKpsk2 transcript recorded from the reference `noiseprotocol`
  library with fixed keys (both suites, message 2, hash, both transport directions),
  framing, the token reference vector, the store, the activation table, management.
- `SENDSPIN_SERVER=ws://192.168.0.48:8927/sendspin ./gradlew … --tests '*LiveServer*'`
  runs the full bring-up against a real MA from the JVM.
- On the phone: `adb logcat -s SendspinClient:* SendspinNative:*` shows the handshake
  (`noise handshake complete (psk=…)`), activation, `clock ready → client/state
  available`, and the engine's `write chunk#` / `sync sample drift=` lines.
- MA's side over its API (`sendspin` / password in the team notes): `players/all`,
  `config/players/setup {player_id}` → `config/flows/submit {flow_id, values:
  {pairing_token}}` pairs; `config/players/invoke_action` with
  `<client_id>||protocol||management_enter` / `unpair` drives management and unpair.
