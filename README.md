# CAMusic - a Sendspin client for Android

**Your phone as a Music Assistant speaker, a remote for every other speaker in the
house, a standalone player for your own library when the servers are off, and a light
show that talks straight to the Hue Bridge.**

CAMusic joins Music Assistant as a real Sendspin player, so MA can stream to it, group
it with your other speakers and speak Home Assistant announcements through it. The same
app browses the library, drives whichever player you are actually listening to, and can
drop Music Assistant entirely and play straight from your own server - Navidrome, any
Subsonic-compatible server, or Jellyfin - including with no network at all, once tracks
are downloaded.

Current release: **v0.8.0**. See [Releases](https://github.com/engabd11/CAMusic/releases)
and [docs/release-notes/](docs/release-notes/).

---

## The two halves

The app has two independent playback paths, and the badge in the top right of Now
Playing always says which one you are on. They share a UI and almost nothing else, which
is why most features here had to be answered twice.

| | **Music Assistant** | **Your own library** |
|---|---|---|
| Servers | Music Assistant | Navidrome, Subsonic / OpenSubsonic, Jellyfin |
| Where audio is decoded | This phone, from MA's stream | This phone, from the file |
| Plays to | Any MA speaker, or this phone | This phone only |
| Grouping | Yes | No - an MA feature |
| Server-side DSP / EQ | Yes | No |
| Continuous play (radio) | Yes, server-side | Generated on device, where the server can suggest |
| Gapless | MA's own per-player setting, driven from the app | Yes |
| Light Sync | Home Assistant path | Direct to the bridge |
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
  frequency, Q, gain and filter type, tone controls, preamp, and presets. When the server
  refuses a change it now says why, rather than reporting success and doing nothing.
- **Music Assistant's own per-player settings, from here.** Gapless and crossfade are
  MA's - it applies them to the stream before it reaches the phone - so the app reads the
  `ConfigEntry` list the server declares and renders whatever it finds, labels and
  permitted options included. A build that renames a setting, or grows a fourth mode,
  needs no release here.
- Home Assistant TTS announcements arrive like any other MA player.
- mDNS discovery on the LAN, or type the URL.

## As a controller

- Browse the MA library - *Recently played*, *Recently added*, *Continue listening*,
  *For you* and favourites shelves - and **search as you type**, debounced, with results
  held across keystrokes and a cache so backspacing is instant.
- Play to **any** MA player. Now Playing reflects and controls whichever is selected.
- Queue: view, jump, reorder, remove, clear, save as a playlist, or **transfer** the
  queue and playhead to another speaker mid-track.
- **Album and artist screens** with an About section, related albums, top tracks and
  similar artists.
- **Version picker** - every copy of a track across every provider, so the 16/44 stream,
  the 24/96 purchase and the CD rip are a choice rather than whichever the row came from.
- **Cross-device resume** - start on the phone, finish at the desk, same second of the
  same track.
- **Playlists**: create, delete, and add tracks - on both backends.
- **Lyrics**: LRC-timed where the provider has them. The sung line sits centred, scrolls
  smoothly and scales into place, with a 200 ms lead and a trim in Settings for a server
  whose timings are off.
- **Quality badge and card**: `FLAC • 96/24 • 3 Mb/s`, channels, file size, and what
  Music Assistant did to the level - normalisation is reported, not guessed at.
- **Similar**: acoustically similar tracks, or a natural-language search over sonic
  embeddings ("late night drive, warm synths").
- Favourites, preview, playback speed, radio mode, sleep timer.
- Speaker grouping around a leader, with per-player and group volume.

## Standalone: your own library

Add servers under **Settings → Libraries** and pick which one the Library tab browses.
No Music Assistant in the path - the phone talks to the server and decodes the file
itself, which is what makes downloads and offline playback possible.

**A list of servers, not a switch.** Each is a card with its own credentials, status and
stream-quality setting; one is marked Active. Adding a provider is an adapter against
the `MusicSource` interface, not a change to the app around it.

### Supported today

| Provider | Sign-in | Notes |
|---|---|---|
| **Navidrome** | Username + password | The reference implementation. Its OpenSubsonic extensions give synced lyrics, ReplayGain tags and an exact per-track format, so the quality badge reads `FLAC • 96/24 • 3 Mb/s` rather than just the codec. |
| **Subsonic / OpenSubsonic** | Username + password | Gonic, Airsonic, Astiga, Ampache's Subsonic API - anything speaking the protocol. Same client as Navidrome; what differs is how much of it the server implements. |
| **Jellyfin** | Username + password | Browses the music library and streams the original file. Its `MediaStreams` carry codec, rate, depth, bitrate and channels per track, so it gets the full quality badge too. |
| **Music Assistant** | Optional credentials | Not a `MusicSource` - it owns a server-side queue and plays to speakers this app never decodes for. It is the app's other half, not another library. |

**Capabilities are probed, not assumed.** A plain Subsonic server is never offered a
lyrics pane it can only fill with a shrug: the app asks `getOpenSubsonicExtensions` and
lights up only what the server actually declared. Where a provider genuinely lacks
something, the feature is left out rather than shown empty.

The one place that shows today: **on-device radio needs a similar-tracks endpoint**,
which only the Subsonic protocol has. On Jellyfin, continuous play falls back to ranking
your downloads, so with nothing downloaded it has nothing to offer. Everything else in
the list below works the same on all three.

### Planned

Listed in the app's own provider picker, greyed, so the roadmap is visible where it is
asked about. Auth and endpoints for each are written up in
[docs/providers.md](docs/providers.md).

**HTTP APIs** - the same shape as what exists, so these are adapters rather than
projects:

| Provider | Sign-in | What it needs |
|---|---|---|
| **Emby** | Username + password | Jellyfin's ancestor; near-identical API behind its own auth header |
| **Plex** | plex.tv PIN flow | No server password is ever typed - a browser round-trip and a polled `/pins/{id}` |
| **Audiobookshelf** | Bearer token | `/api/libraries`, and a music library alongside the audiobooks |
| **Kodi** | Username + password | JSON-RPC rather than REST; browses the library Kodi already scanned |

**Filesystems** - a different class of work, and worth saying so plainly. These are
folders rather than APIs: nothing answers "list the artists", so each needs a crawler, a
**tag reader** (the app has none today) and a local index. The argument is for one
`IndexedFileSource` that the transports plug into rather than eight separate adapters.

| Provider | Transport | Sign-in |
|---|---|---|
| **This device** | `MediaStore.Audio` | A runtime permission |
| **SMB (v2/v3)** | jcifs-ng | Username / password / domain |
| **WebDAV** | OkHttp `PROPFIND` | Basic or bearer |
| **Google Drive** | Drive REST v3 | OAuth |
| **OneDrive** | Microsoft Graph | OAuth |
| **Dropbox** | Dropbox HTTP API | OAuth |
| **Box** | Box API | OAuth |
| **pCloud** | pCloud API | OAuth |

`MediaStore` is the cheapest of the eight by a distance - Android has already crawled and
tagged the phone's own music, so it needs no crawler, no tag reader and no index. It is
the right one to build first, and it proves the shared shape before any OAuth is written.

### On any of them

- Browse artists, albums, playlists, genres and starred items, and search all of it.
- A **real queue** on **ExoPlayer**: gapless album playback, exact seek, shuffle,
  repeat, and a lock-screen media notification.
- **Continuous play, generated on device** *(Navidrome and Subsonic; see above)*. Radio
  mode and "don't stop the music" are MA *server* features, so this backend used to
  simply stop at the end of the album. It now builds the next batch itself, on a ladder
  that stops at the first rung that answers: tracks like this one, then tracks from
  around this record, then the artist's best-known songs, then the genre, then anything
  at all. It tops up two tracks early so ExoPlayer's gapless transitions survive, keeps a
  rolling history so it doesn't circle back, and falls back to ranking your downloads
  offline.
- **Smooth transitions**, 1-12 seconds, off by default - and deliberately not called
  crossfade, because one ExoPlayer has one output and two tracks cannot overlap through
  it. Suppressed automatically while the queue is a single album, because a record is
  sequenced and fading between its tracks damages it.
- **24-bit reaches this player too**, behind the bit-perfect setting and only when the
  source is actually ≥24-bit.
- **ReplayGain, applied** - off, track or album, defaulting to album. Boosts are capped
  at +3 dB, because a boost multiplies samples already mastered against full scale.
- Original files by default (`format=raw`), so a FLAC stays a FLAC.
- **Download** a track, album or playlist for offline, audio and cover art, with a
  storage cap and a Wi-Fi-only option - or keep whatever is playing, from the chip on the
  player. A **Downloads screen** searches and sorts them, retries what failed, shows the
  format each file actually is, and breaks the space down by album so the thing worth
  deleting is findable. The storage cap never evicts the track you are listening to.
- With the server unreachable the library drops to **Offline** and runs on what is on
  the phone.
- Stars and plays are written back **to the library the track came from** - a Jellyfin
  guid is not something Navidrome can record a play against - including on the "play at
  original quality" path, where Music Assistant is selected but Navidrome serves the
  bytes.

## Light Sync

Two transports, picked in **Settings → Light Sync**, which follows the library backend
automatically unless you pin it by hand.

A **Quick Settings tile** toggles the direct path from the notification shade, which is
where you want it when someone walks into the room.

### Direct to the Hue Bridge

No Home Assistant, no integration, nothing between the music and the room.

Discover a bridge over mDNS - with cloud discovery and a manual IP as fallbacks - press
its link button to pair, choose an entertainment area, and the Lights tab becomes a
direct control surface. Underneath: the decoded PCM is tapped out of the player's own
render chain, run through an FFT with SuperFlux onset detection and a mel filterbank,
and turned into per-light colour by a Kotlin port of syncoV2's effects engine. That goes
to the bridge over DTLS 1.2 on a pre-shared key, at 60 Hz, which is what the
Entertainment API expects.

What the port carries: five intensity rungs from Subtle to Extreme plus an **Auto**
picker that reads the music and moves between them, a tempo PLL and structure tracker
driving the show off the beat grid, 3D room geometry and spatial waves, the Fireworks
effect, per-lamp attack and spectral pop, **stereo pan** so a hit lands on the side of
the room it came from, vocal shimmer, an eye-safety limiter, a timing delay queue so the
lights land with the audio, and album-art and song colour extracted the way syncoV2 does
it - by how much of the cover each colour occupies, not by what would look good as a UI
accent. The track is pre-scanned before it starts, so the show knows the song's shape
from the first bar.

Two of syncoV2's 61 mode parameters remain unread and both are genuinely unreachable:
one belongs to the Movies effect, which is deliberately dropped - this is a music player,
there is no video for a brightness-follows-the-soundtrack mode to accompany - and one
needs a per-track loudness profile only an offline scan produces.

**Scope:** the tap sits in the ExoPlayer chain, so direct mode drives the show from *this
phone's local playback* - Navidrome and downloads. Music playing on a remote MA player,
or streamed to this phone as a Sendspin player, is still the Home Assistant path's job.

### Through Home Assistant

The original path, unchanged. Drives the
[syncoV2](https://github.com/engabd11/syncoV2) `hue_music_sync` integration over the HA
WebSocket API: per-zone enable, intensity ladder, effect, brightness ceiling, timing
offset, live tunables, and all 19 colour schemes previewed with their real gradient
colours. It follows an HA `media_player` entity, which is what lets it reach speakers
this phone is not playing through - and what stops it seeing the local player.

## Requirements

- Android 12+ (API 31)
- A [Music Assistant](https://music-assistant.io) server (2.9+) with the Sendspin player
  provider enabled
- *Optional:* a library of your own as a standalone backend - Navidrome, any Subsonic /
  OpenSubsonic server, or Jellyfin. See [Standalone: your own library](#standalone-your-own-library)
- *Optional:* a Philips Hue Bridge with an entertainment area, for direct Light Sync
- *Optional:* Home Assistant with syncoV2, for Light Sync through HA

## Setup

1. Install the APK from [Releases](https://github.com/engabd11/CAMusic/releases).
2. Pick a discovered MA server or enter its URL. Credentials are encrypted at rest with
   the Android Keystore.
3. The phone appears in Music Assistant under the name in **Settings → CAMusic player**.
4. For a library of your own, add the server under **Settings → Libraries** and tap
   *Browse this library*. For Light Sync, choose a transport under **Settings → Light
   Sync** - pair a bridge, or add a Home Assistant URL and long-lived token.

## Audio

```
Music Assistant path                     Navidrome / offline path
--------------------                     ------------------------
WebSocket binary frame                   HTTP (format=raw) or local file
  [type=4][server_ts_us][payload]                    |
        |                                       ExoPlayer
  FLAC / Opus -> MediaCodec -+                  (gapless, exact seek, ReplayGain,
  PCM         -> passthrough-+-> AudioTrack      radio, smooth transitions,
        |                                        24-bit float output)
  scheduled against the                              |
  Kalman-filtered server clock                  AudioAnalysisTap -> Light Sync
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

- **Gapless on the MA path is Music Assistant's to decide, not this app's.** MA applies
  it per player - disabled, standard or smart, the same three-way choice it offers for
  crossfade - to the stream *before* it reaches the phone. There is no client-side gapless
  to implement, and an earlier attempt to fake one by holding the read-ahead buffer
  through `stream/end` broke the pause button instead: MA sends a byte-identical
  `stream/end` for a track boundary and a pause, and 4 MB of held buffer meant pause kept
  playing for half a minute. **Settings → CAMusic player** now drives MA's own setting, so
  changing it no longer means opening Music Assistant.
- **Gapless does work on the standalone path**, where ExoPlayer owns the whole queue.
- ReplayGain is applied on the standalone path only. MA does its own normalisation
  server-side, and applying it twice would be worse than not applying it.
- **Whether the MA DSP actually reaches the audio is still open.** The app no longer
  hides the server's answer, and MA's per-track DSP state is shown - but the remaining
  question needs a device against a real MA server to settle.
- **Direct Light Sync cannot see MA playback.** See the scope note above.
- **Releases are signed with the local debug key.** There is no release keystore. Updates
  work because that key has been stable since v0.1.0; a CI-signed build would have a
  different signer and Android would refuse to install it over an existing copy.
- Cleartext is allowed to LAN addresses only. A server reached over plain HTTP on a
  public hostname is refused rather than sent credentials in the clear - use HTTPS for
  anything off the local network.

## Roadmap

Sequenced with reasoning and file lists in
[docs/v0.8-plan.md §8](docs/v0.8-plan.md). This is the short version, in roughly the
order it is expected to happen.

### Audiophile core

- **Bit-perfect exclusive-mode output** via the native AAudio I24 path, which is written
  and deliberately not compiled. `flac_decode()` is still a skeleton and the ring buffer
  is byte-level rather than frame-level. The largest single audio item.
- **True overlapping crossfade** on the standalone path - a second ExoPlayer ping-ponged
  with volume ramps, which moves queue ownership and touches ReplayGain, the notification
  and the analysis tap. The shipped smooth transitions are the cheap half.
### Feature completion

- **Warm reconnect.** `client/goodbye` with `reason: "restart"` on backgrounding, so MA
  holds the player slot for ~30 seconds and a quick app switch doesn't drop the phone out
  of the speaker list. Attempted once and reverted because it fired mid-song; it must fire
  only while idle.
- **More libraries.** The `MusicSource` interface and the server list are in, so each
  remaining provider is an adapter rather than a change to the app. The full list, split
  by how much work each actually is, is under
  [Planned](#planned) - and `MediaStore` (the phone's own music) is the one to build
  first, because Android has already done the crawling and tagging the other seven need.

### Light Sync

- Reach **MA playback** as well as local - a second tap on `SendspinAudioEngine`, which is
  what would let direct mode replace the HA path outright rather than sit beside it.

### Platform

- **Android Auto.** media3-session is already a dependency and the `MediaSession` already
  exists; the work is a manifest declaration and a browse tree.
- **A Glance home-screen widget.**
- **A release keystore and CI signing.** See Known limits.

### Housekeeping

- **Room** for the download index, which is a JSON file rewritten in full on every change.
- **Instrumented tests.** The 463 unit tests cover protocol, clock, DSP, parsing, the
  server list and the Light Sync engine; nothing covers the audio path, the service
  lifecycle or the UI.
- **Crash reporting** - ACRA or similar, self-hosted.
- **The files over 700 lines**, and the 87 `runCatching` sites that swallow a failure
  without recording it.

## Architecture

```
Jetpack Compose UI  ·  Material 3  ·  OLED design system (true black, album accent)
  Now Playing · Library · Speakers · Lights · Settings
        |
   ViewModels (per screen; player + library VMs hoisted to the app root)
   state collected lifecycle-aware, so a backgrounded screen stops working
        |
        +-- Sendspin protocol --  SendspinClient · ClockSync · Kalman filter
        |   (this phone as an MA player)      -> SendspinAudioEngine -> AudioTrack
        |
        +-- MA main API --------  MaApiClient (/ws) · MaRepository
        |   (browse, search, transport, queues, grouping, DSP, player config)
        |
        +-- OpenSubsonic -------  SubsonicClient · LocalPlayer (ExoPlayer)
        |                         LocalRadio · DownloadManager
        |
        +-- Hue Bridge ---------  HueBridgeClient · HueDtlsClient · SyncoEngine
        |   (direct Light Sync)   AudioAnalysisTap · TrackScanner · AlbumColours
        |
        +-- Home Assistant -----  HaClient · LightSyncRepository
            (Light Sync via syncoV2)

  SendspinConnectionService (foreground) keeps the process and socket alive
  SendspinService / LocalPlaybackService own the media notifications
```

One detail worth knowing: control JSON and binary audio share a single WebSocket, and
`stream/clear` only means anything if it is still ordered against the audio around it.
Everything the socket produces therefore goes through **one** queue drained by one
consumer, rather than separate flows.

Colour comes from the artwork, twice, for two different jobs. The UI clusters covers in
CIELAB and ranks by vividness against size, because an accent has to stay legible on
black. Light Sync clusters the same cover and ranks purely by how much of it each colour
occupies, because there the weights are dwell time in a room. Sharing one extraction
between them was a bug, not a saving.

## Building

JDK 17 and the Android SDK (compileSdk 36). No NDK needed.

```bash
./gradlew assembleRelease        # app/build/outputs/apk/release/app-release.apk
./gradlew :app:testDebugUnitTest # 463 unit tests across 54 classes
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

- [docs/release-notes/](docs/release-notes/) - what changed in each release, and why
- [docs/v0.8-plan.md](docs/v0.8-plan.md) - the current plan, a table of what shipped
  against it, and the remaining roadmap sequenced
- [docs/direct-hue-plan.md](docs/direct-hue-plan.md) and
  [docs/direct-hue-bridge-gap-analysis.md](docs/direct-hue-bridge-gap-analysis.md) -
  the direct Light Sync port, and what it was measured against
- [docs/v0.5.0-analysis.md](docs/v0.5.0-analysis.md) - the full codebase analysis the
  roadmap grew out of
- [docs/improvement-roadmap.md](docs/improvement-roadmap.md) - the API audit, and a
  Corrections section recording where earlier notes were wrong
- [docs/providers.md](docs/providers.md) - the `MusicSource` adapter recipe, and the
  auth and endpoints every planned provider needs
- [docs/protocol-alignment.md](docs/protocol-alignment.md) - what MA's Sendspin provider
  actually speaks
- [docs/architecture-decision.md](docs/architecture-decision.md)
- [docs/design-brief.md](docs/design-brief.md)
- [CONTRIBUTING.md](CONTRIBUTING.md)

## Credits

The Sendspin client and audio engine are modelled on
MA and Navidrome APIs. The Light Sync effects engine is a port of
[syncoV2](https://github.com/engabd11/syncoV2). Fully compatible with Philips Hue official API and specs.

Built by **Cyborg Automation AU** (cyborgautomation.com.au)

## License

MIT - see [LICENSE](LICENSE)
