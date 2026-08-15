# Direct Hue Bridge Light Sync — Implementation Plan

> **Status: shipped, and the scope below has since widened.** This document
> records the original build. Two things in it are no longer true:
>
> - **Music Assistant playback is no longer excluded.** Routing Sendspin audio
>   through ExoPlayer (see `exoplayer-upgrade-plan.md`) put the analysis tap in
>   MA's render chain too, so the direct path drives both players. The transport
>   is chosen by `SendpinApp.activeLightSyncSource`.
> - **The tap does not deactivate itself when sync is off.** It stays in the
>   processor chain and gates analysis instead, because sink membership is decided
>   once per configuration — see `AudioAnalysisTap`.

## Scope

Direct connection from CAMusic to the Hue Bridge, bypassing Home Assistant.
Originally for the local player only (Navidrome/ExoPlayer), with the MA path
staying on HA → syncoV2 — see the status note above for what changed.

## Architecture

```
Local player (ExoPlayer)
  → AudioProcessor tap (decoded PCM)
    → AudioAnalyzer (FFT + beat detection + melbank)
      → SyncoEngine (effects + modes + palettes → per-channel RGB)
        → HueStreamEncoder (RGB → xy+Brightness, gamut clamp, frame encode)
          → HueDtlsClient (DTLS 1.2 PSK, AES-128-GCM)
            → Hue Bridge (UDP port 2100)
              → Lights
```

## New files

| File | Lines (est.) | Depends on | What |
|------|-------------|------------|------|
| `hue/HueDtlsClient.kt` | ~500 | javax.crypto | DTLS 1.2 PSK client. Port of syncoV2 `dtls.py`. |
| `hue/HueStreamEncoder.kt` | ~250 | — | HueStream v2 frame encoder. Port of `stream.py` encoder. |
| `hue/HueBridgeClient.kt` | ~350 | OkHttp | mDNS discovery, link-button auth, CLIP v2 API. Port of `bridge.py`. |
| `hue/HueModels.kt` | ~100 | kotlinx.serialization | Data models for entertainment configs, channels, lights. |
| `audio/Fft.kt` | ~120 | — | Radix-2 FFT (2048-point, zero-padded). No external library. |
| `audio/AudioAnalyzer.kt` | ~600 | Fft | SuperFlux onset detection, melbank, bands, salience. Port of `analyzer.py`. |
| `audio/AudioAnalysisTap.kt` | ~200 | AudioAnalyzer, media3 | ExoPlayer AudioProcessor. Zero-overhead pass-through when off. |
| `hue/SyncoEngine.kt` | ~1500 | AudioAnalyzer | Effects engine + modes + palettes. Port of `engine.py` + `modes.py` + `palette.py`. |
| `hue/DirectLightSync.kt` | ~300 | All above | Orchestrator: bridge → DTLS → stream → engine → tap lifecycle. |
| `res/raw/hue_root_certs.pem` | ~50 | — | Signify root CA certificates (2 PEM blocks from the API docs). |

## Modified files

| File | Change |
|------|--------|
| `data/AppSettings.kt` | Add: `hueBridgeIp`, `hueAppKey` (encrypted), `hueClientKey` (encrypted), `hueAppId`, `hueEntertainmentConfigId`, `lightSyncMode` ("ha" \| "direct") |
| `audio/LocalPlayer.kt` | Add `AudioAnalysisTap` to `DefaultRenderersFactory.setAudioProcessors()` |
| `ui/screens/SettingsScreen.kt` | New "Light Sync" category in Settings: mode selector (HA / Direct), bridge discovery (mDNS scan), link button flow, entertainment area picker. This is where the transport choice lives — the Light Sync tab itself just shows controls. |
| `ui/screens/LightSyncScreen.kt` | No mode selector here. Shows a small indicator of which transport is active (HA or Direct) for clarity, but the switching is in Settings. |
| `app/build.gradle.kts` | No new dependencies needed (javax.crypto, OkHttp, media3 all present) |

## Key decisions

1. **DTLS**: Port syncoV2's pure-Python DTLS client. Uses `javax.crypto.Cipher` (AES-128-GCM) on API 31+ (our minSdk). No external DTLS library. ~500 lines.

2. **HTTPS**: Hue Bridge uses HTTPS with Signify private root CA. Bundle the two PEM certs in `res/raw/hue_root_certs.pem`. Custom `SSLContext` trusting those CAs. For self-signed (older bridges): trust-on-first-use, store cert in DataStore.

3. **FFT**: Write radix-2 FFT in Kotlin. 2048-point (2×1024 zero-padded, matching syncoV2). ~120 lines. No external library.

4. **Audio tap**: ExoPlayer `AudioProcessor`. `isActive()` returns false when direct sync is off → ExoPlayer bypasses it entirely. When on: copies PCM to analyzer, passes audio through unchanged. Thread-safe: analyzer runs on a background thread, tap on the audio thread.

5. **Synco engine**: Port `effects/engine.py` + `effects/modes.py` + `color/palette.py`. Reuse `LightSyncRepository.PALETTES` values (already defined). 5 intensity modes with their `ModeParams`. 19 colour schemes.

6. **MVP vs full**: Port the full engine including modes and palettes (user said "intensities and colours will be the same"). Defer: song structure detection (`structure.py`), tempo PLL (`tempo.py`), stereo pan — the show works without them.

## Implementation order

1. `res/raw/hue_root_certs.pem` — no deps
2. `hue/HueModels.kt` — no deps
3. `hue/HueStreamEncoder.kt` — no deps (pure math)
4. `hue/HueDtlsClient.kt` — no deps (javax.crypto only)
5. `hue/HueBridgeClient.kt` — depends on HueModels
6. `audio/Fft.kt` — no deps
7. `audio/AudioAnalyzer.kt`` — depends on Fft
8. `audio/AudioAnalysisTap.kt` — depends on AudioAnalyzer + media3
9. `hue/SyncoEngine.kt` — depends on AudioAnalyzer
10. `hue/DirectLightSync.kt` — depends on all above
11. `data/AppSettings.kt` — additions
12. `audio/LocalPlayer.kt` — wire audio tap
13. `ui/screens/SettingsScreen.kt` — bridge setup UI
14. `ui/screens/LightSyncScreen.kt` — mode indicator

## What this does NOT change

- The existing HA-based Light Sync path (Mode A) is untouched.
- `LightSyncRepository`, `HaClient`, `LightSyncViewModel` work exactly as before.
- The MA player path is untouched.
- The only change to `LocalPlayer` is adding the audio processor to the renderers factory.