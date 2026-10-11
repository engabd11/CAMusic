# Bit-perfect USB output: CAMusic's own USB Audio Class driver

Status (2026-09-29): **M1–M5 done**, tested on a Galaxy S23 with a Sennheiser BTD 700 (#282, #283, #284, #286,
and the M5 PR). M6 remains — **What remains (M6)** at the end of this file is the full list: feedback-endpoint
clocking, UAC2 and high speed, more DACs, and the fixes found reviewing M1–M5. It follows v0.14.1, which made Direct to DAC play at all.

## Why

Testing v0.14.1 on a Galaxy S23 with a Sennheiser BTD 700 (USB id `3542:3001`) showed
that every output mode, Direct to DAC included, ends in the same place. Android's audio
policy on that phone runs the USB output at a fixed **48 kHz / 16-bit**. AAudio's client
flow graph resamples a 44.1 kHz or 96 kHz file to 48 kHz and truncates 24-bit to 16-bit
before a byte reaches USB. Measured from the device log:

```
AAudioServiceEndpointMMAP: openWithConfig() got rate = 48000 … AUDIO_FORMAT_PCM_16_BIT
AAudioFlowGraph: configure() source 96000 float → sink 48000 PCM_16
```

No Android audio API gives an ordinary app control over this. The only way to deliver a
file's own samples, at its own rate and depth, is to stop using Android's audio stack for
the DAC and drive the USB device ourselves. USB Audio Player Pro, HiBy Music and Neutron
all do this. [ExclusiveOutput.kt](../../app/src/main/java/com/engabd/sendpin/audio/ExclusiveOutput.kt)
already names it as "the only way to actually own the hardware".

**Definition of done:** the samples the DAC receives are exactly the samples in the file.
That means no resampling, no change of bit depth, no mixing and no digital volume. The
Output & signal path page must be able to prove it, not just claim it.

## How it works

```
ExoPlayer ── decoded PCM ──► UsbBitperfectOutput (AudioSink, Kotlin)
                                  │  exact float → int16/int24/int32, same rate
                                  ▼
                           usb_audio (native, C++)
                             ├── descriptor parser (UAC1 + UAC2)
                             ├── control requests: alt setting, sample rate, volume
                             ├── isochronous OUT scheduler (usbfs URBs)
                             └── feedback endpoint reader (async DACs)
                                  │
                           /dev/bus/usb/… fd from UsbDeviceConnection
                                  ▼
                                 DAC
```

1. **Find the DAC and ask permission.** `UsbManager`, a `USB_DEVICE_ATTACHED` intent filter
   for audio-class devices, and Android's own USB permission dialog. That dialog is the
   only one the platform offers, and "use by default" makes it a one-time question.
2. **Read what it can do.** `UsbDeviceConnection.getRawDescriptors()` gives the full
   configuration. We parse the AudioControl interface (UAC1 or UAC2, clock source and
   selector, feature unit) and each AudioStreaming alternate setting (Type I PCM: subslot
   size, bit resolution, channels, and for UAC1 the discrete rates). UAC2 rates come from a
   `GET RANGE` request on the clock source.
3. **Take the device.** Claim the AudioControl and AudioStreaming interfaces with
   `force = true`, which detaches the kernel's `snd-usb-audio` driver. While CAMusic holds
   the DAC, Android and every other app lose it: notifications, calls and other players go
   to the phone speaker or nowhere. Releasing hands it back to the kernel. This is exactly
   what the other bit-perfect players do, and the UI has to say so plainly.
4. **Configure for the track.** Choose the alternate setting whose bit resolution matches
   the file (or the nearest one that is not smaller, padded exactly), then `SET_INTERFACE`.
   Set the rate: a UAC1 endpoint `SET_CUR`, or a UAC2 clock-source `SET_CUR`. Read it back
   to confirm. If the DAC cannot do the file's rate, say so and fall back to the Android
   path rather than resample quietly.
5. **Stream.** Android's Java USB API has **no isochronous transfers**, and USB audio is
   isochronous. So the fd from `UsbDeviceConnection.getFileDescriptor()` goes to native
   code, which submits and reaps isochronous URBs through the usbfs ioctls
   (`USBDEVFS_SUBMITURB` / `REAPURB`), with 8–16 URBs in flight. Packet sizes follow the
   rate: at 44.1 kHz on a full-speed 1 ms frame that is 44 frames nine times and 45 once,
   from a fractional accumulator. High-speed DACs use 125 µs microframes.
6. **Clocking.** Adaptive and synchronous DACs take the nominal schedule. Asynchronous DACs,
   which is most good ones, publish their real clock on a feedback endpoint (isochronous
   IN, 10.14 or 16.16 fixed point), and packet sizes follow it so the DAC's buffer never
   drifts. This is what keeps a long album free of clicks.
7. **Volume.** Bit-perfect means no digital volume. If the DAC has a feature-unit volume
   control, the volume slider drives the DAC's own volume over USB. If it has none, as with
   many dongles and the BTD 700 whose volume lives in the headphones, the slider is disabled
   and says why. Digital volume stays available as an explicit, labelled choice that turns
   off "bit-perfect".
8. **PCM from the decoder.** ExoPlayer's float path, from Android's own decoders (see
   `FloatSafeCodecSelector`), carries 16- and 24-bit samples **exactly**: a float's 24-bit
   mantissa holds them without loss. So float to int16/int24 by scaling and rounding is
   lossless, and a unit test proves it for every 16- and 24-bit value. A 32-bit integer
   source is not exact through float; that is rare, and the signal path page will say so
   when it happens.
9. **Everything else stays.** The Light Sync analysis tap reads a copy of the PCM, so the
   lights keep working without touching the output. Gapless works across tracks with the
   same format. A change of format re-configures the DAC, with a short, clean gap.

## Milestones

Each milestone is its own PR, testable on the S23 with the BTD 700 over Wi-Fi adb.

| # | Deliverable | How it is verified |
|---|---|---|
| **M1** | USB permission flow, plus a **USB DAC report** in Diagnostics: raw descriptors, parsed UAC version, alternate settings, rates, feedback endpoint, volume control. The parser is pure Kotlin with unit tests on captured descriptors. | The BTD 700's real descriptors, captured and committed as a test fixture |
| **M2** | Native engine: claim, configure, isochronous streaming of a **test tone** at a chosen rate and depth; clean release back to Android | The tone plays, the DAC reports the rate, Android audio returns on release |
| **M3** | `UsbBitperfectOutput` AudioSink: the new **"USB bit-perfect"** output rung, per-track format switching, gapless within a format, seek, pause | FLAC at 44.1/16, 96/24 and 192/24 plays; the signal path shows the DAC's own confirmed rate and depth |
| **M4** | Feedback-endpoint clocking, underrun recovery, unplug and replug, screen off, process death | An album plays end to end with the screen off; unplugging falls back cleanly |
| **M5** | DAC hardware volume (feature unit), honest UI ("CAMusic has the DAC; other sounds are paused"), and a signal-path readout of what the DAC confirmed | The slider moves the DAC's volume; the page proves bit-perfect from the DAC's read-back |
| **M6** | Hardening across DACs (UAC1 and UAC2, full and high speed), shipped as **experimental** | At least three different DACs |

## Decisions

Decided 2026-09-29: **our own usbfs layer**, **take the DAC while playing and hand it back after 30 s paused**, and **digital volume offered as a labelled option, off by default**. The reasoning is kept below.

1. **Native USB layer.** One option is a small usbfs-based layer of our own: only control
   transfers (through Java's `controlTransfer`) and isochronous URBs, a few hundred lines,
   Apache-2.0 like the rest of the app. The other is libusb, which is LGPL-2.1: it must be
   linked dynamically, and the licence obligations go into the attributions.
   *Recommendation: our own.* We need a very small part of libusb, and the usbfs ioctls are
   stable kernel ABI.
2. **When CAMusic takes the DAC.** It can take it only while playing, and hand it back after
   a short pause. Or it can hold it for as long as the mode is on.
   *Recommendation: hold while playing, and hand it back after 30 s paused.* Holding it
   longer takes calls and notifications away from the user for no reason.
3. **Digital volume for DACs without hardware volume.** It can be offered as a labelled
   non-bit-perfect option, or not offered at all.
   *Recommendation: offer it, off by default.*

## Risks

- **Vendor kernels.** usbfs isochronous transfers from an app fd are what the existing
  players rely on, but some kernels have bugs at high speed. M2 finds out on the S23 early.
- **Device variety.** UAC2 clock selectors, implicit feedback and odd descriptors are where
  DAC drivers get hard. M6 exists for this, and the M1 report means users can send us
  their DAC's descriptors.
- **Power.** Some DACs draw more than a phone likes to give. That is not ours to fix, but
  the error has to be readable.
- **Verifying bit-perfection.** A DAC's rate indicator proves the rate, not the bits.
  A digital loopback, for example a USB DAC with S/PDIF out into a PC capture card, would
  let us compare captured samples with the file.

## What remains (M6)

Written 2026-09-29, reviewing M1–M5. The BTD 700 on the S23 is still the only device any of
this has run on; the list below is what the mode needs before it can leave experimental, in
the order the risk sits.

### Clocking: the feedback endpoint (the core gap)

Everything so far streams on the nominal schedule — the native engine's frame accumulator,
no feedback read. The BTD 700 tolerates that (albums verified by ear, 0 packet errors), but
a genuinely asynchronous DAC's clock drifts off nominal over a session: the one-second ring
eventually underruns (silent padding, then clicks) or overruns. This is the difference
between "bit-perfect samples" and a player that can hold any DAC for a whole album.

- Parse the streaming interface's isochronous IN endpoint as the feedback endpoint — explicit
  feedback (10.14 fixed point on UAC1, 16.16 on UAC2) and implicit feedback (a bidirectional
  interface with no explicit feedback endpoint: the OUT schedule follows the IN endpoint's
  data). It must not grab a headset's microphone endpoint; the M2 parser already traces
  feature units back to the streaming terminal, and the same care applies here.
- Native: submit an iso IN URB alongside the OUT chain and feed the observed rate into the
  `owed` accumulator, slew-limited so one misread cannot jerk the schedule. Expose the
  observed rate in `nativeStats`.
- Adaptive and synchronous DACs keep the nominal schedule. The choice comes from the
  alternate setting's sync type in the descriptors, and the signal path should say which
  one ran.
- Verification: `silentFrames` must stay 0 over a multi-hour album on an asynchronous DAC,
  and the Risks section's digital loopback (a DAC's S/PDIF out into a capture card, captured
  bits compared with the file) is the only real proof of bits — do it once, on one DAC, and
  the claim stops resting on the DAC's own rate read-back.

### UAC2 and high speed

The code paths exist and are fixture-tested, but no real UAC2 or high-speed DAC has been
taken:

- `UsbAudioSession.setUac2Rate` uses the first clock source. A DAC with a clock selector —
  several sources, one chosen — needs the source that actually feeds the streaming terminal
  (walk the selector's connections) and the selector's own SET_CUR.
- High-speed microframe scheduling: `UsbAudioMath.packetsPerSecond` handles `bInterval` and
  the M1 parser reads the interval, but no high-speed device has exercised it. Verify packet
  sizes, `maxPacket` and the 125 µs cadence on hardware.
- Implicit feedback is the common shape on UAC2 boxes; it is covered by clocking above.

### More DACs

At least three different DACs before experimental lifts (the milestone's own bar). The M1
report page is the intake: Share/Copy hands over raw descriptors, and each report becomes a
parser fixture the suite runs on. The report card should say that plainly — that a shared
report becomes a fixture — and power-draw failures have to read as an error, never a hang.

### What review found in M1–M5 (each small, none blocking v0.15.0)

- `usb_audio.cpp`'s `nativeRestart` requires `!thread.joinable()`. A streaming thread that
  self-exits through the REAPURB-error break in `run()` leaves the thread joinable, so
  `resumeFromAndroid` returns success and restarts nothing. `streamDied` → failover covers
  playback today; join-on-dead-thread (or a restartable flag) closes it.
- `UsbBitperfectOutput.drainBeforeSwitch` blocks the playback thread for up to 1.5 s (a
  `Thread.sleep(5)` loop) on every format switch — bounded and deliberate for gapless
  play-out, but it stalls the whole pipeline, and feedback clocking changes hand-back
  timing anyway. Revisit the two together.
- `UsbVolumePlayer.setDeviceMuted(false)` sets the level to 0.5 rather than restoring the
  pre-mute level. Keep the pre-mute level.
- The README's signing sentence ("a stable local key that has been in use since v0.1.0")
  predates the v3 key rotation in v0.14.0; the lineage is stable, the key is not. One
  clarifying sentence.
- `silentFrames` and `packetErrors` are counted natively and shown nowhere. Surface them in
  the signal path (or the USB DAC report) — underrun visibility is how drift gets diagnosed
  on DACs we don't own.

### What lifts the mode out of experimental

- Feedback clocking proven silent over a full album on at least one asynchronous DAC.
- Three different DACs, each verified by ear with 0 packet errors.
- The loopback bit-proof on one device.
- Both unit suites, both lints and release builds of both flavours green (the standing bar).
