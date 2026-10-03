# Multi-room with Music Assistant

CAMusic as a Sendspin player on Music Assistant, and AirPlay output for what the phone plays itself. [Back to the README](../README.md).

Music Assistant is one of the [libraries](libraries.md), and it brings **speaker grouping across the house**.

**As a controller.** Group and ungroup speakers, set per-player sync offsets, drive the queue,
and reach MA's gapless, crossfade and DSP settings from here rather than from its web UI. The
library front page is built from MA's own Discover rows, so a row your providers add appears as soon as Music Assistant offers it.

**As a speaker.** CAMusic registers itself as a Sendspin player, so MA can stream to this phone
like any other speaker, including inside a synced group.

- **FLAC, Opus and PCM**, decoded by `MediaCodec` and played through a native Oboe output engine
  with its own timeline.
- **Clock sync** by a two-state Kalman filter over an NTP-style four-point exchange, with drift
  correction in native code. The offset is persisted, so a reconnect starts warm.
- **Announcements** (Home Assistant TTS, doorbells, timers) reach the phone in the background
  and duck whatever is playing.
- **A signed latency trim** on the Music Assistant server's page in Settings → Libraries, for when this phone's output path runs ahead
  of or behind the rest of the room.
- **Play at original quality** streams the untouched file straight from your library server when
  MA would otherwise have transcoded it for the phone.
- **The progress bar follows the official Music Assistant app's model**: server readings anchor
  it, a local projection ticks between them, and a seek or skip on this phone holds the bar
  until the new stream's first chunk arrives. It also covers the one case MA misses: on a track short enough to send in one burst, MA's own clock stays stopped, and the bar keeps ticking.

## AirPlay

Whatever this phone decodes itself (a Navidrome, Jellyfin, Emby or Plex library, files on the phone, a streaming account) can be sent to an **AirPlay** receiver: an Apple TV, a HomePod, an
AirPlay speaker. The control sits in the top-left of Now Playing; tap it for the receivers on
the network, enter the PIN an Apple TV shows the first time, and the phone remembers the
pairing. The phone goes quiet while the receiver plays, the volume slider follows it, and the
receiver's own Now Playing shows the track. The sender is `airplay2-sender-cpp` (AirPlay 2 and
legacy RAOP), vendored and built as part of the app. A Music Assistant queue plays to MA's own speakers, and AirPlay output applies to what the phone decodes itself.
