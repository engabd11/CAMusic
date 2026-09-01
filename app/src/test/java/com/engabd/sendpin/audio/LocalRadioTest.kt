package com.engabd.sendpin.audio

import com.engabd.sendpin.ma.MaItem
import kotlinx.coroutines.runBlocking
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
    ) : RadioSource {
        val asked = mutableListOf<String>()
        override suspend fun similarToTrack(trackId: String, count: Int) =
            similarTrack.also { asked += "similarTrack" }
        override suspend fun similarToAlbum(albumId: String, count: Int) =
            similarAlbum.also { asked += "similarAlbum" }
        override suspend fun topSongs(artistName: String, count: Int) =
            top.also { asked += "top" }
        override suspend fun byGenre(genre: String, count: Int) =
            this.genre.also { asked += "genre" }
        override suspend fun random(count: Int) =
            this.random.also { asked += "random" }
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
        assertEquals(listOf("similarTrack", "similarAlbum", "top", "genre", "random"), source.asked)
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
        radio.remember(listOf("x", "y", "z"))
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
        val downloads = listOf(track("d1"), track("d2"))
        val first = radio.offline(downloads, seed = null, count = 1, exclude = setOf("d1"))
        assertEquals(listOf("d2"), first.map { it.itemId })
        // d1 excluded, d2 now in history: nothing left to offer.
        assertTrue(radio.offline(downloads, seed = null, count = 1, exclude = setOf("d1")).isEmpty())
    }

    @Test
    fun `offline with nothing downloaded offers nothing rather than crashing`() {
        assertTrue(LocalRadio().offline(emptyList(), seed = track("seed"), count = 5).isEmpty())
    }
}
