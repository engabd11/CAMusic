# Contributing to CAMusic

## Getting Started

1. Fork the repository
2. Clone your fork: `git clone https://github.com/YOUR_USERNAME/CAMusic.git`
3. Open in Android Studio (JDK 17, compileSdk 36)
4. Create a feature branch: `git checkout -b feat/my-feature`

## Project Structure

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
│   ├── jellyfin/                # Jellyfin library adapter
│   │   └── JellyfinClient.kt / JellyfinSource.kt
│   ├── library/                 # MusicSource interface, server list, per-provider adapters
│   │   ├── MusicSource.kt
│   │   ├── MusicSources.kt
│   │   ├── SubsonicSource.kt
│   │   ├── ServerConfig.kt
│   │   └── ScanLibrarySource.kt
│   ├── local/                   # Local files / MediaStore source, Room download DB
│   │   ├── LocalMediaSource.kt
│   │   ├── DownloadMappers.kt
│   │   └── db/
│   ├── ma/                      # Music Assistant API client, repository, library VM
│   │   ├── MaApiClient.kt
│   │   ├── MaRepository.kt
│   │   ├── MaModels.kt / DspModels.kt
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

## Code Conventions

- **Kotlin:** Follow the [Google Kotlin Style Guide](https://developer.android.com/kotlin/style-guide)
- **C++:** Google C++ style (enforced by Android Studio defaults)
- **Commit messages:** [Conventional Commits](https://www.conventionalcommits.org/) — `feat:`, `fix:`, `refactor:`, `docs:`, `release:`
- **PRs:** against the `master` branch

## Testing

- **Unit tests:** `app/src/test/` (JUnit + MockK) — 538 tests across 69 classes
- **Instrumented tests:** `app/src/androidTest/` (AndroidX Test)
- **NDK and CMake are required** — the native Oboe output engine in `app/src/main/cpp/` is part of the build
- **Run:** `./gradlew :app:testMobileDebugUnitTest` or `./gradlew :app:connectedMobileDebugAndroidTest`
- **Flavors:** `:app` builds `mobile` and `tv`. CI runs the unit tests against both — `./gradlew :app:testTvDebugUnitTest` as well, before pushing

## Pull Requests

1. Ensure your code compiles: `./gradlew assembleMobileDebug assembleTvDebug`
2. Add tests for new functionality
3. Update documentation if needed
4. Open a PR against the `master` branch

## Questions?

Open an issue or discussion on GitHub.