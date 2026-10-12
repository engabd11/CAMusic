# Architecture

How playback, the light engine and the Hue bridge fit together. [Back to the README](../README.md).

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
│  Emby · Plex ·│      └────────┬──────┘ │
│  local files  │               │        │
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

Position on the Music Assistant path comes from one `MaPlayhead` (owned by `MaNowPlaying`, read
by the screen, the notification, Android Auto and the car alike), anchored on the server's own
`elapsed_time` and its capture time rather than on a local counter, so the progress bar agrees with the server, and with itself.

## Modules

```
            :app  (UI, playback, Light Sync, Music Assistant, Sendspin, Spotify,
              │    local files, MusicSources: ServerConfig -> provider)
              │
   ┌──────────┼──────────────┬──────────┬──────────┬──────────┬─────────────┐
   │          │              │          │          │          │             │
:providers: :providers:   :providers: :providers: :providers: :providers:  :providers:
 subsonic    jellyfin ◄──── emby       plex        mpd        qobuz, tidal  foobar2000
   │          │              │          │          │          │             │
   └──────────┴──────────────┴────┬─────┴──────────┴──────────┴─────────────┘
                                  │
                                :core  (models, Http, MusicSource API, ServerConfig,
                                        RemotePlayback, LocalTrack, StreamQuality)
```

Dependencies point down only. `:core` depends on no other module; a provider depends on
`:core` (Emby also on Jellyfin, whose client it extends) and never on `:app` or on an
unrelated provider; `:app` depends on all of them and is the only place that chooses a
provider, in `MusicSources`. Files kept their `com.engabd.sendpin.*` packages when they
moved, so an import does not say which module a class lives in.

Why this split first: each provider is a self-contained client for one server's API, and
its parsers are the most heavily unit-tested code in the app. In its own module a provider
compiles, tests and can be changed without touching the rest, and a new library is a new
module (see CONTRIBUTING.md, Adding a library provider).

Still inside `:app`, because they reach back into it: Spotify (it feeds the Light Sync
audio tap), Music Assistant (its view model is the library screen's), the light engine,
Sendspin, USB output and local files. Each needs its dependencies on `SendpinApp` and the
UI turned around before it can move.

## Audio pipeline

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

Library servers ──► ExoPlayer ──► LocalDsp ──► TapRenderersFactory ──► AudioTrack
and local files                                        │
                                                       ├──► AudioAnalysisTap ──► Light Sync
                                                       └──► AirPlayOutputProcessor ──► AirPlay receiver
```

Both paths feed the same analysis tap, which is what lets one light engine serve whichever is
playing. `LocalDsp` sits ahead of the tap so the show reacts to what you actually hear.
