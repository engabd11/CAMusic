# Library providers

CAMusic browses and plays from two kinds of thing:

- **Music Assistant**, which owns a server-side queue and plays to speakers this app
  never decodes for. It is a mode, not a provider, and does not implement
  `MusicSource`.
- **Everything else** — a library the *phone itself* plays. Navidrome, Subsonic,
  Jellyfin, Emby and Plex today; Audiobookshelf, Kodi, a WebDAV share and the cloud
  drives next. These are all the same shape: list artists and albums, answer a
  search, hand out a URL ExoPlayer can open.

That second shape is `library/MusicSource.kt`. Before it existed, Navidrome was a
`SubsonicClient?` field with `when (backend)` around forty call sites, and a third
server could not be added without touching all of them.

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
| **Subsonic-compatible** | Same | Gonic, Airsonic, Astiga, Ampache's Subsonic API. Same client; capabilities probed via `getOpenSubsonicExtensions`, so an older server loses lyrics rather than offering a pane it can't fill. |
| **Jellyfin** | `POST /Users/AuthenticateByName`, `Authorization: MediaBrowser …` | Token and user id both persisted — the user id is in the *path* of most endpoints. `MediaSources[].MediaStreams[]` carries a complete format reading. |
| **Emby** | `POST /Users/AuthenticateByName`, `X-Emby-Authorization` header | Jellyfin's ancestor: near-identical `/Items` DTOs, but no `/universal` negotiator — a transcode names its container in the path, `/Audio/{id}/stream.mp3`, instead. |
| **Plex** | plex.tv PIN flow (`PlexAuth`) → `X-Plex-Token`; no server password is ever typed | `/library/sections` → `/library/sections/{id}/all?type=8\|9\|10` (artist/album/track), read from whichever of `Metadata`/`Directory` the response used. Streaming needs the track's own metadata first — see `PlexClient`'s class doc — so `item()` caches each track's `Media[].Part[].key` and cover path as it's parsed. No favourites, playlist writes, lyrics or rich format reading; see `PlexSource`'s class doc for why each is left out rather than faked. |

Plex was the odd one out: its PIN flow needs a browser round-trip and a polled
`/pins/{id}` rather than a password ever touching this app. `AuthStyle.LINKED_ACCOUNT`
exists for exactly that, and `LibrariesSettings.PlexSignInRow` is the "Sign in" button
it needed instead of credential fields — it mints a PIN via `PlexAuth.requestPin`,
opens `PlexAuth.authUrl` in the browser, and polls `PlexAuth.pollPin` every couple of
seconds until plex.tv hands back a token or the user gives up.

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
