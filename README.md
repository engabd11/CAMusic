<p align="center">
  <img src="docs/app-icon.png" alt="CAMusic" width="120" />
</p>

<h1 align="center">CAMusic</h1>

<p align="center"><strong>One app. Your music. Your Phillips hue lights. Your music assistant speakers.</strong></p>

<p align="center">
  Play your library, sync your hue lights, group your music assistant speakers, and control it all from one place <br>
  all local and in one place.
</p>

<p align="center">
  <a href="https://github.com/engabd11/CAMusic/releases"><img src="https://img.shields.io/github/v/tag/engabd11/CAMusic?label=release&sort=semver" alt="Release" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/engabd11/CAMusic" alt="License" /></a>
  <img src="https://img.shields.io/badge/Android-12%2B-green" alt="Android 12+" />
  <img src="https://img.shields.io/badge/platform-Android-3DDC84" alt="Platform" />
</p>

---

## Why CAMusic?

Most music apps make you choose. Want to play your own collection? That's one app. Stream to
speakers around the house? Another one. Sync your lights to the music? A third, probably with a
subscription. Group speakers? Yet another, if it's even available. This app evolved from the idea behind syncoV2, but having music playing in one place and lights sync in another is definitely annoying at times. If you prefer a dashboard card on Home Assistant have the option to do so, with syncoV2 only.

**CAMusic puts it all in one app** — a music player, a multi-speaker controller, and a light-sync
engine, with no feature locked behind a paywall:

| Feature | CAMusic | Typical player | Light-sync app |
|---|:---:|:---:|:---:|
| Play your own music library | ✅ | ✅ | ❌ |
| Multiple library servers | ✅ | ✅ (some) | ❌ |
| Group speakers | ✅ | ❌ | ❌ |
| Philips Hue Entertainment sync | ✅ | ❌ | ✅ (paid) |
| Offline playback | ✅ | ✅ (some) | ❌ |
| Parametric EQ per speaker | ✅ | ❌ | ❌ |
| 24-bit / hi-res output | ✅ | ❌ | ❌ |
|| Driving controls over the map | ✅ | ❌ | ❌ |
|| Speed limit alert with tolerance | ✅ | ❌ | ❌ |
|| Auto-pause for phone calls | ✅ | ❌ | ❌ |
|| Listening statistics | ✅ | ❌ | ❌ |
|| USB DAC detection | ✅ | ❌ | ❌ |
|| Settings export / import (encrypted) | ✅ | ❌ | ❌ |
|| Home-screen widget | ✅ | ✅ (some) | ❌ |
|| One app, no subscription | ✅ | — | — |

---

<p align="center">
  <img src="docs/screenshots/now-playing.jpg" width="180" />
  <img src="docs/screenshots/library.jpg" width="180" />
  <img src="docs/screenshots/speakers.jpg" width="180" />
  <img src="docs/screenshots/light-sync.jpg" width="180" />
  <img src="docs/screenshots/codec-detail.jpg" width="180" />
  <img src="docs/screenshots/settings.jpg" width="180" />
</p>

## What it does

### Your music, your servers

Add as many libraries as you like — Navidrome, Jellyfin, any Subsonic-compatible server, or files
already on your phone — and switch between them freely. Each server is a card with its own
credentials, status and stream quality; one tap sets it active.

| Server | Auth | What it brings |
|---|---|---|
| **Navidrome** | Username + password | The reference — OpenSubsonic extensions give synced lyrics, ReplayGain, and exact per-track format metadata |
| **Subsonic / OpenSubsonic** | Username + password | Gonic, Airsonic, Astista, Ampache's Subsonic API — anything speaking the protocol |
| **Jellyfin** | Username + password | Original file streaming with per-track codec, bitrate, depth and channels |
| **This device** | Runtime permission | Music already on the phone or SD card — works entirely offline |
| **Music Assistant** | mDNS or URL | Not just a library — a full player ecosystem (see below) |

Capabilities are probed, not assumed. A plain Subsonic server is never offered a lyrics pane it
can't fill; the app asks what the server supports and lights up only what it declared.

### Music Assistant — play anywhere

When a Music Assistant server is in the picture, CAMusic becomes more than a player:

- **Browse the full library** — artists, albums, tracks, playlists, radio stations and podcasts,
  plus recently played, recently added, continue listening, for you and favourites shelves, and
  search-as-you-type with debounced results.
- **Play to any MA speaker** — the phone, a grouped set, or any other player on the network.
  Now Playing reflects and controls whichever speaker you select.
- **Queue management** — view, jump, reorder, remove, clear, save as playlist, or transfer the
  queue and playhead to another speaker mid-track.
- **Album and artist screens** — about section, related albums, top tracks, similar artists.
- **Version picker** — every copy of a track across every provider. The 16/44 stream, the 24/96
  purchase and the CD rip are a choice, not whatever the row came from.
- **Cross-device resume** — start on the phone, finish at the desk, same second of the same track.
- **Per-player parametric EQ** — bands with frequency, Q, gain and filter type, tone controls,
  preamp and presets, driven through MA's own DSP pipeline.
- **Server-side gapless and crossfade** — the app reads MA's config entries and renders whatever
  it finds, so a new MA build needs no update here.
- **Speaker grouping** around a leader, with per-player and group volume.
- **Home Assistant TTS announcements** arrive like any other MA player.

### As a Sendspin player on Music Assistant

CAMusic registers with Music Assistant over the Sendspin WebSocket protocol and stays available in
the background through a foreground service, so playback and announcements start without opening
the app. It stays in the background even when the app is closed for instant and reliable TTS, unless the battery saving setting in the app is enabled.

The clock-sync engine uses an NTP-style four-point exchange feeding a two-dimensional Kalman filter
that tracks offset *and* drift — grouped playback stays in step. The player reports `state: "error"`
and mutes until the filter converges, rather than joining a group with an offset still seconds wide.

FLAC, Opus and PCM are decoded through **ExoPlayer**, with a custom `DataSource` that holds each
frame until the Kalman-filtered server clock says it is due, and written to the platform
`AudioTrack`. 24-bit is opt-in: with bit-perfect on, the decoder is asked for float output and the
track is built from what it actually reports, not from the depth the server claimed — because
two-byte samples in a three-byte frame is noise, not degradation.

ExoPlayer is **the** engine here, not one of two. It was an experimental toggle until v0.9, on the
reasoning that an unvalidated path should not silently become everyone's player — and hardware
settled it the other way: the hand-built `MediaCodec` → `AudioTrack` engine it replaced produces no
audio at all on the test device, and this one does. A switch whose off position is silence is not a
safety measure, so it is gone.

> **On Oboe:** there is a native output engine in `app/src/main/cpp/`, reachable from
> **Settings → Music Assistant server → Experimental**, and it is **off by default and currently
> produces no sound**. The AAudio stream opens and starts, and then nothing is ever consumed from
> it. Three genuine defects have been found and fixed on the way to that one without being it. The
> evidence, the mechanism and the one measurement that would settle it are in
> [PR #59](https://github.com/engabd11/CAMusic/pull/59). It is opt-in, it blocks nothing, and
> the README used to claim audio flowed through it — it does not.

Routing the stream through ExoPlayer is also what put MA playback inside the light-sync analysis
tap, so the direct Hue path drives both players rather than only local files.

Per-player sync offset is applied to frame scheduling locally and also written back to MA's config.
The player's volume **is** the phone's media volume: the on-screen slider, the hardware rocker and
Music Assistant's own level are one number, not three.

### Philips Hue Entertainment Sync - Direct or through home asssitant

CAMusic drives Philips Hue lights in time with the music using the Hue Entertainment API
(DTLS 1.2, 60 Hz). Two transports, picked in **Settings → Light Sync**
it follows the library backend automatically unless you pin it by hand. A **Quick Settings tile**
toggles the direct path from the notification shade.

#### Direct to the Hue Bridge

No Home Assistant, no integration, nothing between the music and the room. CAMusic connects
directly to a Philips Hue Bridge — discover a bridge over mDNS (with cloud discovery and manual
IP as fallbacks), press its link button to pair, choose an entertainment area, and the Lights
tab becomes a direct control surface.

Underneath: the decoded PCM is tapped out of the player's own render chain, run through an FFT with
SuperFlux onset detection and a mel filterbank, and turned into per-light colour by a Kotlin port of
[syncoV2](https://github.com/engabd11/syncoV2)'s effects engine. That goes to the bridge over
DTLS 1.2 on a pre-shared key, at 60 Hz — exactly what the Entertainment API expects.

What the port carries:

- Five intensity rungs (Subtle → Extreme) plus **Auto** — reads the music and moves between them
- A tempo PLL and structure tracker driving the show off the beat grid
- 3D room geometry and spatial waves
- The Fireworks effect
- Per-lamp attack and spectral pop
- **Stereo pan** — a hit lands on the side of the room it came from
- **Room gestures** — a sound that *travels* across the stereo field travels across the lamps
- Vocal shimmer
- An eye-safety limiter
- A timing delay queue so the lights land with the audio
- Album-art and song colour extracted the way syncoV2 does it — by how much of the cover each
  colour occupies, not by what would look good as a UI accent
- Pre-scanned tracks — the show knows the song's shape from the first bar

The room is rendered as a **field**, not as a set of independent lamps. Each lamp's continuous
drive is blended with its neighbours' through a Gaussian kernel over the entertainment area's real
3-D positions, so a glow spreads across bulbs instead of stopping at one. The kernel is
row-stochastic — it redistributes the room's energy and can neither brighten nor dim it — and being
purely spatial it has no temporal memory, so it cannot shift the timing. Colour drifts along a
tilted axis using height and depth as well as left-to-right, and the height-to-frequency mapping is
blended rather than snapped to one of five bands.

A **sustain layer** carries what the beat-driven layers cannot. Onset detection deliberately gates
out narrowband material, and the per-bin attack measure is taken against a baseline a held note
settles into — both correct for percussion, and between them they left a long vocal lighting almost
nothing. A separate envelope reads sustained, pitched, mid-heavy material (inverted onset width,
chroma stability, mid presence), damped against live transients so a beat and a bloom never claim
the same moment, and blooms the room slowly rather than pulsing it.

**Room gestures** (off by default, under Lights → Advanced) are the layer for sound that *moves*.
Stereo pan has always placed a hit on the side of the room it came from; this reads pan over time
instead of instantaneously, so a pad sweeping across the stereo field sweeps across the lamps, and a
swell that rises with nothing hitting under it lifts the room as it climbs.

How it is drawn depends on the shape your lamps are actually in, classified once per session from
the entertainment area's real positions:

| Your room | What a sweep looks like |
|---|---|
| Lamps in a **line** | A soft front travelling along it |
| Lamps **around you** | The same front, going round — and round the *front* of the room, never behind you |
| Lamps **scattered** | A virtual source moving through the room, brightest at whatever it passes |
| Two lamps **on a shelf** | Nothing. A swell still blooms them together, but a traversal is refused |

That last row is the important one. Two lamps cannot express "across the room", and flickering
between them reads as a fault rather than as motion — Philips make the same call for their own
`AreaEffect`: better to show nothing than to show it in the wrong place.

Most music has neither a real stereo traversal nor a linear swell in it, and the detector is built
around that rather than in spite of it. It subtracts the broadband pan centroid, so a mix that
simply leans left cannot trip it; it requires movement to be *monotone* and to not come back, so a
vibrato is not a sweep; it requires a neighbouring frequency bin to agree, so one bin drifting is
not a source moving; and it caps itself at four gestures a minute. A track with nothing to find
looks exactly as it did before, which is the correct answer rather than a missed opportunity.
Speed comes from the measured length of the sound's own movement, so a slow pad and a fast riser do
not look alike, and the whole layer is additive and capped at a third of full scale — a wash, not a
flash.

> **Scope:** the tap sits in the ExoPlayer render chain, and since v0.8.8 Music Assistant playback
> flows through ExoPlayer too — so direct mode drives the show for **both** this phone's local
> playback (Navidrome, Jellyfin, downloads, local files) and MA playback to this phone. Music
> playing on a *remote* MA speaker still needs the Home Assistant path, since this phone never
> decodes that audio.

#### Through Home Assistant (Philips Hue via syncoV2)

The original path. Drives the
[syncoV2](https://github.com/engabd11/syncoV2) `hue_music_sync` integration over the HA WebSocket
API, which controls Philips Hue lights through the Entertainment API: per-zone enable, intensity ladder, effect, brightness ceiling, timing offset, live tunables,
and all 19 colour schemes previewed with their real gradient colours. It follows an HA
`media_player` entity, which is what lets it reach speakers this phone is not playing through.

#### Creative light-show layers

Four additive layers that use CAMusic's existing analysis infrastructure — pre-scanned track
data, real-time structure detection, instrument-to-position mapping, and phone motion — to do
things no Hue app can. Each is a toggle, off by default, layered on top of the existing music
sync rather than replacing it:

- **Music DNA** — every track gets a deterministic visual fingerprint: tempo → wave speed,
  key → base hue, intensity profile → brightness arc, section structure → colour shifts. Same
  song always produces the same show.
- **Emotional arc** — colour temperature follows the song's structural journey: verses are cool
  blue, builds warm toward orange, drops go hot and saturated, breakdowns go cold, outros fade
  warm.
- **Phantom stage** — instruments mapped to fixed physical positions in the room. Bass left
  corner, vocals centre, guitar right, drums spread by height. When a solo comes in, that part
  of the room brightens — you can see who is playing by which part of the room is lit.
- **Phone as conductor** — the phone's accelerometer and gyroscope become a conductor's baton.
  Tilt shifts colour through the spatial field, a sharp flick flashes all lights, slow circular
  motion rotates hue around the room. Phone flat auto-disables after 5 seconds.

All layers implement a shared `LightShowLayer` interface and run after `SyncoEngine.render()`
and before `FieldSafety`. Direct-to-bridge only (the HA path has no analysis tap access).

See [docs/creative-light-shows.md](docs/creative-light-shows.md) for the full design.

### Offline Playback and Library — download and go

Download a track, album or playlist for offline use — audio and cover art — with a storage cap and
a Wi-Fi-only option. A dedicated **Downloads screen** searches, sorts, retries failures, shows the
format each file actually is, and breaks storage down by album so the thing worth deleting is
findable. The storage cap never evicts the track you're listening to.

With the server unreachable the library drops to **Offline** and runs on what's on the phone. Stars
and play counts write back to the library the track came from when you're back online.

### Audiophile-grade playback

|| Feature | Music Assistant path | Standalone path |
|---|:---:|:---:|
| Hi-res (88.2 / 96 kHz) | ✅ | ✅ |
| 24-bit float output | ✅ (bit-perfect mode) | ✅ (bit-perfect mode) |
| Gapless | ✅ (server-side) | ✅ (ExoPlayer) |
| Beat-matched crossfade | ✅ (server-side) | ✅ (when scans available) |
| ReplayGain | ✅ (MA normalisation) | ✅ (track / album) |
| Original file format | ✅ (format=raw) | ✅ (original stream) |
| Continuous play / radio | ✅ (server-side) | ✅ (on-device) |
| Smooth transitions | — | ✅ (1–12 s, auto-suppressed for albums) |
| USB DAC detection | ✅ | ✅ |
| EQ | ✅ (server DSP) | Planned |

When a USB DAC is plugged in, CAMusic detects it through an `AudioDeviceCallback`
and posts a low-priority notification showing what the DAC can do — sample rates,
bit depths — and points to **Settings → Audio** to pin the output to it. It does
not change routing on its own: if you already chose a Bluetooth headset, plugging
in a DAC doesn't yank the audio away. It just tells you the option is there.

### Driving — control the music without leaving the map

A lot of cars have no Android Auto. The phone sits in a cradle running Google Maps, and changing a
track means leaving the map, finding the app, hitting a small target and going back — while
driving. This is the one feature in the app that is a *safety* feature rather than a polish or
correctness one, and it is built to that standard: **three controls, very large, over whatever is
on screen.**

Two window mechanisms, chosen in **Settings → Playback & audio → Driving**:

| | Floating window (default) | Full-width bar |
|---|---|---|
| Permission | **None** | Draw over other apps |
| Targets | System-sized | 76dp, against the platform's 48dp minimum |
| Position | System decides | Either edge, draggable |
| Starts from | The app must be open first | Anywhere |

The default costs no permission at all, which for something you set up once in a car park is worth
more than the larger buttons. The full-width bar is there when it isn't.

**What turns it on is the car's Bluetooth**, not Google Maps being in front. That framing is the
expensive one: reading the foreground app needs either `PACKAGE_USAGE_STATS` or an
`AccessibilityService` — a Settings-screen grant, or the most policy-sensitive permission on the
platform — and the requirement is *control music without leaving the map*, not *know that Maps is
running*. Nominate your car from the phone's paired devices and connecting to it is the trigger. A
**Quick Settings tile** and a Settings switch cover a car with no pairing, an aux cable, or simply
wanting it on.

It never appears with nothing playing, whatever the trigger says. Three controls, a title that
truncates rather than scrolls — movement in peripheral vision while driving is the worst possible
place for a marquee — and nothing that invites reading.

**Optional driving enhancements** (all off by default, under **Settings → Playback & audio → Driving**):

- **Speed limit alert** — a gentle audible beep when GPS speed exceeds a limit you set, with a
  configurable tolerance percentage so minor overages don't nag. Uses a 5-consecutive-reading
  rule to filter GPS noise, and repeats at most every 30 seconds, not every reading. The speed
  is never shown on screen — reading a number while driving is worse than hearing a tone.
- **Auto-pause for phone calls** — pauses playback when a call starts ringing and does not
  auto-resume when it ends (a notification offers to resume instead). Surprising auto-resume
  after a conversation is worse than a tap.
- **Speed-adaptive volume** — gradually increases volume at higher speeds to compensate for road
  noise, with smooth 1-second ramping so the change is never a jump.

### Home-screen widget

What's playing, with previous, play/pause and next, without unlocking or opening anything. Built
with Glance, and driven through the same routing as every other surface — so it addresses the
speaker you're actually listening to, not always the phone.

### Playback details

- **Lyrics** — LRC-timed where the provider has them. The sung line sits centred, scrolls smoothly,
  scales into place, with a 200 ms lead and a trim in Settings for a server whose timings are off.
- **Quality badge** — `FLAC • 96/24 • 3 Mb/s`, channels, file size, and what Music Assistant did to
  the level. Normalisation is reported, not guessed at.
- **Similar** — acoustically similar tracks, or a natural-language search over sonic embeddings
  ("late night drive, warm synths").
- **Playlists** — create, delete, and add tracks on both backends.
- **Swipe-to-queue** — swipe a track row right to add it to the queue, left to play it next. Both
  actions snap back after firing, with haptic feedback at the trigger point.
- **Listening statistics** — a "Your Listening" screen showing the last 7 days: total listening
  time, most played track, top artists, format breakdown, and server breakdown. Uses the same
  completion threshold as the scrobbler (half the track or four minutes), so a skip doesn't inflate
  the stats.
- **Settings export / import** — every server config, credential, and preference exported as a
  password-encrypted portable blob. Import re-encrypts credentials under the new device's Keystore,
  so the file's password-derived encryption never reaches disk.
- **Favourites, preview, playback speed, sleep timer.**

---

## Requirements

- **Android 12+** (API 31)
- **Music Assistant** 2.9+ (optional — for the MA player, speaker control, and HA Light Sync path)
- A self-hosted music library — **Navidrome**, any Subsonic/OpenSubsonic server, or **Jellyfin**
  (optional — the app works with local files alone)
- **Philips Hue Bridge** with an entertainment area (optional — for Hue Entertainment light sync)
- **Home Assistant** + syncoV2 (optional — for Hue light sync through HA)
- A car stereo paired over **Bluetooth** (optional — the trigger for driving mode; a Quick Settings
  tile covers a car without one)

Everything is optional except Android 12+. Start with one server or just local files and add more
as you go.

Two permissions are asked for only if you use the feature that needs them: **Bluetooth** (to notice
your car connecting) and **draw over other apps** (only for driving mode's full-width bar — the
default floating window needs neither).

## Setup

1. Install the APK from [Releases](https://github.com/engabd11/CAMusic/releases).
2. The **onboarding wizard** asks where your music lives: Music Assistant, Navidrome, Jellyfin,
   or local files on the device. Credentials are encrypted at rest with the Android Keystore.
3. Optional steps in the same wizard: set up Philips Hue light sync (direct to bridge and/or through
   Home Assistant), register this phone as a Music Assistant player, and grant the two permissions
   that decide whether playback survives the screen going off — notifications, and unrestricted
   battery.
4. Everything can be changed later under **Settings → Libraries** and **Settings → Light Sync**.
   The Music Assistant player's own settings — name, stream format, gapless, announcements, and the
   live status readout — live on that server's card, so the server, its library and the player are
   configured in one place.
5. Optional, and worth two minutes if you drive: **Settings → Playback & audio → Driving**. Turn it
   on, pick your car from the phone's paired devices, and the transport appears over the map
   whenever you connect to it and something is playing.

---

## Architecture

```
Jetpack Compose UI  ·  Material 3  ·  OLED design system (true black, album accent)
  Now Playing · Library · Speakers · Lights · Settings
        |
   ViewModels (per screen; player + library VMs hoisted to the app root)
   state collected lifecycle-aware, so a backgrounded screen stops working
        |
        +-- Sendspin protocol --  SendspinClient · ClockSync · Kalman filter
        |   (this phone as an MA player)      -> SendspinExoEngine -> AudioTrack
        |
        +-- MA main API --------  MaApiClient (/ws) · MaRepository
        |   (browse, search, transport, queues, grouping, DSP, player config)
        |
        +-- OpenSubsonic -------  SubsonicClient · LocalPlayer (ExoPlayer)
        |                         LocalRadio · DownloadManager
        |
        +-- Jellyfin -----------  JellyfinClient · JellyfinSource
        |
        +-- Hue Bridge ---------  HueBridgeClient · HueDtlsClient · SyncoEngine
        |   (Philips Hue Entertainment      AudioAnalysisTap · TrackScanner · AlbumColours
        |    direct to bridge)
        |
        +-- Home Assistant -----  HaClient · LightSyncRepository
            (Philips Hue via syncoV2)

  SendspinConnectionService (foreground) keeps the process and socket alive
  SendspinService / LocalPlaybackService own the media notifications
```

One detail worth knowing: control JSON and binary audio share a single WebSocket, and
`stream/clear` only means something if it is still ordered against the audio around it. Everything
the socket produces therefore goes through **one** queue drained by one consumer, rather than
separate flows.

Colour comes from the artwork, twice, for two different jobs. The UI clusters covers in CIELAB
and ranks by vividness against size, because an accent has to stay legible on black. Light Sync
clusters the same cover and ranks purely by how much of it each colour occupies, because there the
weights are dwell time in a room. Sharing one extraction between them was a bug, not a saving.

### Audio pipeline

```
Music Assistant path                     Navidrome / offline path
--------------------                     ------------------------
WebSocket binary frame                   HTTP (static/original) or local file
  [type=4][server_ts_us][payload]                    |
        |                                            |
  SendspinDataSource                                 |
  (blocks until the frame is due,                    |
   per the Kalman-filtered clock)                    |
        |                                            |
     ExoPlayer  -------------------------------  ExoPlayer
        |                                        (gapless, exact seek, ReplayGain,
   AudioTrack                                     radio, smooth transitions,
   (OboeAudioSink is opt-in                       24-bit float output)
    and currently silent)                             |
        |                                            |
        +--------- AudioAnalysisTap ------------------+
                          |
                   Philips Hue Entertainment
```

Both paths run through ExoPlayer, which is what lets one analysis tap serve both. Before that the
MA path was a hand-built `MediaCodec` → `AudioTrack` pipeline the tap could not see, and direct
light sync was local-playback-only as a result.

Formats are advertised, not requested: MA may only send something the client listed, so the list
*is* the setting. 48 and 44.1 kHz are always offered; 88.2/96 kHz with hi-res on; 176.4/192 kHz
only with bit-perfect on **and** only after probing that the device will open a track at that rate.

The native output engine in `app/src/main/cpp/` is compiled and callable — it is what
`OboeAudioSink` drives — but it is **opt-in, off, and does not currently produce sound**; see the
note above and [PR #59](https://github.com/engabd11/CAMusic/pull/59). What ships today is the
platform `AudioTrack`. True bit-perfect *exclusive-mode* output that bypasses the Android mixer is a
later phase again.

---

## Roadmap

> CAMusic started as a Music Assistant Sendspin client. The idea has grown: **one app that
> replaces the two or three you're using right now** — a player, a speaker controller, and a
> light-sync engine, all local, all free. Here's where it's heading.

### ✅ Shipped

- [x] Music Assistant Sendspin player (FLAC, Opus, PCM)
- [x] Clock-synced grouped playback (Kalman filter, NTP-style four-point exchange)
- [x] MA library browser, search, queue, speaker control, playlists
- [x] Navidrome, Subsonic/OpenSubsonic, and Jellyfin as standalone libraries
- [x] Local files on device (MediaStore source)
- [x] Multiple library servers — add, switch, manage independently
- [x] Offline downloads with storage cap and Wi-Fi-only option
- [x] Room-backed download index (migrated from legacy JSON on first boot)
- [x] Continuous play / radio on the standalone path (on-device, with fallback ladder)
- [x] Smooth transitions on the standalone path (1–12 s, auto-suppressed for albums)
- [x] 24-bit output on both paths (bit-perfect mode)
- [x] ReplayGain on the standalone path (track / album, boost capped at +3 dB)
- [x] Direct Philips Hue Bridge light sync (no HA needed — DTLS 1.2, 60 Hz)
- [x] Home Assistant light sync via syncoV2 (Philips Hue Entertainment API)
- [x] Auto intensity, tempo tracking, stereo pan, vocal shimmer, Fireworks effect
- [x] Track pre-scanning (the show knows the song's shape from the first bar)
- [x] Album-art colour extraction (occupancy-weighted, not UI-accent guessing)
- [x] Per-player parametric EQ through MA's server-side DSP
- [x] Speaker grouping with per-player and group volume
- [x] LRC-timed lyrics with smooth scroll and timing offset
- [x] Quality badge and detail card (`FLAC • 96/24 • 3 Mb/s`)
- [x] Version picker — every copy of a track across every provider
- [x] Cross-device resume
- [x] Onboarding wizard (first-launch: pick server, optional Light Sync, optional MA player,
      permissions with a reason)
- [x] Adaptive grid layout for tablets and foldables
- [x] Direct Philips Hue light sync from Music Assistant playback (v0.8.8 — MA audio now flows
      through ExoPlayer, so the analysis tap sees it)
- [x] Sendspin player on ExoPlayer (v0.8.8; the Oboe output behind it is opt-in and still silent —
      see Next up)
- [x] Light sync as a spatial field — neighbouring lamps share a drive, colour drifts in three
      dimensions, height blends between bands (v0.8.8)
- [x] Sustain layer — held vocals bloom the room instead of lighting nothing (v0.8.8)
- [x] Hardware volume keys follow whichever player is actually making the sound (v0.8.8)
- [x] Radio stations and podcasts in the Music Assistant library (v0.8.8)
- [x] Warm reconnect — `client/goodbye` with `reason: "restart"`, so a quick app switch doesn't
      drop the phone from the speaker list

**v0.9:**

- [x] **Music Assistant playback confirmed on hardware** — and five bugs found in the same session
      and fixed: audio focus deafness, a pause that played 20 s on, two connections racing over one
      `client_id`, direct light sync not noticing MA, and the idle light show lost for MA
- [x] **Driving mode** — a slim always-reachable transport over the map, with Picture-in-Picture as
      the permission-free default and a full-width overlay behind it, triggered by the car's
      Bluetooth
- [x] **Glance home-screen widget**
- [x] ExoPlayer promoted from experimental to the only Music Assistant engine
- [x] The now-playing cover flies between the mini bar and the full player
- [x] Album colour accuracy — accents keep the cover's own lightness instead of being pinned to one
      perceptual value, and white can no longer outvote a colour it is a minority of
- [x] Wide colour gamut on displays that have one
- [x] One authority on which player owns the audio output, and internal audio-focus arbitration so
      the app's two players stop evicting each other through `AudioManager`
- [x] Instrumented tests — the MediaSession id collision and player identity surviving a rename
- [x] "Keep the music going" on Jellyfin, via its own Instant Mix
- [x] Favourites from the player on Navidrome and Jellyfin
- [x] Light Sync starts when you switch it on, rather than when something happens to play
- [x] **Room gestures** — a sound that travels across the stereo field travels across the lamps, and
      a swell with no beat under it lifts the room as it rises. The room is classified by shape
      (line / ring / field / cluster) and a cluster is *refused* rather than faked. Off by default,
      confirmed on a Galaxy S23 (v0.9.1)
- [x] Player status moved onto its own server's page; the empty top-level player section removed
- [x] The three largest files split by responsibility (1,457 → 596 and 2,121 → 1,703)

**v0.10:**

- [x] **Dead code removal** — `SendspinAudioEngine` (1,035 lines, unselected since v0.8.8) removed
- [x] **Jellyfin scrobble fix** — position now tracked from the player, not wall clock, so pauses
      don't drift the scrobbled position
- [x] **Beat-matched crossfade** — transitions align to the beat grid of both tracks when pre-scan
      data is available, falling back to fixed-duration smooth transitions otherwise
- [x] **USB DAC detection** — `AudioDeviceCallback` notices a USB DAC connect, posts a notification
      with its capabilities, and points to Settings to pin the output
- [x] **Swipe-to-queue** — swipe right on a track row to add to queue, left to play next, with
      haptic feedback and snap-back
- [x] **Listening statistics** — a "Your Listening" screen with 7-day stats: total listening time,
      most played track, top artists, format and server breakdown, backed by a Room `play_history`
      table
- [x] **Settings export / import** — password-encrypted portable JSON blob with all servers,
      credentials, and preferences; re-encrypts credentials under the new device's Keystore on import
- [x] **Speed limit alert** — optional GPS-speed beep with configurable tolerance percentage,
      5-consecutive-reading noise filter, and 30-second repeat interval
- [x] **Auto-pause on phone call** — pauses on `CALL_STATE_RINGING`, does not auto-resume (offers
      a notification instead)
- [x] **Speed-adaptive volume** — gradually increases volume at higher speeds with smooth ramping
- [x] **Creative light-show layers** — four additive Hue layers using CAMusic's analysis
      infrastructure: Music DNA (deterministic visual fingerprint per track), Emotional Arc
      (colour temperature follows song structure), Phantom Stage (instruments mapped to physical
      positions), Phone as Conductor (motion-driven lighting). PR #71

### Next up

- [ ] **On-device verification of v0.9 and v0.10.** The playback chain is confirmed; the driving bar,
      the widget, the shared-element flight, the palette rework, the file split, the stats screen,
      swipe-to-queue, USB DAC detection, and the driving enhancements (speed alert, auto-pause,
      adaptive volume) are not. Several can only be judged by eye, one only in a car, and the speed
      alert only on a road.
- [ ] **True overlapping crossfade** on the standalone path — a second ExoPlayer ping-ponged with
      volume ramps, moving queue ownership and touching ReplayGain, the notification, and the
      analysis tap. The shipped smooth transitions and beat-matched crossfade are the first two
      halves; this is the third.
- [ ] **Android Auto** — media3-session is already a dependency and the `MediaSession` exists.
      Both media services are plain `Service`s though, so the work is a `MediaLibraryService` and a
      browse tree, not just a manifest line. It overlaps driving mode in purpose but not in reach:
      Auto only helps cars that have it, which is exactly the case driving mode exists to cover.
- [ ] **The Oboe silence** — [PR #59](https://github.com/engabd11/CAMusic/pull/59). Blocks
      nothing; the path is opt-in and off.
- [ ] **Music Assistant gapless** — MA treats gapless as crossfade, so there is no server switch to
      ask for: joining two streams without a seam is client-side work here.
- [ ] **Room gestures** shipped in v0.9.1 (see the shipped list above). The spatial swell detector
      and rendering are built; the remaining work is device verification of detection frequency
      against real music.

### Planned

- [ ] **More library providers** — each is an adapter against the `MusicSource` interface, not a
      change to the app:
  - Emby (near-identical API to Jellyfin)
  - Plex (plex.tv PIN flow — no server password ever typed in the app)
  - Audiobookshelf (music libraries alongside audiobooks)
  - Kodi (JSON-RPC library browser)
- [ ] **Network filesystems** — SMB, WebDAV, Google Drive, OneDrive, Dropbox, Box, pCloud. These
      need a crawler, a tag reader and a local index. `MediaStore` proves the shared shape;
      `IndexedFileSource` is the interface they'll plug into.
- [ ] **Bit-perfect exclusive-mode output** — the native AAudio I24 path is written and
      deliberately not compiled. `flac_decode()` is still a skeleton and the ring buffer is
      byte-level rather than frame-level. The largest single audio item on the roadmap.
- [ ] **Wider instrumented coverage** — 727 unit tests cover protocol, clock, DSP, parsing, the
      server list, the Philips Hue Entertainment sync engine, speed alert logic, beat-matched
      crossfade, and the creative light-show layers, and two instrumented tests now pin
      the two regressions that each cost a release. Nothing yet covers the audio path end to end,
      the service lifecycle, or the UI.
- [ ] **A hosted crash backend** — crashes are stored locally and, with a token configured, filed
      automatically as a GitHub issue. What is missing is somewhere to send them that isn't a
      repository, and reporting more than the most recent one per launch.

> **On signing:** releases are signed with the local debug key, deliberately. It has been stable
> since v0.1.0, so updates install over each other cleanly. Because that key lives on one machine
> and a CI runner generates a fresh one per run, releases are cut locally — see the header of
> `.github/workflows/release.yml`.

---

## Known limitations

Written down because the alternative is discovering them by ear:

- **The playback chain is confirmed on hardware; most of what came after it is not.** A release
  build ran on a Galaxy S23 and Music Assistant playback produced audio — which cleared the whole
  of the stabilisation work in one run, and turned up five further bugs in the same session, all
  fixed. What has *not* been on a device is everything shipped since: the driving bar, the widget,
  the shared-element flight, the palette rework and the file split. Two of those can only be judged
  by eye and one only in a car. This is the honest gap between "the tests pass" and "it works".
- **Driving mode's Picture-in-Picture path has not been tried against Google Maps in navigation
  mode.** Android 12+ lets an app call `setHideOverlayWindows(true)` to suppress non-system
  overlays over itself. Whether Maps does is assumed *not*. If it does, the full-width overlay
  becomes the default instead — the code for that is already there; it is a one-line change to
  which mechanism leads.
- **The native Oboe output path is silent.** Off by default, opt-in, and clearly labelled as such
  in Settings. See [PR #59](https://github.com/engabd11/CAMusic/pull/59).
- **Direct Philips Hue light sync cannot follow a remote speaker.** It taps this phone's own render
  chain, which now covers both local playback *and* Music Assistant playing to this phone. Music
  playing on a different speaker never reaches this phone's decoder, so that case still needs the
  Home Assistant path.
- **Cleartext is allowed to LAN addresses only.** A server reached over plain HTTP on a public
  hostname is refused rather than sent credentials in the clear — use HTTPS for anything off
  the local network.
- **Audiobook chapters are unverified.** Podcasts and radio browse against confirmed Music
  Assistant commands; the audiobook chapter listing follows the same naming pattern but has not
  been checked against a live server, so an audiobook may open empty.
- **One streaming app per Hue entertainment area.** If the Hue app or an HDMI Sync Box already
  holds the area, CAMusic refuses rather than taking it — the Entertainment API allows only one.
- **Room gestures do not reach Extreme or Fireworks.** Both are separate renderers that return
  before the layer the gesture joins. Correct rather than missing — Extreme is a different show, not
  a louder one — but it means turning the setting on and selecting Extreme looks like the feature is
  broken.
- **"Bottom to top" will not render in most rooms.** The Hue app places lamps on a two-dimensional
  floor plan and its own API example reports `z: 0.0` for every channel, so most entertainment areas
  have no height for a rising gesture to travel through. A swell in a flat room lifts every lamp
  together instead, which is the honest answer — sweeping "upward" across lamps that are all at the
  same height would pick an arbitrary order and read as a flicker.
- **How often a real traversal occurs is still unmeasured.** The detector is deliberately strict and
  every detection is logged whether or not the lights move, so playing an album with
  `adb logcat -s DirectLightSync` is what answers it. If the honest answer turns out to be "rare
  outside electronic and film music", that is a good outcome for a feature whose hard requirement is
  not firing when there is nothing to find.

---

## Building

JDK 17 and the Android SDK (compileSdk 36). The **NDK and CMake are required** — the native Oboe
output engine in `app/src/main/cpp/` is part of the build even though the path it feeds is off by
default. (The README said "no NDK needed" for several releases after that stopped being true.)

```bash
./gradlew assembleRelease        # app/build/outputs/apk/release/app-release.apk
./gradlew :app:testDebugUnitTest # 727 unit tests
./gradlew :app:lintDebug
```

CI runs exactly `:app:testDebugUnitTest`, `:app:lintDebug` and `assembleDebug` — run that set
before pushing rather than a superset. `assembleRelease` looks stricter and is not:
its `lintVital` only reports issues marked fatal, so it will happily pass a `lintDebug` error.

```bash
./gradlew :app:connectedDebugAndroidTest   # instrumented tests; needs a device
```

Judge anything about how the app *feels* on a release build. A debug build carries Compose
composition tracing, skips R8, and runs `debuggable`, which suppresses most of ART's optimisation
— scroll performance measured on one is measuring the build, not the app.

```bash
./gradlew :app:installRelease -PsideBySide   # installs alongside, own empty data
./gradlew :app:generateBaselineProfile       # needs a device; WIPES app data
./gradlew :app:compileReleaseKotlin -PcomposeMetrics   # skippability reports
```

Releases are cut locally. See the header of
`.github/workflows/release.yml`.

## Documentation

- [Release notes](docs/release-notes/) — what changed in each release, and why
- [v0.10 plan](docs/v0.10-plan.md) — the current plan: correctness fixes, new features, and
  driving enhancements
- [Creative light shows](docs/creative-light-shows.md) — the four additive Hue layers: music DNA,
  emotional arc, phantom stage, and phone-as-conductor
- [v0.9 plan](docs/v0.9-plan.md) and [what's left of it](docs/v0.9-remaining.md) — the previous
  plan, and the working queue that re-verified it against the source. The second is worth reading
  for the parts that turned out **wrong**: it opens with five load-bearing premises of the plan
  that did not survive being checked, and later records three of its own that did not survive
  being built.
- Oboe investigation ([PR #59](https://github.com/engabd11/CAMusic/pull/59), not yet merged) — why
  the native output path is silent, what has been ruled out, and the one measurement that would
  settle it
- [Spatial swell design](docs/spatial-swell-plan.md) and
  [implementation](docs/spatial-swell-implementation.md) — lights that move when the sound does.
  The first is the case for the feature; the second is how it was built, and both are worth reading
  for what they got **wrong**: the implementation doc opens with five claims of the design doc that
  reading the code disproved, and closes with four more that only survived until the tests ran —
  including a circle fit that was quietly shrinking every fitted radius, and two detector
  conditions that turned out to be missing entirely
- [v0.8 plan](docs/v0.8-plan.md) — the previous plan, and what shipped against it
- [Direct Hue plan](docs/direct-hue-plan.md) and
  [gap analysis](docs/direct-hue-bridge-gap-analysis.md) — the direct Light Sync port, and what
  it was measured against
- [v0.5.0 analysis](docs/v0.5.0-analysis.md) — the full codebase analysis the roadmap grew out of
- [Improvement roadmap](docs/improvement-roadmap.md) — the API audit, and a Corrections section
  recording where earlier notes were wrong
- [Providers](docs/providers.md) — the `MusicSource` adapter recipe, and the auth and endpoints
  every planned provider needs
- [Protocol alignment](docs/protocol-alignment.md) — what MA's Sendspin provider actually speaks
- [Architecture decision](docs/architecture-decision.md)
- [Design brief](docs/design-brief.md)
- [Track prescan](docs/track-prescan.md)

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). PRs against `master`, conventional commits, Android Studio
for builds. The project uses Kotlin + Jetpack Compose + media3 + ExoPlayer.

**Note on signing:** Releases are signed with the Android debug keystore so they can be
installed without a dedicated release key. This means updating from one release to the next
requires the same signer — installing a GitHub release over a locally-built debug APK, or
vice versa, will show "package conflicts with an existing package". Uninstall the old build
first, or use the `-PsideBySide` Gradle flag to install a test copy with its own data directory.

## Credits

The Sendspin client and audio engine are modelled on MA and Navidrome APIs. The Philips Hue
Entertainment sync effects engine is a port of [syncoV2](https://github.com/engabd11/syncoV2).
Fully compatible with the Philips Hue Entertainment API and specs.

Built by **Cyborg Automation AU** — [cyborgautomation.com.au](https://cyborgautomation.com.au)

## License

MIT — see [LICENSE](LICENSE).
