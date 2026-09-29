# Bit-perfect USB output: CAMusic's own USB Audio Class driver

Status (2026-09-29): **M1–M5 done**, tested on a Galaxy S23 with a Sennheiser BTD 700 (#282, #283, #284, #286,
and the M5 PR). M6 — other DACs, UAC2 and high speed, the feedback endpoint — is what remains, and needs DACs
other than the BTD 700 to test against. It follows v0.14.1, which made Direct to DAC play at all.

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
