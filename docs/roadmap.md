# Sendpin Android — roadmap (resume + re-align to the current Sendspin spec)

## Context

The native **Android player** for the synco → Sendspin music-player program. The Home Assistant
side already has a switchable Music Assistant / Navidrome backend, a dashboard card that browses
(Artists/Albums/Tracks/Playlists) + searches + plays/enqueues, volume + seek, and speaker grouping.
This app is the on-device Sendspin **player**: it registers with Music Assistant, plays audio
locally, and (later) works offline.

**Decisions:**
- **Android first.** Windows/Linux desktop is a later stage (keep the protocol core reusable, but
  don't pay full KMP cost yet).
- **Standard Sendspin (16-bit FLAC) first.** Keep the AAudio **I24** hi-res path for a later 24-bit
  bit-perfect phase.
- **Backend-first, wire the design last.** Functional plain Compose now; polished design applied at
  the end (design can happen in parallel and never blocks the protocol work). Hand the design over
  ~M2.
- **Offline download is in scope** (M3).

## The #1 work: align to what Music Assistant actually speaks

Two working, permissively-licensed clients — **massdroid** (MIT) and **MA's own mobile app**
(Apache 2.0) — show MA speaks a **plain WebSocket + JSON** Sendspin (text frames = JSON, binary =
audio), **not** the spec's Noise-secured variant. So no packet capture and no Noise library are
needed; the exact message shapes, formats, clock, and gotchas are in
[`protocol-alignment.md`](protocol-alignment.md). nowdroid's original plain-WS approach was close —
the work is matching the `{type,payload}` messages, the flac/opus/pcm @ 48k/16 formats, the Kalman
clock, and the reconnect/ordering behaviour, then wiring the audio engine.

## Milestones (Android-first)

- **M0 — Re-align + build.** Discovery → `_sendspin-server._tcp` + TXT `path` (**done**). Rewrite the
  message models (`{type,payload}`), the plain-WS client (connect → optional auth → `client/hello` →
  `server/hello` → time loop, with reconnect/backoff), and the Kalman clock (**added**).
  *Milestone:* the app connects and **registers as a player in Music Assistant**.
- **M1 — Playback.** FLAC 16-bit + PCM, **gapless**, sample-accurate sync vs a real MA + a second
  player. Foreground service + audio focus (scaffolded).
- **M2 — Control + library.** `controller` role: browse Artists/Albums/Tracks/Playlists (same model
  as the HA card), search, queue, transport, volume — bidirectional (device ⇄ MA). Wire the design.
- **M3 — Offline download.** DownloadManager (original audio) + Room DB + a Downloads view + offline
  playback + settings (storage cap, Wi-Fi-only, per-track/album/playlist). Prefer original FLAC.
- **M4 — Navidrome-direct standalone.** Browse/search/play from OpenSubsonic when MA is down.
- **M5 (deferred) — 24-bit bit-perfect hi-res.** Keep AAudio I24; source the original FLAC.
- **Later — Desktop.** Promote a `:core` module to KMP + a Compose Desktop app.

## How we work

- The JVM test suite is the main verifiable surface off-device — protocol
  (de)serialization, clock math, format negotiation, the light-sync DSP and room
  geometry (`./gradlew :app:testDebugUnitTest`, ~526 tests). Everything that
  needs real hardware — audio output, the Hue bridge, the phone's own volume —
  is still unverified there, and that gap is the one thing standing between this
  and calling it production-ready.
- **The wire format is plain WebSocket + JSON**, not Noise. Music Assistant's
  Sendspin server speaks `{type, payload}` text frames with binary audio, which
  is what `SendspinClient` implements. (The published Sendspin spec has since
  grown a Noise-encrypted handshake and a pairing flow; MA does not require it
  yet, so this is a forward-compatibility item rather than outstanding work.)
