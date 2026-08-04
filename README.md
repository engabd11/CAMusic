# CAMusic Sendspin Client for Android.

**Your phone as a Music Assistant speaker, a remote for every other speaker in the
house, and a standalone player for your own library when the servers are off.**

CAMusic joins Music Assistant as a real Sendspin player, so MA can stream to it, group
it with your other speakers and speak Home Assistant announcements through it. The same
app browses the library, drives whichever player you are actually listening to, and can
drop Music Assistant entirely and play straight from Navidrome - including with no
network at all, once tracks are downloaded.



---

## The two halves

The app has two independent playback paths, and the badge in the top right of Now
Playing always says which one you are on. They share a UI and almost nothing else.

| | **Music Assistant** | **Navidrome / OpenSubsonic** |
|---|---|---|
| Where audio is decoded | This phone, from MA's stream | This phone, from the file |
| Plays to | Any MA speaker, or this phone | This phone only |
| Grouping, Light Sync | Yes | No - both are MA features |
| Server-side DSP / EQ | Yes | No |
| Works with MA down | No | Yes |
| Works with no network | No | Yes, for downloads |

## As a Music Assistant player

- Registers over the Sendspin WebSocket protocol and stays available in the background
  through a foreground service, so playback and announcements start without opening the
  app.
- **Clock-synced.** An NTP-style four-point exchange feeds a two-dimensional Kalman
  filter tracking offset *and* drift, so grouped playback stays in step. The player
  reports `state: "error"` and mutes until that filter has converged, rather than
  joining a group with an offset still seconds wide.
- FLAC, Opus and PCM, decoded with `MediaCodec` and played through `AudioTrack`.
- **24-bit is opt-in.** With bit-perfect on, the FLAC decoder is asked for float output
  and the track is built from what the decoder actually reports - never from the depth
  the server claimed, because two-byte samples in a three-byte frame is noise, not
  degradation.
- Per-player **sync offset**, applied locally to frame scheduling as well as written to
  MA's config.
- **Per-player parametric EQ** through MA's own server-side DSP pipeline: bands with
  frequency, Q, gain and filter type, tone controls, preamp, and presets.
- Home Assistant TTS announcements arrive like any other MA player.
- mDNS discovery on the LAN, or type the URL.

## As a controller

- Browse and search the MA library, plus *Recently played*, *Recently added*, *Continue
  listening*, *For you* and favourites shelves.
- Play to **any** MA player. Now Playing reflects and controls whichever is selected.
- Queue: view, jump, reorder, remove, clear, save as a playlist, or **transfer** the
  queue and playhead to another speaker mid-track.
- **Playlists**: create, delete, and add tracks - on both backends.
- **Lyrics**: LRC-timed where the provider has them, scrolling in step with playback.
- **Similar**: acoustically similar tracks, or a natural-language search over sonic
  embeddings ("late night drive, warm synths").
- Favourites, preview, playback speed, radio mode, sleep timer.
- Speaker grouping around a leader, with per-player and group volume.

## Standalone: Navidrome / OpenSubsonic

Switch the Library tab in **Settings → Library**. No Music Assistant in the path.

- Browse artists, albums, playlists, genres and starred items, and search all of it.
- A **real queue** on **ExoPlayer**: gapless album playback, exact seek, shuffle,
  repeat, and a lock-screen media notification.
- **ReplayGain, applied** - off, track or album, defaulting to album. Boosts are capped
  at +3 dB, because a boost multiplies samples already mastered against full scale.
- Original files by default (`format=raw`), so a FLAC stays a FLAC.
- **Download** a track, album or playlist for offline, audio and cover art, with a
  storage cap and a Wi-Fi-only option.
- With the server unreachable the library drops to **Offline** and runs on what is on
  the phone.
- Stars, ratings and scrobbles are written back, including on the "play at original
  quality" path where MA is selected but Navidrome serves the bytes.

## Light Sync

Drives the [syncoV2](https://github.com/engabd11/syncoV2) `hue_music_sync` Home
Assistant integration over the HA WebSocket API: per-zone enable, intensity ladder,
effect, brightness ceiling, timing offset, live tunables, and all 19 colour schemes
previewed with their real gradient colours.

## Requirements

- Android 12+ (API 31)
- A [Music Assistant](https://music-assistant.io) server (2.9+) with the Sendspin player
  provider enabled
- *Optional:* Navidrome / OpenSubsonic, as a standalone backend
- *Optional:* Home Assistant with syncoV2, for Light Sync

## Setup

1. Install the APK from [Releases](https://github.com/engabd11/sendspin-nowdroid/releases).
2. Pick a discovered MA server or enter its URL. Credentials are encrypted at rest with
   the Android Keystore.
3. The phone appears in Music Assistant under the name in **Settings → This player**.
4. For Navidrome, add the server under **Settings → Servers**; for Light Sync, add a
   Home Assistant URL and long-lived token in the same place.

## Audio

```
Music Assistant path                     Navidrome / offline path
--------------------                     ------------------------
WebSocket binary frame                   HTTP (format=raw) or local file
  [type=4][server_ts_us][payload]                    |
        |                                       ExoPlayer
  FLAC / Opus -> MediaCodec -+                  (gapless, exact seek,
  PCM         -> passthrough-+-> AudioTrack      ReplayGain, float output)
        |
  scheduled against the Kalman-filtered server clock
```

Formats are advertised, not requested: MA may only send something the client listed, so
the list *is* the setting. 48 and 44.1 kHz are always offered; 88.2/96 kHz with hi-res
on; 176.4/192 kHz only with bit-perfect on **and** only after probing that the device
will open a track at that rate.

A native AAudio `I24` exclusive-mode pipeline lives in `app/src/main/cpp/`. **It is not
compiled and nothing calls it.** True bit-perfect output that bypasses the Android mixer
is a future phase, not a current feature.

## Known limits

Written down because the alternative is discovering them by ear:

- **Track transitions on the MA path are not gapless.** They were, briefly, and it broke
  the pause button: MA sends `stream/end` for a pause and for a track boundary with a
  byte-identical message, and the client advertises a 4 MB read-ahead. Holding that
  buffer through `stream/end` meant pause kept playing for half a minute. Pause won.
  Gapless there needs a way to tell the two apart.
- **Gapless does work on the Navidrome path**, where ExoPlayer owns the whole queue.
- ReplayGain is applied on the Navidrome path only. MA does its own normalisation
  server-side, and applying it twice would be worse than not applying it.
- media3 is pinned to 1.8.0: 1.9+ requires compileSdk 36 and AGP 8.7.3 tops out at 35.
- The XML window theme is still `Theme.Material.NoActionBar` while the Compose layer is
  Material 3.
- Everything is a debug build signed with a local key. There is no release signing config.

## What's planned

- **Light Sync for the local player.** syncoV2 follows an HA `media_player` entity, and
  the Navidrome player is an ExoPlayer inside this process that HA cannot see - so the
  backend most likely to be used for critical listening is the one the lights ignore.
  The plan is a second mode that authenticates to HA directly and drives the zone from
  the app's own playback state. See §17 of the improvement roadmap.
- **MA player config from the app** - the stream quality per player that currently has
  to be set in Music Assistant's own UI.
- **Version picker.** `music/tracks/track_versions` and `album_versions` list every copy
  of a track across every provider; the client layer is done, the UI is not. This is the
  command that speaks to why anyone runs a local library next to streaming.
- **Cross-device resume** via `getPlayQueue`/`savePlayQueue` - client done, UI not.
- **MA loudness readout.** `StreamDetails` carries `loudness`, `loudness_album` and
  `volume_normalization_gain_correct`; the quality card currently says nothing about
  what MA did to the level.
- **Bit-perfect exclusive-mode output** via the native AAudio path.
- Toolchain bump (AGP + compileSdk 36) to unpin media3.

## Architecture

```
Jetpack Compose UI  ·  Material 3  ·  OLED design system (true black, album accent)
  Now Playing · Library · Speakers · Lights · Settings
        |
   ViewModels (per screen; player + library VMs hoisted to the app root)
        |
        +-- Sendspin protocol --  SendspinClient · ClockSync · Kalman filter
        |   (this phone as an MA player)      -> SendspinAudioEngine -> AudioTrack
        |
        +-- MA main API --------  MaApiClient (/ws) · MaRepository
        |   (browse, search, transport, queues, grouping, DSP, player config)
        |
        +-- Home Assistant -----  HaClient · LightSyncRepository
        |
        +-- OpenSubsonic -------  SubsonicClient · LocalPlayer (ExoPlayer) · DownloadManager

  SendspinConnectionService (foreground) keeps the process and socket alive
  SendspinService / LocalPlaybackService own the media notifications
```

One detail worth knowing: control JSON and binary audio share a single WebSocket, and
`stream/clear` only means anything if it is still ordered against the audio around it.
Everything the socket produces therefore goes through **one** queue drained by one
consumer, rather than separate flows.

Colour comes from the artwork: covers are clustered in CIELAB to pull a lead accent plus
companion swatches, which drive every glow, gradient and control tint.

## Building

JDK 17 and the Android SDK (compileSdk 35). No NDK needed.

```bash
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest # 167 unit tests: protocol, clock, scheduling, parsing
./gradlew :app:lintDebug
```

Releases are cut **locally**, not from CI: a CI runner generates a fresh debug keystore
every run, and Android refuses to update an app whose signer changed. See the header of
`.github/workflows/release.yml`.

## Documentation

- [docs/improvement-roadmap.md](docs/improvement-roadmap.md) - the API audit, what
  shipped, and a Corrections section recording where earlier notes were wrong
- [docs/protocol-alignment.md](docs/protocol-alignment.md) - what MA's Sendspin provider
  actually speaks
- [docs/architecture-decision.md](docs/architecture-decision.md)
- [docs/design-brief.md](docs/design-brief.md)
- [CONTRIBUTING.md](CONTRIBUTING.md)

## Credits

The Sendspin client and audio engine are modelled on
[massdroid](https://github.com/sfortis/massdroid_native) (MIT) and Music Assistant's own
mobile app (Apache-2.0). Light Sync targets
[syncoV2](https://github.com/engabd11/syncoV2).

Built by **Cyborg Automation AU** (cyborgautomation.com.au)

## License

MIT - see [LICENSE](LICENSE)
