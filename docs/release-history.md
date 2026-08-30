# Release history

> Moved out of the README, which had grown to two hundred lines of checklist. This is the
> full record; the README keeps a short "recent releases" summary and points here.

## Roadmap

> CAMusic started as a Music Assistant Sendspin client. The idea has grown: **one app that
> replaces the two or three you're using right now** — a player, a speaker controller, and a
> light-sync engine, all local, all free. Here's where it's heading.

### ✅ Shipped

- [x] Music Assistant Sendspin player (FLAC, Opus, PCM)
- [x] Clock-synced grouped playback (Kalman filter, NTP-style four-point exchange)
- [x] MA library browser, search, queue, speaker control, playlists
- [x] Navidrome, Subsonic/OpenSubsonic, Jellyfin, Emby and Plex as standalone libraries
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

**v0.10.5** (PR #89 — fifteen items):

- [x] **Announcements that finish, and that can be heard over a video** — `stream/end` is
      byte-identical for "paused", "track ended" and "announcement finished", and the engine
      discarded the tail for all three. A discriminator tells them apart from a locally-issued
      pause and MA's own player events; announcements now request ducking focus with speech
      attributes rather than fighting for the output
- [x] **Downloads as a first-class library** — artists, albums and tiles in the same switcher as
      every server, replacing a flat list with its own rendering branch
- [x] **Local device files actually work** — six separate defects, any one of which emptied the
      library, including a folder list written as JSON and read as pipe-separated, and a
      reachability test comparing SAF tree URIs against MediaStore URIs
- [x] **A light show for a remote Music Assistant speaker** — scheduled from the track's offline
      analysis, with an honest status line and a speaker-offset control
- [x] **Lights from other apps** — `MediaProjection` capture, with detection of apps that forbid it
- [x] **Album colours for the room** — k-means++ seeding, a most-chromatic-quarter cluster
      representative and a neutral-swatch cap backported from the UI palette, which had them and
      the light-sync extraction did not
- [x] Artist pages paint on the first round-trip again; the wave seek bar's track moves with the
      wave; sliders stop snapping back on release and claim a 44dp touch target; the driving bar
      docks to the edge, its drag and its close button work, and the floating window reads the
      setting that selects it; in-place queue shuffle; the cover turns like a page on an album
      change; a Motion setting; and the stats screen clears the mini player
- [x] **16 KB page alignment** — the one native library this project builds was 4 KB-aligned, so
      Android loaded the app in a compatibility mode and said so on first launch

**v0.10.6** (PR #90):

- [x] **Analyser v3 — a key detector that can actually hear.** `TrackScan.key` is where the Music
      DNA layer takes its entire anchor hue from, and measured against real music the old one
      agreed with itself on a transposed copy of the same track **12.4 %** of the time, against a
      1-in-12 chance floor. Three faults, each found by measuring rather than reading: a chroma
      that could not resolve a semitone below ~186 Hz, an assumption of A440 against a corpus
      sitting 4–7 cents sharp, and third harmonics being heard as tonics. Now **71.1 %** shift
      consistency and **90.9 %** detune stability
- [x] **An analysis harness** (`tools/analysis-harness/`) that runs the real scan path over real
      music and scores it on two measures needing no ground truth — transpose invariance and
      detune stability. Not in CI; the corpus is somebody's music library
- [x] **The light-show layers stop littering** — roughly 240 maps and 2,400 short-lived arrays a
      second removed from the one thread in the app with a hard deadline

**v0.10.7** (PRs #93, #94, #95):

- [x] **Android Auto** — a `CarMediaLibraryService` browse tree and a session facade
      (`CarSessionPlayer`), browsing every configured library server. A track tapped in the car
      always plays on *this phone*, never a speaker in another room
- [x] **First Android TV release**, behind a new `tv` product flavour: a D-pad Now Playing,
      Library, Queue, Light Sync and Settings, compiling the same `app/src/main` business logic
      as the phone. The phone release still ships as the `mobile` APK, matching every prior
      release's package id
- [x] **Phone UI polish** — two-row library carousels, art-directed category tiles, settings
      descriptions behind an info chip, one motion language across the app, and the album cover
      no longer flashing between two tracks of the same album

**v0.10.8** (PR #96):

- [x] **Six settings-gated creative features** — swipe-to-skip, the live audio visualizer, the
      music map timeline, harmonic DJ mode, phone-sensor gesture controls, and Listening DNA stats
- [x] **On-device stem separation** for the Phantom Stage lighting layer
- [x] A seek that could leak into the next track; a transport icon that shifted width; the sleep
      timer chip shifting its neighbours

**v0.10.9** (PRs #100–#109):

- [x] **A native Oboe engine for the Sendspin player**, replacing the broken ExoPlayer path, with
      real drift correction in native code
- [x] **The MA playhead rewritten** on an idempotent, server-anchored model
- [x] **Multi-room sync fixed** — group-state detection, and a shared-timeline bug that could mute
      a whole track once a speaker joined
- [x] **Light Sync steady on the MA player**, which had been erratic since MA playback landed
- [x] **A webOS TV app** for LG televisions, **dynamic GPS-based speed limits**, **Effects mode**,
      a generated cover for playlist-less playlists, a reordered Continue Listening shelf that now
      carries songs, and full Music Assistant 2.10.0 API compliance

**v0.11.0** (PRs #110–#114):

- [x] **Emby and Plex as libraries.** Two more `MusicSource` adapters: Emby over its
      near-identical-to-Jellyfin API, and Plex over its own, signing in through the plex.tv PIN
      flow so no Plex password is ever typed into the app. Both browse, play, download and
      scrobble; every server kind now shows its own brand mark rather than a generic glyph
- [x] **Real recorded sound for the ambience effects** — a bundled looping bed per effect,
      played by a parallel clip player, with the synthesised sound kept for the effects whose
      audio has to line up with their lights. The show's audio moved to a process-scoped holder,
      so swiping the app away no longer leaves a bed looping with nothing able to reach it
- [x] **The master Light Sync switch and an ambience show stopped fighting.** A show that
      self-opened the bridge session now says so, and stopping it puts the switch back only if it
      was the one that turned it on. Two `action: start` PUTs on one entertainment area — the
      state that poisons a session for good — can no longer be raced into
- [x] **A visualiser that reads as a spectrum**: per-bin AGC divided back out, bars running low to
      high rather than mirrored, and asymmetric analyser ballistics with peak-hold caps
- [x] **Lyrics that read as typography**, and **Settings reorganised** into Playback & Behavior
      with the card-in-card nesting removed
- [x] **The MA burst no longer holds the room out of step.** `AudioAnalysisTap` reports how far
      behind the newest sample each frame is, and `FrameDelayQueue` subtracts it — so the backlog a
      `stream/start` burst leaves behind is held and correctly timed rather than chased at four
      times real time, which had been racing the show and painting the idle show over the last two
      seconds of every track
- [x] **Downloads delete off the main thread.** Both halves of a delete are disk — the unlink and
      the Room write — and every caller outside the eviction loop is a tap handler, so
      "Delete all downloads" over a filled cap blocked the UI thread behind thousands of `unlink`
      calls and a full table wipe
- [x] **Plex genre browse filters by tag id**, which is what Plex takes, rather than by name,
      which is what Jellyfin and Subsonic take

### Next up

- [ ] **On-device verification of the newest work.** The playback chain is confirmed on hardware.
      What is not: the driving bar's window behaviour, the MediaProjection consent and
      foreground-service ordering (which has no compile-time signal if it is wrong), the
      announcement drain caps, the remote-speaker show's timing against a real cast group, and
      Android Auto end to end (browse tree, search, and — the one that matters most — that a track
      tapped in the car never starts playing on a speaker in another room). Several of these can
      only be judged by eye, one only in a car, and the speed alert only on a road.
- [ ] **True overlapping crossfade** on the standalone path — a second ExoPlayer ping-ponged with
      volume ramps, moving queue ownership and touching ReplayGain, the notification, and the
      analysis tap. The shipped smooth transitions and beat-matched crossfade are the first two
      halves; this is the third.
- [ ] **Android Auto — built, not yet verified in a car.** A `CarMediaLibraryService` browse tree
      and a session facade (`CarSessionPlayer`, in the same spirit as `SendspinService`'s
      `ShadePlayer`) sit beside the two existing media services rather than replacing either.
      Browses every configured library server; a track tapped in the car always plays on *this
      phone*, never a remote MA speaker, whichever one the Speakers screen has selected — and for a
      Music Assistant track it moves the Speakers selection here too, so the car's own now-playing
      and transport buttons address the player it just started rather than the one in the kitchen.
      Untested beyond compilation — wants a Desktop Head Unit pass and, ideally, a real head unit.
- [ ] **The Oboe silence** — [PR #59](https://github.com/engabd11/CAMusic/pull/59), closed
      unresolved. Blocks nothing; the path is opt-in and off.
- [ ] **How often a room gesture actually fires.** The detector shipped in v0.9.1 and is
      deliberately strict; what is unmeasured is how much real music contains a traversal it should
      find. Every detection is logged whether or not the lights move, so an album and
      `adb logcat -s DirectLightSync` is what answers it.

### Planned

- [ ] **More library providers** — each is an adapter against the `MusicSource` interface, not a
      change to the app. Emby and Plex shipped in v0.11.0; still to come:
  - Audiobookshelf (music libraries alongside audiobooks)
  - Kodi (JSON-RPC library browser)
- [ ] **Network filesystems** — SMB, WebDAV, Google Drive, OneDrive, Dropbox, Box, pCloud. These
      need a crawler, a tag reader and a local index. `MediaStore` proves the shared shape;
      `IndexedFileSource` is the interface they'll plug into.
- [ ] **Bit-perfect exclusive-mode output** — the native AAudio I24 path is written and
      deliberately not compiled. `flac_decode()` is still a skeleton and the ring buffer is
      byte-level rather than frame-level. The largest single audio item on the roadmap.
- [ ] **Wider instrumented coverage** — ~1,000 unit tests cover protocol, clock, DSP, parsing, the
      server list, the Philips Hue Entertainment sync engine, speed alert logic, beat-matched
      crossfade, the creative light-show layers, the announcement drain policy, the downloads index
      and the SAF-to-MediaStore folder mapping; two instrumented test classes pin the two
      regressions that each cost a release. Nothing yet covers the audio path end to end, the
      service lifecycle, or the UI — and nothing can cover the MediaProjection consent sequence
      without a device and a human tapping a dialog.
- [ ] **A hosted crash backend** — crashes are stored locally and, with a token configured, filed
      automatically as a GitHub issue. What is missing is somewhere to send them that isn't a
      repository, and reporting more than the most recent one per launch.

> **On signing:** releases are signed with the local debug key, deliberately. It has been stable
> since v0.1.0, so updates install over each other cleanly. Because that key lives on one machine
> and a CI runner generates a fresh one per run, releases are cut locally — see the header of
> `.github/workflows/release.yml`.

---
