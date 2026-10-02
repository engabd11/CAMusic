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
