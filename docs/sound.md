# Sound and lyrics

How CAMusic plays, from the equaliser to a USB DAC, and where synced lyrics come from. [Back to the README](../README.md).

## Sound

Playback is built to be worth good headphones and a good DAC.

- **A ten band equaliser** for everything this phone plays, built on RBJ biquads in a
  zero latency cascade, with automatic headroom so a boosted band stays clean on loud
  masters. Music Assistant's own parametric DSP is exposed separately for the rooms it runs.
- **The real signal path**, reported a stage at a time: what the file declares, what the
  decoder handed over, what the sink was configured with, and whether the high resolution
  float path is engaged. Where resolution is being lost, the card says so in a sentence.
- **High resolution output** and a quality badge that reads `FLAC • 96/24 • 3 Mb/s` with a
  detail card behind it.
- **ReplayGain that can lift as well as cut** (track or album). A quiet master is raised by up to +6 dB through a limiter, so peaks stay clean, and untagged files can be turned down 3, 6 or 9 dB to sit beside the levelled ones.
- **Gapless playback**, and on any queue (DJ Radio included) a **real overlap crossfade**
  where the next song starts under the last one, with beat matched fades that align to the beat
  grid of both tracks when scan data is available.
- **Shuffle that spreads things out**: artists are kept apart, and an artist's albums take turns (Settings → Audio → Between tracks).
- **A sleep timer that lives with the player** rather than the screen: it keeps counting with the app closed, fades the player's own volume instead of the phone's (so the phone's volume stays where you set it), and offers **End of this song**.
- **Playback that survives the real world.** A dropped connection pauses and picks up where it
  was when the network returns, instead of skipping through the whole queue; the queue is
  saved, so Bluetooth play after a reboot, the system's resume card and Android Auto's
  "recent" all carry on where you left off.
- **Sound modes** recolour whatever is playing, ahead of the equaliser. Vinyl decorrelates
  its crackle, pop and rumble left from right and adds a band-limited surface hiss. Lo-fi
  applies a persistent vari-speed slowdown, speed and pitch together, with wow-and-flutter
  modulation on top. Old Radio band-limits to telephone range with light saturation, AM
  static bursts shaped by the same "speaker", and a slow carrier warble.
- **Three output modes**: Standard, High resolution and USB bit-perfect. They're compared in
  the table under [Output modes](#output-modes) below.
- **USB bit-perfect** (experimental). On many phones Android runs a USB DAC at a fixed
  48 kHz / 16-bit and converts every file to that. In this mode CAMusic drives the DAC with
  **its own USB audio driver**, bypassing Android's audio stack entirely, and sends **the
  file's own samples at the file's own rate and bit depth**. A 16/44.1 album reaches the DAC
  as 16/44.1, and a 96/24 file as 96/24.
  - **Volume**: the volume keys and slider drive the DAC's own hardware volume, so the
    samples stay untouched. Digital volume for a DAC that lacks its own is an opt-in.
  - **Over-rate tracks**: a track above the DAC's highest rate (192 kHz on a 96 kHz DAC) is
    converted by CAMusic at an exact ratio and still goes out on its own driver, labelled
    as converted.
  - **Signal path**: the Output & signal path page shows what the DAC itself confirmed.
  - **Sharing the DAC**: CAMusic takes the DAC while playing and hands it back to Android
    after 30 s paused. Unplugging pauses playback, like unplugging headphones.
  - **Tested on**: a Galaxy S23 with a Sennheiser BTD 700. The Diagnostics page can read any
    USB DAC's own description of itself and play a test tone through the driver.
- **USB DAC awareness**: connect one and CAMusic tells you what it can do and offers to pin
  the output to it.
- **DJ Radio**: one button on the library's front page, and the room has a set on. Tapping it
  opens six songs picked for maximum spread across your library, each tagged with the brief it fits (Morning, Focus, Workout, Party, Sundown, Before sleep or Surprise me), so a set starts
  from a deliberate corner of the library rather than a guess. From there it picks what plays
  next by how the music actually sounds as well as what it is filed under: the genre tags
  your library carries, plus the energy, tempo and spectral balance the offline scan already
  measured, folded at half and double time the way a DJ hears it. Tracks **overlap** instead of fading out: the outgoing one keeps playing on a second deck while the next comes up under it, so one song flows into the next. **Smart fade** plans each join
  off the scan rather than the clock: it leaves where the music actually stops rather than
  where the file does, starts the overlap on a downbeat, sizes it in whole bars, and skips any
  dead air at the front of the next track. Fade mode, crossfade length and how close the next
  track has to be are all yours to set; Harmonic DJ mode adds key matching on top.
- **Keep the music going** on every library the phone plays itself, and the shuffle button
  decides what "going" means: shuffled, the next track is a random song from the library;
  unshuffled, it is the next track on the record, then the next record, and so on. Plus
  favourites, a version picker that lists every copy of a track across every provider, and
  cross-device resume. Where a server lacks its own "more like this", an on-device sonic index supplies one, matching local tracks on the same tempo, key, energy and spectral
  shape the offline scan already measured.

### Output modes

Settings › Audio Engine & DSP › Output & signal path. Each mode takes more out of the way
between the decoder and the DAC.

| | Standard | High resolution | USB bit-perfect |
|---|---|---|---|
| **How it plays** | Through Android's audio | Through Android's audio | CAMusic's own USB driver, bypassing Android's audio entirely |
| **Sample rate at the output** | Whatever Android's mixer runs at (often a fixed 48 kHz) | Whatever Android's mixer runs at | **The file's own rate**, confirmed by the DAC |
| **Bit depth** | 16-bit | Up to what the decoder produced (24-bit and more) | **The file's own depth** where the DAC has it |
| **Samples reaching the DAC** | Processed and resampled | Resampled by Android if the rates differ | **Unchanged** (bit-perfect) |
| **Equaliser, sound modes, ReplayGain** | On | Off whenever the float path is in use | Off |
| **Light Sync from the audio** | Yes | Off whenever the float path is in use | Off |
| **System sound effects** (e.g. Samsung Dolby Atmos) | Applied | Applied | Bypassed |
| **Volume** | Android's media volume | Android's media volume | The DAC's own hardware volume (digital only if you opt in) |
| **Other apps and calls** | Share the output as usual | Share the output as usual | The DAC belongs to CAMusic while it plays, and goes back to Android after 30 s paused |
| **Needs** | Any output | Any output; best with a 24-bit-capable DAC or LDAC | A USB DAC and one USB permission prompt |
| **Best for** | Everyday listening with the equaliser and Light Sync | Bluetooth LDAC and hi-res files through Android | The most faithful playback from a USB DAC |

In USB bit-perfect, a track above the DAC's highest rate (192 kHz on a 96 kHz DAC) is converted by CAMusic at an exact ratio (192 → 96 kHz) rather than handed to Android. The signal path labels that track "not bit-perfect: converted". Pure and Direct to DAC, the older Android-based rungs, have been retired from the picker and keep working for anyone who had selected them.

## Lyrics

Synced lyrics come from wherever they are: the server's own, lyrics embedded in the file, or a
sidecar `.lrc` beside it, for local files and downloads too. **Find lyrics online**
(Settings → Audio → Playback behaviour, off by default) asks [LRCLIB](https://lrclib.net)
when those come up empty, and sends just the artist and title. Tap a line to seek to it, and
nudge the timing per song when a file's lyrics run early or late; the nudge is remembered for
that song.
