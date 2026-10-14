# Getting started

Install, first run and what you need. [Back to the README](../README.md).

1. Install the APK from [Releases](https://github.com/engabd11/CAMusic/releases).
2. Open it and follow the onboarding: pick a server or a streaming account, sign in, and
   optionally pair a Hue bridge by pressing the button on the bridge when asked. A Music
   Assistant server that needs a login says so on the spot rather than pretending to connect.
3. Start listening. A server address is the whole of it, and for Music Assistant, Jellyfin,
   Emby, Plex and MPD the setup screen finds the server on your network for you.

**Updating:** Settings, System, Storage & About, About, then **Check for updates**. It asks
GitHub for the latest release only when you tap it, downloads the APK for your device (phone
or TV), checks it against the release's checksum and signing key, and hands it to Android,
which asks you to confirm. The first time, Android also asks you to allow CAMusic to install
apps. Your settings and library stay. Installing the APK from Releases by hand still works.

**Requirements:** Android 12 or newer. Any Subsonic, Jellyfin, Emby, Plex, MPD, foobar2000
(with Beefweb) or Music Assistant server for the library, or a Spotify Premium, Qobuz or Tidal
account, a Hue bridge with an entertainment area for Light Sync, a Music Assistant server for
multi-room grouping, and an AirPlay receiver for casting. Each one is optional and independent
of the others.

Settings, servers and credentials can be exported as a password-encrypted file (a passphrase of
at least ten characters, entered twice, stretched with 600,000 rounds of PBKDF2) and imported
on another device, where the credentials are re-encrypted under that device's Keystore. The
file also carries the playlists made in the app, favourites kept on the phone, lyric timing
fixes and game records, and, if you tick it, your play history for Stats (merged without
doubling plays). Older exports still import, and an older version of the app still reads a new
export, keeping what it knows. The player identity stays with each phone, so two phones never
appear to Music Assistant as one speaker. Stats keeps up to 100,000 plays.
