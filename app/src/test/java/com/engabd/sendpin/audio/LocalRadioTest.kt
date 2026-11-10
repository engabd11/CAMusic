package com.engabd.sendpin.audio

import com.engabd.sendpin.local.db.TrackSignalEntity
import com.engabd.sendpin.ma.MaItem
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The radio that keeps the Navidrome player going past the end of the queue.
 *
 * The network half is a `RadioSource`, so what is covered here is the part with
 * decisions in it: which rung is asked first, when a rung is skipped, and what is
 * filtered out before anything reaches the queue.
 */
class LocalRadioTest {

    private fun track(
        id: String,
        artist: String? = "Artist",
        album: String? = "album-1",
        genre: String? = null,
    ) = MaItem(
        itemId = id, provider = "subsonic", name = "Track $id",
        uri = id, mediaType = "track", subtitle = artist,
        image = null, duration = 180,
        parentId = album,
        genres = listOfNotNull(genre),
    )

    /** Records what was asked, and answers only what it was told to. */
    private class FakeSource(
        val similarTrack: List<MaItem> = emptyList(),
        val similarAlbum: List<MaItem> = emptyList(),
        val top: List<MaItem> = emptyList(),
        val genre: List<MaItem> = emptyList(),
        val random: List<MaItem> = emptyList(),
        val sounds: List<MaItem> = emptyList(),
        /** A genre this small answers nothing past its first page. */
        val genreSize: Int = Int.MAX_VALUE,
    ) : RadioSource {
        val asked = mutableListOf<String>()
        val genresAsked = mutableListOf<Pair<String, Int>>()
        override suspend fun similarToTrack(trackId: String, count: Int) =
            similarTrack.also { asked += "similarTrack" }
        override suspend fun similarToAlbum(albumId: String, count: Int) =
            similarAlbum.also { asked += "similarAlbum" }
        override suspend fun topSongs(artistName: String, count: Int) =
            top.also { asked += "top" }
        override suspend fun byGenre(genre: String, count: Int, offset: Int): List<MaItem> {
            asked += "genre"
            genresAsked += genre to offset
            return if (offset > 0 && offset >= genreSize) emptyList() else this.genre
        }
        override suspend fun random(count: Int) =
            this.random.also { asked += "random" }
        override suspend fun soundsLike(seed: MaItem, count: Int) =
            sounds.also { asked += "soundsLike" }
    }

    @Test
    fun `shuffled, it goes straight to the bottom rung and asks for nothing else`() = runBlocking {
        // The transport shuffle is the listener saying they do not want "more like
        // this", so the ladder — whose whole job is to stay near the seed — is not
        // the right question. Only `random` should be asked.
        val source = FakeSource(
            similarTrack = listOf(track("near")),
            random = listOf(track("a"), track("b"), track("c")),
        )
        val picked = LocalRadio().random(source, count = 3)
        assertEquals(listOf("random"), source.asked)
        assertEquals(setOf("a", "b", "c"), picked.map { it.itemId }.toSet())
    }

    @Test
    fun `a shuffled pick never repeats the queue or the session`() = runBlocking {
        val radio = LocalRadio()
        val source = FakeSource(random = listOf(track("a"), track("b"), track("c"), track("d")))
        val first = radio.random(source, count = 2, exclude = setOf("a"))
        assertTrue(first.none { it.itemId == "a" })
        // Whatever the first call served is history now, so a second call cannot
        // hand back the same songs — the failure that reads as "it keeps playing the
        // same five tracks".
        val second = radio.random(source, count = 2, exclude = setOf("a"))
        assertTrue(second.none { it.itemId in first.map { t -> t.itemId } })
    }

    @Test
    fun `the strongest rung answers and the rest are never asked`() = runBlocking {
        val source = FakeSource(similarTrack = listOf(track("a"), track("b")))
        val picked = LocalRadio().next(source, seed = track("seed"), count = 5)
        assertEquals(listOf("a", "b"), picked.map { it.itemId })
        assertEquals(listOf("similarTrack"), source.asked)
    }

    @Test
    fun `an empty rung falls through to the next`() = runBlocking {
        val source = FakeSource(similarTrack = emptyList(), similarAlbum = listOf(track("c")))
        val picked = LocalRadio().next(source, seed = track("seed"), count = 5)
        assertEquals(listOf("c"), picked.map { it.itemId })
        assertEquals(listOf("similarTrack", "similarAlbum"), source.asked)
    }

    @Test
    fun `random is the floor, so a server with no metadata still plays something`() = runBlocking {
        // The realistic plain-Subsonic case: no last.fm, no similarity, no top songs.
        val source = FakeSource(random = listOf(track("r1"), track("r2")))
        val picked = LocalRadio().next(source, seed = track("seed", genre = "Jazz"), count = 5)
        assertEquals(listOf("r1", "r2"), picked.map { it.itemId })
        // distinct: an empty genre page from a random offset is asked again from the top.
        assertEquals(listOf("similarTrack", "similarAlbum", "top", "genre", "random"), source.asked.distinct())
    }

    @Test
    fun `rungs the seed cannot supply are skipped entirely`() = runBlocking {
        // No artist, no album, no genre — only the track id and the floor are askable.
        val seed = track("seed", artist = null, album = null, genre = null)
        val source = FakeSource(random = listOf(track("r1")))
        LocalRadio().next(source, seed = seed, count = 5)
        assertEquals(listOf("similarTrack", "random"), source.asked)
    }

    @Test
    fun `no seed at all still has a floor`() = runBlocking {
        val source = FakeSource(random = listOf(track("r1")))
        val picked = LocalRadio().next(source, seed = null, count = 5)
        assertEquals(listOf("r1"), picked.map { it.itemId })
        assertEquals(listOf("random"), source.asked)
    }

    @Test
    fun `what is already queued is never suggested`() = runBlocking {
        // The one mistake that makes a radio feel broken rather than uninspired.
        val source = FakeSource(similarTrack = listOf(track("a"), track("b"), track("c")))
        val picked = LocalRadio().next(source, seed = track("seed"), count = 5, exclude = setOf("a", "c"))
        assertEquals(listOf("b"), picked.map { it.itemId })
    }

    @Test
    fun `a rung that is entirely duplicates falls through rather than returning nothing`() = runBlocking {
        val source = FakeSource(
            similarTrack = listOf(track("a")),
            similarAlbum = listOf(track("z")),
        )
        val picked = LocalRadio().next(source, seed = track("seed"), count = 5, exclude = setOf("a"))
        assertEquals(listOf("z"), picked.map { it.itemId })
    }

    @Test
    fun `history stops the radio circling back on itself`() = runBlocking {
        val radio = LocalRadio()
        val source = FakeSource(similarTrack = listOf(track("a"), track("b")))
        val first = radio.next(source, seed = track("seed"), count = 1)
        assertEquals(listOf("a"), first.map { it.itemId })
        // "a" is now spent, so the same answer from the server yields the next one.
        val second = radio.next(source, seed = track("seed"), count = 1)
        assertEquals(listOf("b"), second.map { it.itemId })
    }

    @Test
    fun `history is bounded, so a long session forgets its oldest picks`() = runBlocking {
        val radio = LocalRadio(historyLimit = 2)
        radio.remember(listOf(track("x"), track("y"), track("z")))
        val source = FakeSource(similarTrack = listOf(track("x")))
        // "x" fell out of a two-deep history, so it is playable again.
        assertEquals(listOf("x"), radio.next(source, seed = track("seed"), count = 5).map { it.itemId })
    }

    @Test
    fun `duplicates within one answer are collapsed`() = runBlocking {
        val source = FakeSource(similarTrack = listOf(track("a"), track("a"), track("b")))
        val picked = LocalRadio().next(source, seed = track("seed"), count = 5)
        assertEquals(listOf("a", "b"), picked.map { it.itemId })
    }

    @Test
    fun `a bonus re-ranks within the winning rung, not across rungs`() = runBlocking {
        // Harmonic DJ mode's online widening: the bonus favours "c" within the rung
        // that actually answered, but must never pull a weaker rung ahead of it.
        val source = FakeSource(similarTrack = listOf(track("a"), track("b"), track("c")))
        val picked = LocalRadio().next(
            source, seed = track("seed"), count = 2,
            bonus = { if (it.itemId == "c") 5 else 0 },
        )
        assertEquals(listOf("c", "a"), picked.map { it.itemId })
        assertEquals(listOf("similarTrack"), source.asked)
    }

    @Test
    fun `a default bonus leaves ordering exactly as before`() = runBlocking {
        val source = FakeSource(similarTrack = listOf(track("a"), track("b"), track("c")))
        val picked = LocalRadio().next(source, seed = track("seed"), count = 5)
        assertEquals(listOf("a", "b", "c"), picked.map { it.itemId })
    }

    // ─── The pool and the listener's history ────────────────────────────────

    @Test
    fun `the genre page rotates through every genre the seed has`() = runBlocking {
        val radio = LocalRadio(random = Random(7))
        val seed = track("seed", album = null).copy(genres = listOf("Jazz", "Soul"))
        val source = FakeSource(genre = listOf(track("g")))
        radio.pool(source, seed, want = 40)
        radio.pool(source, seed, want = 40)
        radio.pool(source, seed, want = 40)
        assertEquals(listOf("Jazz", "Soul", "Jazz"), source.genresAsked.map { it.first })
    }

    @Test
    fun `the genre page starts somewhere random, and from the top when that is past the end`() = runBlocking {
        val seed = track("seed", album = null, genre = "Jazz")
        val offsets = (1..20).map { n ->
            val source = FakeSource(genre = listOf(track("g")))
            LocalRadio(random = Random(n)).pool(source, seed, want = 40)
            source.genresAsked.single().second
        }
        assertTrue(offsets.distinct().size > 1, "always the same page: $offsets")
        assertTrue(offsets.all { it in 0..LocalRadio.GENRE_MAX_OFFSET })

        // A genre smaller than the offset asked answers nothing; page one is asked instead.
        val small = FakeSource(genre = listOf(track("g")), genreSize = 1)
        val pool = LocalRadio(random = Random(3)).pool(small, seed, want = 40)
        assertTrue(pool.any { it.itemId == "g" })
        assertEquals(0, small.genresAsked.last().second)
    }

    @Test
    fun `with no similarity on the server the pool asks what sounds alike on this phone`() = runBlocking {
        // Plex, MPD, foobar2000 and local files have no similar-songs call at all.
        val bare = FakeSource(sounds = listOf(track("s1")), random = List(60) { track("r$it") })
        val pool = LocalRadio().pool(bare, track("seed"), want = 40)
        assertTrue("soundsLike" in bare.asked)
        assertEquals("s1", pool.first().itemId, "a sonic match leads the pool, ahead of random")

        // A server that does answer is not second-guessed.
        val subsonic = FakeSource(similarTrack = listOf(track("a")), sounds = listOf(track("s1")))
        LocalRadio().pool(subsonic, track("seed"), want = 40)
        assertTrue("soundsLike" !in subsonic.asked)
    }

    @Test
    fun `one artist's top songs are a small share of the pool`() = runBlocking {
        val top = List(40) { track("t$it", artist = "Same Act") }
        val others = List(60) { track("r$it", artist = "Act $it") }
        val pool = LocalRadio().pool(FakeSource(top = top, random = others), track("seed"), want = 40)
        val fromTop = pool.count { it.itemId.startsWith("t") }
        assertEquals(LocalRadio.topSongsCap(40), fromTop)
        assertTrue(fromTop <= 6)
    }

    @Test
    fun `what was played or served before a restart is not served again`() = runBlocking {
        val radio = LocalRadio()
        val now = 1_000_000_000L
        radio.memory = RadioMemory.fromDisk(
            // A counted play and a serve, both under the one key form.
            signals = listOf(
                TrackSignalEntity("subsonic:a", lastServedAt = now - 60_000),
                TrackSignalEntity("subsonic:b", lastServedAt = now - 30_000),
            ),
            clock = { now },
            keyOf = { "${it.provider}:${it.itemId}" },
        )
        val source = FakeSource(similarTrack = listOf(track("a"), track("b"), track("c")))
        assertEquals(listOf("c"), radio.next(source, seed = track("seed"), count = 5).map { it.itemId })
    }

    @Test
    fun `a library smaller than the history window still plays, longest-ago heard first`() = runBlocking {
        var now = 0L
        val radio = LocalRadio()
        radio.memory = RadioMemory(clock = { now })
        listOf("a", "b", "c").forEach { now += 1_000; radio.remember(listOf(track(it))) }
        val source = FakeSource(similarTrack = listOf(track("c"), track("b"), track("a")))
        // Every track was heard: the ladder used to come back empty and the music stopped.
        assertEquals(listOf("a"), radio.next(source, seed = track("seed"), count = 1).map { it.itemId })

        // The DJ pool lets back as many as its batch needs, the same way round.
        val pool = radio.pool(FakeSource(random = listOf(track("c"), track("b"))), track("seed"), want = 40, count = 1)
        assertEquals(listOf("b"), pool.map { it.itemId })
    }

    @Test
    fun `a queue started anywhere still has a seed`() {
        // Album, artist and playlist pages, the car and Now Playing queue tracks the
        // library screen never sees; the seed is rebuilt from the track itself.
        val playing = LocalTrack(
            id = "ma-1", title = "Song", artist = "Act, Guest", album = "Record",
            genre = "Jazz", scrobbleId = "nav-1", scrobbleProvider = "subsonic", durationMs = 200_000,
        )
        val seed = LocalRadio.seedOf(playing, recorded = null)
        assertEquals("nav-1", seed.itemId)
        assertEquals("subsonic", seed.provider)
        assertEquals("Act, Guest", seed.subtitle)
        assertEquals(listOf("Jazz"), seed.genres)

        val recorded = track("nav-1", genre = "Soul")
        assertEquals(recorded, LocalRadio.seedOf(playing, recorded))
    }

    @Test
    fun `the rebuilt seed reaches the similar, artist and genre rungs`() = runBlocking {
        val seed = LocalRadio.seedOf(LocalTrack(id = "x", title = "Song", artist = "Act", genre = "Jazz"), null)
        val source = FakeSource(random = listOf(track("r")))
        LocalRadio().next(source, seed = seed, count = 5)
        assertEquals(listOf("similarTrack", "top", "genre", "random"), source.asked.distinct())
    }

    // ─── Offline ─────────────────────────────────────────────────────────────

    @Test
    fun `offline picks from the seed's genre, not the whole library`() {
        // Sized so the assertion cannot depend on the shuffle: 12 matching tracks is
        // exactly the window `offline` considers for count = 4, so every pick has to
        // come from them. An earlier version of this test had 4 matches in a window of
        // 12 and asserted that at least one was picked, which is true about 86% of the
        // time — a test that fails one run in seven is worse than no test.
        val jazz = List(12) { track("j$it", genre = "Jazz") }
        val metal = List(8) { track("m$it", genre = "Metal") }
        val picked = LocalRadio().offline(jazz + metal, seed = track("seed", genre = "Jazz"), count = 4)
        assertEquals(4, picked.size)
        assertTrue(picked.all { it.itemId.startsWith("j") })
    }

    @Test
    fun `offline respects the queue and its own history`() {
        val radio = LocalRadio()
        val downloads = listOf(track("d1"), track("d2"), track("d3"))
        val first = radio.offline(downloads, seed = null, count = 1, exclude = setOf("d1"))
        assertTrue(first.single().itemId in setOf("d2", "d3"))
        // d1 queued, the first pick now in history: only the other one is left.
        val second = radio.offline(downloads, seed = null, count = 1, exclude = setOf("d1"))
        assertEquals(setOf("d2", "d3") - first.single().itemId, second.map { it.itemId }.toSet())
        // Whatever is queued stays out even when everything else has been heard.
        assertTrue(radio.offline(downloads, seed = null, count = 1, exclude = setOf("d1", "d2", "d3")).isEmpty())
    }

    @Test
    fun `offline on a tiny library plays the longest-ago heard rather than stopping`() {
        // Was: empty, and the queue ended. Two downloads and a 200-deep history is a
        // library smaller than its own no-repeat window.
        val radio = LocalRadio()
        val downloads = listOf(track("d1"), track("d2"))
        radio.memory.served(listOf("d1"))
        radio.memory.served(listOf("d2"))
        assertEquals(listOf("d1"), radio.offline(downloads, seed = null, count = 1).map { it.itemId })
    }

    @Test
    fun `offline with nothing downloaded offers nothing rather than crashing`() {
        assertTrue(LocalRadio().offline(emptyList(), seed = track("seed"), count = 5).isEmpty())
    }
}
