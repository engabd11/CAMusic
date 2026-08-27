# Sendspin protocol — alignment target (what Music Assistant actually speaks)

**Updated 2026-07-27 after studying two working, permissively-licensed clients:**
- **massdroid** (`github.com/sfortis/massdroid_native`, **MIT**) — native Android/Kotlin MA player.
- **MA mobile app** (`github.com/music-assistant/mobile-app`, **Apache 2.0**) — MA's own KMP app.

Both are compatible with nowdroid's MIT license (attribute in ported files). They are the ground
truth: **no packet capture and no Noise library are needed.**

## The key correction

The published spec (`github.com/Sendspin/spec`) describes a fuller, **Noise-secured** protocol
(`client/init` → Noise KKpsk2, Curve25519 ids, port 8927, binary type-byte framing). **Music
Assistant's current built-in Sendspin provider does NOT use that.** What both working clients speak
is a **plain WebSocket + JSON** protocol on MA's own web port:

- Connect: `ws://<ma-host>:<ma-port>/sendspin` (MA default port **8095**; `wss://` for TLS). The
  mDNS `_sendspin-server._tcp` record still locates a server, but MA integrates the endpoint on its
  main port, and a standalone player can also just take a host:port.
- **Text** WebSocket frames = JSON protocol messages. **Binary** frames = audio chunks. (No Noise, no
  per-frame type byte — the WS frame kind *is* the discriminator.)
- `client_id` is a **plain stable string** (a per-install UUID is fine — nowdroid's `PlayerIdentity`
  is OK; drop the Curve25519 idea).

So nowdroid's original plain-WS assumption was basically right; the work is matching the exact
message shapes, formats, and clock behaviour below.

## Handshake

**Direct / proxy (WebSocket):**
1. Open `ws://host:8095/sendspin`.
2. *(proxy/auth mode only)* send `{ "type":"auth", "token":"<token>", "client_id":"<id>" }` →
   receive `{ "type":"auth_ok" }` (or `{ "type":"auth_error", "message":"…" }`). On a LAN direct
   connection there may be no token step.
3. Send `client/hello` (payload below) → receive `server/hello`.
4. Time-sync loop (`client/time`/`server/time`) begins; `client/state` reports availability.
5. `stream/start` → binary audio frames; `server/state` carries now-playing metadata;
   `server/command` carries volume/mute; `stream/clear`/`stream/end` bound tracks.

**Remote (WebRTC):** MA opens a `sendspin` data channel alongside the `ma-api` channel; same JSON
protocol over the channel, **no per-channel auth** (inherited). (Later phase — direct WS first.)

## Messages (envelope: `{ "type", "payload" }`)

- `auth` → `{ token, client_id }` *(proxy only)*; replies `auth_ok` / `auth_error{message}`.
- `client/hello` → `{ client_id, name, version, supported_roles:["player@v1","metadata@v1"],
  device_info{product_name,manufacturer,software_version}, player@v1_support{supported_formats[],
  buffer_capacity, supported_commands:["volume","mute"]} }`
- `server/hello` → `{ server_id, name, version, active_roles[], connection_reason:"discovery"|"playback" }`
- `client/time` → `{ client_transmitted }` (µs)
- `server/time` → `{ client_transmitted, server_received, server_transmitted }` (client stamps its
  own **T4 receive time** locally, at the WS onMessage callback — see Clock)
- `client/state` → `{ state:"synchronized", player{ volume, muted, static_delay_ms } }`
- `server/state` → `{ metadata{ title, artist, album, album_artist, artwork_url, year, track,
  progress{ track_progress, track_duration, playback_speed }, repeat, shuffle, timestamp } }`
- `stream/start` → `{ player{ codec, sample_rate, channels, bit_depth, codec_header? } }`
- `stream/request-format` → `{ player{ codec, sample_rate, bit_depth, channels } }` (client asks for
  a format change; server replies with a new `stream/start`)
- `stream/clear` (no payload) — seek / track jump; `stream/end` (no payload) — end of stream
- `server/command` → `{ player{ command, volume?, mute? } }` (e.g. `command:"volume"`)
- `group/update` → `{ playback_state:"playing"|"stopped", group_id, group_name }`
- `client/goodbye` → `{ reason }` — `shutdown` | `restart` (warm, ~30 s resume grace) | `user_request`

## Audio formats

`codec ∈ {flac, opus, pcm}`, `sample_rate` (default **48000**), `channels` (2), `bit_depth` (**16**),
optional `codec_header` (base64, for FLAC). Battle-tested choices from massdroid:
- **List FLAC first** in `supported_formats` — it is the server's fallback order when no preferred
  format override is set; listing opus first makes grouped sync fall back to opus.
- Keep everything **48 kHz / 16-bit** for grouped sync + Android `AudioTrack` PCM16 (no resample in
  the timing path). 24-bit hi-res is a later, opt-in phase.
- `buffer_capacity` ≈ a few MB (massdroid uses 4 MB ≈ 30 s FLAC) so a throughput dip rides the buffer.

## Clock sync (Kalman)

NTP 4-point exchange feeding a **2-D Kalman filter (offset + drift)** — a port of the Sendspin
reference `sendspin-js/time-filter.ts` (the same filter massdroid ships). Critical detail: **stamp T4
(client-received) at the WebSocket `onMessage` callback**, before deserialize/coroutine dispatch —
capturing it later biases the offset low and the player plays late. Don't report
`client/state` available / start grouped playback until the filter has converged (≥ ~8 low-RTT
samples, error ≤ ~5 ms). `ClockKalmanFilter.kt` implements this (pure Kotlin, JVM-tested).

## Gotchas (learned from the reference)

- **Flexible duration**: `track_duration`/`track_progress` can be `123456` **or** `123456.0` (MA
  multiplies a float duration by 1000 without an int cast). A strict `Long` serializer drops the whole
  `server/state` and triggers reconnect loops — use a serializer that accepts both.
- **Ordered stream**: control JSON and binary audio share one WebSocket; process them through **one
  ordered flow** so `stream/clear` can't be reordered past audio frames.
- **static_delay_ms**: manual per-player sync trim, sent in `client/state`.

## nowdroid rewrite map (M0)

| nowdroid file | becomes |
|---|---|
| `discovery/MaDiscovery.kt` | `_sendspin-server._tcp` + TXT `path` (**done**) |
| `protocol/Messages.kt` + `AudioFormatSpec.kt` | reference-accurate `{type,payload}` models + `SendspinIncoming.parse` + a flexible-long serializer |
| `protocol/ClockSync.kt` | the Kalman `ClockKalmanFilter` (**added, JVM-tested**) wired in |
| `protocol/SendspinClient.kt` | plain-WS lifecycle: connect → (auth) → hello → time loop → ordered text/binary flow, reconnect/backoff |
| `protocol/AudioFrame.kt` + `audio/*` | binary audio chunk handling per `stream/start` format (M1) |
| `service/SendspinService.kt`, `ui/viewmodel/PlayerViewModel.kt` | orchestrate the above |

References ported/adapted with attribution: massdroid (MIT), MA mobile-app (Apache 2.0), sendspin-js.

## Music Assistant 2.10 — what changed for a client (verified against tags `2.9.13` and `2.10.0`)

The API surface the app uses is **unchanged**: every command it sends
(`music/*/library_items`, `music/*/get`, `music/search`, `player_queues/play_media`,
`player_queues/get_active_queue`, `config/players/*`, …) still exists with the same argument
names, and `player_queues/play_media` still takes `queue_id` / `media` / `option` / `radio_mode`.
Three behaviour changes are worth knowing:

- **Library listings are "summary" items now.** `music/<type>/library_items` gained
  `summary: bool = True`, so a listing returns the slim `*Summary` model rather than a fully
  hydrated one. It still carries `item_id`, `provider`, `media_type`, `uri` and the provider
  mappings, so browse and playback are unaffected — but `None` fields are omitted from the
  wire payload, so never treat an absent key as a meaningful value.

- **Errors are localised on the wire.** `ErrorResultMessage` resolves a `translation_key` into
  `details` for the connection's locale, and drops the key. `error_code` is therefore the only
  stable thing to branch on (`MediaNotFoundError` = 2) — the message text is whatever language
  the connection asked for.

- **"The requested media item could not be found." is not about the uri.** A uri
  `play_media` cannot resolve is *swallowed* (`queue_loader.py`, `except MusicAssistantError:
  "Skipping %s: %s"`), and a request where nothing resolved ends with the distinct
  `no_playable_items` — "There is nothing to play here." The generic `media_not_found` wording
  reaches a client from `play_index` → `_load_item` instead: the item resolved, and then the
  *stream* could not be obtained (provider refused, file gone, ffmpeg failed) or the audio
  buffer could not be prepared. `player_queues/controller.py` re-raises those as
  `MediaNotFoundError`, and the translation layer then replaces the actionable message with the
  generic one — the real reason is left in the **server log** and nowhere else.
  `MaRepository.describePlayFailure` re-probes `music/item_by_uri` so the app can tell the two
  apart instead of blaming the library.

### Known gap (not yet fixed)

`MaRepository.setPreferredSendspinFormat` matches the server's `preferred_sendspin_format`
options as `"<codec>_<rate>_<depth>"`. Music Assistant writes them as
`"<codec>:<rate>:<depth>:<channels>"` (`sendspin/player.py: format_to_option_value`, unchanged
between 2.9 and 2.10), so nothing but `"automatic"` has ever matched and the save is a silent
no-op. Harmless — the advertised format list already decides what the client receives — but the
codec preference in Settings does not reach the server.
