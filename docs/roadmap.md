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

- No Kotlin/Gradle/NDK build in the agent environment — code is written + pushed; you build/run in
  Android Studio (NDK 26+). The plan is to grow a JVM-testable protocol core so message
  (de)serialization, clock math, format negotiation, and Noise vectors get **JVM unit tests**
  (`./gradlew :app:testDebugUnitTest`, later `:core:test`) — the main verifiable surface off-device.
- The protocol layer is being re-aligned incrementally so the tree isn't left half-rewritten; the
  Noise handshake + binary framing land once the live capture confirms the wire format.
