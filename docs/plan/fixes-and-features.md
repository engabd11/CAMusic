# PR 120: bug fixes, honest hi-res output, and USB exclusive mode

This file used to be the original 894-line task-by-task plan for this branch. Most of it never
happened, and the parts that did diverged enough from the plan's description that keeping the two
side by side would have been actively misleading. This is a record of what actually landed instead
— what shipped, what was cut and why, and the one finding worth carrying forward so it does not get
rediscovered the hard way.

The branch started from a review that found Phase 1's seven bug fixes and Phase 2's three refactors
never implemented, and Phase 3's fifteen features shipped as classes with tests and almost no way to
reach them from the app. Separately, the user reported two real problems: 24-bit output does not
work with hi-res enabled, and there was no way to hand a USB DAC the source's own bits untouched.
Both turned out to share one root cause in media3's own pipeline — see below.

## What shipped

**Five bug fixes**, re-implemented fresh against master since none of the branch's originals had
landed:

- `HueDtlsClient.splitRecords`/`splitHandshake` read a length off the wire and sliced on the
  strength of it with no bounds check, so a truncated or malformed datagram threw
  `IndexOutOfBoundsException` out of the light-sync receive loop. Both now stop at the bytes
  actually present rather than trusting the declared length, and are `internal` so
  `HueDtlsClientTest` can hand them the buffers a real bridge will not reliably produce.
- The DTLS receive buffers went from 4096 to 16384 bytes. `DatagramPacket` truncates silently past
  the buffer size, and a short buffer of our own making looked identical to a short datagram off
  the wire — feeding the same bounds-check bug from the other direction.
- `DrivingMode`'s car receiver now registers with `RECEIVER_NOT_EXPORTED`, matching
  `DrivingPip.registerControls` one file over. This is lint cleanliness, not a crash fix —
  `ACTION_ACL_CONNECTED` is a protected system broadcast, so API 33+ was never going to throw here.
- `CarLibraryBridge.sourceCache` is a `ConcurrentHashMap`. It is read and written from
  `suspend fun sourceFor`, which runs on whichever thread the `MediaLibraryService` callback landed
  on, and two browse requests for one server can be in flight together.
- `SendspinNativeOutput`'s class doc named `OboeRenderer`, a class that has not existed since
  `SendspinNativeEngine` replaced it. It now names the real producer: the engine's decode thread,
  feeding PCM through `write()`. The same stale name was still sitting in a comment in
  `sendspin_output_engine.h`; fixed there too, comment-only, no C++ changed.

Two items from the original seven were rejected rather than implemented — see "What was dropped"
below.

**The signal-path rework**, because the Output card's "Decoder output" row was structurally
incapable of reporting more than 16 bits for any file, in any setting. `SignalPathProbe` — first in
this app's own processor chain — is deleted; it could never have seen anything but audio media3 had
already flattened. The decoder's real format is now read where it is still visible: at
`AudioLeadProbe`, the `ForwardingAudioSink` wrapper one layer further out than
`DefaultAudioSink`. What the sink actually carries downstream of that is *derived* from media3's
own float-output rule rather than pretended to be observed, and `SignalPath.State` gains a
`processorsBypassed` flag — true whenever hi-res output engages float or Exclusive output is on —
so the card can say plainly that the equaliser and the Light Sync tap are out of the path, instead
of leaving that silent. See "The media3 finding" below for the mechanism.

**Exclusive output**, a new mode that removes every stage this app puts between the decoder and the
DAC and asks the platform for the source's own rate and depth. `ExclusiveOutput.kt` holds the
policy — what it forces on, what it disables, whether a route can carry a format untouched — as a
plain, Android-free object in the shape of `FormatNegotiator`, so it is unit-testable on its own.
Turning it on: forces float output unconditionally, builds `LocalPlayer` with no processors at all
(equaliser, vinyl/lo-fi, the Light Sync tap), forces the output resampler off, fixes in-app gain at
unity (ReplayGain and the fades go with it — the volume slider still works, because on a local
session it already drives the phone's own media volume rather than anything inside the player), and
pins an attached USB output if nothing is already pinned. The Output card explains what it turns off
and why, `LocalEqPanel` shows the curve as inert with a pointer back to the card rather than hiding
it, and the same treatment now reaches the sound-mode rows described below. The honest limit is
stated in the UI: Android exposes no app-level exclusive or DIRECT flag, and this cannot bypass
AudioFlinger, the platform's own mixer.

**The Sendspin path stops claiming a depth it cannot carry.** The native ring is `int16_t` and
`SendspinNativeEngine` explicitly truncates 24-bit PCM on arrival, so advertising 24-bit to Music
Assistant only ever bought bandwidth for bits that were dropped the moment they landed. The
advertised depth is now capped at a new `SendspinNativeEngine.OUTPUT_BIT_DEPTH = 16` where the
format list is built; `FormatNegotiator`'s own signature and its `HIRES_BIT_DEPTH` doc are untouched
— that one genuinely describes the local ExoPlayer path. `convertPcm24To16` now rounds to nearest
and saturates at full scale instead of dropping the low byte outright, which used to bias every
sample toward zero and could wrap `0x7FFFFF` up into the most negative sample there is.

**Vinyl and lo-fi reach the UI.** `VinylNoiseProcessor` and `LoFiProcessor` were already wired into
`LocalPlayer`'s chain and into `AppSettings`; they had no way to be switched on. Three bugs were
fixed first, all in the two processors themselves:

- `onReset()` on both used to do `active = Config()`. `reset()` reaches a processor from
  `DecoderAudioRenderer.onDisabled()` — a track change, a stop, a release — by which point `pending`
  has already been drained into `active`, and `AppSettings.pref()` dedupes the settings `Flow` that
  refills `pending`. So a reset silently switched the mode off and it never came back for the rest
  of the session, with no re-emission left to switch it back on. `LocalDsp.onReset()` already had
  this right — it clears only derived state (`sections`, `preampLinear`), never the config — and
  both processors now match it. `LoFiProcessor` still shrinks `heldSample`/`lpState` back on reset,
  the genuinely-derived, channel-count-sized state that plays the same role `sections` does.
- Vinyl crackle was noise, not clicks. `generateNoise` used to call `rng.nextBoolean()` on every
  *sample* to sign the decaying envelope, which produces a burst of white noise under an exponential
  envelope rather than a click. The polarity is now chosen once, when an impulse triggers, and held
  fixed for the life of that impulse's decay — for both the crackle and the dust-pop envelopes.
- `crackleInterval` took a `sampleRate` parameter and ignored it, counting purely in samples, so a
  96 kHz file crackled at roughly twice the rate of a 44.1 kHz one for the same intensity. It now
  scales the interval by the ratio of the actual rate to a 44.1 kHz reference, so the density is
  constant in real time; at 44.1 kHz itself the output is bit-for-bit unchanged, so existing material
  keeps its character. `popInterval` already scaled correctly, so the asymmetry between the two
  functions is gone.

With those fixed, "Sound modes" — Vinyl and Lo-fi, each a toggle plus an intensity slider — sits in
the Now Playing options sheet, right after "Keep the music going"/"Don't stop the music" and before
"Playback speed". Gated on `isLocalSession`, for the exact reason that section's own comment already
gives for the switch above it: both processors live in `LocalPlayer`'s chain, so on the Music
Assistant path they would be switches that silently did nothing to the player making the sound. When
Exclusive output is on, the rows stay visible but disabled, with a subtitle built from
`ExclusiveOutput.disables`'s own "Vinyl / Lo-fi sound modes" entry rather than a second copy of the
reasoning. `NowPlayingViewModel` carries both configs and the exclusive-output flag through the same
mechanism `radioMode`/`toggleRadioMode` already used — a deduped backing `MutableStateFlow`
collected from `AppSettings` in `init`, bundled (with `radioMode`) into one `ToggleSnap` value so the
local-session branch of `state`'s combine stays inside Kotlin's 5-flow ceiling per call.

## The media3 finding

`DefaultAudioSink.configure()` builds its processor pipeline as:

```java
pipelineProcessors.addAll(availableAudioProcessors);
if (shouldUseFloatOutput(inputFormat.pcmEncoding)) {
  pipelineProcessors.add(toFloatPcmAudioProcessor);
} else {
  pipelineProcessors.add(toInt16PcmAudioProcessor);
  pipelineProcessors.add(audioProcessorChain.getAudioProcessors());
}
```

i.e. `[trimming, channelMapping] + toInt16PcmAudioProcessor + audioProcessorChain.getAudioProcessors()`
on the ordinary path. Verified directly against the media3 1.10.1 sources jar in the Gradle cache
(`androidx.media3:media3-exoplayer:1.10.1`), not assumed from the compiled class. Two consequences:

1. **media3's own 16-bit converter runs ahead of every processor this app adds.** Whatever a
   processor in `audioProcessorChain` observes, it observes after `toInt16PcmAudioProcessor` has
   already run — which is why `SignalPathProbe`, sitting in that chain, could never report more than
   16 bits for any file at any setting. It was not a bug in the probe; the position in the pipeline
   made the correct answer unreachable from there.
2. **`shouldUseFloatOutput` (`enableFloatOutput && Util.isEncodingHighResolutionPcm(encoding)`)
   skips the `else` branch entirely** — `audioProcessorChain.getAudioProcessors()` is never called
   at all on the float path. With hi-res output on and a genuinely wide decode, the equaliser and
   the Light Sync audio tap silently drop out of the signal, with nothing on screen saying so. This
   is media3's own behaviour, not a bug of this app's, but until Part B it was invisible.

Both are now surfaced rather than hidden: the decoder reading moved to `AudioLeadProbe` (a sink
wrapper, unaffected by any of this, since it sits outside `DefaultAudioSink` entirely), the sink
stage is derived from the same `shouldUseFloatOutput` rule rather than pretended to be measured, and
`processorsBypassed` says on screen exactly when case 2 applies.

## What was dropped, and why

**Twelve features that shipped as classes with tests and no way to reach them from the app**,
deleted rather than wired up — see commit `bd562ce` for the full reasoning per item. In short:
`alarm/` (`AlarmScheduler` calls `setAlarmClock` without `SCHEDULE_EXACT_ALARM`, which throws on
minSdk 31, and it needs a whole alarm UI to be worth anything); `wallpaper/MusicWallpaperService`
(reads `AudioAnalysisTap.frames`, which is only live while Light Sync runs — on a home screen it
would have been a 60 fps redraw of a static gradient); `audio/StemSoloProcessor` (redundant with the
equaliser's existing `HIGH_PASS`/`LOW_PASS` bands, and a 500–2000 Hz band-pass labelled "Guitar" is
not stem separation); `audio/EqPreset` (an orphan whose name collided with the existing preset
composable in `LocalEqPanel`); `hue/AlbumColourOverride` (persisted, never read by the light show);
`p2p/CollaborativeQueue` and its `queue.html` (its own doc references a `QueueServer` that was never
written, and it would open a listening socket to do it — not something to ship half-built);
`audio/AmbientNoiseMonitor` (holds the microphone open through playback for a feature nothing
consumes); and `audio/{ListeningJournal,AcousticSimilarity,HapticEngine,TransitionBridge,
LullabyController}` (orphans with zero references from app code).

**`SyncoEngine`'s render-map "optimisation"** — reusing one `HashMap` across frames instead of
allocating one per call. Rejected: `render()` runs at roughly 50 Hz with maybe twenty entries, which
is not a real cost, and reusing the map would alias every frame handed out.
`SyncoEngineTest` collects a hundred consecutive frames into a list; with a shared, mutated map they
would all become the same final frame in the test's eyes. This would have introduced a bug, not
fixed one.

**The three Phase-2 "god-object" refactors** — splitting `AppSettings`, splitting
`LibraryViewModel`, and extracting an `OutputRouter`. All three are large, untested, and carry no
user-visible benefit, which is exactly the kind of churn not worth taking on in the same branch as
real audio-path changes.

## Follow-up deliberately not done: the Oboe engine stays 16-bit

The Sendspin/Music Assistant path is honest about being 16-bit now, but it is still 16-bit. The only
way to change that is converting the native Oboe output engine and its ring buffer from `int16_t` to
float — a real project, not a follow-on of this one. It touches the drift-correction resampler, the
dither stage, the compressor and the volume stages that grouped-playback sync depends on, all inside
`sendspin_output_engine.cpp`'s real-time audio callback, and none of it can be verified without a
physical device and a second speaker to keep in sync. Deliberately out of scope here.

## What still needs checking on a real device

None of this has run on hardware yet. Reproducing the approved plan's on-device steps faithfully:

1. Play a known 24-bit FLAC from the local library with hi-res **off**. Settings → Playback & audio
   → Output → Signal path should read *File 24-bit, Decoder output 24-bit, To Android 16-bit* and
   warn about truncation. Before this change it read 16-bit at the decoder line regardless of the
   file.
2. Turn hi-res **on**, restart the app, replay the same file. Decoder output should stay 24-bit and
   "To Android" should become 32-bit float, with the bypass note showing. Confirm the equaliser
   genuinely stops having an audible effect — that is media3's behaviour, now stated on screen
   rather than silent.
3. Plug in a USB DAC, turn on Exclusive output. Confirm it pins the DAC, the equaliser and both
   sound modes show as inert with a reason, in-app volume is fixed at unity, and the Signal path
   panel reports the stream rate against the mixer rate.
4. Connect to Music Assistant with hi-res on and confirm the advertised format list is 16-bit
   (logcat on `stream/start`), and that grouped playback with a second speaker still locks.
5. Toggle Vinyl and Lo-fi from the Now Playing sheet mid-track: audible immediately, no gap, and
   still on after a track change — the `onReset` fix specifically.
