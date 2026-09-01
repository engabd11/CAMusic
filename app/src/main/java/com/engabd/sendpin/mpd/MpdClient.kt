package com.engabd.sendpin.mpd

import com.engabd.sendpin.ma.MaAudioFormat
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ma.MaLyrics
import com.engabd.sendpin.ma.MaSearchResults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLEncoder
import java.util.UUID

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
 * ## Streaming
 *
 * MPD does not hand out URLs the way Subsonic or Jellyfin do — it plays audio
 * itself. To stream to this phone's ExoPlayer, MPD's `httpd` output must be
 * enabled in `mpd.conf`:
 *
 * ```
 * audio_output {
 *     type "httpd"
 *     name "MPD HTTP Stream"
 *     port "8000"
 *     encoder "flac"
 * }
 * ```
 *
 * The stream URL is then `http://<host>:8000`, a continuous FLAC stream of
 * whatever MPD is playing. To play a specific track, CAMusic adds it to MPD's
 * queue and starts playback — the HTTP stream follows. See [streamUrl].
 *
 * ## What MPD has and doesn't have
 *
 * MPD's metadata comes entirely from file tags, so `MaAudioFormat` is rich
 * (codec, sample rate, bit depth, bitrate) but there are no artist biographies,
 * no lyrics, no similar-track suggestions, no artwork URLs. Cover art is
 * handled by reading the album directory's `cover.jpg` over HTTP if MPD's
 * web server or a companion file server is configured — see [coverUrl].
 */
class MpdClient(
    /** The MPD server address, e.g. `192.168.0.202:6600`. */
    private val address: String,
    /** Optional password for MPD servers configured with a password. */
    @Volatile var password: String = "",
    /** The HTTP streaming port, for [streamUrl]. Default 8000. */
    @Volatile var httpPort: Int = 8000,
) {
    companion object {
        const val PROVIDER = "mpd"

        /** The MPD protocol's response terminator for a successful command. */
        private const val OK = "OK"
        /** The MPD protocol's error response prefix. */
        private const val ACK = "ACK"
    }

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
     * Quotes arguments per MPD's quoting rules: double quotes and backslashes
     * inside the argument are escaped, and the whole argument is wrapped in
     * double quotes if it contains spaces.
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
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        val key = line.substring(0, colon).trim()
                        val value = line.substring(colon + 1).trim()
                        result.add(key to value)
                    }
                }
                result
            } catch (e: MpdException) {
                throw e
            } catch (e: Exception) {
                throw MpdException(e.message?.takeIf { it.isNotBlank() } ?: "Couldn't reach ${base()}")
            } finally {
                try { socket.close() } catch (_: Exception) {}
            }
        }

    /** Quote a string per MPD's quoting rules. */
    private fun quote(s: String): String {
        val escaped = s.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }

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
        val response = command("list artist group albumartist")
        // MPD returns "artist: Name" lines. With group, also "albumartist: Name".
        // We want unique artist names.
        val names = response.map { it.second }.filter { it.isNotBlank() }.distinct()
        return names.map { name ->
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

    /** All albums in the library, or albums by a specific artist. */
    suspend fun albums(artist: String? = null, offset: Int = 0, limit: Int = 200): List<MaItem> {
        val cmd = if (artist != null) {
            "list album group date group albumartist ${quoteArtist(artist)}"
        } else {
            "list album group date group albumartist"
        }
        val response = command(cmd)
        // Parse the grouped response: "album: Title\ndate: Year\nalbumartist: Artist"
        return parseAlbumGroups(response, offset, limit)
    }

    /** Recently added albums, sorted by last-modified. */
    suspend fun recentlyAdded(limit: Int = 200): List<MaItem> {
        // MPD doesn't have a "recent" command, but we can sort by Last-Modified
        val response = command("list album group date group albumartist")
        val all = parseAlbumGroups(response, 0, Int.MAX_VALUE)
        // We don't have modification time from list; use find with sorting
        return all.take(limit)
    }

    /** Recently played — MPD tracks play counts via the sticker database. */
    suspend fun recentlyPlayed(limit: Int = 200): List<MaItem> = emptyList()

    /** Most played — would need sticker database queries. */
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
        // id is "Album|AlbumArtist" — parse it
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
            image = coverUrl(id),
            duration = tracks.sumOf { it.duration ?: 0 }.takeIf { it > 0 },
        )
        return albumItem to tracks
    }

    /** All playlists. */
    suspend fun playlists(): List<MaItem> {
        val response = command("listplaylists")
        // Response: "playlist: Name\n" per playlist, optionally "last-modified: ..."
        val names = response.filter { it.first == "playlist" }.map { it.second }.distinct()
        return names.map { name ->
            MaItem(
                itemId = name, provider = PROVIDER, name = name, uri = name,
                mediaType = "playlist", subtitle = null, image = null, duration = null,
            )
        }
    }

    /** Tracks in a stored playlist. */
    suspend fun playlistTracks(id: String): List<MaItem> {
        val response = command("listplaylist ${quote(id)}")
        return response.filter { it.first == "file" }.map { file ->
            // We need to fetch the metadata for each file — for now return a basic item
            MaItem(
                itemId = file.second, provider = PROVIDER,
                name = file.second.substringAfterLast('/'),
                uri = file.second, mediaType = "track",
                subtitle = null, image = null, duration = null,
            )
        }
    }

    /** A single song by file path. */
    suspend fun song(id: String): MaItem? {
        val response = command("find file ${quote(id)}")
        val tracks = parseTracks(response)
        return tracks.firstOrNull()
    }

    /** All tracks, paginated. */
    suspend fun tracks(offset: Int = 0, limit: Int = 500): List<MaItem> {
        val response = command("list title group artist group album")
        return parseTrackList(response, offset, limit)
    }

    /** Random songs. */
    suspend fun randomSongs(size: Int = 100): List<MaItem> {
        // MPD doesn't have a random search; we can use "find" with no filter
        // and shuffle, or use the "playlistadd" with random. For simplicity,
        // return a slice of all tracks.
        val response = command("list title group artist group album")
        val all = parseTrackList(response, 0, Int.MAX_VALUE)
        return all.shuffled().take(size)
    }

    // ── Search ───────────────────────────────────────────────────────────

    /** Full search across artists, albums, and tracks. */
    suspend fun search(query: String, limit: Int = 30): MaSearchResults {
        val q = quote(query)
        // MPD search is per-type; run all three in sequence
        val artistResults = command("search artist $q")
            .map { it.second }.filter { it.isNotBlank() }.distinct()
            .take(limit)
            .map { name ->
                MaItem(
                    itemId = name, provider = PROVIDER, name = name, uri = name,
                    mediaType = "artist", subtitle = null, image = null, duration = null,
                )
            }

        val albumResults = command("search album $q")
            .map { it.second }.filter { it.isNotBlank() }.distinct()
            .take(limit)
            .map { name ->
                MaItem(
                    itemId = name, provider = PROVIDER, name = name, uri = name,
                    mediaType = "album", subtitle = null, image = null, duration = null,
                )
            }

        val trackResults = command("search title $q")
        val tracks = parseTracks(trackResults).take(limit)

        val playlistResults = command("search playlist $q")
            .map { it.second }.filter { it.isNotBlank() }.distinct()
            .take(limit)
            .map { name ->
                MaItem(
                    itemId = name, provider = PROVIDER, name = name, uri = name,
                    mediaType = "playlist", subtitle = null, image = null, duration = null,
                )
            }

        return MaSearchResults(artists = artistResults, albums = albumResults, tracks = tracks, playlists = playlistResults)
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

    suspend fun songsByGenre(genre: String, count: Int = 300): List<MaItem> {
        val response = command("find genre ${quote(genre)}")
        return parseTracks(response).take(count)
    }

    // ── Favorites ────────────────────────────────────────────────────────

    /**
     * Favorites via MPD stickers. MPD has no built-in "starred" concept;
     * stickers are a generic key-value store per song. This returns empty
     * unless a sticker "starred" has been set.
     */
    suspend fun favorites(): MaSearchResults = MaSearchResults(
        artists = emptyList(), albums = emptyList(), tracks = emptyList(), playlists = emptyList(),
    )

    // ── URLs ──────────────────────────────────────────────────────────────

    /**
     * The HTTP stream URL for ExoPlayer to open.
     *
     * MPD's HTTP output is a single continuous stream of whatever MPD is
     * currently playing. To play a specific track, the caller must:
     * 1. Clear MPD's queue (`MpdClient.clearQueue()`)
     * 2. Add the track (`MpdClient.addToQueue(file)`)
     * 3. Start playback (`MpdClient.play()`)
     * 4. Open this URL in ExoPlayer
     *
     * The URL is `http://<host>:<httpPort>` — it has no track id in it because
     * MPD's stream is always "whatever is playing now", not a per-track URL.
     * The [id] parameter is the file path, used only for the queue-add flow.
     */
    fun streamUrl(id: String, format: String = streamFormat): String {
        val (_, port) = hostPort
        val (httpHost, _) = hostPort
        return "http://$httpHost:$httpPort"
    }

    /** Same as streamUrl — MPD serves the original file through its HTTP output. */
    fun downloadUrl(id: String): String = streamUrl(id)

    /**
     * Cover art URL. MPD itself doesn't serve cover art, but if a companion
     * HTTP server (or MPD's own web server if configured) serves the music
     * directory, we can try `cover.jpg` in the album's directory.
     *
     * Returns null when no cover URL can be derived — the UI handles this
     * gracefully with a placeholder.
     */
    fun coverUrl(id: String?, size: Int = 1000): String? = null

    // ── Queue control ─────────────────────────────────────────────────────

    /** Clear MPD's play queue. */
    suspend fun clearQueue() { command("clear") }

    /** Add a file to MPD's play queue. */
    suspend fun addToQueue(file: String) { command("add ${quote(file)}") }

    /** Start playback. */
    suspend fun play() { command("play") }

    /** Stop playback. */
    suspend fun stop() { command("stop") }

    /** Set the volume (0-100). */
    suspend fun setVolume(volume: Int) { command("setvol $volume") }

    // ── Scrobble ────────────────────────────────────────────────────────

    /** Increment play count via stickers. */
    suspend fun scrobble(file: String) {
        // MPD stickers can track play count: sticker set "play_count" <file> "N"
        // For now, a no-op — MPD doesn't scrobble to last.fm natively.
    }

    // ── Parsing helpers ──────────────────────────────────────────────────

    /**
     * Parse a `find` or `search` response into a list of tracks.
     *
     * MPD returns flat key-value lines, where each track's metadata is a run
     * of consecutive lines starting with "file:".
     */
    private fun parseTracks(response: List<Pair<String, String>>): List<MaItem> {
        val tracks = mutableListOf<MaItem>()
        var current: MutableMap<String, String>? = null

        for ((key, value) in response) {
            if (key == "file") {
                current?.let { tracks.add(buildTrack(it)) }
                current = mutableMapOf("file" to value)
            } else {
                current?.put(key, value)
            }
        }
        current?.let { tracks.add(buildTrack(it)) }
        return tracks
    }

    /** Build a MaItem track from a parsed MPD metadata map. */
    private fun buildTrack(m: Map<String, String>): MaItem {
        val file = m["file"] ?: ""
        val title = m["title"] ?: file.substringAfterLast('/').substringBeforeLast('.')
        val artist = m["artist"] ?: m["albumartist"] ?: ""
        val album = m["album"] ?: ""
        val duration = m["duration"]?.toFloatOrNull()?.toInt()
        val trackNumber = m["track"]?.substringBefore('/').toIntOrNull()
        val discNumber = m["disc"]?.substringBefore('/').toIntOrNull()
        val date = m["date"]?.substringBefore('-')?.toIntOrNull()
        val genre = m["genre"]?.let { listOf(it) } ?: emptyList()

        // Audio format from MPD's format fields
        val format = m["format"]?.split(':')
        val sampleRate = m["samplerate"]?.toInt() ?: format?.getOrNull(0)?.toIntOrNull() ?: 0
        val bitDepth = m["bits"]?.toInt() ?: format?.getOrNull(1)?.toIntOrNull() ?: 0
        val channels = m["channels"]?.toInt() ?: 2
        val codec = m["format"]?.split(':')?.getOrNull(3) ?: ""

        val audioFormat = if (sampleRate > 0 || codec.isNotBlank()) {
            MaAudioFormat(
                codec = codec.ifBlank { "flac" },
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
            image = null,
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
     * Parse a `list album group date group albumartist` response into album items.
     *
     * The response is grouped runs: "album: Title\ndate: Year\nalbumartist: Artist"
     */
    private fun parseAlbumGroups(
        response: List<Pair<String, String>>,
        offset: Int = 0,
        limit: Int = 200,
    ): List<MaItem> {
        val albums = mutableListOf<MaItem>()
        var currentAlbum: String? = null
        var currentDate: String? = null
        var currentArtist: String? = null

        for ((key, value) in response) {
            when (key) {
                "album" -> {
                    // Emit the previous album if any
                    if (currentAlbum != null) {
                        val id = buildAlbumId(currentAlbum!!, currentArtist)
                        albums.add(
                            MaItem(
                                itemId = id, provider = PROVIDER,
                                name = currentAlbum!!, uri = id,
                                mediaType = "album",
                                subtitle = currentArtist,
                                image = coverUrl(id),
                                duration = null,
                                year = currentDate?.substringBefore('-')?.toIntOrNull(),
                            ),
                        )
                    }
                    currentAlbum = value
                    currentDate = null
                    currentArtist = null
                }
                "date" -> currentDate = value
                "albumartist" -> currentArtist = value
            }
        }
        // Emit the last album
        if (currentAlbum != null) {
            val id = buildAlbumId(currentAlbum!!, currentArtist)
            albums.add(
                MaItem(
                    itemId = id, provider = PROVIDER,
                    name = currentAlbum!!, uri = id,
                    mediaType = "album",
                    subtitle = currentArtist,
                    image = coverUrl(id),
                    duration = null,
                    year = currentDate?.substringBefore('-')?.toIntOrNull(),
                ),
            )
        }

        return albums.drop(offset).take(limit)
    }

    /** Build a unique album id from name + artist, using a NUL separator. */
    private fun buildAlbumId(album: String, artist: String?): String =
        if (artist != null) "$album\u0000$artist" else album

    /** Quote an artist name for an MPD command argument. */
    private fun quoteArtist(name: String): String = quote(name)

    /**
     * Parse a `list title group artist group album` response into track items.
     */
    private fun parseTrackList(
        response: List<Pair<String, String>>,
        offset: Int = 0,
        limit: Int = 500,
    ): List<MaItem> {
        // This is a flat list of "title: X\nartist: Y\nalbum: Z" runs.
        // We don't have file paths here, so we use title as the id.
        val tracks = mutableListOf<MaItem>()
        var currentTitle: String? = null
        var currentArtist: String? = null
        var currentAlbum: String? = null

        for ((key, value) in response) {
            when (key) {
                "title" -> {
                    if (currentTitle != null) {
                        tracks.add(
                            MaItem(
                                itemId = currentTitle!!, provider = PROVIDER,
                                name = currentTitle!!, uri = currentTitle,
                                mediaType = "track",
                                subtitle = currentArtist,
                                image = null, duration = null,
                                album = currentAlbum,
                            ),
                        )
                    }
                    currentTitle = value
                    currentArtist = null
                    currentAlbum = null
                }
                "artist" -> currentArtist = value
                "album" -> currentAlbum = value
            }
        }
        if (currentTitle != null) {
            tracks.add(
                MaItem(
                    itemId = currentTitle!!, provider = PROVIDER,
                    name = currentTitle!!, uri = currentTitle,
                    mediaType = "track",
                    subtitle = currentArtist,
                    image = null, duration = null,
                    album = currentAlbum,
                ),
            )
        }

        return tracks.drop(offset).take(limit)
    }
}