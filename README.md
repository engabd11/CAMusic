# Sendspin for Android

**Bit-perfect 24-bit hi-res sendspin player for Music Assistant.**

Stream FLAC 96/24 or PCM 96/24 from your Music Assistant server to your Android device with zero quality loss, using AAudio's exclusive I24 mode to bypass Android's 16-bit audio mixer.

## Features

- **Bit-perfect 24-bit audio** — AAudio `I24` Exclusive mode, identical to `AAUDIO_FORMAT_PCM_I24_PACKED`
- **Hi-res support** — FLAC 96/24, PCM 96/24, FLAC 48/24, PCM 48/24, with automatic format negotiation
- **FLAC decoding** — Native libFLAC decoder via JNI, decoding to packed 3-byte i24 LE (zero-conversion passthrough)
- **Zero-copy PCM path** — PCM frames are byte-identical to AAudio I24, no format conversion needed
- **Synchronized playback** — NTP clock sync with Music Assistant for multi-room audio
- **mDNS discovery** — Automatically discovers Music Assistant servers on your LAN
- **Foreground service** — Playback continues when app is backgrounded
- **Audio focus** — Properly handles phone calls and other audio interruptions
- **Material 3 design** — Clean, modern UI with dynamic color support (Android 12+)

## Requirements

- Android 12+ (API 31)
- Music Assistant server on your network with sendspin support
- ARM64 device recommended (x86_64 for emulator testing)

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    Jetpack Compose UI                     │
│              (NowPlaying + Settings screens)              │
├─────────────────────────────────────────────────────────┤
│                    PlayerViewModel                        │
│            (state management, lifecycle)                  │
├──────────────────────┬──────────────────────────────────┤
│   Sendspin Protocol   │        Audio Pipeline             │
│   (Kotlin)            │        (C++/JNI)                  │
│                       │                                  │
│  • Messages.kt        │  • audio_engine.cpp (Oboe/AAudio) │
│  • SendspinClient.kt  │  • flac_decoder.cpp (libFLAC)    │
│  • ClockSync.kt       │  • Ring buffer (packed i24 LE)   │
│  • AudioFrame.kt      │                                  │
├──────────────────────┴──────────────────────────────────┤
│                  mDNS Discovery (NSD)                     │
│              (_mass._tcp.local. services)                 │
└─────────────────────────────────────────────────────────┘
```

### Audio Pipeline

```
WebSocket Binary Frame
    │
    ├─ PCM codec:  raw packed 3-byte i24 LE
    │              → memcpy → ring buffer → AAudio I24 DMA
    │              (ZERO conversion)
    │
    └─ FLAC codec: raw FLAC frame
                   → libFLAC decode → pack to 3-byte i24 LE
                   → ring buffer → AAudio I24 DMA
```

## Building

1. Install Android Studio Hedgehog (2024.1.1+) or later
2. Install NDK 26+ (for C++/CMake support)
3. Clone and open in Android Studio:
   ```bash
   git clone https://github.com/engabd11/sendspin-android.git
   ```
4. Sync Gradle and run on device or emulator

### libFLAC (Optional)

FLAC decoding is optional for v0.1. To enable:

1. Cross-compile libFLAC for Android:
   ```bash
   git clone https://github.com/xiph/flac.git
   cd flac && git checkout 1.5.0
   cmake -B build-android-arm64 \
     -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
     -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=26 \
     -DBUILD_CXXLIBS=OFF -DBUILD_PROGRAMS=OFF -DBUILD_EXAMPLES=OFF \
     -DBUILD_TESTING=OFF -DWITH_OGG=OFF -DCMAKE_BUILD_TYPE=Release
   cmake --build build-android-arm64
   ```
2. Copy `build-android-arm64/src/libFLAC/libFLAC.a` to `app/src/main/jniLibs/arm64-v8a/`
3. Uncomment the FLAC lines in `app/src/main/cpp/CMakeLists.txt`

## License

MIT License — see [LICENSE](LICENSE)

## Credits

Built by **Cyborg Automation AU** (cyborgautomation.com.au)
