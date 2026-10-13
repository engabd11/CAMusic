package com.engabd.sendpin.mpd

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The protocol-parsing half of the MPD client, driven with the lines a real
 * daemon sends.
 *
 * `MpdUrlTest` covers the URL half, which is where "the server is down" bugs
 * live. These cover the half where "the server is up and everything is blank"
 * bugs live — and every one of them was: MPD spells a response key the way the
 * tag is spelled (`Title`, `AlbumArtist`, `Format`), the parsers all looked keys
 * up in lowercase, so every track came back titled after its filename with no
 * artist, no album, and no album list at all behind it.
 *
 * The responses below are shaped like an MPD 0.23 session's, capitalisation
 * included — the capitalisation *is* the thing under test.
 */
class MpdParseTest {

    private val client = MpdClient(address = "192.168.0.202:6600")

    private fun response(vararg lines: String) = MpdClient.parseLines(lines.toList())

    /** The separator [MpdClient] builds album ids with. */
    private val nul = Char(0)

    // ── Songs ─────────────────────────────────────────────────────────────

    private val oneSong = arrayOf(
        "file: Miles Davis/Kind of Blue/01 So What.flac",
        "Last-Modified: 2019-03-04T18:22:11Z",
        "Format: 44100:16:2",
        "Time: 545",
        "duration: 545.520",
        "Artist: Miles Davis",
        "AlbumArtist: Miles Davis",
        "Album: Kind of Blue",
        "Title: So What",
        "Track: 1/5",
        "Disc: 1/1",
        "Date: 1959-08-17",
        "Genre: Jazz",
    )

    @Test
    fun `a song response keeps its tags`() {
        val track = client.parseTracks(response(*oneSong)).single()
        assertEquals("So What", track.name)
        assertEquals("Miles Davis", track.subtitle)
        assertEquals("Kind of Blue", track.album)
        assertEquals(1, track.trackNumber)
        assertEquals(1, track.discNumber)
        assertEquals(1959, track.year)
        assertEquals(listOf("Jazz"), track.genres)
        assertEquals(545, track.duration)
        assertEquals("Miles Davis/Kind of Blue/01 So What.flac", track.itemId)
    }

    @Test
    fun `the audio format comes off MPD's three-part Format field`() {
        val format = assertNotNull(client.parseTracks(response(*oneSong)).single().audioFormat)
        assertEquals(44100, format.sampleRate)
        assertEquals(16, format.bitDepth)
        assertEquals(2, format.channels)
        assertEquals("flac", format.codec)
    }

    @Test
    fun `an unreadable Format field is dropped, not thrown on`() {
        // MPD writes `*` for a field the decoder hasn't settled yet, and names the
        // DSD rate where a sample rate would go. `toInt` on either took the whole
        // listing down with a NumberFormatException.
        val track = client.parseTracks(
            response(
                "file: x/y.dsf",
                "Format: dsd64:*:2",
                "Title: Something",
            ),
        ).single()
        val format = assertNotNull(track.audioFormat)
        assertEquals(0, format.sampleRate)
        assertEquals(0, format.bitDepth)
        assertEquals("dsf", format.codec, "dsf/dff are what StreamQuality counts as lossless")
    }

    @Test
    fun `an unknown extension is not called FLAC`() {
        val track = client.parseTracks(
            response("file: x/y.mpc", "Format: 44100:16:2", "Title: Something"),
        ).single()
        assertEquals("mpc", assertNotNull(track.audioFormat).codec)
    }

    @Test
    fun `a file with no extension reports no format at all`() {
        // Better a badge with nothing on it than one asserting a codec nobody said.
        val track = client.parseTracks(
            response("file: stream-dump", "Format: 44100:16:2", "Title: Something"),
        ).single()
        assertNull(track.audioFormat)
    }

    @Test
    fun `duration falls back to the older Time field`() {
        val track = client.parseTracks(
            response("file: a/b.flac", "Time: 210", "Title: Something"),
        ).single()
        assertEquals(210, track.duration)
    }

    @Test
    fun `a title-less file is named after its filename, not its path`() {
        val track = client.parseTracks(response("file: Some Artist/Album/03 Track.flac")).single()
        assertEquals("03 Track", track.name)
    }

    @Test
    fun `listallinfo directory entries are not tracks`() {
        val tracks = client.parseTracks(
            response(
                "directory: Miles Davis",
                "Last-Modified: 2019-03-04T18:22:11Z",
                "directory: Miles Davis/Kind of Blue",
                "file: Miles Davis/Kind of Blue/01 So What.flac",
                "Title: So What",
                "file: Miles Davis/Kind of Blue/02 Freddie Freeloader.flac",
                "Title: Freddie Freeloader",
            ),
        )
        assertEquals(listOf("So What", "Freddie Freeloader"), tracks.map { it.name })
    }

    @Test
    fun `parseSongs keeps the albumartist an MaItem has no room for`() {
        // Search builds album ids out of it, so it has to survive the parse.
        val song = client.parseSongs(response(*oneSong)).single()
        assertEquals("Miles Davis", song["albumartist"])
        assertEquals("Kind of Blue", song["album"])
    }

    @Test
    fun `a dot in a directory name is not an extension`() {
        val track = client.parseTracks(
            response("file: Artist/The 12.5 Sessions/track", "Format: 44100:16:2"),
        ).single()
        assertNull(track.audioFormat, "\"5 sessions/track\" is not a codec")
    }

    // ── Grouped album lists ───────────────────────────────────────────────

    @Test
    fun `a grouped album list takes the header standing above each album`() {
        // `list album group date group albumartist` prints the group values first
        // and the albums under them — the reverse of what the first cut assumed,
        // which paired every album with the next one's artist and year.
        val albums = client.parseAlbumGroups(
            response(
                "AlbumArtist: Miles Davis",
                "Date: 1959",
                "Album: Kind of Blue",
                "Date: 1970",
                "Album: Bitches Brew",
                "AlbumArtist: Bill Evans",
                "Date: 1961",
                "Album: Sunday at the Village Vanguard",
            ),
        )
        assertEquals(3, albums.size)
        assertEquals("Kind of Blue", albums[0].name)
        assertEquals("Miles Davis", albums[0].subtitle)
        assertEquals(1959, albums[0].year)
        assertEquals("Bitches Brew", albums[1].name)
        assertEquals("Miles Davis", albums[1].subtitle)
        assertEquals(1970, albums[1].year)
        assertEquals("Sunday at the Village Vanguard", albums[2].name)
        assertEquals("Bill Evans", albums[2].subtitle)
        assertEquals(1961, albums[2].year)
    }

    @Test
    fun `an album id carries its artist, so two Greatest Hits are two albums`() {
        val albums = client.parseAlbumGroups(
            response(
                "AlbumArtist: Queen",
                "Album: Greatest Hits",
                "AlbumArtist: Fleetwood Mac",
                "Album: Greatest Hits",
            ),
        )
        assertEquals(2, albums.size)
        assertEquals("Greatest Hits${nul}Queen", albums[0].itemId)
        assertEquals("Greatest Hits${nul}Fleetwood Mac", albums[1].itemId)
    }

    @Test
    fun `a windowed album list pages`() {
        val lines = (1..10).flatMap { listOf("AlbumArtist: A$it", "Album: Album $it") }
        val page = client.parseAlbumGroups(MpdClient.parseLines(lines), offset = 4, limit = 3)
        assertEquals(listOf("Album 5", "Album 6", "Album 7"), page.map { it.name })
    }

    @Test
    fun `an album with no albumartist still has an id`() {
        val album = client.parseAlbumGroups(response("Album: Untagged")).single()
        assertEquals("Untagged", album.itemId)
        assertNull(album.subtitle)
    }

    // ── Line parsing ──────────────────────────────────────────────────────

    @Test
    fun `keys are lowercased and values are not`() {
        assertEquals("albumartist" to "Miles Davis", MpdClient.parseLine("AlbumArtist: Miles Davis"))
        assertEquals("file" to "A/B.flac", MpdClient.parseLine("file: A/B.flac"))
    }

    @Test
    fun `a value may contain a colon`() {
        // A movement title does, and so does a Windows path.
        assertEquals(
            "title" to "Symphony No. 5: Allegro",
            MpdClient.parseLine("Title: Symphony No. 5: Allegro"),
        )
    }

    @Test
    fun `a line that is not a key-value pair is dropped`() {
        assertNull(MpdClient.parseLine("OK MPD 0.23.5"))
        assertNull(MpdClient.parseLine(""))
    }
}
