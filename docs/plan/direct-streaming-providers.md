# CAMusic — Direct Streaming Providers (Spotify, Qobuz, Tidal)

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** Add three direct streaming sources so users **without** Music Assistant can browse
their Spotify, Qobuz and Tidal libraries, play their media in CAMusic's own engine, and get
Hue light sync with zero extra wiring. Music Assistant stays the app's second mode and is
not required by any of this.

**Architecture:** Each provider is a new `MusicSource` plugged into the existing
`ServerKind`/`ServerConfig`/`MusicSources` plumbing — the same seam Subsonic, Jellyfin,
Plex, Emby, MPD, Local and Downloads already use. No call sites change; `ServerKind.playsLocally`
already routes everything non-MA into the local playback path, and Light Sync reads whatever
the engine plays through the existing taps. Spotify embeds librespot (route B, chosen over
App Remote + `PlaybackCapture`): its audio is decoded in-process, so it feeds the analysis
tap natively and needs no capture consent and no Spotify app installed.

**Tech Stack:** librespot-java `lib` + Android sink modules (Spotify), Qobuz web-app endpoints
(undocumented API), Tidal reverse-engineered API + device OAuth, existing `MusicSource`/
`MaItem`/`MaSearchResults` contracts, kotlin.test on JUnit4, fixed-seed determinism where
anything random appears.

---

## How to use this plan

- Phases are ordered by risk-adjusted value: Qobuz first (simplest client, plain FLAC URLs),
  Spotify second (the marquee provider, but the librespot embed has the most moving parts),
  Tidal last (most brittle API).
- Within each phase, tasks are bite-sized (2–15 minutes of focused work).
- File paths are relative to `app/src/main/java/com/engabd/sendpin/` unless stated.
- Build/test loop (Gradle 9.7.1, JDK 17 — set JAVA_HOME):

  ```bash
  cd "C:/Users/Abdullah/test 2/CAMusic" && JAVA_HOME="C:/Users/Abdullah/AppData/Local/Temp/jdk17/jdk-17.0.20.1+1" ./gradlew :app:testMobileDebugUnitTest --tests "com.engabd.sendpin.library.qobuz.SomeTest"
  ```

- `--tests "com.engabd.sendpin.library.*"` is the regression gate before pushing.
- RED→GREEN per task per the camusic-development skill: failing test first, confirm it
  fails for the missing feature, implement minimally, re-run, conventional commit, push
  immediately (PR built up incrementally).

---

## Provider reality check (read before coding)

| | Official 3rd-party API? | Auth | Audio | Risk |
|---|---|---|---|---|
| Spotify | No streaming API for apps like this; Web API is dev-mode (5 allowlisted users, owner needs Premium) — we do NOT use it | librespot credentials (Premium account required) | librespot decodes Ogg Vorbis → PCM in-process | librespot is an unofficial client; Spotify periodically breaks it; ToS gray zone — same zone MA operates in |
| Qobuz | No. Community clients use the web app's undocumented endpoints | Email + password (or token) | Plain FLAC stream URLs, up to 24/192 | Undocumented API can change; MA has kept it stable for years |
| Tidal | No. Reverse-engineered API + device OAuth | Device-auth flow | AAC 320 standard; HiRes FLAC needs harder auth — **defer HiRes to v2** | Most brittle of the three |

**Not in scope, deliberately:** YouTube Music (no viable path — Google removed OAuth, requires
cookies + PO-token server, and blocks playback capture on Android; MA's own docs call it
best-effort), and no DRM circumvention anywhere.

---

## Phase 0 — Shared plumbing (no network code)

**Status: LANDED** (on `feat/direct-streaming-providers`) with two deviations from the
original shape below, both for the better:

- The three kinds shipped **`experimental = true`, `supported = false`** while their
  sources were being built — greyed in the picker's own "Experimental" section, never
  addable. Now that all three sources have landed they are **`supported = true`,
  `experimental = true`**: the flags are independent, so `supported` opens the source and
  `experimental` keeps the honest label on the row. Leaving `supported = false` after the
  adapters landed was what made ~3,300 lines of working code unreachable from the UI —
  `addableStable` / `addableExperimental` and a test that they partition `addable` exist
  so that cannot recur quietly. No skeleton `MusicSource` implementations were registered:
  `MusicSources.create`'s existing `else -> null` already answers an unbuilt kind, and a
  dead skeleton would only have to be replaced wholesale in its phase anyway.
- "Needs an address" turned out to be a *derived* question, not an `AuthStyle` one:
  `ServerKind` gained `cloudAccount` (account, not server — Plex is LINKED_ACCOUNT *and*
  has a host, so auth style alone never separated them), with `hasAddress = !cloudAccount &&
  auth != NONE`, `needsCredentials`, and `needsAddress` rebuilt on top. The settings form's
  address field and the connect gating both key off these, so a cloud kind's form is
  credentials-only by construction. Brand marks are deliberately **not** wired while
  experimental (a Spotify-green logo on a greyed row reads as "supported").

### Task 0.1: `ServerKind` entries + picker rows ✅

**Objective:** `SPOTIFY`, `QOBUZ`, `TIDAL` appear in the server picker with honest blurbs.

- `library/ServerConfig.kt`: add the three enum entries.
  - `SPOTIFY("Spotify", "Your Premium account, played by this device via an embedded Spotify client. Light sync included.", auth = USER_PASSWORD)` — urlHint empty (no address field; hide it for address-less kinds or use a placeholder explaining it's unused).
  - `QOBUZ("Qobuz", "Streaming in FLAC up to 24/192 from your Qobuz subscription.", auth = USER_PASSWORD)`.
  - `TIDAL("Tidal", "Your Tidal library via device sign-in.", auth = LINKED_ACCOUNT)`.
  - All three `supported = true`. ✅ — done, with `experimental = true` kept alongside.
- Settings form: for address-less kinds (`SPOTIFY`) the address field must not be required —
  check how `ServerConfig` ids servers (generated id, so URL is not the identity — verify in
  code before assuming the form can omit the field).
- **Test:** kind metadata (labels non-blank, auth styles correct, `playsLocally` true).

### Task 0.2: Source skeletons registered in `MusicSources` — superseded

**Objective:** Dropped. Unbuilt kinds answer `null` from `MusicSources.create` already;
each phase registers its own source when it has a real client behind it.

- `library/MusicSources.kt`: `ServerKind.SPOTIFY -> SpotifySource(...)`, `QOBUZ -> QobuzSource(...)`, `TIDAL -> TidalSource(...)`.
- Skeleton sources implement `MusicSource` honestly: `capabilities` = `SEARCH` only (added
  to as phases land), `probe()` returns a "not configured yet" `SourceError`, all browse
  methods return empty.
- **Test:** `MusicSources.create` round-trips all three kinds; `probe()` message distinguishes
  "no credentials" from "unreachable".

---

## Phase 1 — Qobuz (easiest real provider)

**Status: LANDED** on `feat/direct-streaming-providers` (client + source + player glue).
Live smoke against a real Qobuz account is the only outstanding step — unit tests are
all fixture-based. Two design decisions worth keeping:

- **Stream urls are `qobuz://track/<id>` scheme uris, resolved at player-open time** via
  `StreamSchemes` + `StreamSchemeResolver` (see Phase 2 note below): the signed
  `track/getFileUrl` urls expire within minutes, so they are fetched fresh per track and
  never cached. Quality walks MA's chain (27→7→6→5) with a re-sign per attempt.
- **No app credentials are bundled.** MA's app id/secret are registered to their project
  and explicitly not for reuse. `ProviderAppCredentials` resolves them in two steps: the
  config's own options (`qobuzAppId`/`qobuzAppSecret`, `tidalClientId`/`tidalClientSecret`)
  first, then `BuildConfig`, which `app/build.gradle.kts` fills from gradle properties:

  ```properties
  # ~/.gradle/gradle.properties — never committed
  camusic.qobuz.appId=...
  camusic.qobuz.appSecret=...
  camusic.tidal.clientId=...
  camusic.tidal.clientSecret=...
  ```

  A build with no pair is not broken: the connect form (Settings → Libraries, and the
  first-run wizard) asks for one, so a fork or a plain checkout can still sign in with
  credentials of its own. Abdullah to supply CAMusic's own registered pair the same way.

### Task 1.1: API client skeleton ✅

**Objective:** `qobuz/QobuzClient.kt` — base URL (`https://www.qobuz.com/api.json/0.2/`),
app-id + auth handling, request signing exactly as MA's `music_assistant/providers/qobuz/`
does it (read MA's provider source as the wire-format reference — per the camusic skill, a
working integration elsewhere is evidence of what the endpoint accepts).

- Endpoints: `login` (or `user/login`), `catalog/search`, `album/get`, `artist/get`,
  `track/getFileUrl`, `userLibrary/getArtistsAlbums`-family, `playlist/get`.
- **Test:** parse fixture JSON (copied from MA's test fixtures where they exist, else
  hand-built against documented field names) into `MaItem`s. Fixed fixtures, no live network.

### Task 1.2: `QobuzSource`

**Objective:** Full `MusicSource` on top of the client.

- `probe()` = login round-trip; capabilities: `SEARCH, GENRES?, FAVORITES, PLAYLIST_READ,
  METADATA` — set only what the fixtures prove.
- `albums/artists/playlists/albumDetail/artistDetail/albumTracks` map catalog + user-library
  responses into `MaItem` (provider id `"qobuz"`, media types per item).
- `streamUrl(id)` = `track/getFileUrl` result — a plain HTTPS URL this phone decodes with
  media3. Cache per-track URLs briefly (they expire); refresh on `StreamEndPolicy` retry.
- **Test:** source-level fixtures → `MaItem` lists; `streamUrl` returns the fixture URL;
  expiry path returns empty and the player's existing retry handles it.

### Task 1.3: Wire-up + manual smoke

- Settings entry (username/password), probe from UI, browse, play, confirm Light Sync reacts
  (engine path — no extra code expected; verify, don't assume).
- Live testing needs a real Qobuz account — **blocker note: ask Abdullah for a test
  account when this phase starts.**

---

## Phase 2 — Spotify via embedded librespot (route B)

**Status: LANDED** on `feat/direct-streaming-providers` (dependency, engine, sink, source,
remote transport, Light Sync feed). Live smoke with a real Premium account is the
outstanding step — all unit tests are fixture-based. Design decisions worth keeping:

- **The dependency must be `librespot-player:1.6.5:thin`** plus a
  `resolutionStrategy.force("kotlin-stdlib:2.2.21")`: the default artifact shades the whole
  kotlin-stdlib (built against 2.4) into its jar, which defeats any version force and
  flags every enum `entries` use in the codebase as needing opt-in. Thin is shade-free.
- **Spotify is the MPD shape, not the scheme-uri shape:** `remotePlayback()` hands
  `LocalPlayer` a `SpotifyRemote` transport; librespot's `Player` owns playback
  (`load/play/pause/seek/volume`), and the app mirrors the queue for display. Scheme uris
  (`spotify://track/<id>`) stamp queue items but nothing resolves them through ExoPlayer.
- **Audio leaves librespot through `SpotifySink`** (a `SinkOutput`): AudioTrack write +
  big-endian→little-endian pair swap (desktop sinks are SourceDataLine-shaped; wrong swap
  = full-scale noise) + feed of `SpotifyEngine.tap` via `analyseExternal` in the same
  write. New feed `LightSyncFeed.SPOTIFY_PCM` ranks with the real-audio feeds;
  `SpotifyEngine.playing` (driven by librespot's EventsListener) picks it.
- **No Spotify developer app exists in this path**: browsing rides the Web API with the
  user's own session token (`session.tokens().getToken().accessToken`); search rides
  librespot's `SearchManager` (Gson → kotlinx bridge into the Web API parsers).

### Task 2.1: Dependency + module wiring ✅

**Objective:** librespot-java `lib` (and its Android sink/decoder modules from
`devgianlu/librespot-android`) available to the app.

- Gradle: prefer published artifacts (`xyz.gianlu.librespot:lib`) over vendoring; pin the
  version; document in the PR why (deprecated upstream, still the only JVM embed — go-librespot
  is Go and not embeddable in an Android app practically).
- R8/ProGuard: librespot uses protobuf + NanoHTTPD — add keep rules only as release builds
  demand them.
- **Test:** unit test constructs the librespot `Session.Configuration` with a fake sink —
  proves the dependency resolves and is minSdk-31 compatible at compile time.

### Task 2.2: `SpotifySource` (library half)

**Objective:** Browse/search/playlists via librespot's metadata API — NOT the Web API
(dev-mode caps make it useless for an OSS app).

- librespot gives track/album/artist/playlist metadata for items the session can load.
  Search is the gap: librespot has no search. Options, in order: (a) ship without search at
  first — browse user's saved library + playlists only; (b) Web API **optionally**, only for
  users who supply their own dev-mode client id in settings (power-user escape hatch, their
  own 5-user allowlist); (c) later, a community search mirror. Start with (a).
- Map to `MaItem` with provider id `"spotify"`.
- **Test:** fixtures from librespot metadata protos → `MaItem` lists; unknown types skipped
  not crashed.

### Task 2.3: Session + playback service

**Objective:** `spotify/SpotifySession.kt` — login (Premium credentials), session lifecycle,
`SpotifyPlaybackService`-style foreground owner (mirror how `PlaybackCaptureService` handles
foreground lifetime).

- Queue operations: librespot `player.load(trackUri)`, `play/pause`, `next/prev`, volume —
  wrap behind the same internal surface `LocalPlayer` exposes so NowPlaying/transport UI
  works unchanged.
- Track metadata events → the same now-playing state LocalPlayer feeds.
- **Test:** queue-order logic and metadata mapping as pure JVM tests with a fake player
  (librespot `Session` is too network-bound to construct live in tests — keep the wrapper
  thin and test the mapping, per the "engines are pure JVM" convention).

### Task 2.4: Audio sink → CAMusic engine

**Objective:** librespot `Sink` implementation writing decoded PCM into CAMusic's audio path.

- v1: sink → `AudioTrack` + feed `AudioAnalysisTap` (same shape as the capture tap) so
  Light Sync works immediately.
- v2 (separate PR): route sink PCM through `SignalPath`/`LocalDsp`/exclusive output so
  Spotify gets bit-perfect + DSP parity with local files. Do not claim bit-perfect in v1.
- **Test:** sink receives known PCM frames → tap produces the expected AnalysisFrame values
  (fixed fixtures, per `AudioAnalysisTap`'s existing test patterns).

### Task 2.5: Wire-up + manual smoke

- Login screen (Premium credentials; state plainly that free accounts cannot play), browse,
  play, transport controls, Light Sync reacting, provider badge shows "Spotify"
  (`MaParse.streamProviderLabel` already maps it).
- **Blocker note: needs Abdullah's Premium test account for live testing.**

---

## Phase 3 — Tidal

**Status: LANDED** on `feat/direct-streaming-providers` (client, source, device sign-in
row). Live smoke with a real Tidal account + this app's own developer registration is the
outstanding step. Design decisions worth keeping:

- **Device authorization flow** (RFC 8628 on `auth.tidal.com/v1/oauth2`): the app mints a
  code, the user approves in a browser, the token pair + expiry + user id + country live in
  `ServerConfig` options (`tidalAccessToken`/`tidalRefreshToken`/`tidalTokenExpiresAt`/
  `tidalUserId`/`tidalCountryCode`). `TidalSignInRow` (the Plex PIN-row shape) drives it.
- **Client credentials** are the app's own Tidal developer registration
  (`tidalClientId`/`tidalClientSecret` options) — MA's are theirs, as with Qobuz.
- **BTS manifests**: `playbackinfopostpaywall` answers are base64 JSON whose `uris` may be
  AES-128-ECB encrypted (key = SHA-256(manifestKey + securityToken)); `streamUrl` walks
  HI_RES → LOSSLESS → HIGH and decrypts where needed. Stream urls expire → same
  `tidal://track/<id>` scheme-uri mechanism as Qobuz.
- Favourites wrap payloads in an `item` envelope; the client parsers unwrap both shapes.

### Task 3.1: Device-auth client ✅

**Objective:** `tidal/TidalClient.kt` — device authorization flow (user opens a URL, enters
a code), token storage in `ServerConfig` options, refresh handling.

### Task 3.2: Catalog + library endpoints

- Favorites, playlists, albums, artists, search via the reverse-engineered endpoints MA's
  tidal provider uses (same reference discipline as Qobuz).
- `streamUrl` = AAC 320 playback URLs only for v1.

### Task 3.3: `TidalSource` + wire-up

- Same shape as `QobuzSource`; `AuthStyle.LINKED_ACCOUNT` form (PIN-style flow — precedent:
  Plex's plex.tv PIN sign-in).
- **Test:** fixtures → `MaItem`s; token refresh state machine as a pure JVM test.

---

## Phase 4 — Polish (cross-cutting)

**Landed (PR: streaming-provider pages, 2026-09-13):** each account has its own settings
page (`ui/screens/settings/providers/ProviderAccountPage.kt`) in the service's colours and
type (`ui/design/ProviderSkin.kt`), with the settings its client actually exposes
(`library/ProviderSettings.kt`): Spotify's librespot quality / normalisation / autoplay /
crossfade / preload / device name, Qobuz's `format_id` ceiling, Tidal's `audioquality`
ceiling; an account card from each provider's own answer (`ProviderAccount`); brand marks
from dashboard-icons. The generic form's music-folder and stream-quality cards no longer
show for accounts. Also fixed on the way: librespot rejected the app's UUID player id as a
device id ("Device ID must be 40 chars long"), so Spotify could never have signed in — it is
now a SHA-1 of the player id. Live smoke with real accounts remains the outstanding step.

- Search federation: once a source has real search, confirm the existing multi-source search
  (`CarLibraryBridge.searchAll` pattern) includes it.
- Settings copy: one line per provider naming the account requirement (Premium / subscription).
- `dist/` APK build + version bump follows the house release process.
- Update `camusic-development` skill: new provider dirs, librespot pitfalls, fixture sources.

---

## What this deliberately does not do

- No YouTube Music (see reality check).
- No Web API dependency in the core Spotify path (dev-mode 5-user cap makes it a dead end
  for an OSS app; it returns only as an optional power-user search add-on).
- No Tidal HiRes (v2), no DRM circumvention, no server-side queueing (MA remains the answer
  for multi-room + server queues).
