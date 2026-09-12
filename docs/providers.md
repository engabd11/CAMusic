# Library providers

CAMusic browses and plays from two kinds of thing:

- **Music Assistant**, which owns a server-side queue and plays to speakers this app
  never decodes for. It is a mode, not a provider, and does not implement
  `MusicSource`.
- **Everything else** — a library the *phone itself* plays. Navidrome, Subsonic,
  Jellyfin, Emby and Plex today; Audiobookshelf, Kodi, a WebDAV share and the
  cloud drives next.
  These are all the same shape: list artists and albums, answer a search, hand out
  a URL ExoPlayer can open.
- **MPD**, which is a `MusicSource` for browsing and neither of the above for
  playing: it plays its own music and the phone drives it. See below.

That second shape is `library/MusicSource.kt`. Before it existed, Navidrome was a
`SubsonicClient?` field with `when (backend)` around forty call sites, and a third
server could not be added without touching all of them.

---

## A provider that plays its own music

MPD is the exception the interface had to grow a seam for, and it is worth knowing
why before adding anything shaped like it (Kodi will be, and so is any UPnP
renderer).

MPD is not a library with a stream endpoint — it is a *player* with a library
attached. Its only way to send audio somewhere else is the `httpd` output, which
is one live stream of whatever it is currently playing: no duration, no position,
no seek, and the same URL for every track. The first cut of this provider streamed
that, and everything downstream of the decision was wrong. The music came out of
the DAC MPD was configured for *and* out of the phone, a second apart. The scrub
bar had nothing to scrub. Pause paused the phone while MPD played on.

moOde, Volumio, piCorePlayer and Mopidy are all MPD underneath, which makes this provider
theirs too: point CAMusic at the box's 6600 and it browses whatever those front ends have
already scanned, with the transport living where the DAC lives.

So the transport goes the other way:

- `MusicSource.remotePlayback()` returns a `RemotePlayback` for a source that
  plays itself, and null — the default — for every other one.
- `LocalPlayer.remote` holds it. Every transport method delegates to it instead of
  to ExoPlayer, and the state flows are filled by polling it once a second rather
  than from ExoPlayer's callbacks. Nothing above `LocalPlayer` changes: Now
  Playing, the queue sheet, the mini bar and scrobbling all read the same flows
  they always did.
- `MusicSource.serverQueue()` / `serverQueueIndex()` let the app adopt what the
  player is already doing when it connects, since MPD does not stop because a
  phone was closed.

What follows from it, and is worth copying rather than rediscovering:

- **The app's queue and the player's queue are one list.** Every command addresses
  a track by index, so a queue edit is sent as the same edit on both sides.
- **Settings that act on this phone's output are meaningless.** The volume slider,
  playback speed, the sound modes and the app's own ReplayGain all operate on a
  signal that isn't here. Hand what the player supports to the player (MPD has
  `replay_gain_mode`, and the Now Playing options sheet drives it), and hide the
  rest rather than leaving a control that silently does nothing.
- **Cover art need not be a URL.** MPD's is binary down the protocol socket, so
  the items carry an `mpd-art://` URL and a Coil fetcher resolves it. Everything
  in between goes on treating art as a string.

---

## Adding a provider

Four steps. Jellyfin is the worked example — read `JellyfinClient.kt` and
`JellyfinSource.kt` alongside this.

### 1. Register the kind

Add a constant to `ServerKind` in `library/ServerConfig.kt` with `supported = true`,
a one-line `blurb`, a `urlHint` and the right `AuthStyle`. It is probably already
there with `supported = false` — the unbuilt providers are listed on purpose so the
Settings picker shows where this is going rather than pretending nothing else exists.

### 2. Write the client

A plain class in its own package (`jellyfin/`, `plex/`, …). It owes nothing to the
interface — it is the provider's API, spoken well. Conventions worth matching, all
of them learned from `SubsonicClient`:

- **Return `MaItem`.** It is the app's universal item model and every screen already
  renders it. A provider-specific model would have to be converted somewhere, and
  that somewhere would be worse than here.
- **Fill in `MaItem.audioFormat`** wherever the server reports codec / sample rate /
  bit depth / bit rate. That is what the quality badge reads. Convert bits per second
  to kbps; a raw `3011000` renders as `3011000k`.
- **Guess the scheme from the address.** A LAN box is almost never on TLS and
  defaulting it to `https` just fails to connect; a public hostname almost always is,
  and defaulting *that* to `http` sends credentials in the clear. Copy
  `SubsonicClient.base()`.
- **Share one `OkHttpClient`.** A `companion object` `by lazy`, with
  `LanOnlyCleartext`. Per-instance clients mean a connection pool per view model.
- **One exception type**, carrying enough to tell a rejected login from an unreachable
  host. That distinction is the only thing the library branches on.
- **Report 0, not a guess.** A server that doesn't send a channel count has not said
  "stereo". Absent and default are different answers and the badge can show the
  difference.

### 3. Write the source

A delegating `MusicSource` implementation. Most of it is one-liners. The two things
that need thought:

- **`capabilities`.** Be honest. A shelf that is always empty, a lyrics pane that
  never finds any, a star that silently does nothing — those are worse than the
  feature being absent, because a user cannot tell a missing capability from a broken
  one. `SubsonicSource` *probes* rather than declares, because OpenSubsonic
  capabilities vary per server.
- **The optional half of the interface has defaults** returning empty or null. Only
  override what the server can actually do.

### 4. Wire it up

Add the `when` branch in `MusicSources.create`, and — if the provider needs a
sign-in or a library choice before it can answer anything — a branch in
`MusicSources.prepare`. That is the whole integration; nothing above it changes.

Add a URL-building and item-parsing test under `app/src/test/`, modelled on
`subsonic/SubsonicUrlTest.kt`. Those two are where provider bugs actually live and
they need no device.

---

## The providers, and what each one needs

### Built

| Provider | Auth | Notes |
|---|---|---|
| **Navidrome** | Subsonic token (`t=md5(password+salt)`) | The reference implementation. OpenSubsonic extensions give lyrics, ReplayGain and full format data. |
| **Subsonic-compatible** | Same | Gonic, Airsonic, Astiga, Ampache, Funkwhale, epoupon's LMS. Same client; capabilities probed via `getOpenSubsonicExtensions`, so an older server loses lyrics rather than offering a pane it can't fill. Ampache ships with the Subsonic backend disabled (Admin → Server Config) and authenticates with the Subsonic password from the account page, not the web login; Funkwhale issues its own Subsonic password rather than accepting the account one — both quirks are named on the connect form. |
| **Jellyfin** | `POST /Users/AuthenticateByName`, `Authorization: MediaBrowser …` | Token and user id both persisted — the user id is in the *path* of most endpoints. `MediaSources[].MediaStreams[]` carries a complete format reading. |
| **Emby** | `POST /Users/AuthenticateByName`, `X-Emby-Authorization` header | Jellyfin's ancestor: near-identical `/Items` DTOs, but no `/universal` negotiator — a transcode names its container in the path, `/Audio/{id}/stream.mp3`, instead. |
| **Plex** | plex.tv PIN flow (`PlexAuth`) → `X-Plex-Token`; no server password is ever typed | `/library/sections` → `/library/sections/{id}/all?type=8\|9\|10` (artist/album/track), read from whichever of `Metadata`/`Directory` the response used. Streaming needs the track's own metadata first — see `PlexClient`'s class doc — so `item()` caches each track's `Media[].Part[].key` and cover path as it's parsed. No favourites, playlist writes, lyrics or rich format reading; see `PlexSource`'s class doc for why each is left out rather than faked. |
| **MPD** | Optional password (`OPTIONAL_USER_PASSWORD`) | Plays its own music to its own outputs; this phone drives the transport. See `MpdRemote` and `RemotePlayback`. |
| **foobar2000** | Optional basic auth (`OPTIONAL_USER_PASSWORD`) | Desktop player with the Beefweb plugin (`foo_beefweb`), which exposes a REST API on port 8880. Same shape as MPD: plays its own audio to its own output, and this phone drives the transport. Library browsing via the file system browser (`GET /api/browser/entries`); no search API. Artwork over HTTP (`GET /api/artwork/{plId}/{index}`). See `FoobarClient`, `FoobarRemote`, `FoobarSource`. |

Plex was the odd one out: its PIN flow needs a browser round-trip and a polled
`/pins/{id}` rather than a password ever touching this app. `AuthStyle.LINKED_ACCOUNT`
exists for exactly that, and `LibrariesSettings.PlexSignInRow` is the "Sign in" button
it needed instead of credential fields — it mints a PIN via `PlexAuth.requestPin`,
opens `PlexAuth.authUrl` in the browser, and polls `PlexAuth.pollPin` every couple of
seconds until plex.tv hands back a token or the user gives up.

### Loudness levelling, which is different on every one of them

There is no shared ReplayGain. Each provider either measured the music or it didn't,
and where it did it says so in its own way — so `Capability.REPLAY_GAIN` is what the
Now Playing options sheet asks before it offers the control at all, and a library with
no measurement behind it gets no switch rather than one that silently levels nothing.

| Provider | What it has | Where the correction happens |
|---|---|---|
| **Navidrome / OpenSubsonic** | the `replayGain` object (`trackGain`, `albumGain`) on every song, gated behind the OpenSubsonic extensions | this phone — `ReplayGain.factor` is a scalar on the player's volume |
| **Jellyfin** | its own loudness scan, published as `NormalizationGain` and `AlbumNormalizationGain` (older builds: a bare `LUFS`, corrected against −18) | this phone, through the same scalar |
| **MPD** | `replay_gain_mode`, a server-side setting | MPD's own mixer — no audio passes through the phone at all |
| **Music Assistant** | per-player volume normalization: LUFS measured at ingest, corrected to a target while it streams | MA's own pipeline; the sheet renders whatever `volume_normalization*` config entries the server declares |
| **Downloads** | whatever the file's library measured, stored with it | this phone |
| **Emby** | nothing — normalization is still an open feature request there | — |
| **Plex** | loudness analysis exists, but stays inside Plexamp's sonic-analysis store rather than on readable metadata | — |

The two absences are the point of the capability: Emby's DTOs are otherwise
Jellyfin's, so a source that declared this because the shapes match would put a dead
control on screen for every Emby user there is.

### Experimental — streaming accounts

**Spotify, Qobuz and Tidal** are `supported` and `experimental` in `ServerKind` — two
independent flags. `supported` says a source can be built from the kind, so they are
addable, in the first-run wizard and in Settings → Libraries under an "Experimental"
heading; `experimental` says how settled they are, which is the honest label for adapters
built on wire formats their providers never published. They are a different shape from
everything above: an **account, not a server** — `ServerKind.cloudAccount` gives them a
setup form with no address field, and `ServerKind.addableStable` / `addableExperimental`
are what the picker's two lists render (every addable kind is in exactly one of them, so
a working source cannot go unreachable again).

Per-provider design is in `docs/plan/direct-streaming-providers.md`: Qobuz via its
undocumented web-app endpoints (plain FLAC URLs), Spotify via an embedded librespot
client (real PCM in-process, Light Sync on its own tap), Tidal via device sign-in and its
v1 API with BTS manifest playback. Live-account smoke tests are still outstanding.

Qobuz and Tidal need the *calling application's* own credentials alongside the user's
account. `ProviderAppCredentials` resolves them: the config's own options first, then
whatever `BuildConfig` was given from gradle properties. A build with no pair is not
broken — the connect form asks for one. No brand marks in the picker while experimental —
a real Spotify logo reads as "as supported as Navidrome". YouTube Music is deliberately
absent: OAuth withdrawn, cookie + PO-token machinery, playback capture blocked on
Android.

### Planned — HTTP APIs

These are the same shape as what exists and should be adapters, not projects.

| Provider | Auth | Browse | Stream |
|---|---|---|---|
| **Audiobookshelf** | `POST /login` → bearer token | `/api/libraries`, `/api/libraries/{id}/items` | `/api/items/{id}/file/{ino}` |
| **Kodi** | HTTP basic, JSON-RPC not REST | `AudioLibrary.GetArtists` / `GetAlbums` / `GetSongs` over `POST /jsonrpc` | `/vfs/{encoded path}` |

### Planned — filesystems

**A different class of work, and worth saying so before anyone starts.** SMB, WebDAV
and the five cloud drives are not APIs that answer "list the artists" — they are
folders. Every one of them needs:

1. A **transport** (jcifs-ng for SMB; OkHttp with `PROPFIND` for WebDAV; each drive's
   own SDK or REST API for the clouds).
2. A **crawler** that walks the tree and finds audio files.
3. A **tag reader** — the app has no metadata extractor today. `MediaMetadataRetriever`
   handles the common cases on-device; anything better means a library.
4. A **local index**, because re-crawling a NAS to draw the album grid is not viable.
   Room, or the same JSON-index approach `DownloadManager` uses.

Steps 3 and 4 are shared by all of them, which is the argument for building one
`IndexedFileSource` that the seven transports plug into rather than seven adapters.

| Provider | Transport | Auth |
|---|---|---|
| **SMB (v2/v3)** | jcifs-ng | Username / password / domain |
| **WebDAV** | OkHttp `PROPFIND` | Basic or bearer |
| **Google Drive** | Drive REST v3 | OAuth |
| **OneDrive** | Microsoft Graph | OAuth |
| **Dropbox** | Dropbox HTTP API | OAuth |
| **Box** | Box API | OAuth |
| **pCloud** | pCloud API | OAuth |
| **This device** | `MediaStore.Audio` | None — a runtime permission |

`MediaStore` is the cheapest of these by far: Android has already crawled and tagged
the phone's own music, so it needs no crawler, no tag reader and no index. It is the
right one to build first, and it is what proves the `IndexedFileSource` shape before
any OAuth is written.

---

## Icons

Every `ServerKind` has a glyph — `ui/design/ServerKindIcon.kt` — used in the provider
picker, each server's own card, and the Library screen's top-line badge alike, so a
server reads as the same thing wherever it shows up.

Where a real brand mark exists it's used: `res/drawable-nodpi/ic_logo_*.png`, sourced
from [dashboard-icons](https://github.com/walkxcode/dashboard-icons) (CC0 — icons
built for exactly this, naming a self-hosted service in someone else's UI). Everything
else falls back to a generic Material glyph. Adding a real mark for a kind that
doesn't have one: drop a same-named PNG in that folder and add its line to
`serverKindLogoRes`; there's nothing else to wire up.

---

## What is deliberately not abstracted

**Playback routing.** `MusicSource.streamUrl` says where the bytes are; whether they
go to this phone's ExoPlayer or to a Music Assistant speaker is decided by
`ServerKind.playsLocally`, not by the source. Pushing playback behind the interface
would mean every method carrying an MA-shaped exception.

**Navidrome-specific reach-through.** Two features go to Navidrome *regardless of the
active library*: every download comes from it, and "play at original quality" streams
from it while Music Assistant is the library. `LibraryViewModel.navidromeClient()` is
that path, and it is deliberately separate from the active `source` — conflating the
two is how asking "is Navidrome reachable" used to overwrite the library you were
browsing.
