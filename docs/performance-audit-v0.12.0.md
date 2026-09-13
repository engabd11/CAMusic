# CAMusic v0.12.0 — Runtime Performance Audit

**Author:** Kilo (AI analysis)
**Date:** September 06, 2026
**Scope:** Live runtime audit of v0.12.0 (commit `4d03f7b`) on an Android emulator: background execution and battery cost, memory/leak analysis, rendering and composition churn, and an ANR investigation. Both backends exercised — Music Assistant (Sendspin/Oboe path) and Navidrome (ExoPlayer path). Every code-level root cause below was verified against the source; all line numbers are current at `4d03f7b`. No source changes were made — this document is the deliverable, with suggested fixes in §6.

**Amended after the P0 fixes landed (#165).** Implementing F1 and F2 proved two things in
§6 wrong, and both are corrected in place: §6.1's draw-only gate does not stop the churn
(the read is in composition — and the composition-level gate has to key off the
amplitude, not `playing`, or the wave jumps sideways on every pause), and §6.2's "leave
the full player scrolling" carve-out was the larger half of F2. F1's 907-frame measurement also carries an attribution caveat now —
see the note under F1 in §5. The findings themselves stand; the fix advice and the split
between F1 and F2 are what changed.

---

## 1. Executive Summary

| # | Finding | Severity | Fix effort |
|---|---------|----------|------------|
| F1 | `WaveSeekBar` redraws at 60 fps even when playback is paused | **P0** | Trivial (~5 lines) |
| F2 | Mini-player title marquee keeps the *whole UI* redrawing during playback on every screen | **P0** | Small (~10 lines) |
| F3 | Navidrome path buffers the entire track at start → 92.3% CPU burst | P1 | Medium |
| F4 | `AppSettings` pref-flow fan-out (2,400 live flow chains on the heap) | P2 | Medium |
| F5 | 5s MA group poll churns ~129 `MaPlayer` / 88 `MaQueue` instances, parsed on main | P2 | Small |
| F6 | Memory: **no lifecycle leaks** (verified via HPROF) | ✅ OK | — |
| F7 | Intermittent main-thread ANR on heavy transition (captured once, not reproducible in 5+ attempts) | P3 | Investigation |
| F8 | Keep-alive background connection is honest, gated, and cheap | ✅ OK | — |

The two P0s are the same class of bug: **animation that keeps ticking when nothing is watching**. Together they burn double-digit percent CPU on main + RenderThread for pixels the user is not looking at — pure battery waste during every listening session. Both fixes are small and carry no visual regression (F1's static draw is bit-identical at amplitude 0; F2 only pauses scrolling for a paused track).

---

## 2. Environment & Methodology

### 2.1 Setup
- Device: `emulator-5554` (`sdk_gphone16k_x86_64`), Android 17, 16 KB pages, `ro.build.type=user` (no adb root).
- App: debug build of v0.12.0 (`versionCode 64`), installed **over** v0.11.8 with user data intact.
- Backends: Music Assistant at `192.168.0.48:8095`, Navidrome at `192.168.0.210:4533`.

### 2.2 Tools that worked
- **Perfetto** with textual configs (requires `--txt` on this build) + `trace_processor_shell` for SQL analysis.
- **`am dumpheap`** — 61 MB ART HPROF captured and parsed with a custom parser (Android HPROF 1.0.3 dialect, 8-byte roots).
- **Custom JDWP probe** — the app is debuggable, so JDWP attach works even on a user build. Used for live thread-contention probes (`ThreadReference.ownedMonitors` / `contendedMonitor`).
- **`dumpsys media_session` / `gfxinfo` / `dropbox`** — playback state, frame stats, ANR trace.
- **uiautomator + screencap** for UI-driven scenarios.

### 2.3 Tools that did not work (documented for future sessions)
- `am profile` produces 0-byte traces on this image; `simpleperf` is permission-blocked on a user build; `adb root` unavailable. Perfetto replaced profiling; JDWP replaced stack sampling.

### 2.4 UI-automation lessons (so the next audit doesn't rediscover them)
- Always `screencap` **and** `uiautomator dump` before tapping: screenshots render at ~886×1999 for a 1080×2424 device (~×1.21 scale error when eyeballing), while the XML dump gives true device-space bounds.
- Custom `ToggleRow` switches only register taps on their **text area**, not the switch graphic; the a11y `checked` attribute is unreliable on these custom Views (reports `false` while ON).
- The volume control mirrors the system media stream; synthetic slider drags don't move it, but `adb shell cmd media_session volume --stream 3 --set 100` does.

---

## 3. Measured Baselines

| Scenario | CPU | Details |
|----------|-----|---------|
| Idle, backgrounded, keep-alive ON | **0.0%** | Sendspin wake lock held (by design); heartbeat every 30s, ≈ 11 KB/min (~0.65 MB/h) |
| MA playback, foreground | **15.3%** | Oboe/AAudio path; audio itself cheap (see F2 trace: `AudioOut_D` only 217 ms CPU per 15 s) |
| MA playback, backgrounded | ~11.5% | Playback survives backgrounding cleanly |
| Navidrome track start | **92.3% burst** | Whole-track prefetch (F3); settles to 1.1 KB/s steady afterward |
| Memory (any scenario) | 190 → 228 MB PSS | Grows with artwork cache + audio buffers, then plateaus; no growth loop |

The idle baseline is excellent: the background Sendspin connection costs CPU-time nothing; its cost is one wake lock + ~11 KB/min of heartbeats, which is the deliberate price of receiving Home Assistant TTS announcements. See F8.

---

## 4. Findings

### F1 (P0) — `WaveSeekBar` redraws at 60 fps while paused

**Code:** `ui/design/SendspinDesign.kt`

- Line **1441**: `rememberInfiniteTransition(label = "waveSeekBarPhase")` is created unconditionally.
- Line **1448**: `val phase = if (reduced) 0f else travelling` — reduced-motion is handled, but **paused is not**.
- Lines **1490–1494**: `waveY()` reads `phase` inside the `Canvas` draw lambda, so the draw invalidates **every frame** as long as the seek bar is composed — even though line 1450 animates `amplitudeDp` to **0** when `playing == false`.

The amplitude fades to zero when paused, but the phase keeps advancing and the draw lambda keeps re-running at display refresh rate, recomputing a flat line 60 times a second.

**Measured:** Now Playing screen, playback paused, nothing else animating: **907 frames in 15 s** (~60 fps), RenderThread 15–27% CPU + main thread ~11%. On a screen the user perceives as static.

> **Attribution caveat (added while implementing #165).** This number may not be the wave's
> alone. `WaveSeekBar` is only composed under the **Wave** seek style; with the **Line**
> style it never enters the composition at all — and a paused Now Playing under Line, with
> the wave fix already in, still measured **862 frames / 15 s** from the title marquee (F2)
> by itself. Unless this session's install was on the Wave style, some or most of the 907
> frames above belong to F2. The two findings are both real and both fixed; only the split
> between them is uncertain.

Contrast with the sibling slider: `glowPulse` (line **1660**) gates its read at line **1673** — `val effectiveGlow = if (reduced || !playing) 0.5f else pulseAlpha` — so when paused nothing reads the animating value and no redraw is driven. `WaveSeekBar` is missing exactly that gate.

### F2 (P0) — Mini-player marquee keeps the whole UI redrawing during playback, on every screen

**Code:**
- `ui/screens/NowPlayingOverlay.kt:636` — `MiniPlayerBar`'s title applies `Modifier.titleMarquee()`.
- `ui/design/Motion.kt:357–367` — `titleMarquee()` → `basicMarquee(iterations = Int.MAX_VALUE, ...)`. It gates on reduced-motion and on content overflow, but **not on playback state**, and the mini bar is hosted app-wide (Library, Search, Settings…), not just on Now Playing.

**Measured (perfetto, 15 s, MA playback active, user sitting on the Library screen):**

| Metric | Value |
|--------|-------|
| Frames (`Drawing 1080x2424`) | **353 / 15 s** |
| `Recomposer:recompose` slices | **884 (~59/s)** |
| RenderThread busy | **2.74 s / 15 s (18.3%)** |
| Main thread busy | 2.49 s / 15 s (16.6%) |
| `AudioOut_D` (the actual audio work) | **217 ms / 15 s (1.4%)** |
| DisplayList ops | 16,922 `CircularRRectOp` + 19,037 `FillRectOp` ≈ **144 GPU ops/frame** |

`gfxinfo` for the same scenario: **155 frames / 12 s** (~13 fps of continuous churn). With playback stopped, the Library produces **0 frames / 15 s**. The audio engine uses 1.4% of a CPU core; making a title scroll somewhere below the fold uses ~35% across main + RenderThread.

The marquee animates in periodic passes separated by a rest interval, so the churn is bursty — but during passes it invalidates far beyond its own text node (884 recompositions/15 s across the composition), because the scroll offset ends up read in composition rather than isolated to a draw layer.

**Battery implication:** during every listening session, on every screen, the app holds the display pipeline at ~13–60 fps for a strip the listener is not looking at. Fixing F1+F2 is the single largest battery win available in this codebase.

### F3 (P1) — Navidrome whole-track prefetch burst

The ExoPlayer/MediaCodec path requests the entire track on start. Measured: **92.3% CPU burst at track start** (decoder + buffer churn on a large allocation), then a steady state of 1.1 KB/s of socket traffic and ~11.5% CPU (playback survives backgrounding cleanly). The burst is wasted work on every track change and is visible as a hitch on slow devices.

### F4 (P2) — Settings pref-flow fan-out

HPROF analysis shows **2,400 live `AppSettings$pref$$inlined$map$1` flow chains**. Every `pref { ... }` accessor (`data/AppSettings.kt:1137` is one example) builds a fresh `map` chain per collector, and rows/screens each collect several. They are small, but 2,400 of them is allocation and subscription overhead plus a recomposition hazard: any pref write fans out to every chain.

### F5 (P2) — MA group poll churn, parsed on main

`service/MaNowPlaying.kt`:
- Lines **224–230**: event stream sampled every **300 ms** → `refresh()`.
- Lines **231–236**: `while (true) { delay(POLL_MS) }` with `POLL_MS = 5_000L` (line **613**) polling group/queue state.

Heap shows **129 live `MaPlayer` and 88 `MaQueue` instances** — each poll parses fresh model objects with no `distinctUntilChanged` before emission, so unchanged state still churns allocations (and recompositions downstream), on the main dispatcher.

### F6 (✅ OK) — No memory leaks

Custom-parsed 61 MB HPROF: exactly **1 `MainActivity`**, bounded Coil image cache, no orphaned service/ViewModel contexts, PSS plateaus at ~228 MB. The 2400-chain (F4) and instance-churn (F5) smells are allocation hygiene, not leaks — nothing grows without bound.

### F7 (P3) — Intermittent ANR on heavy transition

Dropbox captured one `data_app_anr`: *"Input dispatching timed out … waited 15001 ms"* — with **~0% CPU during the window**, i.e. the main thread was **blocked, not spinning** (lock/IO wait, not a compute loop). The process died before a trace could be pulled.

Not reproduced in 5+ subsequent attempts: 3 rapid background/foreground stress cycles, track-end waits, and screen-transition probes — JDWP owned/contended-monitor probes on all threads came back clean each time (only daemon sleep-monitors at baseline). It remains real (it happened once, unattended) but rare. §6.6 lists hardening steps.

### F8 (✅ OK) — Keep-alive is honest, gated, and cheap

`service/Playback.kt:464–491` — `AppLifecycleObserver.onBackground`:

```kotlin
val grouped = engine?.grouped == true
if (!keepAlive && !grouped && _connected.value && !_isPlaying.value) {
    disconnect(stopService = true, reason = "user_request")
}
```

Live-verified end-to-end (toggled OFF via UI, DataStore confirmed, then restored):
- **OFF** → service stops, wake lock released, heartbeats cease. Exactly what the setting promises (`data/AppSettings.kt:1137`, default ON).
- **ON** → connection held, wake lock held, 0.0% CPU idle, 30s heartbeats ≈ 11 KB/min.

The documented rationale (background connection **is** the HA TTS announcement feature; grouping implies stronger intent than the battery preference) is implemented correctly. No change needed.

---

## 5. What Is Already Good

- **Idle background cost:** 0.0% CPU; the wake lock is the feature, not a bug.
- **Audio engine:** `AudioOut_D` at 1.4% CPU during playback — the Oboe path is efficient; the UI around it is what costs.
- **Memory discipline:** no leaks, bounded caches, stable PSS.
- **Reduced-motion support:** `LocalReducedMotion` exists and is respected in most places (and is exactly the pattern F1/F2 fixes extend to "paused"/"not visible").

---

## 6. Suggested Fixes (prioritized, with code)

### 6.1 F1 — Gate `WaveSeekBar` phase on playing state (P0, ~5 lines)

**Corrected after implementation (#165).** This section originally proposed gating the
*draw lambda* on `amplitudePx <= 0`. That is not sufficient: `phase` is read at line
**1448**, in **composition**, not only inside the `Canvas`. A draw-only gate stops the
draws and leaves the recomposition — ~60/s — running underneath them.

The fix is to gate the read itself — but **on the amplitude, not on `playing`**:

```kotlin
// SendspinDesign.kt — amplitudeDp declared above phase
val phase = if (reduced || amplitudeDp <= 0f) 0f else travelling
```

`!playing` looks like the obvious gate (and shipped first) but is wrong, for the reason
`WaveSeekBar`'s own header gives: it snaps the phase to 0 the instant playback stops,
while the amplitude still has `WAVE_AMPLITUDE_MS` = 500 ms of flattening left at full
height. With `WAVE_PERIOD_MS` = 1600 that is the curve jumping sideways by up to a whole
wavelength on every pause, and back on resume. The amplitude gate keeps the wave
travelling the whole way down, so it settles onto the rail; the phase read drops out at
zero amplitude, which is exactly when the phase stops meaning anything on screen.

Cost: `amplitudeDp` becomes a composition read, so each half-second transition
recomposes ~30 times — against 60 times a second for as long as the screen is up. The
infinite transition above can stay as it is: once nothing reads `travelling`, nothing
recomposes or redraws from it.

The `glowPulse` gate at line 1673 is *not* the precedent it appears to be — it swaps an
alpha, a scalar with no shape to jump. A phase is a shape parameter.

**Measured, controlled A/B on the audit emulator** (paused full player, Wave style, short
title, 15 s windows): stock **905 frames** → **0 frames**. Measured with the `!playing`
gate; the amplitude gate above is the same steady state (the read is gone once the wave
is flat) and differs only across the 500 ms flatten, where it is the version that does
not jump.

### 6.2 F2 — Pause the mini-player marquee when nothing is playing (P0, ~10 lines)

`basicMarquee` has no pause API, so gate the modifier:

```kotlin
// Motion.kt
fun Modifier.titleMarquee(running: Boolean = true, restMs: Int = TITLE_MARQUEE_REST_MS): Modifier {
    if (!running || LocalReducedMotion.current) return this
    return this.basicMarquee(iterations = Int.MAX_VALUE, ...)
}

// every call site, e.g. NowPlayingOverlay.kt (MiniPlayerBar title)
modifier = Modifier.titleMarquee(running = st.isPlaying)
```

**Corrected after implementation (#165).** This section originally scoped the gate to the
mini bar and argued the full Now Playing screen could keep scrolling because "the user *is*
looking at it". That carve-out turned out to be the larger half of the finding: a paused
full player is not being watched either, and it churned hardest of all — **862 frames / 15 s**
on the audit install with a long title and the F1 fix already in.

So the gate belongs on **every** marquee, not just the mini bar: the full player's title,
artist and album, the mini bar, and the TV Now Playing screen's title. A paused screen is a
still screen, on every screen of the app. (Compose already suspends a marquee when the
window is not visible, so screen-off and backgrounded were covered; `running` closes the
paused-and-visible case.)

**Measured after gating all of them:** every paused configuration — wave/marquee ×
Line/Wave seek style, short/long title — renders **0 frames / 15 s**. Playing is untouched
(1505 frames / 25 s, all animations live).

Also worth measuring after this change: whether the ~59 recompositions/s are the marquee offset being read in composition. If churn persists, isolate the scrolling text in `Modifier.graphicsLayer { }` reads so invalidation stays on its own layer.

### 6.3 F3 — Cap Navidrome prefetch with a custom `LoadControl` (P1)

```kotlin
val loadControl = DefaultLoadControl.Builder()
    .setBufferDURATIONms(...)           // e.g. 30–60 s instead of the whole track
    .setBackBuffer(10_000, true)
    .setPrioritizeTimeOverSizeThresholds(true)
    .build()
```

This converts the 92.3% start-of-track burst into a shallow ramp, removes the hitch on slow devices, and saves mobile data for users who skip tracks often. Gapless behavior is unaffected (buffer still spans boundaries if ≥ one track… verify with the longest-tail FLAC; if needed, scale the buffer by bitrate).

### 6.4 F4 — One settings snapshot instead of per-collector chains (P2)

Expose a single immutable `AppSnapshot` data class, `combine`d once and exposed as one `StateFlow`, and migrate row/screen collectors to read fields from it instead of collecting individual `pref { }` flows. This collapses the 2,400 chains to O(screens) and turns "any pref write fans out everywhere" into "one flow, `distinctUntilChanged`-protected".

### 6.5 F5 — Deduplicate MA poll results (P2, small)

```kotlin
// MaNowPlaying.kt — after parsing, before emitting:
.mapNotNull { MaParse.event(it) }
.filter { it.isPlayerOrQueue }
.sample(300)
.distinctUntilChanged()   // don't refresh()/re-alloc on identical state
.collect { refresh() }
```

plus `equals` on the parsed models (or compare by `(playerId, position, state, queueHash)`) and consider moving `MaParse.event` off the main dispatcher (it's pure parsing).

### 6.6 F7 — ANR hardening (P3)

Since the main thread was *blocked* (0% CPU): audit synchronous I/O reachable from click handlers during screen transitions (DataStore first-reads, DB on main, binder calls), and enable in debug builds:

```kotlin
StrictMode.setThreadPolicy(
    StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build()
)
```

Keep the JDWP live-probe recipe from §7.3 ready; if the ANR recurs while a probe is attached, `ownedMonitors`/`contendedMonitor` on all threads will name the holder immediately.

---

## 7. Appendix — Reproduction Recipes & Tooling Notes

### 7.1 F1 (paused redraw)
Play a track on MA, pause it, stay on Now Playing, then `adb shell dumpsys gfxinfo com.engabd.sendpin framestats` → hundreds of frames with a static screen.

### 7.2 F2 (marquee churn)
Start MA playback, navigate to Library, wait 25s (marquee rest passes), `perfetto` 15s textual config; query `Recomposer:recompose` slice count and RenderThread runtime via trace_processor.

### 7.3 JDWP on Android 17 (works on user builds for debuggable apps)
`adb forward tcp:59101 jdwp:<pid>` then a raw JDWP client. Dialect notes that cost real time to discover, recorded here: 8-byte IDs everywhere; `IDSizes` returns 5 fields; `AllThreads` returns serials, not object ids; `ThreadReference` command mapping differs from docs — **11/1 = threadName (string reply), 11/4 = status pair, 11/9 = tagged contended monitor, 11/11 = owned monitors list**; `ReferenceType.Instances` is 2/13 and returns `[tag][8-byte id]` pairs.

### 7.4 Heap
`adb shell am dumpheap com.engabd.sendpin /data/local/tmp/camusic.hprof` + `adb pull`, then parse (ART HPROF 1.0.3, 8-byte roots). Class-instance histograms used for F4/F5/F6.

### 7.5 Perfetto
Textual configs need the `--txt` flag on this build; analyze with `trace_processor_shell <trace> -q <sql>`.

### 7.6 Raw numbers, for the record
- Paused Now Playing: 907 frames/15 s; RenderThread 15–27%; main ~11%.
- Library during MA playback: 353 frames/15 s; 884 recompositions/15 s; RenderThread 2.74 s/15 s; main 2.49 s/15 s; `AudioOut_D` 217 ms/15 s; 16,922 `CircularRRectOp` + 19,037 `FillRectOp`.
- `gfxinfo` Library: 155 frames/12 s playing vs 0 frames/15 s stopped.
- Navidrome: 92.3% CPU burst at track start; 1.1 KB/s steady; 11.5% backgrounded.
- MA: 15.3% CPU foreground; ~11.5% backgrounded; `AudioOut_D` 1.4%.
- Memory: 190 → 228 MB PSS plateau; 2,400 pref-flow chains; 129 `MaPlayer` / 88 `MaQueue`; 1 `MainActivity`.
- Keep-alive idle: 0.0% CPU; wake lock held; 30 s heartbeats ≈ 11 KB/min.

---

**Update 2026-09-13.** F1 and F2 re-landed (revert of `e5cfff7`) on top of the playhead rewrite
(#171). The "stuck at 0:02" seen after #165 was the old optimistic-freeze machinery, which #171
removes; the gates themselves only decide when the wave phase is read and when the marquees run.
Measured on the emulator with the gates back: 0 frames rendered over 10 s on a paused Now
Playing, and the bar advances, holds on pause and resumes on the local path.

