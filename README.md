# CAMusic - a Sendspin client for Android

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

Today that means Light Sync needs Home Assistant *and* syncoV2, and follows an HA
`media_player` entity — so it cannot see the Navidrome/offline player, which lives
inside this process where HA has no view of it. Connecting to the Hue Bridge directly
is the planned answer to both; see **What's planned**.

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
3. The phone appears in Music Assistant under the name in **Settings → Sendspin player**.
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

- **Gapless on the MA path is a server setting this app doesn't expose yet.** Music
  Assistant decides it per player - disabled, standard or smart - the same way it decides
  crossfade, so it is changed in MA's own UI rather than here. Not a protocol problem to
  solve client-side: an earlier attempt to infer track boundaries by holding the read-ahead
  buffer through `stream/end` broke the pause button instead, because MA sends a
  byte-identical `stream/end` for both and 4 MB of held buffer meant pause kept playing
  for half a minute.
- **Gapless does work on the Navidrome path**, where ExoPlayer owns the whole queue.
- ReplayGain is applied on the Navidrome path only. MA does its own normalisation
  server-side, and applying it twice would be worse than not applying it.
- **The quality card says nothing about level.** MA's `StreamDetails` carries `loudness`,
  `loudness_album` and `volume_normalization_gain_correct` - so whether the server
  normalised a carefully-mastered file is knowable, and currently unasked.
- **Releases are signed with the local debug key.** There is no release keystore. Updates
  work because that key has been stable since v0.1.0; a CI-signed build would have a
  different signer and Android would refuse to install it over an existing copy.
- Cleartext is allowed to LAN addresses only. A server reached over plain HTTP on a
  public hostname is refused rather than sent credentials in the clear - use HTTPS for
  anything off the local network.

## What's planned

The full reasoning, file lists and risks for each item are in
[docs/v0.5.0-analysis.md](docs/v0.5.0-analysis.md). This is the short version, with
anything already shipped struck from it.

### Audiophile core

- **MA player config, from the app.** Gapless (disabled / standard / smart) and crossfade
  are per-player settings Music Assistant already owns; today they mean a trip to MA's own
  UI. This is a UI job rather than a protocol one - `MaRepository.playerConfigEntry()`
  already reads an arbitrary `ConfigEntry` with the `options` the server declares, and
  handles MA's protocol-wrapped key names, which is exactly what `preferred_sendspin_format`
  and the sync delay already do.
- **MA loudness readout** in the quality card - what the server did to the level, not
  just what format it sent.
- **Bit-perfect exclusive-mode output** via the native AAudio I24 path in
  `app/src/main/cpp/`, which is written and deliberately not compiled. Bypasses the
  Android mixer: no resampling, no system volume, no other app's audio mixed in.
- **Crossfade on the Navidrome path.** The MA path gets this from the server config
  above; the local ExoPlayer queue is ours, so it needs its own. Gapless is right for an
  album, a crossfade is what a party playlist wants.

### Feature completion

- **Version picker.** `music/tracks/track_versions` and `album_versions` list every copy
  of a track across every provider; the client layer is done, the UI is not. This is the
  command that speaks to why anyone runs a local library next to streaming.
- **Cross-device resume** via `getPlayQueue`/`savePlayQueue` - client done, UI not.
  Start on the phone, finish at the desk, same second of the same track.
- **Warm reconnect.** `client/goodbye` with `reason: "restart"` on backgrounding, so MA
  holds the player slot for ~30 seconds and a quick app switch doesn't drop the phone
  out of the speaker list. Attempted once and reverted: the first cut disconnected on
  every backgrounding, including mid-song. It needs to fire only while idle.
- **A Downloads screen** with sort, search, retry and a storage breakdown, rather than a
  shelf in the Library.

### Light Sync, direct to the bridge

The direction for Light Sync, and the largest single item on this list.

Today Light Sync goes App → HA WebSocket → syncoV2 → Hue Entertainment API → Bridge, and
follows an HA `media_player` entity. That has three consequences: it needs Home Assistant
and the syncoV2 integration, it cannot see the Navidrome/offline player at all — that is
an ExoPlayer inside this process, invisible to HA — and it reacts at the speed an entity
updates, which is seconds.

**Direct mode cuts the whole path out: App → Hue Bridge over DTLS/UDP.**

- mDNS discovery of the bridge, the physical link-button auth flow, then the Entertainment
  API streaming per-light colour at 25–50 Hz.
- syncoV2's music-reactive algorithm ported from Python to Kotlin and run on-device.
- Driven by a tap on the **decoded PCM** — `SendspinAudioEngine` on the MA path, an
  ExoPlayer `AudioProcessor` on the Navidrome path. Real onset detection and per-band FFT
  at audio frame rate rather than polling an entity.

Which is why this replaces, rather than complements, the older plan of a second HA-driven
mode for the local player. It is the only approach that reaches the offline player at all,
it is the only one that works for someone with a Hue Bridge and no Home Assistant, and
having the samples in hand is strictly better than watching a proxy for them.

The colour schemes, tunables, effect definitions and the whole Light Sync screen carry
over unchanged — only the transport underneath is different. The hard part is the DTLS
streaming and the ~2000 lines of beat detection and colour logic to port.

### Platform

- **Android Auto.** media3-session is already a dependency and the `MediaSession`
  already exists; the work is a manifest declaration and a browse tree.
- **Home screen widget** (Glance) and a **Quick Settings tile** for Light Sync.
- **A release keystore and a CI release pipeline.** See Known limits.

### Housekeeping

- Room for the download index, which is a JSON file rewritten in full on every change.
- Instrumented tests. The 174 unit tests cover protocol, clock and parsing; nothing
  covers the audio path, the service lifecycle or the UI.
- Crash reporting - ACRA or similar, self-hosted.
- The six files over 700 lines, and the 184 `runCatching` sites that swallow a failure
  without recording it.

### Shipped since the roadmap was written

Wi-Fi-only downloads and the storage cap (both were settings that did nothing) ·
toggleable background connection for TTS · proguard keep rules · a network security
config and LAN-only cleartext · MA's output limiter · multi-disc album grouping ·
composer credits · the frequently-played shelf · the `MaDiscovery` null-listener crash

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

JDK 17 and the Android SDK (compileSdk 36). No NDK needed.

```bash
./gradlew assembleRelease        # app/build/outputs/apk/release/app-release.apk
./gradlew :app:testDebugUnitTest # 174 unit tests: protocol, clock, scheduling, parsing
./gradlew :app:lintDebug
```

Judge anything about how the app *feels* on a release build. A debug build carries
Compose composition tracing, skips R8 and runs `debuggable`, which suppresses most of
ART's optimisation - scroll performance measured on one is measuring the build.

```bash
./gradlew :app:installRelease -PsideBySide   # installs alongside, own empty data
./gradlew :app:generateBaselineProfile       # needs a device; WIPES app data
./gradlew :app:compileReleaseKotlin -PcomposeMetrics   # skippability reports
```

Releases are cut **locally**, not from CI: a CI runner generates a fresh debug keystore
every run, and Android refuses to update an app whose signer changed. See the header of
`.github/workflows/release.yml`.

## Documentation

- [docs/v0.8-plan.md](docs/v0.8-plan.md) - the plan for the next release: continuous
  play on the Navidrome path, the quality badge and its pop-up, lyrics, the album and
  artist screens, live search, the DSP that does nothing, and the roadmap sequenced
- [docs/v0.5.0-analysis.md](docs/v0.5.0-analysis.md) - the full codebase analysis and
  the roadmap the "What's planned" section above summarises
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
