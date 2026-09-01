package com.engabd.sendpin.mpd

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading MPD's transport, and building the art URLs that stand in for the
 * covers it will only hand over down a socket.
 *
 * These are the two halves of "MPD plays it, the phone drives it": the phone can
 * only draw a scrub bar it can read a position off, and the position comes from
 * `status` — elapsed and duration in seconds, with decimals, which nothing else
 * in this app reports that way.
 */
class MpdStatusTest {

    private val client = MpdClient(address = "192.168.0.202:6600")

    private fun response(vararg lines: String) = MpdClient.parseLines(lines.toList())

    /** The separator [MpdClient] builds album ids with. */
    private val nul = Char(0)

    // ── status ────────────────────────────────────────────────────────────

    private val playingStatus = arrayOf(
        "volume: 42",
        "repeat: 0",
        "random: 0",
        "single: 0",
        "consume: 0",
        "playlist: 12",
        "playlistlength: 9",
        "state: play",
        "song: 3",
        "songid: 14",
        "time: 12:245",
        "elapsed: 12.345",
        "duration: 245.320",
        "bitrate: 1024",
        "audio: 44100:16:2",
    )

    @Test
    fun `a playing status reads as a position in a queue`() {
        val s = MpdClient.readStatus(response(*playingStatus))
        assertTrue(s.playing)
        assertFalse(s.stopped)
        assertEquals(3, s.songIndex)
        assertEquals(12_345, s.elapsedMs, "elapsed is seconds with decimals, not milliseconds")
        assertEquals(245_320, s.durationMs)
        assertEquals(42, s.volume)
        assertEquals(9, s.playlistLength)
    }

    @Test
    fun `a paused player is not a stopped one`() {
        // The difference decides whether the queue is over — which is what tops
        // the queue up — so it cannot be collapsed into "not playing".
        val s = MpdClient.readStatus(response("state: pause", "song: 1", "elapsed: 5.0"))
        assertFalse(s.playing)
        assertFalse(s.stopped)
        assertEquals(1, s.songIndex)
    }

    @Test
    fun `a stopped player has run out`() {
        val s = MpdClient.readStatus(response("state: stop", "playlistlength: 4"))
        assertTrue(s.stopped)
        assertFalse(s.playing)
        assertEquals(-1, s.songIndex, "stopped with nothing selected")
    }

    @Test
    fun `a mixer MPD cannot touch reports no volume`() {
        // A bit-perfect ALSA output with software volume off — which is the whole
        // reason to run MPD on a box with a DAC — answers -1. The app has to leave
        // the control alone rather than show a slider sitting at zero.
        val s = MpdClient.readStatus(response("state: play", "volume: -1", "song: 0"))
        assertEquals(-1, s.volume)
    }

    @Test
    fun `an empty status still parses`() {
        // A server that answers `status` with nothing usable must not throw: it is
        // polled once a second, and an exception a second is an unusable app.
        val s = MpdClient.readStatus(emptyList())
        assertEquals("stop", s.state)
        assertEquals(0L, s.elapsedMs)
        assertEquals(-1, s.songIndex)
    }

    @Test
    fun `the repeat flags are read as the app spells them`() {
        // MPD keeps two flags where the app has one three-way mode: `single` on
        // top of `repeat` is this track for ever, `repeat` alone is the queue.
        val one = MpdClient.readStatus(response("state: play", "repeat: 1", "single: 1"))
        assertTrue(one.repeat)
        assertTrue(one.single)
        val all = MpdClient.readStatus(response("state: play", "repeat: 1", "single: 0"))
        assertTrue(all.repeat)
        assertFalse(all.single)
    }

    // ── cover art URLs ────────────────────────────────────────────────────

    @Test
    fun `a track's art URL survives being a file path`() {
        // Paths hold spaces, ampersands and hashes, all of which end a URL early
        // if they go in raw.
        val url = MpdArt.url("Miles Davis/Kind of Blue #1/01 So What.flac")
        assertEquals(
            "Miles Davis/Kind of Blue #1/01 So What.flac",
            MpdArt.idFrom(url),
        )
    }

    @Test
    fun `an album's art URL survives the NUL in its id`() {
        val id = "Greatest Hits${nul}Queen"
        val url = MpdArt.url(id)
        assertEquals(id, MpdArt.idFrom(url))
        assertTrue(MpdArt.isAlbumId(id), "the NUL is what says this names an album")
    }

    @Test
    fun `a file path is not an album id`() {
        // The two go to different MPD commands: an album has to be resolved to one
        // of its songs first, since MPD's art commands only speak about songs.
        assertFalse(MpdArt.isAlbumId("Miles Davis/Kind of Blue/01 So What.flac"))
    }

    @Test
    fun `nothing in, nothing out`() {
        assertNull(MpdArt.url(null))
        assertNull(MpdArt.url(""))
        assertNull(MpdArt.idFrom(null))
        assertNull(MpdArt.idFrom("https://example.com/cover.jpg"), "not one of ours")
    }

    @Test
    fun `tracks and albums carry their art`() {
        // Every item used to come back with `image = null`, which is what made the
        // whole library draw placeholder squares on a server that had covers.
        val track = client.parseTracks(
            response("file: A/B/01.flac", "Title: One", "Album: B"),
        ).single()
        assertEquals("A/B/01.flac", MpdArt.idFrom(track.image))

        val album = client.parseAlbumGroups(
            response("AlbumArtist: A", "Album: B"),
        ).single()
        assertEquals("B${nul}A", MpdArt.idFrom(album.image))
    }
}
