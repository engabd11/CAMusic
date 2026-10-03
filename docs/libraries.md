# Libraries

Every library CAMusic can play, how each one behaves, and the features that work across all of them. [Back to the README](../README.md).

Point CAMusic at a server you already run. Every backend is a first class citizen, and you
can add as many as you like and switch between them freely.

| Server | Browse | Play | Download | Scrobble |
|---|:---:|:---:|:---:|:---:|
| **Navidrome** | ✅ | ✅ | ✅ | ✅ |
| **Subsonic-compatible**: Gonic, Airsonic, Ampache, Funkwhale, LMS | ✅ | ✅ | ✅ | ✅ |
| **Jellyfin** | ✅ | ✅ | ✅ | ✅ |
| **Emby** | ✅ | ✅ | ✅ | ✅ |
| **Plex** | ✅ | ✅ | ✅ | ✅ |
| **MPD** | ✅ | ✅ | n/a | n/a |
| **Music Assistant** | ✅ | ✅ | n/a | ✅ |
| **On-device files** | ✅ | ✅ | n/a | n/a |
| **Spotify · Qobuz · Tidal** | 🔬 | 🔬 | n/a | n/a |
| **foobar2000** (via Beefweb) | 🔬 | 🔬 | n/a | n/a |

🔬 **Experimental**: the three streaming *accounts* are played on this phone with light sync, and Music Assistant is optional (see [Streaming services](streaming-services.md)). foobar2000 works like MPD: the desktop player keeps the sound and the phone drives it over the [Beefweb](https://github.com/hyperblast/beefweb) plugin's REST API. It is marked experimental until it has been run against a real install. All of them are
offered in first-run setup and in Settings → Libraries, under their own heading.

Artists, albums, playlists, radio, podcasts and audiobooks, with search across all of them.
Multi-disc albums group properly, liner notes and biographies appear where the server has
them, and each server shows its own brand mark everywhere it is listed. The picker groups
servers into families (Subsonic servers, media servers and MPD), so a moOde or Funkwhale owner finds theirs by name, whichever API it speaks, and **every server's settings page is dressed
in that server's own colours**: Jellyfin's purple-to-cyan, Emby's green, Plex's gold, Music
Assistant's blue, and the streaming services in their full brand palettes.

Plex signs in through a plex.tv PIN: tap **Sign in with Plex**, finish it in the browser, and
your Plex password stays at plex.tv where it belongs.

**Subsonic-compatible** is Navidrome's API by another name, so anything speaking it works the same way: Gonic, Airsonic, Ampache, Funkwhale and epoupon's LMS. Each server answers with what
it has: lyrics, ReplayGain and exact formats ride the OpenSubsonic extensions, so an older
server just loses those extras rather than showing empty panes. Two need one extra step: Ampache needs an admin to switch its Subsonic backend on (Admin → Server Config) and wants the Subsonic password from your account page rather than the web login, and Funkwhale takes a generated password: Settings → Subsonic API → Request a password creates the one CAMusic wants.

MPD is the one library that plays its own music, and moOde, Volumio, piCorePlayer and Mopidy are
all MPD underneath, so the one row is their app as much as MPD's. Every other server hands out a URL
per track and the phone decodes it; MPD is already a player, usually on the box the DAC is plugged
into, so the sound stays there and the phone becomes the remote: play, pause, seek, skip, the queue, shuffle and repeat, all of it addressing MPD. Point it at the protocol port (6600) and that is the whole setup: MPD keeps its own output (an `httpd` output is unnecessary), and there is one place to configure. ReplayGain is MPD's
own, set from the loudness control in the Now Playing options sheet. Covers come down the
protocol socket, embedded art first and a folder cover behind it. MPD plays its files itself rather than handing them over, so downloads are n/a. The Now Playing overlays and quality badge
show MPD's own live output (codec, sample rate, depth, channels and the bitrate it is decoding right now) rather than this phone's, and Light Sync runs from an offline scan the same way it
does for any other server driving a speaker in another room.

**Continue listening** leads the library with the albums and songs you were last in the
middle of, including Jellyfin's own resume shelf.

**Search all libraries** (Settings → Libraries, off by default) asks every library you have
set up at once and groups the answers by library; a slow server costs only its own row, and a
chip in the search bar switches between *All* and *This library* for one search. Android Auto
voice search always looks everywhere.

**Playlists are yours to edit** (remove, reorder and rename) on Navidrome and the Subsonic family, Jellyfin, Emby, MPD (stored playlists, written over the protocol) and Downloads. A
library that cannot keep playlists of its own, Plex among them, gets app-kept ones instead, so
"Add to playlist" works everywhere. **Ratings** go back to the server where it has them (Subsonic
stars, Plex, MPD's `rating` sticker). Long libraries arrive whole: Jellyfin, Emby and Plex are
paged to the end rather than stopping at the server's first 200 or 500.

**This device** is a library like the others: scanned off the main thread, rescanned when
files change, with embedded or folder art, genres from the tags, and `.m3u` playlists.

**Downloads you can trust.** Anything can be taken offline for the train, with a storage cap
and a Wi-Fi only option, and downloads browse as a library of their own. Files are keyed by
server as well as track, so two servers' track 42 stay separate; a download resumes where it
stopped, survives the app being closed, and is offered only for libraries that actually hand a
file over. A playlist downloads as a playlist and stays one in Downloads.

**A stream format for mobile data** sits beside the Wi-Fi one on each server's page, so the
phone can pull a smaller transcode away from home and the original on the sofa.

**Artful pages, if you want them.** Settings → Appearance → Album & artist pages (and Library
look, beside it) adds a *Gallery* hero (the
record sliding out of its sleeve, an artist banner in duotone), gallery tiles that glow in
their cover's colour, and optional shelves, the sleeve's colours, your listening, the latest
release, the discography along a timeline. Every one is painted from the cover's palette and
every one is off until you switch it on; the pages as they were remain the default.
