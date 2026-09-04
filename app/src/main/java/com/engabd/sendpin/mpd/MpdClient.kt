package com.engabd.sendpin.mpd

import com.engabd.sendpin.ma.MaAudioFormat
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ma.MaSearchResults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * An exception from the MPD protocol client.
 *
 * [isAuth] follows the same contract as the other providers: a rejected password
 * is worth re-prompting for, an unreachable host is not. MPD signals an auth
 * failure with `ACK [4@0] {password} incorrect password`.
 */
class MpdException(message: String, val isAuth: Boolean = false) : Exception(message)

/**
 * A Music Player Daemon library, browsed through the MPD protocol and streamed
 * through its HTTP output.
 *
 * Built to the same shape as [com.engabd.sendpin.subsonic.SubsonicClient] and
 * [com.engabd.sendpin.jellyfin.JellyfinClient]: items come back as [MaItem] with
 * `provider = `[PROVIDER], the stream URL is built on demand, and every failure
 * is an [MpdException] carrying something a user can read.
 *
 * ## Protocol
 *
 * MPD speaks a line-based text protocol over a raw TCP socket on port 6600.
 * Commands are `command arg1 arg2\n`; responses are `key: value\n` lines
 * terminated by `OK\n` or `ACK [code@seq] {command} message\n`. This client opens
 * one socket per command — simpler than pooling, and MPD's lightweight protocol
 * makes the connection overhead negligible on a LAN.
 *
 * ## Playing
 *
 * MPD does not hand out URLs the way Subsonic or Jellyfin do — it plays audio
 * itself, to whatever output its own config names, which on the kind of box
 * people run MPD on is a DAC. So this client does not fetch audio at all: it
 * puts the app's queue into MPD's queue and works the transport — `play`,
 * `pause`, `seekcur`, `next` — while `status` says where the playhead is. See
 * [MpdRemote] and [com.engabd.sendpin.audio.RemotePlayback].
 *
 * The `httpd` output this once required is no longer needed for anything.
 *
 * ## What MPD has and doesn't have
 *
 * Metadata comes entirely from file tags, so `MaAudioFormat` is rich (codec,
 * sample rate, bit depth) and there are no artist biographies, no lyrics and no
 * similar-track suggestions. Artwork it does have, embedded or beside the file —
 * see [coverArt] — and its own ReplayGain, which [setReplayGainMode] hands the
 * app's preference to.
 */
class MpdClient(
    /** The MPD server address, e.g. `192.168.0.202:6600`. */
    private val address: String,
    /** Optional password for MPD servers configured with a password. */
    @Volatile var password: String = "",
) {
    companion object {
        const val PROVIDER = "mpd"

        /** The MPD protocol's response terminator for a successful command. */
        private const val OK = "OK"
        /** The MPD protocol's error response prefix. */
        private const val ACK = "ACK"

        /** Refuse a cover past this — a phone's art view is not worth 20 MB of heap. */
        private const val MAX_ART_BYTES = 20 * 1024 * 1024
        /** MPD hands art over in chunks; this bounds a server that never finishes. */
        private const val MAX_ART_CHUNKS = 4096

        /**
         * One `key: value` response line, or null for anything that isn't one.
         *
         * The key is lowercased. MPD spells a key the way the tag is spelled —
         * `Title`, `Artist`, `AlbumArtist`, `Track`, `Date`, `Format` — while
         * `file`, `directory`, `playlist` and `duration` come back lowercase.
         * Every parser below looks a key up in one spelling, and this is what
         * makes that true: reading `m["title"]` off a map keyed `Title` is how
         * every track arrived titled after its filename, with no artist, no
         * album, no track number and no format.
         *
         * Internal so the parsers can be tested against real MPD output without
         * a socket. See `MpdParseTest`.
         */
        internal fun parseLine(line: String): Pair<String, String>? {
            val colon = line.indexOf(':')
            if (colon <= 0) return null
            return line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
        }

        /**
         * [parseLine] over a whole response, for the parser tests.
         *
         * Not for [command], whatever this used to claim: reading a response means
         * stopping at `OK` or raising on `ACK`, so the socket is read a line at a
         * time and each line goes through [parseLine] as it arrives. This is how a
         * test hands the same parsers a recorded response with no socket under it.
         */
        internal fun parseLines(lines: List<String>): List<Pair<String, String>> =
            lines.mapNotNull(::parseLine)

        /**
         * A `status` response as [MpdStatus]. Pure, so it can be tested against
         * what a real daemon sends without one being there.
         *
         * Every field has a fallback, and none of them throws: a server that
         * answers oddly is polled once a second, and once a second is a bad rate
         * for an exception.
         */
        internal fun readStatus(response: List<Pair<String, String>>): MpdStatus {
            val m = response.toMap()
            return MpdStatus(
                state = m["state"] ?: "stop",
                songIndex = m["song"]?.toIntOrNull() ?: -1,
                elapsedMs = m["elapsed"].toMillis(),
                durationMs = m["duration"].toMillis(),
                volume = m["volume"]?.toIntOrNull() ?: -1,
                repeat = m["repeat"] == "1",
                single = m["single"] == "1",
                random = m["random"] == "1",
                playlistLength = m["playlistlength"]?.toIntOrNull() ?: 0,
                audioFormat = m["audio"],
                bitrateKbps = m["bitrate"]?.toIntOrNull() ?: 0,
            )
        }

        /** Seconds as MPD writes them — `245.320` — in milliseconds. */
        private fun String?.toMillis(): Long =
            this?.toFloatOrNull()?.let { (it * 1000).toLong() }?.coerceAtLeast(0L) ?: 0L

        /**
         * [MpdStatus.audioFormat] as (sample rate Hz, bit depth, channels), the
         * same `"rate:bits:channels"` shape and the same tolerant parsing
         * [buildTrack] already uses for a track's own `Format` tag — any part can
         * be `*` (not decided yet) or non-numeric (`dsd64` in place of a rate for
         * DSD, `f` in place of bit depth for MPD's internal float pipeline), and
         * `toIntOrNull` turns any of those into 0 rather than failing the whole
         * reading over one odd field.
         */
        internal fun parseAudioFormat(raw: String?): Triple<Int, Int, Int>? {
            val parts = raw?.split(':') ?: return null
            val sampleRate = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val bitDepth = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val channels = parts.getOrNull(2)?.toIntOrNull() ?: 0
            return Triple(sampleRate, bitDepth, channels)
        }

        /**
         * MPD's `outputs` response as a list of [MpdOutput].
         *
         * The response is repeated blocks, each starting at `outputid` — so a new
         * output starts whenever that key is seen again, the same way a listing
         * of tracks would be split on `file`.
         */
        internal fun parseOutputs(response: List<Pair<String, String>>): List<MpdOutput> {
            val outputs = mutableListOf<MpdOutput>()
            var id: Int? = null
            var name = ""
            var enabled = false
            fun flush() {
                id?.let { outputs.add(MpdOutput(it, name, enabled)) }
            }
            for ((key, value) in response) {
                if (key == "outputid") {
                    flush()
                    id = value.toIntOrNull()
                    name = ""
                    enabled = false
                } else when (key) {
                    "outputname" -> name = value
                    "outputenabled" -> enabled = value == "1"
                }
            }
            flush()
            return outputs
        }
    }

    /** One of MPD's configured audio outputs, from the `outputs` command. */
    data class MpdOutput(val id: Int, val name: String, val enabled: Boolean)

    /**
     * MPD's configured outputs — the ALSA/DAC device names its own config
     * names, not anything this phone can see. Fetched on demand rather than
     * polled alongside [status]: the set essentially never changes while an
     * app session runs, so [MpdRemote] caches this rather than sending it
     * once a second next to a `status` that does change that often.
     */
    suspend fun outputs(): List<MpdOutput> = parseOutputs(command("outputs"))

    val serverUrl: String get() = base()

    /**
     * The scheme for MPD is always http — it is a plain TCP protocol with no
     * TLS support. The address the user typed may or may not have a scheme;
     * strip it and normalise.
     */
    private fun base(): String {
        val b = address.trim().trimEnd('/')
            .removePrefix("http://").removePrefix("https://")
        return "http://$b"
    }

    /** Host and port parsed from the address, for the TCP socket. */
    private val hostPort: Pair<String, Int>
        get() {
            val clean = address.trim().trimEnd('/')
                .removePrefix("http://").removePrefix("https://")
            val host = clean.substringBefore(':')
            val port = clean.substringAfter(':', "6600").toIntOrNull() ?: 6600
            return host to port
        }

    /**
     * The transcode/format setting. MPD's HTTP stream is always the encoder
     * configured in `mpd.conf` (typically FLAC), so this is informational —
     * the source keeps it for interface compatibility.
     */
    @Volatile
    var streamFormat: String = "raw"

    // ── Protocol transport ────────────────────────────────────────────────

    /**
     * One MPD command, returning the full response as a list of key-value pairs.
     *
     * Opens a fresh socket, sends the command, reads until `OK` or `ACK`, and
     * closes. The MPD protocol is stateful in theory (playback state persists
     * across connections) but each browse command is self-contained.
     *
     * Keys come back lowercased — see [parseLine], which is where that matters.
     */
    private suspend fun command(cmd: String): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            val (host, port) = hostPort
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, port), 5000)
                socket.soTimeout = 10000

                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

                // Read the greeting: "OK MPD 0.24.0\n"
                val greeting = reader.readLine()
                if (greeting == null || !greeting.startsWith("OK")) {
                    throw MpdException("That address didn't answer like an MPD server")
                }

                // Send password if configured
                if (password.isNotBlank()) {
                    writer.write("password ${quote(password)}\n")
                    writer.flush()
                    val authLine = reader.readLine()
                    if (authLine == null || authLine.startsWith(ACK)) {
                        throw MpdException("MPD rejected the password", isAuth = true)
                    }
                }

                // Send the command
                writer.write("$cmd\n")
                writer.flush()

                // Read the response
                val result = mutableListOf<Pair<String, String>>()
                while (true) {
                    val line = reader.readLine() ?: throw MpdException("MPD closed the connection")
                    if (line == OK) break
                    if (line.startsWith(ACK)) {
                        val msg = parseAck(line)
                        val isAuthError = line.contains("incorrect password")
                        throw MpdException(msg, isAuth = isAuthError)
                    }
                    parseLine(line)?.let(result::add)
                }
                result
            } catch (e: MpdException) {
                throw e
            } catch (e: kotlinx.coroutines.CancellationException) {
                // A cancelled browse is not a server failure. Wrapping it would
                // surface "the server said no" the moment the user leaves a screen.
                throw e
            } catch (e: Exception) {
                throw MpdException(e.message?.takeIf { it.isNotBlank() } ?: "Couldn't reach ${base()}")
            } finally {
                try { socket.close() } catch (_: Exception) {}
            }
        }

    /**
     * Quote a string per MPD's quoting rules.
     *
     * Backslashes and double quotes are escaped; single quotes are escaped as
     * `\'` per the MPD protocol spec. The whole argument is wrapped in double
     * quotes.
     */
    private fun quote(s: String): String {
        val escaped = s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("'", "\\'")
        return "\"$escaped\""
    }

    /**
     * Several commands over one connection, as an MPD command list.
     *
     * MPD executes the whole list before answering, so this is one socket and one
     * round trip where [command] would be one of each per command. That matters
     * for [playQueue], which is a clear, an add per track and a play, and which
     * a listener is waiting on: a queue swap sent as one command list is a queue
     * swap, where the same commands sent one connection at a time are an audible
     * gap with the old track still going during it.
     */
    private suspend fun commandList(vararg cmds: String): List<Pair<String, String>> =
        command((listOf("command_list_begin") + cmds + "command_list_end").joinToString("\n"))

    /** Extract a human-readable message from an MPD ACK response. */
    private fun parseAck(line: String): String {
        // ACK [4@0] {command} message
        val msgStart = line.indexOf('}')
        if (msgStart > 0 && msgStart + 2 < line.length) {
            return line.substring(msgStart + 2).trim().ifBlank { "MPD error: $line" }
        }
        return "MPD error: $line"
    }

    // ── Ping ──────────────────────────────────────────────────────────────

    /** Null when the server answered, else why not. */
    suspend fun pingError(): String? = pingResult()?.message

    /** As [pingError], but keeping the exception for auth/unreachable distinction. */
    suspend fun pingResult(): MpdException? = try {
        command("ping")
        null
    } catch (e: MpdException) {
        e
    }

    // ── Browse ────────────────────────────────────────────────────────────

    /** All artists in the library. */
    suspend fun artists(): List<MaItem> {
        val response = command("list artist")
        return response.map { it.second }.filter { it.isNotBlank() }.distinct().map { name ->
            MaItem(
                itemId = name,
                provider = PROVIDER,
                name = name,
                uri = name,
                mediaType = "artist",
                subtitle = null,
                image = null,
                duration = null,
            )
        }
    }

    /**
     * All albums in the library, or albums by a specific artist.
     *
     * `list {TYPE} {FILTER} [group {TAG}]` — the filter is a tag/value pair and it
     * comes *before* the grouping. A quoted artist tacked on after the `group`
     * clauses is a syntax error, which MPD answers with an ACK, which reached the
     * artist screen as "MPD error" for every artist there has ever been.
     */
    suspend fun albums(artist: String? = null, offset: Int = 0, limit: Int = 200): List<MaItem> {
        val cmd = if (artist != null) {
            "list album artist ${quote(artist)} group date group albumartist"
        } else {
            "list album group date group albumartist"
        }
        val response = command(cmd)
        return parseAlbumGroups(response, offset, limit)
    }

    /**
     * Recently added albums — newest file modification time first.
     *
     * MPD has no "recently added" of its own, but 0.21+ takes a filter expression
     * with `sort` and `window`, and sorting by `Last-Modified` descending is as
     * close as the daemon gets. Older servers answer that with an ACK, so the
     * plain album list stands behind it: not truly recent, but never empty.
     *
     * The command this replaced was not MPD syntax in any version — `window`
     * takes `START:END`, and `added` is not a filter — so the shelf took an ACK
     * and the fallback on every single load.
     */
    suspend fun recentlyAdded(limit: Int = 200): List<MaItem> {
        val filter = quote("(modified-since \"1970-01-01T00:00:00Z\")")
        return try {
            val songs = parseSongs(command("find $filter sort -Last-Modified window 0:$limit"))
            songs.mapNotNull { m ->
                val album = m["album"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val artist = (m["albumartist"] ?: m["artist"])?.takeIf { it.isNotBlank() }
                Triple(buildAlbumId(album, artist), album, artist)
            }.distinctBy { it.first }.take(limit).map { (id, album, artist) ->
                MaItem(
                    itemId = id, provider = PROVIDER,
                    name = album, uri = id,
                    mediaType = "album", subtitle = artist,
                    image = MpdArt.url(id), duration = null,
                )
            }
        } catch (_: MpdException) {
            albums(offset = 0, limit = limit)
        }
    }

    /** Recently played — not implemented (MPD has no play history without stickers). */
    suspend fun recentlyPlayed(limit: Int = 200): List<MaItem> = emptyList()

    /** Most played — not implemented (MPD has no play counts without stickers). */
    suspend fun mostPlayed(limit: Int = 200): List<MaItem> = emptyList()

    /** Artist detail: the artist's albums. */
    suspend fun artistDetail(id: String): Pair<MaItem?, List<MaItem>> {
        val artistItem = MaItem(
            itemId = id, provider = PROVIDER, name = id, uri = id,
            mediaType = "artist", subtitle = null, image = null, duration = null,
        )
        val artistAlbums = albums(artist = id)
        return artistItem to artistAlbums
    }

    /** Album detail: the album's tracks. */
    suspend fun albumDetail(id: String): Pair<MaItem?, List<MaItem>> {
        // id is "Album\0AlbumArtist" — parse it
        val parts = id.split("\u0000", limit = 2)
        val albumName = parts.getOrNull(0) ?: id
        val albumArtist = parts.getOrNull(1)

        val cmd = if (albumArtist != null) {
            "find album ${quote(albumName)} albumartist ${quote(albumArtist)}"
        } else {
            "find album ${quote(albumName)}"
        }
        val response = command(cmd)
        val tracks = parseTracks(response)
        val albumItem = MaItem(
            itemId = id, provider = PROVIDER, name = albumName, uri = id,
            mediaType = "album",
            subtitle = albumArtist,
            image = MpdArt.url(id),
            duration = tracks.sumOf { it.duration ?: 0 }.takeIf { it > 0 },
        )
        return albumItem to tracks
    }

    /** All playlists. */
    suspend fun playlists(): List<MaItem> {
        val response = command("listplaylists")
        val names = response.filter { it.first == "playlist" }.map { it.second }.distinct()
        return names.map { name ->
            MaItem(
                itemId = name, provider = PROVIDER, name = name, uri = name,
                mediaType = "playlist", subtitle = null, image = null, duration = null,
            )
        }
    }

    /**
     * Tracks in a stored playlist, with full metadata.
     *
     * Uses `listplaylistinfo` (not `listplaylist`) so each track carries its
     * title, artist, album, duration and format — the same metadata a `find`
     * response returns.
     */
    suspend fun playlistTracks(id: String): List<MaItem> {
        val response = command("listplaylistinfo ${quote(id)}")
        return parseTracks(response)
    }

    /** A single song by file path. */
    suspend fun song(id: String): MaItem? {
        val response = command("find file ${quote(id)}")
        return parseTracks(response).firstOrNull()
    }

    /**
     * All tracks, paginated.
     *
     * Uses `listallinfo` which returns every song with full metadata including
     * file paths. The entire response is received and then paginated client-side
     * — MPD doesn't support pagination on `listallinfo`. For very large libraries
     * (10k+ tracks) this is a known limitation; the first page is as expensive
     * as the last.
     */
    suspend fun tracks(offset: Int = 0, limit: Int = 500): List<MaItem> {
        val response = command("listallinfo")
        return parseTracks(response).drop(offset).take(limit)
    }

    /** Random songs — shuffles the full track list. */
    suspend fun randomSongs(size: Int = 100): List<MaItem> {
        val response = command("listallinfo")
        return parseTracks(response).shuffled().take(size)
    }

    // ── Search ───────────────────────────────────────────────────────────

    /**
     * Full search across artists, albums, and tracks.
     *
     * `search` answers with *songs*, whatever tag was matched on — it is `list`
     * that returns bare tag values, and only `list` and `count` take `group`. So
     * the artist and album hits are folded out of the song responses here.
     * Reading every value of a song response as an artist name is what filled the
     * Artists row with file paths and durations; asking `search` to `group` is
     * what made the whole search fail with an ACK.
     *
     * MPD has no `playlist` filter either, so playlists are listed and matched
     * client-side.
     */
    suspend fun search(query: String, limit: Int = 30): MaSearchResults {
        val q = quote(query)

        val artistResults = parseSongs(command("search artist $q"))
            .mapNotNull { it["artist"] ?: it["albumartist"] }
            .filter { it.isNotBlank() }.distinct()
            .take(limit)
            .map { name ->
                MaItem(
                    itemId = name, provider = PROVIDER, name = name, uri = name,
                    mediaType = "artist", subtitle = null, image = null, duration = null,
                )
            }

        // Album hits need the compound Album\u0000AlbumArtist id, or albumDetail has
        // nothing to open the right album with — two artists' "Greatest Hits" are
        // one id otherwise.
        val albumResults = parseSongs(command("search album $q"))
            .mapNotNull { m ->
                val album = m["album"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val artist = (m["albumartist"] ?: m["artist"])?.takeIf { it.isNotBlank() }
                Triple(buildAlbumId(album, artist), album, artist)
            }
            .distinctBy { it.first }
            .take(limit)
            .map { (id, album, artist) ->
                MaItem(
                    itemId = id, provider = PROVIDER, name = album, uri = id,
                    mediaType = "album", subtitle = artist, image = MpdArt.url(id), duration = null,
                )
            }

        val tracks = parseTracks(command("search title $q")).take(limit)

        // Playlists: MPD has no `search playlist` — list all and filter client-side
        val playlistResults = command("listplaylists")
            .filter { it.first == "playlist" }
            .map { it.second }
            .filter { it.contains(query, ignoreCase = true) }
            .take(limit)
            .map { name ->
                MaItem(
                    itemId = name, provider = PROVIDER, name = name, uri = name,
                    mediaType = "playlist", subtitle = null, image = null, duration = null,
                )
            }

        return MaSearchResults(
            artists = artistResults,
            albums = albumResults,
            tracks = tracks,
            playlists = playlistResults,
        )
    }

    // ── Genres ────────────────────────────────────────────────────────────

    suspend fun genres(): List<MaItem> {
        val response = command("list genre")
        return response.map { it.second }.filter { it.isNotBlank() }.map { name ->
            MaItem(
                itemId = name, provider = PROVIDER, name = name, uri = name,
                mediaType = "genre", subtitle = null, image = null, duration = null,
            )
        }
    }

    /**
     * Tracks tagged with [genre], a page at a time.
     *
     * [offset] is honoured rather than ignored: the genre screen pages by asking
     * for the next window, and dropping the offset served it the same first page
     * over and over, appended to itself.
     */
    suspend fun songsByGenre(genre: String, count: Int = 300, offset: Int = 0): List<MaItem> {
        val response = command("find genre ${quote(genre)}")
        return parseTracks(response).drop(offset).take(count)
    }

    // ── Favorites ────────────────────────────────────────────────────────

    /**
     * Favorites via MPD stickers. MPD has no built-in "starred" concept;
     * stickers are a generic key-value store per song. Returns empty.
     */
    suspend fun favorites(): MaSearchResults = MaSearchResults(
        artists = emptyList(), albums = emptyList(), tracks = emptyList(), playlists = emptyList(),
    )

    // ── URLs ──────────────────────────────────────────────────────────────

    /**
     * Cover art, as a URL the image loader knows how to fetch. See [MpdArt].
     *
     * MPD has artwork and this provider used to say it didn't, so every album,
     * every row and every player screen drew a placeholder square. It comes down
     * the protocol socket rather than over HTTP, which is why it needs a scheme of
     * its own rather than a link.
     */
    fun coverUrl(id: String?, size: Int = 1000): String? = MpdArt.url(id)

    // ── Playback: MPD is the player ───────────────────────────────────────

    /**
     * What MPD is doing right now, as `status` and `currentsong` report it.
     *
     * This is the whole reason MPD is driven rather than streamed. MPD's httpd
     * output is a live stream: it has no duration, no position and no seek, so a
     * phone rendering it can only ever show a spinning clock and a dead scrub bar
     * — which is exactly what it did. MPD, on the other hand, knows the elapsed
     * seconds, the duration, which entry of its queue is playing and whether it
     * is paused, and it takes `seekcur`. So the phone asks it.
     */
    data class MpdStatus(
        /** `play`, `pause` or `stop`. */
        val state: String,
        /** Index into MPD's queue, or -1 when stopped with nothing selected. */
        val songIndex: Int,
        val elapsedMs: Long,
        /** From `status`, which reports it for the playing entry only. 0 when unknown. */
        val durationMs: Long,
        val volume: Int,
        val repeat: Boolean,
        val single: Boolean,
        val random: Boolean,
        /** How many entries MPD's own queue holds. */
        val playlistLength: Int,
        /**
         * `status`'s `audio` line — the format MPD is decoding to *right now*,
         * e.g. `"44100:16:2"`. Distinct from a track's own tagged `Format`
         * (see [buildTrack]): a resample, DoP or DSD passthrough can differ
         * from what the file itself claims, and this is what is actually
         * reaching the DAC. Null when MPD is stopped or didn't say.
         */
        val audioFormat: String? = null,
        /** `status`'s `bitrate` line, in kbit/s. 0 when unknown. */
        val bitrateKbps: Int = 0,
    ) {
        val playing: Boolean get() = state == "play"
        val stopped: Boolean get() = state == "stop"
    }

    /**
     * [MpdStatus], or null when MPD couldn't be reached.
     *
     * Null is "no answer", never "stopped": this is polled once a second while
     * anything is playing, and one dropped packet must not blank the screen or
     * look like the queue ending.
     */
    suspend fun status(): MpdStatus? = try {
        readStatus(command("status"))
    } catch (_: MpdException) {
        null
    }

    /**
     * MPD's own queue, in order, with the metadata a browse response carries.
     *
     * Read on connecting, so a phone that opens while MPD is already halfway
     * through a record shows that record rather than an empty player and a
     * transport that appears to control nothing. MPD kept playing while the app
     * was shut; it is the app that has to catch up.
     */
    suspend fun queueTracks(): List<MaItem> = parseTracks(command("playlistinfo"))

    /** The file MPD is playing, or null when it is playing nothing. */
    suspend fun currentSong(): MaItem? = parseTracks(command("currentsong")).firstOrNull()

    /**
     * Replace MPD's queue with [files] and start at [startIndex].
     *
     * One command list, so MPD sees a queue swap rather than a clear followed by
     * an audible gap while each track is added over its own connection.
     */
    suspend fun playQueue(files: List<String>, startIndex: Int = 0) {
        if (files.isEmpty()) {
            commandList("clear")
            return
        }
        val at = startIndex.coerceIn(0, files.lastIndex)
        commandList(
            *buildList {
                add("clear")
                files.forEach { add("add ${quote(it)}") }
                add("play $at")
            }.toTypedArray(),
        )
    }

    /** Append [files] to MPD's queue, leaving what is playing alone. */
    suspend fun enqueue(files: List<String>) {
        if (files.isEmpty()) return
        commandList(*files.map { "add ${quote(it)}" }.toTypedArray())
    }

    /**
     * Drop everything after [after] and put [files] there instead, leaving the
     * entry at [after] playing untouched.
     *
     * The edit DJ Radio makes when it starts over a record that is already on,
     * and the one a tail shuffle makes. Sent as a `delete` of the range and a
     * run of `add`s rather than as [playQueue], which ends in `play` and so
     * restarts the song the listener is in the middle of — see
     * [com.engabd.sendpin.audio.RemotePlayback.replaceUpcoming].
     *
     * The length is read first because `delete START:END` is an error when
     * there is nothing in the range, and an error aborts the whole command
     * list — so a queue of one track (a DJ set's opener) would have had its
     * `add`s silently thrown away with it. A server that will not answer
     * `status` gets the appends alone, which is the harmless half.
     */
    suspend fun replaceAfter(files: List<String>, after: Int) {
        val from = (after + 1).coerceAtLeast(0)
        val length = status()?.playlistLength ?: 0
        val cmds = buildList {
            if (length > from) add("delete $from:$length")
            files.forEach { add("add ${quote(it)}") }
        }
        if (cmds.isEmpty()) return
        commandList(*cmds.toTypedArray())
    }

    /** Insert [files] directly after the playing entry. */
    suspend fun enqueueNext(files: List<String>, after: Int) {
        if (files.isEmpty()) return
        // `add uri position` is MPD 0.23+. On an older server the ACK leaves the
        // queue untouched, and the caller's append is the honest fallback.
        val at = (after + 1).coerceAtLeast(0)
        try {
            commandList(*files.mapIndexed { i, f -> "add ${quote(f)} ${at + i}" }.toTypedArray())
        } catch (_: MpdException) {
            enqueue(files)
        }
    }

    suspend fun playAt(index: Int) { command("play $index") }
    suspend fun resume() { command("pause 0") }
    suspend fun pause() { command("pause 1") }
    suspend fun next() { command("next") }
    suspend fun previous() { command("previous") }

    /** Seek within the playing entry. MPD takes seconds, with decimals. */
    suspend fun seekMs(ms: Long) {
        command("seekcur %.3f".format(java.util.Locale.US, (ms.coerceAtLeast(0L) / 1000.0)))
    }

    suspend fun removeAt(index: Int) { command("delete $index") }
    suspend fun moveInQueue(from: Int, to: Int) { command("move $from $to") }
    suspend fun shuffleQueue() { command("shuffle") }
    suspend fun clearQueue() { command("clear") }
    suspend fun setRandom(on: Boolean) { command("random ${if (on) 1 else 0}") }

    /**
     * Repeat and repeat-one, which MPD spells as two flags: `single` on top of
     * `repeat` is "this track for ever", `repeat` alone is "the queue for ever".
     */
    suspend fun setRepeat(mode: String) {
        val repeat = mode != "off"
        val single = mode == "one"
        commandList("repeat ${if (repeat) 1 else 0}", "single ${if (single) 1 else 0}")
    }

    /**
     * MPD's own ReplayGain, set to the app's preference.
     *
     * MPD reads the ReplayGain tags off the file it is decoding and applies them
     * in its own mixer — `off`, `track`, `album` and `auto`, the first three
     * spelled exactly as this app spells them, so the setting passes straight
     * through. This is the only way the preference can mean anything here: the
     * phone's implementation is a scalar on the phone's output, and when MPD is
     * the player the phone's output carries no music to scale.
     */
    suspend fun setReplayGainMode(mode: String) {
        val m = when (mode) {
            "track", "album", "auto" -> mode
            else -> "off"
        }
        command("replay_gain_mode $m")
    }

    /** What MPD says its ReplayGain is set to, or null if it wouldn't say. */
    suspend fun replayGainMode(): String? =
        runCatching { command("replay_gain_status").toMap()["replay_gain_mode"] }.getOrNull()

    /** Stop playback. */
    suspend fun stop() { command("stop") }

    /** Set the volume (0-100). */
    suspend fun setVolume(volume: Int) { command("setvol ${volume.coerceIn(0, 100)}") }

    // ── Cover art ─────────────────────────────────────────────────────────

    /**
     * The cover for [file], as bytes, or null when MPD has none.
     *
     * MPD does serve artwork, in two flavours, and the provider shipped claiming
     * it doesn't: `readpicture` returns the picture embedded in the file's own
     * tags, `albumart` a cover file sitting beside it in the directory. Embedded
     * is tried first because it is per-track correct on a compilation, where one
     * folder cover is wrong for most of the album.
     *
     * Both answer in chunks — `binary: N` then N raw bytes — so this reads until
     * the reported size is complete. Binary is why it cannot go through
     * [command]: that decodes the socket as UTF-8, which mangles every byte above
     * 0x7F, which is most of a JPEG.
     */
    suspend fun coverArt(file: String): ByteArray? =
        binaryCommand("readpicture", file) ?: binaryCommand("albumart", file)

    /**
     * A song under [albumId] — the `Album\u0000AlbumArtist` id the browse screens
     * carry — so an album's cover can be asked for by album.
     *
     * MPD's art commands take a *song* uri and nothing else: there is no such
     * thing as asking it for "the cover of this album", so one of the album's
     * songs stands for it.
     */
    suspend fun anySongIn(albumId: String): String? =
        albumDetail(albumId).second.firstOrNull()?.itemId

    /**
     * One chunked binary command (`albumart` / `readpicture`), over one socket.
     *
     * Returns null when MPD answers ACK — no picture for this song, or a server
     * too old for the command — which is a normal answer, not a failure.
     */
    private suspend fun binaryCommand(cmd: String, uri: String): ByteArray? =
        withContext(Dispatchers.IO) {
            val (host, port) = hostPort
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, port), 5000)
                socket.soTimeout = 10000
                val input = java.io.BufferedInputStream(socket.getInputStream())
                val out = java.io.BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

                if (readAsciiLine(input)?.startsWith("OK") != true) return@withContext null
                if (password.isNotBlank()) {
                    out.write("password ${quote(password)}\n"); out.flush()
                    if (readAsciiLine(input)?.startsWith(OK) != true) return@withContext null
                }

                val buffer = java.io.ByteArrayOutputStream()
                var size = -1
                var guard = 0
                while (guard++ < MAX_ART_CHUNKS) {
                    out.write("$cmd ${quote(uri)} ${buffer.size()}\n"); out.flush()

                    var chunk = -1
                    while (true) {
                        val line = readAsciiLine(input) ?: return@withContext null
                        if (line.startsWith(ACK)) return@withContext null
                        if (line == OK) break
                        val (key, value) = parseLine(line) ?: continue
                        when (key) {
                            "size" -> size = value.toIntOrNull() ?: -1
                            "binary" -> {
                                chunk = value.toIntOrNull() ?: return@withContext null
                                if (size > MAX_ART_BYTES || chunk < 0) return@withContext null
                                val bytes = ByteArray(chunk)
                                var read = 0
                                while (read < chunk) {
                                    val n = input.read(bytes, read, chunk - read)
                                    if (n < 0) return@withContext null
                                    read += n
                                }
                                buffer.write(bytes)
                                input.read()  // the newline MPD writes after the payload
                            }
                        }
                    }
                    // A zero-length chunk means MPD has no more to give; without this
                    // an art command that answers OK with no binary line would spin.
                    if (chunk <= 0 || size < 0 || buffer.size() >= size) break
                }
                buffer.toByteArray().takeIf { it.isNotEmpty() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } finally {
                try { socket.close() } catch (_: Exception) {}
            }
        }

    /**
     * One protocol line off a raw stream, read byte by byte.
     *
     * A `BufferedReader` cannot be used on a connection that also carries binary:
     * it would buffer past the header and eat the front of the picture.
     */
    private fun readAsciiLine(input: java.io.InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (line.isEmpty()) null else line.toString()
            if (b == '\n'.code) return line.toString()
            line.append(b.toChar())
        }
    }

    // ── Parsing helpers ──────────────────────────────────────────────────

    /**
     * Parse a `find`, `search`, or `listallinfo` response into a list of tracks.
     *
     * MPD returns flat key-value lines, where each track's metadata is a run
     * of consecutive lines starting with "file:".
     */
    internal fun parseTracks(response: List<Pair<String, String>>): List<MaItem> =
        parseSongs(response).map(::buildTrack)

    /**
     * The same response, one map per song, before it becomes an [MaItem].
     *
     * Search needs the tags an [MaItem] has no room for — `albumartist`, which is
     * half of an album's id — so the split is here rather than inside
     * [parseTracks]. Keys are lowercase; see [command].
     */
    internal fun parseSongs(response: List<Pair<String, String>>): List<Map<String, String>> {
        val songs = mutableListOf<Map<String, String>>()
        var current: MutableMap<String, String>? = null

        for ((key, value) in response) {
            if (key == "file") {
                current?.let { songs.add(it) }
                current = mutableMapOf("file" to value)
            } else {
                // Skip directory entries from listallinfo — only "file" entries
                // have audio metadata. "directory" entries are just paths.
                if (key != "directory") {
                    current?.put(key, value)
                }
            }
        }
        current?.let { songs.add(it) }
        return songs
    }

    /** Build a MaItem track from a parsed MPD metadata map. */
    private fun buildTrack(m: Map<String, String>): MaItem {
        val file = m["file"] ?: ""
        val title = m["title"] ?: file.substringAfterLast('/').substringBeforeLast('.')
        val artist = m["artist"] ?: m["albumartist"] ?: ""
        val album = m["album"] ?: ""
        // `duration` is MPD 0.20+; `Time` is what older servers send, and both are
        // seconds. Neither is guaranteed for a stream.
        val duration = (m["duration"] ?: m["time"])?.toFloatOrNull()?.toInt()
        val trackNumber = m["track"]?.substringBefore('/')?.toIntOrNull()
        val discNumber = m["disc"]?.substringBefore('/')?.toIntOrNull()
        val date = m["date"]?.substringBefore('-')?.toIntOrNull()
        val genre = m["genre"]?.let { listOf(it) } ?: emptyList()

        // MPD's `Format` is "samplerate:bits:channels" — three parts, and any of
        // them can be `*` where the decoder hasn't said yet (or `dsd64` in place of
        // a rate for DSD). `toIntOrNull` rather than `toInt`, or one such track
        // takes the whole listing down with a NumberFormatException. MPD names no
        // codec at all, so that comes from the file's own extension.
        val format = m["format"]?.split(':')
        val sampleRate = format?.getOrNull(0)?.toIntOrNull() ?: 0
        val bitDepth = format?.getOrNull(1)?.toIntOrNull() ?: 0
        val channels = format?.getOrNull(2)?.toIntOrNull() ?: 2
        val codec = codecFromExtension(file)

        // No guessing: an unrecognised extension used to be reported as FLAC, which
        // is exactly the badge lying about the source that the output pass spent
        // v0.12 stamping out. A rate with no codec name isn't worth a badge either —
        // `StreamQuality.label` renders that as a leading bullet with nothing before
        // it — so no codec means no format block.
        val audioFormat = if (codec.isNotBlank()) {
            MaAudioFormat(
                codec = codec,
                sampleRate = sampleRate,
                bitDepth = bitDepth,
                channels = channels,
            )
        } else null

        return MaItem(
            itemId = file,
            provider = PROVIDER,
            name = title,
            uri = file,
            mediaType = "track",
            subtitle = artist.ifBlank { null },
            image = MpdArt.url(file),
            duration = duration,
            audioFormat = audioFormat,
            year = date,
            genres = genre,
            trackNumber = trackNumber,
            discNumber = discNumber,
            album = album.ifBlank { null },
            parentId = album.ifBlank { null },
        )
    }

    /**
     * The codec implied by a file's extension — MPD's own metadata carries none.
     *
     * The names are the ones [com.engabd.sendpin.audio.StreamQuality] knows, which
     * is what decides whether a track counts as lossless and so whether the hi-res
     * badge may light: DSD files map to `dsf`/`dff` rather than a collective "dsd"
     * for exactly that reason. An extension with no entry is returned as it stands
     * — "MPC" on the badge is honest, "FLAC" would not be — and a file with no
     * extension at all gets nothing.
     *
     * The extension is read off the last path segment, and only when it looks like
     * one: `Artist/The 12.5 Sessions/track` has a dot in a *directory* name, and
     * taking everything after the last dot in the whole path would have put
     * "5 SESSIONS/TRACK" on the quality badge.
     */
    private fun codecFromExtension(file: String): String = when (
        val ext = file.substringAfterLast('/').substringAfterLast('.', "")
            .lowercase()
            .takeIf { it.isNotEmpty() && it.length <= 5 && it.all(Char::isLetterOrDigit) }
            .orEmpty()
    ) {
        "ogg", "oga" -> "vorbis"
        "m4a", "m4b", "aac" -> "aac"
        "wv" -> "wavpack"
        "aif", "aiff" -> "aiff"
        else -> ext
    }

    /**
     * Parse a `list album group date group albumartist` response into album items.
     *
     * MPD prints a grouped list **header first**: each group's tag values, then the
     * albums that belong to it, and a header only when a value changes.
     *
     * ```
     * AlbumArtist: Miles Davis
     * Date: 1959
     * Album: Kind of Blue
     * Date: 1970
     * Album: Bitches Brew
     * ```
     *
     * So an album takes the group values standing at the moment it is printed. The
     * previous reading had it backwards — album first, then its date and artist —
     * which paired every album with the *next* one's year and artist.
     */
    internal fun parseAlbumGroups(
        response: List<Pair<String, String>>,
        offset: Int = 0,
        limit: Int = 200,
    ): List<MaItem> {
        val albums = mutableListOf<MaItem>()
        var date: String? = null
        var artist: String? = null

        for ((key, value) in response) {
            when (key) {
                "date" -> date = value
                "albumartist" -> artist = value.takeIf { it.isNotBlank() }
                "album" -> if (value.isNotBlank()) {
                    val id = buildAlbumId(value, artist)
                    albums.add(
                        MaItem(
                            itemId = id, provider = PROVIDER,
                            name = value, uri = id,
                            mediaType = "album",
                            subtitle = artist,
                            image = MpdArt.url(id),
                            duration = null,
                            year = date?.substringBefore('-')?.toIntOrNull(),
                        ),
                    )
                }
            }
        }

        return albums.distinctBy { it.itemId }.drop(offset).take(limit)
    }

    /** Build a unique album id from name + artist, using a NUL separator. */
    private fun buildAlbumId(album: String, artist: String?): String =
        if (artist != null) "$album\u0000$artist" else album
}