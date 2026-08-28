# Plan: rewrite the Music Assistant playhead on massdroid's model

Status: proposed. Supersedes the `Playback` half of PR #98.

The seek bar for Music Assistant playback jumps: forward and back at the start of
every track, by many seconds across a pause, and it refuses to land where a seek
points. PR #98 fixed three real causes and then hit a fourth that the existing
design cannot express. This plan replaces the design rather than adding a fifth
guard to it.

Everything below was established on-device against MA 2.10 over Sendspin
(SM-S911B, release builds) during the session that produced PR #98. The evidence
is recorded so none of it has to be rediscovered.

---

## 1. The model to copy

`github.com/sfortis/massdroid_native`, `core/.../data/repository/PlayerRepositoryImpl.kt`
— `updatePosition()` and `interpolatedPosition()`. It has none of our jump
problems and is markedly simpler. One decision does the work:

> **Anchor to the server's capture time, never to "now".**

```kotlin
positionBaseTime      = serverElapsed          // elapsed_time
positionBaseTimestamp = serverElapsedAtMs      // elapsed_time_last_updated → local
// displayed = base + (now - baseTimestamp) while playing; frozen at base otherwise
```

Their comment states the intent: *"anchor the base to the SERVER CAPTURE time
(not 'now'), so the ticker re-derives the same value every event arrives — no
backward/forward jump when sparse QUEUE_UPDATEDs land."* It deliberately mirrors
MA's own web UI (`helpers/elapsed.ts`).

**Why this is the whole fix: re-anchoring becomes idempotent.** Every reading
re-derives the same continuous function of `(elapsed, capturedAt, now)`, so a
repeated or sparse reading lands on the value already displayed. There is nothing
to jump, and therefore nothing to filter.

Four rules, in full:

1. Anchor `(elapsed, capturedAt)`; never substitute "now" for `capturedAt`.
2. Interpolate **only while playing**; frozen at `elapsed` otherwise. Never
   advance across paused time.
3. **Cap how far a capture may be forward-projected.** MA *freezes*
   `elapsed_time_last_updated` while paused, so an event arriving after a long
   pause carries a capture stale by the whole pause. massdroid documents the
   symptom as shooting past the track end — "4:04 on a 3:31 track". Beyond the
   cap, anchor the value as-is instead of projecting it.
4. **Re-anchor the timestamp to "now" on the paused→play boundary**, so a stale
   capture cannot jump the position on resume.

massdroid drives all of this from MA's `elapsed_time` / `elapsed_time_last_updated`
— **not** the Sendspin `server/state` progress.

---

## 2. Why ours jumps

`Playback.anchorProgress` anchors inconsistently: sometimes to the server stamp,
sometimes to `nowUs` (the fallback), and on `onAudible` to "now". That makes
re-anchoring **non-idempotent**, so ordinary sparse readings move the bar. Guards
were then added to suppress the movement:

| Guard | Suppresses | What it actually does now |
|---|---|---|
| `isStaleRewind` + `UNAMBIGUOUS_REWIND_MS` (10 s) | "restated" backwards readings | **Rejects legitimate seek corrections under 10 s** outside a 4 s window — "the bar refuses to go where I pointed" |
| `acceptRewindUntilUs` + `REWIND_GRACE_US` (4 s) | seeks look like rewinds | Grace expires before MA's corrections arrive |
| `trackSettleUntilUs` (600 ms) | boundary crosstalk | Band-aid over the same non-idempotence |
| `MAX_ANCHOR_AGE_US` (30 s) | wrong clock epoch | Far too loose to bound projection; a 7.4 s-stale stamp inflated the bar by 10 s in one message |

The premise behind routing the bar through Sendspin `server/state` at all —
"MA's poll says nothing trustworthy about how old it is" — **is wrong**. The poll
carries `elapsed_time_last_updated`. It is *sparse*, not untrustworthy, and
sparse is fine when interpolation is continuous.

---

## 3. Established facts (do not re-derive)

**MA 2.10 identity.** MA registers a Sendspin client as a *protocol* player under
the client's own id, then wraps it in a `universal_player` (`up…`). The wrapper is
what queues address and the app targets, so `targetId() == myPlayerId` is **never**
true. Server log: `Creating universal player up0997467a with protocol players:
['6d5fe665-…']`. The link is `active_output_protocol` on the wrapper.
`group_childs` is empty and useless here. Suspect this break in **any** "is this
phone the player" logic.

**`stream/start` is not audio.** It precedes first sound by **~1.6 s** (measured:
`stream/start` 13:11:10.314, engine READY 13:11:11.956). At the instant
`stream/start` arrives the *outgoing* track is still playing — `isPlaying` only
goes false ~2 ms later.

**MA freezes `elapsed_time_last_updated` while paused**, and its queue clock keeps
advancing across a pause (~5.6 s over a 7.3 s pause). Both produce stale captures.

**MA sends impossible progress.** `track_progress` of 457 044 ms against a
252 000 ms item, confirmed bogus against the server's own `player_queues` view of
the same track. `ProgressProjection` *clamps* an over-long anchor to the duration,
which pins the bar at the track end.

**MA sends `"title": null` metadata around a queue restart — and a seek is a queue
restart** (`players/cmd/seek` → `play_index(seek_position=)`). `NowPlaying.title`
is non-null, so such a message parses to a null snapshot and any identity derived
from it reads as a track change.

**Start latency, unfixed and out of scope here.** tap → `stream/start` ~1.25 s;
`stream/start` → first sound ~1.64 s; total ~2.9 s. Disabling MA's
`acoustid_lookup` / `smart_fades` / `sonic_analysis` moved the server half by only
~50 ms, so those were **not** the cost. The client half contains a reproducible
second FLAC decoder flush ~1.15 s in; leading hypothesis is the scheduled playout
lead honoured by `SendspinSyncDataSource` because the phone is grouped — correct
for group sync, and cutting it would desync speakers. **Cheapest untested probe:
play to the phone ungrouped** (unpaced `SendspinDirectDataSource`) and re-measure.

---

## 4. Keep, replace, delete

**Keep from PR #98** (each fixes something massdroid never had to face):

- `MaPlayer.activeOutputProtocol` + `isSelfOrActiveOutput` — the MA 2.10 identity
  fix, with `MaPlayerOutputProtocolTest`.
- `SendspinPlaybackSupport.PlayheadGate` audible-hold, `SendspinExoEngine.onAudible`
  and `audibleSeq` — `stream/start` is not audio, and the UI freeze must be
  released on the **edge** where a *new* stream is first heard. A level ("is audio
  flowing?") cannot express it: when a skip is requested the outgoing track is
  still audible and stays so for another second.
- `PlayerPositionTracker` fed `isPlaying = playback.isPlayheadRunning` rather than a
  hard-coded `true`, so it does not project across the warm-up.
- Not releasing the optimistic freeze from the poll's `trackChanged` branch when
  the Sendspin path is authoritative.

**Replace:** `Playback.anchorProgress` / `republishPosition` / `ProgressProjection`
with massdroid's two functions — anchor `(elapsed, capturedAt)`, interpolate only
while playing, cap forward projection, re-anchor on resume.

**Delete** (they exist only to paper over non-idempotent anchoring):
`isStaleRewind`, `acceptRewindUntilUs`, `REWIND_GRACE_US`,
`UNAMBIGUOUS_REWIND_MS`, `trackSettleUntilUs`, `TRACK_TRANSITION_SETTLE_US`,
`heldReading`, and the `MAX_ANCHOR_AGE_US` / `MAX_PROJECTED_LEAD_US` pair (one
projection cap replaces both). `PlayheadGate.describesTrack` probably becomes
redundant once captures are no longer over-projected — verify before removing.

**Decide early:** whether to keep sourcing progress from Sendspin `server/state` at
all, or follow massdroid onto `elapsed_time` / `elapsed_time_last_updated`. The
Sendspin path's only real advantage is sub-second freshness; nothing in the UI
needs it, and Light Sync does not use the playhead (see §6). Recommendation: use
MA's fields, and keep Sendspin only for the audible edge.

---

## 5. Verification protocol — read before testing

**The trap that cost this session two false "verified" claims:**
`MediaSessionService: onSessionPlaybackStateChanged position=` is **ExoPlayer's
per-stream position, not the seek bar**. It resets per track, which made a broken
fix look correct twice.

The bar is `NowPlayingViewModel.positionMs` ← `PlayerPositionTracker` ←
`Playback.positionMs`. **Log the bound value.** Temporary `Log.i` in the
`positionMs` combine, the freeze helpers, the poll anchor sites and
`republishPosition` gives the whole picture in one capture.

Other measurement notes:

- `uiautomator dump` is ~2.4 s per sample — fine for *rate* checks over 10 s, far
  too slow for a 1.6 s transient.
- `SendspinClient` `rx` log lines are **truncated**; JSON payload fields are often
  cut off. Don't rely on them for values.
- Media-key events can route to `io.music_assistant.client` (the official MA app,
  also installed). Drive CAMusic's own transport by coordinate.
- `input swipe` moves a Compose slider thumb but **does not commit a drag**, so no
  seek is issued. An in-app seek must be tested by hand, or driven server-side via
  `players/cmd/seek`.
- MA WebSocket probe: `ws://192.168.0.48:8095/ws`, `auth/login {username, password,
  device_name}` → `access_token` → `auth {token}`. Node 24 has a global
  `WebSocket`, no deps. Commands that exist: `providers` (not `providers/all`),
  `config/providers`, `config/providers/get`, `music/item_by_uri`,
  `player_queues/get_active_queue`, `players/cmd/seek`. `http://<host>:8095/info`
  answers unauthenticated.
- **Strip all diagnostics before committing** and prove it:
  `git diff master...HEAD | grep -c Diag` must be 0.

**Cases that must pass**, each traced on the bound value:

1. Skip — one transition, no forward or backward jump, runs 1:1 from the moment
   audio starts.
2. Natural track end → next track — same.
3. Pause — bar holds, no drift.
4. Resume — picks up where it stopped, no jump.
5. **Seek forward and backward, including moves under 10 s** — lands on the target
   and stays. This is the case the current design cannot pass.
6. Seek while paused — lands on target, stays frozen.
7. Steady state — 1.0× over 30 s.

---

## 6. Light Sync is not affected — verified, do not re-investigate

Direct-bridge sync on this phone runs off `AudioAnalysisTap` / `AudioLead`
(ExoPlayer's sink), never the playhead. The only light path that reads a position
is `ScanFrameSource` (`positionMs - offsetMs`), and it is remote-only
(`if (now.isSelf) reset()`) and reads app-scoped `MaNowPlaying`, **not**
`NowPlayingViewModel`. `DirectLightSync`'s `isPlaying` gate uses
`Playback.isPlaying`, whose semantics must not change.

---

## 7. Anti-patterns from this session

Six fixes in a row were wrong and reasoning caught none of them; the device caught
all six. On this path, **do not trust static reasoning** — instrument and capture.

- Don't add a guard to suppress a symptom whose cause is non-idempotent anchoring.
  That is how `isStaleRewind` came to reject seeks.
- Don't infer "audio is playing" from `stream/start`, `_isPlaying`, or
  `sendspinPlaying`. All three are true ~1.6 s early.
- Don't use a **level** where the question is an **edge** ("has the stream I am
  waiting for started?").
- Don't treat a message with no track identity as a track change.
- Don't verify against a proxy signal. Verify the value the UI binds to.
- Don't clamp an implausible reading — reject it. Clamping pins the bar at the
  track end, which is a confident lie.

---

## 8. Known residuals, server-side

Not bugs in this app; decide whether to compensate or reflect them.

- MA's readings lead our playout by ~2 s (the buffer). Every re-anchor on MA's
  word pulls the bar ahead of what is audible.
- MA's queue clock advances across a pause, so resume steps forward. **Check
  whether the audio also skips** before treating the bar as wrong.
