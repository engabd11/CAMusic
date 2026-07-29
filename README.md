# Sendspin for Android

**Your phone as a Music Assistant player — and a remote for everything else in the house.**

Sendspin joins your Music Assistant server as a real Sendspin player, so MA can stream to
it, group it with your other speakers, and speak Home Assistant TTS announcements through
it. The same app also browses the library, drives whichever player you're listening to,
and controls Hue music-sync lighting through Home Assistant.

> **Status: early — v0.1.3.** The player, controller, grouping and light-sync paths all
> work against a live server. Playback is 16-bit; the bit-perfect 24-bit path is not built
> yet (see [Audio](#audio)). Expect rough edges.

## What it does

**As a player**
- Registers with Music Assistant over the Sendspin WebSocket protocol and stays available
  in the background via a foreground service
- Clock-synced playback (NTP-style exchange through a Kalman filter) so it stays in step
  when grouped with other MA speakers
- FLAC, Opus and PCM, decoded with Android's `MediaCodec` and played through `AudioTrack`
- Home Assistant TTS announcements land on it like any other MA player
- mDNS discovery of MA servers on the LAN (`_mass._tcp.`), or type the URL in

**As a controller**
- Browse and search the MA library — artists, albums, tracks, playlists — plus
  *Recently played*, *Recently added*, *Continue listening* and *For you* shelves
- Play to **any** MA player, not just the phone: Now Playing reflects and controls
  whichever player is selected
- Transport, seek, shuffle, repeat, volume, and the live codec/sample-rate/bit-depth of
  whatever is actually streaming
- **Queue** — see it, jump to any track, reorder, remove, clear, or save the whole thing
  as a playlist
- **Lyrics** — LRC-timed where the provider has them, scrolling and highlighting in step
  with playback; plain text otherwise
- **Similar** — acoustically similar tracks to what's playing, or a natural-language
  search over the sonic embeddings ("late night drive, warm synths"), queued as
  *play next* or *add*
- **Favourites** — the heart on Now Playing and on every library track row, read from
  and written back to MA
- **Preview** — audition a library track without touching the queue
- **Player options** — power, playback speed (handy for audiobooks), and *Don't stop
  the music*
- Download tracks for offline playback

**Speakers**
- Group and ungroup MA players around a leader, with per-player and group volume
- Per-player Sendspin **sync offset** (MA's `sendspin_sync_delay` player config), so you
  can nudge a lagging speaker into line by hand

**Light Sync**
- Drives the [syncoV2](https://github.com/engabd11/syncoV2) `hue_music_sync` Home
  Assistant integration over the HA WebSocket API
- Per-entertainment-zone: enable, intensity ladder (with Auto and its selectable rungs),
  effect, brightness ceiling, light timing offset, and the advanced live tunables
- All 19 colour schemes — 16 preset palettes previewed with their real gradient colours,
  plus the two album-art modes and the song-harmony mode
- Pick which media player a zone follows, or leave it on Auto

## Requirements

- Android 12+ (API 31)
- A [Music Assistant](https://music-assistant.io) server (2.9+) on the same network,
  with the Sendspin player provider enabled
- *Optional:* Home Assistant with the [syncoV2](https://github.com/engabd11/syncoV2)
  `hue_music_sync` integration, for the Light Sync tab
- *Optional:* a Navidrome / OpenSubsonic server, as a direct library backend when MA
  isn't reachable

## Setup

1. Install the debug APK (see [Building](#building)) or grab it from the CI artifacts.
2. On first launch, pick a discovered MA server or enter its URL
   (e.g. `http://192.168.0.10:8095`). Add a username and password if your server
   requires auth — they're encrypted at rest with the Android Keystore.
3. The phone appears in Music Assistant under the name set in **Settings → Player**.
4. For Light Sync, open the **Lights** tab and add your Home Assistant URL and a
   long-lived access token (HA → Profile → Security).

## Audio

Playback is currently **16-bit**, in pure Kotlin, with no NDK dependency:

```
WebSocket binary frame
    ├─ FLAC / Opus → MediaCodec → PCM 16 → AudioTrack
    └─ PCM         → passthrough        → AudioTrack
```

The app advertises `flac 48/16` first, which matches Music Assistant's own default and
keeps grouped sync stable. It also advertises 24-bit FLAC formats for the future.

A native AAudio `I24` exclusive-mode pipeline lives in `app/src/main/cpp/` for the planned
bit-perfect 24-bit phase. **It is not compiled** — the `externalNativeBuild` block is
commented out in `app/build.gradle.kts`, so the app builds and runs without the NDK.

## Architecture

```
Jetpack Compose UI  ·  OLED design system (true black, album-derived accent)
  Now Playing · Library · Speakers · Lights · Settings
        │
   ViewModels (per screen; the player VM is hoisted to the app root)
        │
        ├── Sendspin protocol ──  SendspinClient · ClockSync · Kalman filter
        │   (this phone as an MA player)      → SendspinAudioEngine → AudioTrack
        │
        ├── MA main API ────────  MaApiClient (/ws) · MaRepository
        │   (browse, search, transport, queues, grouping, player config)
        │
        ├── Home Assistant ─────  HaClient · LightSyncRepository
        │   (hue_music_sync entities + set_options service)
        │
        └── OpenSubsonic ───────  SubsonicClient   (direct library fallback)

  SendspinService (foreground) keeps the process and connection alive
```

Colour comes from the artwork: covers are clustered in CIELAB (the same approach syncoV2
uses for its album palettes) to pull a lead accent plus companion swatches, which drive
every glow, gradient and control tint in the app.

## Building

Requires JDK 17 and the Android SDK (compileSdk 35). No NDK needed.

```bash
git clone https://github.com/engabd11/sendspin-nowdroid.git
cd sendspin-nowdroid
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

Unit tests (protocol and clock logic):

```bash
./gradlew :app:testDebugUnitTest
```

CI builds the debug APK on every push and pull request and uploads it as an artifact.

## Documentation

- [docs/architecture-decision.md](docs/architecture-decision.md)
- [docs/protocol-alignment.md](docs/protocol-alignment.md)
- [docs/design-brief.md](docs/design-brief.md)
- [docs/roadmap.md](docs/roadmap.md)
- [CONTRIBUTING.md](CONTRIBUTING.md)

## Credits

The Sendspin protocol client and audio engine are modelled on
[massdroid](https://github.com/music-assistant) (MIT) and Music Assistant's own mobile
app (Apache-2.0). Light Sync targets the
[syncoV2](https://github.com/engabd11/syncoV2) `hue_music_sync` integration.

Built by **Cyborg Automation AU** (cyborgautomation.com.au)

## License

MIT — see [LICENSE](LICENSE)
