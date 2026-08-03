# Improvement roadmap — next-level functionality and the audiophile audience

A systematic audit of every API surface the app touches — Music Assistant
(`​/ws`), the Sendspin player protocol, and OpenSubsonic/Navidrome — against
what is implemented and what is not. Each item names the exact commands,
endpoints, or messages, and the file it touches.

> **Status, 2026-08-03.** Most of the tiers below have shipped, and a second audit
> — this time against the MA 2.9.9 commands reference, the published Sendspin spec
> and the Subsonic/Navidrome docs rather than against the code — turned up several
> things this document had wrong. Those are recorded in **Corrections** at the
> bottom; read that before trusting an item here.

## Current state at a glance

| Area | MA API | Sendspin protocol | Navidrome (OpenSubsonic) |
|---|---|---|---|
| Browse | Artists, albums, tracks, playlists + shelves (recent, recent-added, in-progress, recommendations, favorites) | — | Artists, albums, playlists, genres, starred, newest, random, frequent |
| Search | Full search + sonic text search (CLAP embeddings) + similar tracks | — | search3 + playlist name match |
| Play | Play to any player, enqueue (replace/add/next), preview | Clock-synced decode (FLAC/Opus/PCM), gapless across track boundaries | Local queue (**ExoPlayer**), gapless, ReplayGain, exact seek, shuffle, repeat, speed |
| Queue | View, jump, reorder, remove, clear, save-as-playlist, transfer | — | Local queue with full transport |
| Playlists | Create, delete, add/remove tracks | — | Create, delete, add tracks, rename |
| Lyrics | LRC-timed + plain | — | Structured (`getLyricsBySongId`) + legacy |
| Favorites | Add/remove (optimistic) | — | Star/unstar, `setRating` |
| Speakers | Group/ungroup, group volume, sync delay, rename, power, mute | `server/command` volume/mute/set_static_delay, `group/update` | — |
| Downloads | — | — | Track/album/playlist, offline playback, cached covers, Wi-Fi-only, storage cap |
| Light Sync | — | — | Drives syncoV2 via HA WebSocket |
| Notifications | Media notification for selected player | Connection service notification | Local playback notification |
| Sleep timer | Fade-out + pause (shared) | — | — |
| Scrobble | — | — | Now-playing ping + completed-play submission (incl. the play-original path) |

---

## Tier 1 — Audiophile core

### 1. 24-bit bit-perfect playback (both backends)

**Applies to MA and Navidrome.** The Sendspin protocol's `bit_depth` is a
client-advertised, server-respected parameter — the server streams whatever the
client lists in `supported_formats`. The cap is purely on the client side:

- `FormatNegotiator.MAX_BIT_DEPTH` is hardcoded to `16`.
- `SendspinAudioEngine.createTrack()` uses `ENCODING_PCM_16BIT`.
- The native AAudio I24 exclusive-mode pipeline (`app/src/main/cpp/`) is written
  but commented out in `app/build.gradle.kts`.

Enabling 24-bit:

1. Uncomment `externalNativeBuild` in `build.gradle.kts`.
2. Raise `FormatNegotiator.MAX_BIT_DEPTH` to `24`.
3. Add a setting: "Bit-perfect mode" → exclusive AAudio (no Android mixer).
4. In `SendspinAudioEngine`, route to the native engine when 24-bit is requested;
   fall back to the 16-bit `AudioTrack` path when the native stream can't be
   opened (or the device doesn't support it).
5. Show the actual device output rate alongside the stream format badge.

The native engine already requests `SharingMode::Exclusive` with a `Shared`
fallback, so the bit-perfect path degrades gracefully on devices that don't
allow exclusive access.

Files:
- `app/build.gradle.kts` (uncomment NDK)
- `audio/FormatNegotiator.kt`
- `audio/SendspinAudioEngine.kt`
- `data/AppSettings.kt` (new setting)
- `ui/screens/SettingsScreen.kt`

### 2. ReplayGain awareness

MA's DSP pipeline applies ReplayGain server-side. The app should:
- Parse and display ReplayGain tags from `provider_mappings` (MA) or
  OpenSubsonic's `replayGain` fields.
- Show whether MA is applying gain correction (off the `dsp` output format vs
  the input format).
- For the Navidrome backend, apply ReplayGain locally — Android `AudioTrack`
  can't do this natively; it requires either a DSP stage in the decode loop or
  switching the local player to ExoPlayer (which supports ReplayGain via
  `MediaProcessor`).

Files:
- `ma/MaModels.kt` (parse ReplayGain from provider_mappings)
- `audio/StreamQuality.kt` (display gain info)
- `ui/screens/NowPlayingScreen.kt` (show in quality card)

### 3. Gapless playback for the local player

`LocalPlayer` uses `MediaPlayer`, which has a ~200ms gap between tracks. For
album listening this is audible. The Sendspin player is already gapless
(continuous stream), so this only affects the Navidrome/offline path.

Options:
- Switch to `ExoPlayer` with gapless transition support (handles cross-track
  buffering natively).
- Or use two `MediaPlayer` instances with crossfade.

Files:
- `audio/LocalPlayer.kt`

### 4. USB DAC / external audio routing

Android 12+ supports `AudioDeviceInfo` routing. The app should:
- Detect and prefer USB audio devices when connected.
- Show the connected DAC's supported formats (sample rates, bit depths).
- Allow forcing output to a specific device.
- Show when the Android mixer is resampling (compare stream rate vs
  `getNativeOutputSampleRate`).

Files:
- `audio/SendspinAudioEngine.kt`
- `audio/LocalPlayer.kt`
- `data/AppSettings.kt` (device selection)
- `ui/screens/SettingsScreen.kt`

---

## Tier 2 — Features that complete the app

### 5. Playlist management (both backends)

MA (`/ws` API):
- `music/playlists/create` — create a new playlist.
- `music/playlists/add_items` — add tracks to an existing playlist.
- `music/playlists/remove_items` — remove tracks.
- `music/playlists/delete` — delete a playlist.
- `music/playlists/edit` — rename (if MA supports it).

Navidrome (OpenSubsonic):
- `createPlaylist` — create.
- `updatePlaylist` — add/remove songs, name change.
- `deletePlaylist` — delete.

Files:
- `ma/MaRepository.kt`
- `subsonic/SubsonicClient.kt`
- `ma/LibraryViewModel.kt`
- `ui/screens/PlaylistDetailScreen.kt`

### 6. Queue transfer UI ("move music to this speaker")

`MaRepository.transferQueue()` is already implemented but has no UI. The
Speakers screen should have a "Transfer playback here" button on each player
that moves the current queue + position to that speaker. This is the "tap a
speaker, music follows" feature that MA's own app has.

Files:
- `ui/screens/SpeakersScreen.kt`
- `ui/viewmodel/SpeakersViewModel.kt`

### 7. Lyrics on the Navidrome backend

OpenSubsonic has `getLyrics` (legacy) and `getLyricsBySongId` (the extension).
The `LyricsPane` currently only works with the MA backend. Add:
- `SubsonicClient.lyrics(id)` → parse into the same `MaLyrics` model.
- The local player's Now Playing should show lyrics when available.

Files:
- `subsonic/SubsonicClient.kt`
- `ui/viewmodel/NowPlayingViewModel.kt`
- `ui/screens/LyricsPane.kt`

### 8. Artist/album detail on Navidrome

`getArtistInfo2` gives biography, similar artists, and image URLs.
`getAlbumInfo2` gives last.fm notes and MusicBrainz links. The artist/album
detail screens should show this for the Navidrome backend the same way they
show MA's `getArtist`/`getAlbum` metadata.

Files:
- `subsonic/SubsonicClient.kt`
- `ui/viewmodel/ArtistDetailViewModel.kt`
- `ui/viewmodel/AlbumDetailViewModel.kt`
- `ui/screens/ArtistDetailScreen.kt`
- `ui/screens/AlbumDetailScreen.kt`

### 9. Radio mode toggle

MA's `radio_mode` on `play_media` is currently hardcoded to `false`. It should
be a user toggle — "after this queue, keep playing similar music" — because
MA's radio generation is exactly the "don't stop the music" feature but at the
queue level. Expose it as a toggle on the Now Playing screen or in player
options.

Files:
- `ma/MaRepository.kt` (parameter already exists)
- `ma/LibraryViewModel.kt`
- `ui/screens/PlayerOptionsSheet.kt`

### 10. Stream format switching (Sendspin)

`SendspinClient.sendRequestFormat()` exists but is never called. The user
should be able to switch codec/rate on the fly from Settings or Now Playing:
- Switch from FLAC to Opus (saves bandwidth on mobile).
- Switch from 48 kHz to 96 kHz (if the source is hi-res and 24-bit is enabled).
- This is a mid-stream format change — no reconnect needed.

Files:
- `protocol/SendspinClient.kt` (method exists)
- `service/Playback.kt`
- `ui/screens/SettingsScreen.kt`

---

## Tier 3 — Polish that matters

### 11. Multi-disc album grouping

`MaItem.discNumber` is parsed but not used. An album with 2+ discs should show
disc headers in the track list, grouping tracks under "Disc 1", "Disc 2", etc.
Both backends carry this field.

Files:
- `ui/screens/AlbumDetailScreen.kt`
- `ui/screens/LibraryScreen.kt`

### 12. Download management

The roadmap mentions storage cap and Wi-Fi-only, neither implemented:
- Storage cap setting (delete oldest when exceeded).
- Wi-Fi-only download setting (check `ConnectivityManager`).
- Download queue with retry.
- Download status in a dedicated Downloads screen (not just a shelf).

Files:
- `download/DownloadManager.kt`
- `data/AppSettings.kt`
- `ma/LibraryViewModel.kt`
- `ui/screens/LibraryScreen.kt`

### 13. Composer / credits

OpenSubsonic returns `composer`, `lyricist`, `genre` per track. MA returns
`artists[]` with roles. The track row and Now Playing should show
composer/credits for classical and jazz — the audiophile audience cares about
this.

Files:
- `subsonic/SubsonicClient.kt` (parse in `songItem`)
- `ma/MaModels.kt` (parse in `MaParse.item`)
- `ui/screens/NowPlayingScreen.kt`
- `ui/screens/LibraryScreen.kt`

### 14. Frequently-played shelf (Navidrome)

`getAlbumList2(type=frequent)` gives most-played albums. Add this as a shelf
alongside "Recently played" for the Navidrome backend.

Files:
- `ma/LibraryViewModel.kt`
- `subsonic/SubsonicClient.kt`

### 15. `group/update` handling

The Sendspin protocol pushes `group/update` when group playback state changes.
Currently parsed as a no-op. Handling it would give instant group state updates
without waiting for the 5-second poll, which matters for the Speakers screen.

Files:
- `protocol/SendspinClient.kt`
- `protocol/Messages.kt`
- `service/Playback.kt`

### 16. Warm reconnect (`client/goodbye` with `"restart"`)

Currently the goodbye is always `"user_request"`. Sending `"restart"` when the
app is backgrounded (not killed) gives a 30-second resume grace where MA holds
the player slot open. This means a quick app switch doesn't drop the player
from MA's speaker list.

Files:
- `protocol/SendspinClient.kt`
- `service/Playback.kt`
- `service/SendspinConnectionService.kt`
---

## Tier 4 — Planned

### 17. Light Sync for the local player (direct HA authentication)

Light Sync today drives syncoV2 over Home Assistant's WebSocket, and syncoV2
follows an **HA `media_player` entity**. That works because Music Assistant
publishes every one of its players as an entity, so pointing an entertainment area
at "Kitchen" and pressing play in this app lights the room.

The Navidrome/offline player has no such entity. It is an `ExoPlayer` inside this
process — Home Assistant cannot see it, so there is nothing for an area to follow,
and Light Sync is silently useless on the whole standalone backend. That is the gap:
the backend an audiophile is most likely to use for critical listening is the one
the lights ignore.

So Light Sync gets **two modes**, chosen by which player is making the sound:

**Mode A — through Music Assistant (what exists today).** Unchanged. The area
follows an MA player entity and HA does the driving. Keep it as the default
whenever an MA player is selected: it already works, it survives the app being
backgrounded, and it keeps a grouped multi-room setup in step because HA is
watching the same player every speaker is.

**Mode B — direct, for the local player (new).** The app authenticates to Home
Assistant itself — it already holds an HA URL and long-lived token in
`AppSettings` (`haUrl`, `haToken`) for exactly this connection — and drives the
area from its *own* playback state instead of an entity's. Available **only** when
the local player is the active one; with an MA player selected it stays on Mode A,
because two writers on one area would fight.

Design notes for whoever picks this up:

- **The mode is not a user setting.** It follows `localPlayer.active`, the same
  signal Now Playing already uses to decide which player it is showing. A toggle
  would only ever be set wrong.
- **What to send.** syncoV2's `set_options` service and its entity writes are
  already modelled in `ha/LightSyncRepository.kt`; the missing half is a play/pause
  and track-change feed that today comes from the entity. Start by writing the
  area's switch and effect directly on the local player's `playing` and `current`
  flows — the same transitions the entity would have produced.
- **Timing.** The entity path gets beat/level information from syncoV2 watching
  HA's own audio. Driving from here means either sending nothing (the area free-runs
  its effect, which is what `auto` mode already does) or feeding it from the
  decode path. Ship the first; the second wants an `AudioProcessor` tap on the
  ExoPlayer chain and belongs in its own change.
- **Hand-off.** Switching from a local track to an MA speaker mid-listen has to
  release the area cleanly, or Mode A inherits a state Mode B set and never clears.

Files:
- `ha/LightSyncRepository.kt` (a second, direct driver alongside the entity one)
- `ui/viewmodel/LightSyncViewModel.kt` (mode selection off `localPlayer.active`)
- `ui/screens/LightSyncScreen.kt` (say which mode is live, and why the other isn't)
- `audio/LocalPlayer.kt` (expose what the direct driver needs)

---

## Corrections (2026-08-03)

Found by auditing against the specs rather than the code. Where this document and
the reference disagreed, the reference won.

### The MA playlist commands in §5 do not exist

§5 lists `music/playlists/create`, `add_items`, `remove_items`, `delete` and
`edit`. None of those are Music Assistant commands. In the 2.9.9 reference they are:

| Wanted | Actual command | Parameters |
|---|---|---|
| Create | `music/playlists/create_playlist` | `name`, `media_types[]`, `provider_instance_or_domain` — note **no `_id_`** |
| Delete | `music/playlists/remove` *(admin)* | `item_id` — a **bare id**, not the `item_id`+provider pair every read takes |
| Add tracks | `music/playlists/add_playlist_tracks` | `db_playlist_id`, `uris[]` |
| Remove tracks | `music/playlists/remove_playlist_tracks` | `db_playlist_id`, `positions_to_remove[]` — **positions, not ids** |
| Rename | `music/playlists/update` *(admin)* | `item_id`, `update` (a whole Playlist), `overwrite` |

Create and delete shipped with UI against the wrong names, so the feature had never
worked against any server.

### §2 was wrong about where ReplayGain can be applied

It said Android `AudioTrack` "can't do this natively; it requires either a DSP
stage in the decode loop or switching the local player to ExoPlayer (which supports
ReplayGain via `MediaProcessor`)". There is no `MediaProcessor`, and ExoPlayer has
no built-in ReplayGain. A constant per-track gain is just a scalar on the output —
no processor and no buffer copy needed. See `audio/ReplayGain.kt`.

### §1 understated the 24-bit problem

The `MAX_BIT_DEPTH` cap was not the only thing in the way: the AudioTrack was built
from the depth the *server* claimed while the FLAC decoder was never asked for
anything but 16-bit output, so two-byte samples went into three-byte frames. Fixed
in `1bb8034` by building the track from what the decoder reports.

### Subsonic `updatePlaylist` takes a delta, not a track list

`SubsonicClient.updatePlaylist` sent `songId`, which `updatePlaylist` has no
parameter for — the call was accepted and ignored. It takes `songIdToAdd`
(repeated) and `songIndexToRemove` (repeated, an **index into the playlist**).
Repeated parameters were also being comma-joined, which hands the server one id
with commas in it.

### Not in this document at all

The second audit found these, none of which §§1–16 mention:

- `stream/end` released the AudioTrack via `flush()`, which **discards** unplayed
  PCM out of a ~1 s buffer. MA ends the stream between every track, so the last
  second of every track was being thrown away.
- Text and binary frames were handled through separate flows in separate
  coroutines, so `stream/clear` could be applied after the audio it was meant to
  discard — the ordering `protocol-alignment.md:88-89` already required.
- `static_delay_ms` had no writer anywhere: the sync trim did nothing locally.
- `client/state` always reported `"synchronized"`, so the player joined groups
  before its clock had converged. The spec wants `"error"` plus a mute until then.
- `ACTION_AUDIO_BECOMING_NOISY` was handled on neither path.
- A dropped MA socket resolved pending requests to `null`, making a failed load
  indistinguishable from an empty library.
- The "play at original quality" path never scrobbled.

### Still open

- **AGP / compileSdk.** media3 is pinned to 1.8.0 because 1.9+ needs compileSdk 36
  and AGP 8.7.3 tops out at 35.
- **The native AAudio path** (`app/src/main/cpp/`) remains written, unbuilt and
  uncalled. True bit-perfect exclusive-mode output is its own piece of work.
- **Device verification** of everything in the audio path.
