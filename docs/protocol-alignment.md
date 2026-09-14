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
client/time ⇄ server/time                          bursts of 8, back to back until the filter is ready, then every 10 s
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
- **Pairing — all three methods.**
  *Pairing PSK*: the operator pastes the phone's token (`SP:0…`, Settings › This phone ›
  Pairing and trust) into MA's player *Setup*; MA re-handshakes onto the Pairing PSK,
  sends `server/activate {activities:[pairing]}`, the phone answers
  `client/pair-finalize {long_term_psk}` and persists only on `server/pair-finalize`,
  then MA re-handshakes onto the new long-term PSK and the hello says `trust_level:
  user`. 170 ms end to end.
  *Dynamic PIN* (`PinPairingFlow`, `CPace`): `client/pair-init {pairing_index, commit_B}`
  → `server/pair-init {nonce_A}` → the phone derives the PIN
  (`SHA-256(label‖h‖nonce_A‖nonce_B) mod 10^L`) and shows it on the card and as a
  notification → the operator types it into MA → `server/pair-auth`/`client/pair-auth`
  (CPACE-X25519-SHA512 shares, sid = label‖h‖counter) → `server/pair-confirm` verified,
  `client/pair-confirm {client_kc, nonce_B}` + `client/pair-finalize {wrapped_psk}` back
  to back → `server/pair-finalize`. Gesture-gated (the card's *Allow pairing*) when the
  session's PIN is under six digits or the method is escalated; the failure counter is
  the spec's: up on a failed `server_kc`, reset on success, escalation at ten, persisted.
  400 ms after the activation against MA 2.10.3.
  *Static PIN*: an eight-digit PIN the operator provisions on the card (generated per
  device, never a shared default; MA's management can rotate it), shipped off and
  unprovisioned as the spec asks; every attempt is gesture-gated:
  `client/pair-pending` → *Allow pairing* → `client/pair-init` → the same PAKE round.
  Verified against MA 2.10.3 including the pending gesture and MA's
  `pair/abort user_cancelled` when its flow is restarted.
  The pairing window is five minutes and is consumed by the `client/pair-init` it
  admits; `management/open-pairing-window` opens it too. A wrong PIN is
  `pair/abort pin_mismatch` (verified live); an attempt that stalls times out at two
  minutes with `attempt_timeout`.
- **Re-handshake.** Same state machine, prologue = the previous handshake hash, message
  2 still under the old keys; handled on the socket thread under the send lock so
  nothing of ours goes out under the old keys after it. Then `server/hello` →
  `client/hello` → `server/activate` again, and the full player state is re-sent.
- **Management.** With `management` in the activities (long-term paired only), the
  server may list/add/remove records and read/patch the pairing config — `pairing_psk`,
  `static_pin` (enabling with no PIN provisioned and none supplied is `invalid`),
  `dynamic_pin` (`enabled`, `min_pin_length` 4–12, `escalated`), `record_mode`,
  `unpaired_access` — and open the pairing window; every request gets one
  `management/result`. `server/unpair` drops the record, says goodbye `unpaired`, and
  reconnects — MA expects the device to "reconnect as unpaired".
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
- **Volume and mute.** The player volume is the phone's media volume, applied on the
  spec's loudness curve: the OS volume step whose gain (`getStreamVolumeDb`) is nearest
  `(volume/100)^1.5`, and reported back through the inverse. `mute` is a gate on the
  engine's output, independent of the level, so a volume change never clears it and
  `muted` is reported as such.
- **Metadata.** `server/state` is merged as the spec's delta (`MetadataState`): absent
  fields unchanged, `null` fields cleared, nested objects replaced whole, a `null` role
  object clears the role; `playback_speed: 0` is paused.
- **Bring-up timeout.** Thirty seconds from the first message to the first
  `server/activate` (or legacy `server/hello`), after which the socket is closed.
- **Goodbye reasons.** `user_request`, `restart`, `unauthorized`, `pairing_required`,
  `unpaired`, and `another_server` when the user switches Music Assistant servers.

## Synchronisation, against the spec's normative text

| Spec | Implementation |
|---|---|
| Clients MUST use the time-filter algorithm | `ClockKalmanFilter` is the reference filter with the reference `Config` (max_error_scale 0.5, adaptive cutoff 3, forget factor 2, offset process noise 0, drift process noise 1e-11). `ClockKalmanFilterReferenceTest` feeds 80 mixed-RTT exchanges to aiosendspin's port and to ours and pins offset, error and `compute_client_time` to the microsecond. On top: a step detector that re-seeds after a confirmed clock jump, and a persisted-offset seed; neither touches the steady-state estimate. |
| `client/time` often enough to keep the filter convergent; the library's burst strategy is the baseline | Bursts of 8 sequential exchanges, the lowest-RTT sample fed to the filter; back to back until the filter is ready, then every 10 s (`SendspinClient.timeBurst`). A stream start triggers a burst. |
| `available: true` only once the filter has converged | Reported once, on `isReadyForPlaybackStart` (8 samples cold, 3 seeded, error ≤ 5 ms, no step suspected) — ~110 ms after activation on a LAN. |
| Timestamp = server time the first sample is output; translate via the filter; subtract `static_delay_ms`; compensate known output delays | `presentation = serverToLocal(ts) − static_delay`; the native callback aligns each frame's DAC presentation time (Oboe `getTimestamp`) to it, so HAL latency is compensated by measurement, not by a constant. |
| Steady-state error within ±1 ms (MUST), ±0.5 ms target, measured at the output against the filter's prediction | The native drift (`intended − DAC`) is exactly that error. Dead band 300 µs, then proportional resampling. Measured on the S22 Ultra: mean +0.09 ms, range −0.55…+0.88 ms over 90 s, nothing beyond ±1 ms. |
| Speed within ±0.5 % over a 150 ms sliding window for continuous correction | Resampler capped at 0.5 % (was 3 %); gain 0.06 %/ms of error, saturating at ~8 ms. |
| Discrete one-shot resync only for startup, `stream/start`/`clear`, underrun or an error too large to correct smoothly; MUST be rare | Snap (whole-frame skip/insert) at ≥ 10 ms of error or while the output is muted at a stream head; the local-anchor re-anchor is the same one-shot. |
| Late chunks dropped | A chunk whose whole slot has passed is dropped before the write (`SendspinNativeEngine.writeChunk`); the startup trim covers the stream head. |
| No startup warble | The output is muted from `stream/start` until the native lock (a silent snap, one callback) and lifts before the first audible sample at any sane lead. |
| `static_delay_ms` persisted locally | DataStore (`AppSettings.staticDelayMs`), pushed to the client on every session. |

Deliberate deviation: the local clock is `CLOCK_BOOTTIME`, not `CLOCK_MONOTONIC_RAW`. `MONOTONIC` stops during suspend on Android, which put the filter seconds out after every doze; `BOOTTIME` does not, and any NTP slew shows up in the drift term.

## The legacy dialect (MA ≤ 2.9, `aiosendspin` 6.0.5)

Picked from MA's unauthenticated `/info`: `schema_version` ≥ 45 (MA 2.10+) is
encrypted, below is legacy, unknown is encrypted. The same gate the official app uses;
never probed, never downgraded after a failed handshake. Legacy is the pre-spec shape
exactly — `client/hello` first with `client_id` and `version` inside, answered by a
`server/hello` carrying `active_roles`, text frames for JSON, raw binary for audio — and
**must not** carry `trust_level`, `supported_pair_methods` or `unpaired_access`: see
the quirks.

## Endpoints

The server's own advertisement first — `_sendspin-server._tcp` on the configured MA host,
whose TXT `path` and port are what the spec's client-initiated mode says to connect to
(`SendspinServerDiscovery`, ~30 ms on the LAN) — then `ws://host:8927/sendspin` (the MA
docs' default) if nothing answers. MA's `:8095/sendspin` is an authenticated proxy to the
same server (`auth` + token, then `auth_ok`, then the identical protocol) and is the last
fallback for a network where only the web port is reachable. All are the same server
object and clock.

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
- MA learns the offered pairing methods from the hello, so a static PIN provisioned
  mid-session is offered after the next connection. Restarting MA's setup flow cancels an
  attempt in flight (`pair/abort user_cancelled`) and starts a fresh one.
- MA's management *enable static PIN* sends no PIN, which the spec makes `invalid`
  until one is provisioned on the phone.

## Conformance checklist (client-side normative statements)

Every MUST/SHOULD that binds a player client, checked against the code on 2026-09-14:
identity and Noise (§Encryption, §Communication, §Fragmentation) — met; handshake-phase
timeout — met; no undefined fields sent, unknown fields ignored — met; time filter,
burst cadence, `available` gating — met; activation table and rejection order — met;
Pairing PSK required, dynamic PIN and static PIN "SHOULD" — met, with the failure
counter, gesture gating, window lifetime, attempt timeout and the pairing token; token
codec lenient/rejecting — met; management — met; `client/state` fields, persistence of
`static_delay_ms`, volume/mute independence, the loudness curve — met; `server/state`
delta merge — met; `stream/*` semantics, late-chunk drop, no startup warble, ±1 ms
accuracy with the ±0.5 ms target, ±0.5 % speed cap, rare one-shot resync — met;
`another_server` on a switch — met; discovery via `_sendspin-server._tcp` — met. The
only deliberate deviation is the local clock (`CLOCK_BOOTTIME`, above).

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
