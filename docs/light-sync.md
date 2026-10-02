# Light Sync and ambience

The Philips Hue light show and the ambience effects. [Back to the README](../README.md).

## Light Sync

Two paths, chosen in Settings.

### Straight to the bridge

The one to use. CAMusic opens its own DTLS entertainment stream to the Hue bridge and renders
**60 frames a second** from the audio it is decoding.

- **Real spatial awareness.** Lamp positions come from the entertainment area, so waves travel
  across the room, bass sits low, and the room's own shape (a line, a ring, a cluster) changes
  how effects are drawn.
- **Colour from the album art**, extracted for a *room* rather than for a UI accent, and
  weighted so each colour holds for its share of the sleeve. Long-press Now Playing to
  **hand-correct** what the extractor got wrong in a 2 to 6 swatch editor, and the room uses your
  palette from the very next frame.
- **Five intensity rungs** plus Auto, which picks per track.
- **Rhythm Lights**, a tap-along game on the Light Sync screen: four falling lanes (kick, snare, hat and melody) charted from the track's own beat grid rather than lagging a beat
  behind it. A well-timed hit keeps the show intact: it **gates** the lights instead of replacing them with a coloured flash. The room runs exactly the light show that moment of music would always have produced, held down to a floor until a hit opens the gate, and a long combo turns a string of hits into a nearly continuous show.
- **Saved shows.** A dinner, a party and a film score each want their own look. Each show stores intensity,
  palette, brightness and every feature toggle as one preset. Tap a chip to apply it, and tie a
  show to a genre so the room picks it up on its own. **Share a show** as a `camusic://show/…`
  link through any chat: it opens in the app (or paste it on the Lights tab) as a new show
  beside the listener's own, carrying just the look, while the sender's bridge and room stay private.
- **Self-healing.** When the stream drops, it comes back on its own: when the network returns, when the bridge re-announces itself (even at a new IP address) and when the app comes to the front, instead of waiting for a settings change.
- **Easy on the battery.** Sixty frames a second while music plays; paused or idle, it falls to
  keepalive frames and lets go of the wake and Wi-Fi locks after a grace period.
- **Creative layers**: Music DNA, Emotional Arc, Phantom Stage with on-device stem separation,
  and Phone as Conductor. See [docs/creative-light-shows.md](creative-light-shows.md).
- **Flash safety** on a WCAG derived budget, with a 12.5 Hz per channel ceiling that matches
  what the Zigbee relay can carry.
- **Lights from other apps** through MediaProjection capture, so a video or another player can
  drive the room too.

### Through Home Assistant

A syncoV2 bridge for anyone already running that setup, with a smaller effect set.

### When another speaker makes the sound

When the sound comes from a speaker in another room, the show keeps running from an **offline scan** of the track, with beats, sections and spectral shape worked
out in advance and followed against the server's playhead.

## Ambience Effects

Shows with their own sound, ready for the times you want the room rather than the music: **fireworks, thunderstorm,
underwater, fireplace, light train and aurora**, each with a bundled recording.

**The room reacts to the recording.** The same analysis tap Light Sync uses for music runs
on the effect's own player, so the lights are driven by the sound actually reaching the
speaker rather than by a script running alongside it. A thunderclap flashes the room as it
lands, from the side of the field it came from, and how *far away* it reads is taken from the clap's own timbre: air strips the treble out of a distant strike, so one that arrives with a
crack left in it lights the room white and one that is all rumble washes it dim blue. The roll
that follows swells the room for as long as it is audible; the rain's own gusts set the
shimmer between strikes. Fireworks work the same way, a burst to a bang.

Timing is measured rather than tuned. The tap knows the exact position in the file of every
frame it analyses and the sink knows the position being heard, so an event is stamped where its sound is, and the lights are rendered a Hue pipeline ahead of the ear. The show stays in time automatically.

Any effect can take an audio file of your own as its bed, and the lights follow that too.
When a recording is unavailable, the effect falls back to synthesising its own sound and scripting its own events on that synth's playhead.

## Photosensitivity

Light Sync produces fast-changing light effects. These may trigger seizures in people with photosensitive epilepsy, including those with no history of it. Intense and Extreme flash hardest.

Subtle, Medium and High pass through a strict flash limiter built around WCAG 2.3.1 (a budget of 3 whole-room flashes per second). Intense relaxes the limiter (8 per second) and Extreme bypasses it. Intense and Extreme are not suitable for anyone with photosensitivity, so stay on the strict levels if anyone in the room may be affected.
