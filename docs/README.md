# CAMusic documentation

New here? Start with the [main README](../README.md): what CAMusic is for, what it works with and how to get started.

## Using CAMusic

- [getting-started.md](getting-started.md): install, first run, requirements and backup
- [libraries.md](libraries.md): every library, how each one behaves and what works across all of them
- [streaming-services.md](streaming-services.md): Spotify, Qobuz and Tidal accounts (experimental)
- [sound.md](sound.md): equaliser, output modes, USB bit-perfect, ReplayGain, crossfade, DJ Radio and lyrics
- [light-sync.md](light-sync.md): the Hue light show, ambience effects and photosensitivity
- [multi-room.md](multi-room.md): Sendspin on Music Assistant, and AirPlay output
- [listening.md](listening.md): the Set Builder and listening stats
- [platforms.md](platforms.md): Android Auto, Android Automotive, Android TV, LG webOS and Driving mode
- [good-to-know.md](good-to-know.md): behaviour that is deliberate

## How it works

- [architecture.md](architecture.md): playback, the light engine and the Hue bridge
- [protocol-alignment.md](protocol-alignment.md): the Sendspin protocol as Music Assistant actually speaks it
- [architecture-decision.md](architecture-decision.md): why the Sendspin protocol is written in Kotlin rather than wrapped from Rust
- [creative-light-shows.md](creative-light-shows.md): the four creative light-show layers
- [spatial-swell-implementation.md](spatial-swell-implementation.md): room-aware spatial effects
- [track-prescan.md](track-prescan.md): offline scanning, for light sync on a remote speaker
- [ma-playhead-rewrite-plan.md](ma-playhead-rewrite-plan.md): why the playhead is anchored on the server's clock
- [direct-hue-plan.md](direct-hue-plan.md): the original plan for streaming straight to the Hue bridge
- [providers.md](providers.md): adding a music backend
- [../webos/README.md](../webos/README.md): the webOS TV app, its JS Service and its build

## Building and releasing

- [building.md](building.md): build from source
- [signing.md](signing.md): how releases are signed
- [release-history.md](release-history.md): every shipped feature, by release
- [attributions.md](attributions.md): third-party material shipped in the app, and its licences

## Plans and audits

Design records written while the features were built. Each one says what was planned at the time, and the README and the pages above describe the current state.

- [plan/](plan/): implementation plans for AirPlay and Chromecast, focus features, creative features, direct streaming providers, Rhythm Lights, server discovery and the USB bit-perfect driver
- [improvement-roadmap.md](improvement-roadmap.md), [roadmap.md](roadmap.md) and the v0.5 to v0.10 plans ([v0.5.0-analysis.md](v0.5.0-analysis.md), [v0.8-plan.md](v0.8-plan.md), [v0.9-plan.md](v0.9-plan.md), [v0.9-remaining.md](v0.9-remaining.md), [v0.10-plan.md](v0.10-plan.md), [v0.10.6.md](v0.10.6.md))
- [exoplayer-upgrade-plan.md](exoplayer-upgrade-plan.md), [spatial-swell-plan.md](spatial-swell-plan.md), [direct-hue-bridge-gap-analysis.md](direct-hue-bridge-gap-analysis.md) and [design-brief.md](design-brief.md)
- [performance-audit-v0.12.0.md](performance-audit-v0.12.0.md) and [performance-audit-v0.12.2.md](performance-audit-v0.12.2.md)
