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

## The #1 work: re-align to the current spec

The skeleton targets an **older Resonate/sendspin draft**. The exact target is in
[`protocol-alignment.md`](protocol-alignment.md) (derived from github.com/Sendspin/spec). Summary of
the gaps: discovery service/port/path; a mandatory **Noise KKpsk2** handshake + pairing (none today);
`client_id` must be a **Curve25519 public key** (not a UUID); **post-handshake frames are binary**
(type byte; type 0 = JSON, types 4–7 = player audio) — WS-text JSON is pre-handshake only; Kalman
clock sync; `stream/clear` (not per-track `stream/end`) for gapless/seek; `stream/request-format`.

**Verify the live handshake first.** Capture a real device↔MA `/sendspin` session and read the
`Sendspin/sendspin-go` reference before implementing the Noise handshake + binary framing — the spec
is technical preview and a few framing details must be confirmed on the wire, not assumed.

## Milestones (Android-first)

- **M0 — Re-align + build.** Discovery → `_sendspin-server._tcp:8927` + TXT `path` (**done**).
  Rewrite the message models + handshake (client/init → Noise → server/hello → client/hello →
  server/activate), typed binary framing, Kalman-ish clock sync, Curve25519 identity + pairing.
  *Milestone:* the app connects, pairs, and **registers as a player in Music Assistant**.
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
