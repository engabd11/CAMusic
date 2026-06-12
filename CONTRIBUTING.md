# Contributing to Sendspin for Android

## Getting Started

1. Fork the repository
2. Clone your fork: `git clone https://github.com/YOUR_USERNAME/sendspin-android.git`
3. Open in Android Studio
4. Create a feature branch: `git checkout -b feat/my-feature`

## Project Structure

```
app/src/main/
├── cpp/                    # Native C++ code (Oboe, libFLAC)
│   ├── CMakeLists.txt
│   ├── audio_engine.cpp    # AAudio/Oboe output engine
│   ├── flac_decoder.cpp    # libFLAC stream decoder
│   └── audio_engine.h / flac_decoder.h
├── java/com/cyborgautomation/sendspin/
│   ├── MainActivity.kt
│   ├── audio/              # Audio pipeline
│   │   ├── AudioOutput.kt
│   │   ├── FormatNegotiator.kt
│   │   ├── NativeAudioEngine.kt
│   │   ├── NativeFlacDecoder.kt
│   │   └── PlaybackScheduler.kt
│   ├── discovery/          # mDNS discovery
│   │   ├── MaDiscovery.kt
│   │   └── PlayerIdentity.kt
│   ├── protocol/           # Sendspin WebSocket protocol
│   │   ├── AudioFrame.kt
│   │   ├── ClockSync.kt
│   │   ├── Messages.kt
│   │   └── SendspinClient.kt
│   ├── service/            # Foreground service
│   │   └── SendspinService.kt
│   └── ui/                 # Compose UI
│       ├── App.kt
│       ├── screens/ (NowPlayingScreen, SettingsScreen)
│       ├── viewmodel/ (PlayerViewModel)
│       └── theme/ (Color, Theme, Type)
```

## Code Conventions

- Kotlin: Follow [Google Kotlin Style Guide](https://developer.android.com/kotlin/style-guide)
- C++: Google C++ style (enforced by Android Studio defaults)
- Commit messages: [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, `refactor:`, `docs:`)

## Testing

- Unit tests: `app/src/test/` (JUnit + MockK)
- Instrumented tests: `app/src/androidTest/` (AndroidX Test)
- Run: `./gradlew test` or `./gradlew connectedAndroidTest`

## Pull Requests

1. Ensure your code compiles: `./gradlew assembleDebug`
2. Add tests for new functionality
3. Update documentation if needed
4. Open a PR against the `master` branch

## Questions?

Open an issue or discussion on GitHub.
