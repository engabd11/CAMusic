# Architecture Decision Record — Sendspin Protocol Implementation

## Context

The sendspin-android app needs to implement the sendspin protocol (WebSocket + JSON + binary audio frames + NTP clock sync) to communicate with Music Assistant as a player@v1 device.

Two approaches were evaluated:

1. **Rust via JNI** — Wrap `sendspin-rs` (the reference Rust implementation) and call it through JNI
2. **Pure Kotlin** — Re-implement the protocol subset needed for player@v1 in Kotlin

## Decision

**Pure Kotlin re-implementation** is chosen for v1.

## Rationale

- **Simplicity:** No Rust cross-compilation (NDK targets for arm64-v8a, armeabi-v7a, x86_64). No JNI callback complexity. Standard Android toolchain.
- **Protocol scope:** player@v1 requires ~8 message types with straightforward JSON shapes. NTP clock sync is a simple algorithm. Binary audio frames are raw bytes with a header. This is ~500 lines of Kotlin, not a protocol engine.
- **Debugging:** Android Studio debugging, stack traces, and logging work directly on Kotlin code. Rust/JNI stack traces are opaque.
- **Future optionality:** We can always swap in `sendspin-rs` later via JNI if needed. The JNI bridge for audio (Oboe/libFLAC) is already set up.
- **Cost:** ~2 weeks to re-implement vs ~1 week to integrate Rust, but the Rust path introduces ongoing maintenance burden (cross-compilation updates, Rust-Android toolchain drift).

## Consequences

- We maintain our own protocol implementation in `app/.../protocol/`
- Must keep in sync with sendspin protocol changes (but sendspin is stable for player@v1)
- Clock sync algorithm must be verified against sendspin-rs reference

## Date: 2026-06-12
## Status: Accepted
