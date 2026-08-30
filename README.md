<p align="center">
  <img src="docs/app-icon.png" alt="CAMusic" width="120" />
</p>

<h1 align="center">CAMusic</h1>

<p align="center"><strong>One app. Your music. Your Philips Hue lights. Your Music Assistant speakers.</strong></p>

<p align="center">
  A local-first Android music player that drives your lights and your multi-room audio too,<br>
  running entirely on your own network and ready the moment you open it.
</p>

<p align="center">
  <a href="https://github.com/engabd11/CAMusic/releases"><img src="https://img.shields.io/github/v/tag/engabd11/CAMusic?label=release&sort=semver" alt="Release" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache%202.0-blue" alt="License" /></a>
  <img src="https://img.shields.io/badge/Android-12%2B-green" alt="Android 12+" />
  <img src="https://img.shields.io/badge/also-TV%20%C2%B7%20Auto%20%C2%B7%20webOS-3DDC84" alt="Platforms" />
</p>

---

## What it is

Three apps' worth of function in one, all talking to servers you run yourself.

| | |
|---|---|
| 🎵 **A player** | Navidrome, Subsonic, Jellyfin, Emby, Plex and local files. Gapless, bit-perfect, ReplayGain, offline downloads. |
| 🔊 **A speaker** | A full Music Assistant player over the Sendspin protocol, clock-synced for multi-room. |
| 💡 **A light show** | Philips Hue Entertainment, straight to the bridge at 60 Hz, reacting to what is playing. |
| ✨ **An atmosphere** | Scripted ambience effects with their own synthesised sound, no music required. |

Runs on phones, Android TV, Android Auto, and LG webOS TVs.

<p align="center">
  <img src="docs/screenshots/now-playing.jpg" width="30%" />
  <img src="docs/screenshots/light-sync.jpg" width="30%" />
  <img src="docs/screenshots/library.jpg" width="30%" />
</p>

---

## Why CAMusic

CAMusic is short for Cyborg Automation Music, built by [Cyborg Automation AU](https://github.com/engabd11).
The idea started with Philips Hue: Spotify and Samsung's Music Sync can drive the Hue
Entertainment system properly, but if you're on neither, most other players fall back to a
generic colour cycle that leaves the rest of what the bridge can do on the table. CAMusic
renders straight to the bridge at 60 frames a second instead, reading the room's own layout
and the track itself to shape every effect, so a Hue Entertainment setup gets the quality
it's actually capable of no matter what you're playing from.

---

## Your music

Point it at a server you already run. Nothing leaves your network.

| Server | Browse | Play | Download | Scrobble |
|---|:---:|:---:|:---:|:---:|
| **Navidrome / Subsonic** | ✅ | ✅ | ✅ | ✅ |
| **Jellyfin** | ✅ | ✅ | ✅ | ✅ |
| **Emby** | ✅ | ✅ | ✅ | ✅ |
| **Plex** | ✅ | ✅ | ✅ | ✅ |
| **Music Assistant** | ✅ | ✅ | n/a | ✅ |
| **On-device files** | ✅ | ✅ | n/a | n/a |

Plex signs in with a plex.tv PIN rather than a password typed into the app: tap
"Sign in with Plex" and finish it in the browser.

Artists, albums, playlists, radio, podcasts and audiobooks. Search across everything.
Multi-disc albums group properly, liner notes and biographies show where the server has
them, and anything can be taken offline for the train.

**Play at original quality** streams the untouched file straight from Navidrome when
Music Assistant would otherwise have had to transcode it for the phone.

## Music Assistant

Two directions at once, and they are genuinely different features.

**As a controller.** Browse MA's whole library, search it, drive the queue, group and
ungroup speakers, and set per-player sync offsets. MA's own gapless and crossfade
settings are exposed here rather than making you open its web UI.

The library front page is built from **MA's own Discover rows**: Random artists,
Forgotten albums, Never/rarely played, Most played, and whatever else your providers
offer, so a row the server grows appears without an app update.

**As a player.** CAMusic registers itself as a Sendspin player, so MA can stream to this
phone like any other speaker, including as part of a synced group.

- **FLAC, Opus and PCM**, decoded by `MediaCodec` and played through a native Oboe output
  engine with its own timeline. No ExoPlayer in the path.
- **Clock sync** by a two-state Kalman filter over an NTP-style four-point exchange, with
  drift correction in native code. The offset is persisted, so a reconnect skips the cold
  start rather than muting through it.
- **Announcements** (Home Assistant TTS, doorbells, timers) reach the phone even in the
  background, ducking whatever is playing rather than stopping it.
- **A latency trim**, signed, in Settings → Player, for when this phone's output path is
  faster or slower than the rest of the room.

## Light Sync

Two paths. Pick one in Settings.

### Straight to the bridge

The one to use. CAMusic opens its own DTLS entertainment stream to the Hue bridge and
renders **60 frames a second** from the audio it is decoding.

- **Real spatial awareness.** Lamp positions come from the entertainment area, so waves
  travel across the room, bass sits low, and the room's own shape (a line, a ring, a
  cluster) changes how effects are drawn.
- **Colour from the album art**, extracted for a *room* rather than for a UI accent,
  population-weighted so each colour holds for its share of the sleeve.
- **Five intensity rungs** plus Auto, which picks per track.
- **Creative layers**: Music DNA, Emotional Arc, Phantom Stage, Phone Conductor. See
  [docs/creative-light-shows.md](docs/creative-light-shows.md).
- **Flash safety** on a WCAG-derived budget, and a hard 12.5 Hz per-channel ceiling
  because that is what the Zigbee relay can carry.

### Through Home Assistant

A syncoV2 bridge for anyone already running that. Fewer effects, no spatial work.

### When the phone cannot hear the music

Playing to a *remote* speaker means this phone has no audio to analyse. Rather than
going dark, CAMusic drives the show from an **offline scan** of the track: beats,
sections and spectral shape worked out in advance, following the server's playhead.

## Effects

Ambience shows with their own sound, independent of music. **Fireworks, thunderstorm,
underwater, fireplace, light train, aurora.**

The lights and the audio are not two things kept in step; they are two projections of
the same event. A lightning strike is one object; the room reads it when it happens and
the speaker reads it after the propagation delay its own distance implies. So the thunder
always lands with the flash, at whatever intensity, on any hardware.

All sound is synthesised on the phone, so it never loops and never repeats. Each effect
can also take an audio file of your own as a bed instead.

## Everything else

- **Driving mode**: huge targets, swipe anywhere, GPS speed-limit awareness from an
  offline geohashed database.
- **Android Auto**, **Android TV**, and a **webOS** app for LG TVs.
- **Home-screen widget** with artwork and transport.
- **Listening stats**: a listening clock, tempo/energy scatter, artist-variety entropy,
  streaks, and where your music actually came from.
- **DSP**: parametric EQ and per-player settings, driven through MA.

---

## Setup

1. Install the APK from [Releases](https://github.com/engabd11/CAMusic/releases).
2. Open it and follow the onboarding: point it at a server, and optionally pair a Hue
   bridge (press the button on the bridge when asked).
3. That is it. There is no account.

**Requirements:** Android 12+. A Music Assistant server for MA features, a Hue bridge
with an entertainment area for Light Sync, and any Subsonic/Jellyfin server for the
library. All optional and independent.

### On Android TV

Sideload the same APK. The TV flavour has its own D-pad-navigable UI.

---

## Architecture

```
                         ┌──────────────────────────────┐
                         │        UI (Compose)          │
                         │  Library · Now Playing ·     │
                         │  Lights · Effects · Speakers │
                         └───────────────┬──────────────┘
                                         │
                    ┌────────────────────┼────────────────────┐
                    │                    │                    │
            ┌───────▼────────┐   ┌───────▼────────┐   ┌───────▼────────┐
            │  PlaybackOwner │   │ UnifiedNowPlay │   │ DirectLightSync│
            │ who owns sound │   │  one truth for │   │  60 Hz render  │
            └───────┬────────┘   │   the screen   │   │      loop      │
                    │            └───────┬────────┘   └───────┬────────┘
        ┌───────────┴───────────┐        │                    │
        │                       │        │            ┌───────┴────────┐
┌───────▼───────┐      ┌────────▼──────┐ │            │  Hue bridge    │
│  LocalPlayer  │      │   Playback    │ │            │  DTLS · 2100   │
│  (ExoPlayer)  │      │  (Sendspin)   │ │            └────────────────┘
│  Navidrome ·  │      │  MA streams   │ │
│  Jellyfin ·   │      │  to us        │ │
│  local files  │      └────────┬──────┘ │
└───────┬───────┘               │        │
        │                       │        │
        │              ┌────────▼──────────────┐
        │              │ SendspinNativeEngine  │
        │              │ MediaCodec → Oboe     │
        │              │ clock-synced timeline │
        │              └────────┬──────────────┘
        │                       │
        └───────────┬───────────┘
                    │
            ┌───────▼────────┐
            │AudioAnalysisTap│  FFT, beats, key, structure
            └────────────────┘  → feeds the light engine
```

Position on every path comes from `PlayerPositionTracker`, anchored on Music Assistant's
own `elapsed_time` rather than a local counter, so the bar agrees with the server.

### Audio pipeline

```
Music Assistant ──ws──► SendspinClient ──► SendspinTimeline (decode thread)
                             │                     │
                       ClockSync                MediaCodec
                    (Kalman filter)                │
                             │                     ▼
                             └──────────► SendspinNativeOutput
                                          (Oboe · drift correction)
                                                   │
                                                   ├──► speaker
                                                   └──► AudioAnalysisTap ──► Light Sync

Local files ──► ExoPlayer ──► TapRenderersFactory ──► AudioTrack
                                        │
                                        └──► AudioAnalysisTap ──► Light Sync
```

Both paths feed the same analysis tap, which is what lets one light engine serve
whichever is playing.

---

## Recent releases

**v0.11.0**: Emby and Plex as libraries; recorded sound for the ambience effects; a
spectrum analyser that reads as one; lyrics as typography; Settings reorganised.
**v0.10.9**: native Oboe Sendspin engine; MA playhead rewritten on the server's anchor;
webOS TV app; GPS speed limits; Effects mode.
**v0.10.8**: six settings-gated creative features; on-device stem separation.
**v0.10.7**: Android Auto; first Android TV release; phone UI polish.
**v0.10.6**: analyser v3 (key detection 12.4 % → 71.1 % shift-consistent).

Full history: [docs/release-history.md](docs/release-history.md).

### Next up

- Wear OS companion
- Audiobookshelf and Kodi as libraries
- Lyrics beyond the current provider set
- More Effects, and more bundled beds

---

## Known limitations

- **Hue entertainment areas are one client at a time.** If the Hue app takes the area,
  CAMusic's stream is revoked and stays stopped, deliberately, so its own stop button
  works.
- **Music DNA needs a local track scan**, so it does nothing while Music Assistant is
  streaming to the phone. The toggle says so.
- **Negative per-speaker offsets** are refused by Music Assistant, whose config field is
  unsigned. Use this phone's own latency trim, or nudge the other speakers later.
- **Releases are signed with a debug key.** Upgrading from a differently-sourced build
  needs an uninstall first.

---

## Building

```bash
git clone https://github.com/engabd11/CAMusic.git
cd CAMusic
./gradlew assembleMobileDebug          # phone/tablet
./gradlew assembleTvDebug              # Android TV
./gradlew testMobileDebugUnitTest      # ~1,000 unit tests
```

Needs JDK 17+ and the Android SDK. The native audio engine builds through CMake as part
of the normal Gradle build; nothing extra to install.

Flavours are `mobile` (default) and `tv`, so tasks are flavour-qualified. `-PsideBySide`
adds a `.freshtest` application-id suffix for installing alongside a release build.

---

## Documentation

| Document | What it covers |
|---|---|
| [docs/protocol-alignment.md](docs/protocol-alignment.md) | The Sendspin protocol as MA actually speaks it |
| [docs/creative-light-shows.md](docs/creative-light-shows.md) | The four creative light-show layers |
| [docs/spatial-swell-implementation.md](docs/spatial-swell-implementation.md) | Room-aware spatial effects |
| [docs/ma-playhead-rewrite-plan.md](docs/ma-playhead-rewrite-plan.md) | Why the playhead is anchored on MA's clock |
| [docs/track-prescan.md](docs/track-prescan.md) | Offline scanning, for remote-speaker light sync |
| [docs/providers.md](docs/providers.md) | Adding a music backend |
| [docs/architecture-decision.md](docs/architecture-decision.md) | Why direct-to-bridge over Home Assistant |
| [docs/release-history.md](docs/release-history.md) | Every shipped feature, by release |

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Bug reports with a logcat are welcome; so is
anything that makes the light show look better in a real room.

## Credits

CAMusic is developed by Cyborg Automation AU. It's built on
[Music Assistant](https://music-assistant.io), the Sendspin protocol, and
[syncoV2](https://github.com/oliverhoefling/syncoV2), whose colour extraction and effect
model the direct light path is a port of. Clock sync follows MassDroid's approach.

## License

Apache License 2.0. See [LICENSE](LICENSE).
