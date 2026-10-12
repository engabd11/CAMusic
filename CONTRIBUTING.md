# Contributing to CAMusic

## Getting Started

1. Fork the repository
2. Clone your fork: `git clone https://github.com/YOUR_USERNAME/CAMusic.git`
3. Open in Android Studio (JDK 17, compileSdk 37)
4. Create a feature branch: `git checkout -b feat/my-feature`

## Project Structure

The build is split into Gradle modules. Files keep their `com.engabd.sendpin.*` packages
whichever module they live in, so a class's import never says which module it is in.

```
core/                    # What everything builds on, and depends on no other module:
                         #   shared models (MaItem, MaSearchResults...), Http, the
                         #   MusicSource API, ServerConfig/ServerKind, remote playback
                         #   contracts (RemotePlayback, LocalTrack), StreamQuality
providers/
├── subsonic/            # Navidrome and Subsonic-compatible: SubsonicClient + SubsonicSource
├── jellyfin/            # JellyfinClient + JellyfinSource
├── emby/                # EmbyClient + EmbySource (on :providers:jellyfin)
├── plex/                # PlexClient, PlexAuth + PlexSource
├── mpd/                 # MpdClient, MpdRemote, MpdArt + MpdSource
├── qobuz/  tidal/       # Streaming accounts (experimental)
└── foobar2000/          # Beefweb client + FoobarSource (experimental)
app/                     # Everything else: UI, playback, Light Sync, Music Assistant,
                         #   Sendspin, Spotify, local files, and MusicSources, which
                         #   turns a ServerConfig into the right provider
baselineprofile/         # Generates the startup baseline profile (run by hand)
```

Inside `:app`:

```
app/src/main/
├── cpp/                          # Native C++ Oboe output engine (compiled; opt-in, off by default)
│   ├── CMakeLists.txt
│   ├── sendspin_output_engine.cpp
│   └── sendspin_output_engine.h
├── java/com/engabd/sendpin/
│   ├── audio/                    # Audio pipeline (ExoPlayer, Sendspin, analysis, Light Sync tap)
│   │   ├── AudioAnalysisTap.kt
│   │   ├── AudioOutputs.kt
│   │   ├── DeviceCapabilities.kt
│   │   ├── FormatNegotiator.kt
│   │   ├── LocalPlayer.kt
│   │   ├── LocalRadio.kt
│   │   ├── ReplayGain.kt
│   │   ├── SendspinAudioEngine.kt
│   │   ├── StreamQuality.kt
│   │   └── TrackScanner.kt / TrackScan.kt / TrackScanRepository.kt
│   ├── data/                     # Settings, crypto, network policies
│   │   ├── AppSettings.kt
│   │   ├── Crypto.kt
│   │   └── LanOnlyCleartext.kt
│   ├── discovery/                # mDNS discovery for MA and Hue Bridge
│   │   ├── MaDiscovery.kt
│   │   └── PlayerIdentity.kt
│   ├── download/                 # Download manager, offline playback
│   │   └── DownloadManager.kt
│   ├── ha/                       # Home Assistant client (Light Sync via syncoV2)
│   │   ├── HaClient.kt
│   │   └── LightSyncRepository.kt
│   ├── hue/                     # Direct Hue Bridge (DTLS, effects engine, colour extraction)
│   │   ├── HueBridgeClient.kt
│   │   ├── HueDtlsClient.kt
│   │   ├── SyncoEngine.kt
│   │   ├── DirectLightSync.kt
│   │   ├── AlbumColours.kt
│   │   ├── SongPalette.kt
│   │   └── AutoIntensityPicker.kt / SpatialWaves.kt / FireworksEffect.kt / ...
│   ├── library/                 # MusicSources (the provider registry), search, playlists
│   │   ├── MusicSources.kt
│   │   └── ScanLibrarySource.kt
│   ├── local/                   # Local files / MediaStore source, Room download DB
│   │   ├── LocalMediaSource.kt
│   │   ├── DownloadMappers.kt
│   │   └── db/
│   ├── ma/                      # Music Assistant API client, repository, library VM
│   │   ├── MaApiClient.kt
│   │   ├── MaRepository.kt
│   │   ├── DspModels.kt          # (MaModels.kt is in :core)
│   │   └── LibraryViewModel.kt
│   ├── protocol/                # Sendspin WebSocket protocol (messages, clock, formats)
│   │   ├── SendspinClient.kt
│   │   ├── ClockSync.kt / ClockKalmanFilter.kt / MonotonicClock.kt
│   │   ├── Messages.kt / Inbound.kt
│   │   └── AudioFormatSpec.kt
│   ├── service/                 # Foreground service, media sessions, notifications
│   └── ui/                      # Jetpack Compose UI (Material 3, OLED theme)
│       ├── App.kt
│       ├── screens/
│       │   ├── NowPlayingScreen.kt
│       │   ├── LibraryScreen.kt
│       │   ├── SpeakersScreen.kt
│       │   ├── LightSyncScreen.kt
│       │   ├── SettingsScreen.kt
│       │   ├── OnboardingWizard.kt
│       │   └── settings/LibrariesSettings.kt
│       ├── viewmodel/
│       └── theme/
```

## Adding a library provider

A new kind of library is a new module; nothing in `:app` has to know its internals.

1. Add the kind to `ServerKind` in `core/.../library/ServerConfig.kt` (label, blurb, address
   hint, auth style), so the add-a-server screens can offer it.
2. Create `providers/<name>/build.gradle.kts`. Copy an existing provider's: it is the
   library plugin, a namespace and `api(project(":core"))`. Shared settings come from the
   root `build.gradle.kts`.
3. Put the client and a `MusicSource` implementation in it, in `com.engabd.sendpin.<name>`
   and `com.engabd.sendpin.library`. Override only the capabilities the server has; the
   interface's defaults say "not supported" for the rest.
4. Add `include(":providers:<name>")` to `settings.gradle.kts` and
   `implementation(project(":providers:<name>"))` to `app/build.gradle.kts`.
5. Return it from `MusicSources` in `:app` for its `ServerKind`.
6. Put its unit tests in `providers/<name>/src/test/`. They can reach the module's
   `internal` parsers; tests in `:app` cannot.

If the provider needs something that lives in `:app`, move that thing to `:core` (when
nothing in it depends back on `:app`) rather than making a provider depend on `:app`.

## Code Conventions

- **Kotlin:** Follow the [Google Kotlin Style Guide](https://developer.android.com/kotlin/style-guide)
- **C++:** Google C++ style (enforced by Android Studio defaults)
- **Commit messages:** [Conventional Commits](https://www.conventionalcommits.org/) — `feat:`, `fix:`, `refactor:`, `docs:`, `release:`
- **PRs:** against the `master` branch

## Testing

- **Unit tests:** in each module's `src/test/` (JUnit, kotlin-test, coroutines-test; no mocking library), about 2,100 in all. A test lives with the code it tests, because only that module can see its `internal` members
- **Instrumented tests:** `app/src/androidTest/` (AndroidX Test)
- **NDK and CMake are required** — the native Oboe output engine in `app/src/main/cpp/` is part of the build
- **Run:** `./gradlew :app:testMobileDebugUnitTest testDebugUnitTest` (the second runs every library module's tests), or `./gradlew :app:connectedMobileDebugAndroidTest`
- **Flavors:** `:app` builds `mobile` and `tv`. CI runs the unit tests against both — `./gradlew :app:testTvDebugUnitTest` as well, before pushing

## Pull Requests

1. Ensure your code compiles: `./gradlew assembleMobileDebug assembleTvDebug`
2. Add tests for new functionality
3. Update documentation if needed
4. Open a PR against the `master` branch

## Questions?

Open an issue or discussion on GitHub.