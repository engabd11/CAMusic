# Oboe native output: no audio — investigation handoff

**Status: unresolved.** Three fixes attempted, three misses. This records what is now *measured*
rather than assumed, so the next attempt starts from evidence instead of repeating mine.

`useOboeOutput` is opt-in and off by default. `useExoPlayerForSendspin` **on with Oboe off** is a
working configuration, confirmed on device. Nothing else depends on this path — Phase 2 promotes
the ExoPlayer engine alone and explicitly leaves Oboe opt-in.

## The symptom

Grouped MA playback with `useOboeOutput` on. The player reports playing, the stream is healthy, and
no sound comes out. Reproduced on a Samsung SM-S911B (S23), Android 16.

## What is measured, and therefore not in question

From `OboeAudioSink`'s stall log, on the failing run:

```
configured 48000/2ch/16-bit drift=true
first write: 2440 frames accepted of 2440 offered @ 48000/2ch/16-bit, buffered=2440
native ring has refused 50 consecutive buffers (buffered=5515 frames, written=5515)
  driftEma=999924374118us latency=13472us underrun=0 device=3 disconnected=false
```

| Fact | Consequence |
|---|---|
| `buffered == written`, always | **Not one frame is ever consumed** from the ring. The producer side is fine; the callback is not draining. |
| `latency=13472us` | The DAC timestamp anchor **resolved**. `refreshTimestampAnchor` is working and `dac0` is a real number. |
| `underrun=0`, `disconnected=false`, `device=3` | The AAudio stream is open, started, routed to the speaker, and healthy. Earlier logs confirm `AAUDIO_OK` and `requestStart` returning 0. |
| `drift=true` | Drift correction is on, i.e. `SendspinSyncDataSource` — the player is in an MA group. |
| **`driftEma = 999,924,374,118 µs` ≈ 11.57 days** | `intendedHead` sits ~11.6 days ahead of the DAC. This is absolute-clock magnitude, not a scheduling error. |

## Why that causes silence, exactly

`SendspinOutputEngine::onAudioReady` has a path that consumes nothing. With drift correction on and
the output still muted (which it is at stream start), a positive drift inserts `silenceFront` frames
**without advancing the read position**:

```cpp
const bool silent = (g1 < 0.01f && g0 < 0.01f);
if ((silent || adrift >= SNAP_US) && adrift > STEADY_DEADZONE_US) {
    const int64_t driftFrames = driftUs * sampleRate_ / 1000000LL;
    if (driftUs > 0) {
        silenceFront = static_cast<int>(std::min<int64_t>(numFrames, driftFrames));
```

At a drift of 11.6 days, `driftFrames` exceeds `numFrames` by many orders, so `silenceFront` is the
whole callback, every callback. The engine emits pure silence and never advances `readPos_`. The
ring fills, `write` starts refusing, and the stream is silent for ever. **The engine is behaving
correctly for the input it is given.** The input is wrong.

## The lead: an arithmetic coincidence worth checking first

Measured on the same device, same session:

| Quantity | Value |
|---|---|
| Device `CLOCK_BOOTTIME` (`/proc/uptime` field 1) | 338,679 s ≈ **3.39e11 µs** |
| Music Assistant `server/state` timestamps | ≈ **6.55e11 µs** |
| Observed `driftEma` | ≈ **9.999e11 µs** |

`6.55e11 + 3.39e11 = 9.94e11`, within ~0.5% of the observed drift.

**That is the shape of a domain being *added* where it should be *differenced*** — a server-domain
timestamp composed with a local-domain one instead of converted between them. It is a coincidence
of magnitudes, not a proof, and it is where the next session should look first.

The chain to audit, in order:

1. `ClockSync.serverTimeToLocal` — does it return a local-domain instant, or server + local?
2. `SendspinSyncDataSource.targetLocalUsFor` — `serverTimeToLocal(serverTsUs) + SCHEDULE_HEADROOM_US
   - staticDelayMs * 1000`.
3. `SendspinDataSource.armPresentationAnchor` / `presentationLocalUs` — `anchor + mediaTimeUs`,
   where `mediaTimeUs` is ExoPlayer's *media-relative* time and should be near zero at stream start.
4. `SendspinNativeOutput.write` → `bootToMonotonicUs` → the marker the engine stores.

**A caution about (1):** if `serverTimeToLocal` were simply broken, `SendspinSyncDataSource`'s own
`waitUntilDue` would also be wrong, and grouped playback would misbehave on *every* engine rather
than only under Oboe. It does not. So either the fault is downstream of it, or `waitUntilDue`
tolerates what the marker path does not. Establish which before changing it.

**The decisive next measurement** is one native log line inside `onAudioReady` printing
`intendedHead`, `dac0` and `rawDriftUs` separately, once. Everything above infers their difference;
nothing has yet seen the two halves. That needs a C++ edit and an NDK rebuild, which is why it was
not done in the session that found this.

## What was tried, and why each was wrong

Three fixes shipped to `master`. **All three are genuine defects worth keeping** — each would have
bitten later — but none was this bug, and each was described with more confidence than the evidence
supported at the time.

| Commit | Fix | Why it wasn't the cause |
|---|---|---|
| `116f2c7` | `handleBuffer` returned `true` unconditionally, violating the `AudioSink` contract and corrupting `getCurrentPositionUs` | Real, and it masked the true state — but the ring was refusing because it was full, and it was full because nothing drained it. |
| (clock) | `CLOCK_BOOTTIME` passed where the native engine reads `CLOCK_MONOTONIC`, with no conversion | Real — the domains genuinely differ by suspend time, and `write`'s own doc claimed MONOTONIC while every caller passed BOOTTIME. But the drift after fixing it is ~3× device uptime, so the remaining error is not a suspend-time offset. |
| (diagnostics) | The native counters were never logged | Not a fix. It is what produced every number above, and it should have come first. |

**The process lesson, recorded because it cost the most:** F's own entry in the remaining-work plan
said *"add the two diagnostic lines before attempting the fix"*. That was followed half-way — the
diagnostics shipped alongside the first fix rather than ahead of it — and two further attempts were
made by reading Kotlin on one side of a JNI boundary whose failure was on the other. The counters,
once logged, answered in a single run what three fix attempts had not.

## The other half of F, still open regardless

This path fails **silently**: it reports a playing player and emits nothing. Whatever the cause
turns out to be, `OboeAudioSink` should surface the failure or fall back to the platform sink rather
than presenting a working-looking player. The stall is now *detected* (the refusal log) but not
*acted on*. That is a small, self-contained piece of work and does not depend on root-causing the
drift.

## Recommendation

**Leave `useOboeOutput` opt-in and off.** It is documented as experimental, nothing depends on it,
and the working configuration is well established. Pick this up as its own piece with the native log
line above as step one — not as a patch inside a session doing other work, which is how three
plausible-but-wrong fixes got shipped in an afternoon.

**Critical files:** `audio/OboeAudioSink.kt`, `audio/SendspinNativeOutput.kt`,
`audio/SendspinSyncDataSource.kt`, `audio/SendspinDataSource.kt`, `protocol/ClockSync.kt`,
`cpp/sendspin_output_engine.cpp` (`onAudioReady`, `intendedPresentationUs`,
`dacPresentationUsForNextWrite`, `refreshTimestampAnchor`).
