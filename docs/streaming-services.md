# Streaming services (experimental)

Spotify, Qobuz and Tidal as accounts played on the phone. [Back to the README](../README.md).

Spotify, Qobuz and Tidal need an account rather than a server, so CAMusic treats them as **accounts**: a sign-in instead of an address, the music played on this phone by the same engine the self-hosted libraries use, and Light Sync riding on it as normal. Music Assistant is optional, and your account details stay on the phone, encrypted under the Android Keystore.

All three are **experimental**, and the tag is doing real work: streaming services offer third-party players unofficial routes only, so each of these rides an unofficial client or the service's own undocumented endpoints, and a provider's next change can break one. They are offered under their own heading, in first-run setup and in
Settings → Libraries, rather than mixed in with the servers.

**Qobuz and Tidal also want the *app's* own registration**, separate from your account.
A build carries whatever pair it was given at build time (gradle properties, see [docs/plan/direct-streaming-providers.md](plan/direct-streaming-providers.md)). A fork, or an APK built from a plain checkout, starts with an empty pair and the connect form asks for one, so you can use credentials of your own. Spotify skips this step: librespot signs in as your own client.

Each account has a settings page of its own, in the service's colours, with the settings
its client actually exposes rather than a generic stream-quality toggle: Spotify's audio
quality, normalisation, autoplay, crossfade, preload and Spotify Connect device name;
Qobuz's format tiers (MP3, CD, Hi-Res, Hi-Res+); Tidal's tiers (Low, High, HiFi, Max). Each
page also shows who is signed in and what the plan streams up to, and warns when the chosen
tier is above it.

The engineering detail lives in
[docs/plan/direct-streaming-providers.md](plan/direct-streaming-providers.md).

## Spotify 🔬

Plays through an **embedded librespot client**, the same open-source Spotify client Music Assistant uses, so the audio is decoded inside CAMusic itself. That makes it the only streaming service here whose output feeds Light Sync's analysis tap directly. Browsing covers your saved tracks and albums,
playlists, artist pages and search; a Premium account is required, and sign-in uses your own Spotify credentials directly, so a Spotify developer app is unnecessary. Queue editing mid-playback and instant seek are on the roadmap.

## Qobuz 🔬

Browses the API Qobuz's own web player uses and streams **FLAC up to 24 bit / 192 kHz** at its top tier. Your favourites *are* your library
(artists, albums, tracks), alongside your playlists and full search. Requires a Qobuz
subscription and, for the moment, app credentials entered alongside your login (see the
plan doc).

## Tidal 🔬

Signs in the way Tidal's own TV clients do: the app shows you a code, you approve it at link.tidal.com in a browser, so your password stays in the browser. Browses your favourites
and playlists with full search, and plays through Tidal's own playback manifests, from
AAC 320 up to lossless FLAC, depending on what your subscription serves for the track.

## What to expect while experimental

Treat these as best effort: the services offer third-party players unofficial routes only, so each integration talks to endpoints that can change without notice, the way Music Assistant's integrations do. If a provider changes something, it shows *openly*: the status screen says what it cannot hear, and the fix usually lands in a day or two upstream. YouTube Music is outside the roadmap, because Google withdrew the sign-in path third-party clients relied on and its apps block the audio capture the other routes would need.
