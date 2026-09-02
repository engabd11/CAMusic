package com.engabd.sendpin.audio

import com.engabd.sendpin.ma.MaItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * DJ Radio's two decisions: how alike two tracks are, and what that makes the
 * running order.
 *
 * Both halves are pure, which is the point of them being separate from the player
 * and the library — an ordering that puts a folk record after a techno one is a bug
 * a test can state, and stating it needs no Android and no server.
 */
class DjRadioTest {

    private fun item(
        id: String,
        artist: String = "Artist $id",
        genres: List<String> = emptyList(),
    ) = MaItem(
        itemId = id, provider = "subsonic", name = "Track $id",
        uri = id, mediaType = "track", subtitle = artist,
        image = null, duration = 200, genres = genres,
    )

    private fun scan(
        bpm: Float = 120f,
        character: Float = 0.5f,
        tilt: Float = 0f,
        tempo: Float = 0.5f,
        dynamics: Float = 0.5f,
        key: MusicalKey? = null,
    ) = TrackScan(
        durationS = 200f,
        bpm = bpm,
        confidence = 0.9f,
        beats = FloatArray(64) { it * (60f / bpm) },
        accents = FloatArray(64) { 0.5f },
        downbeat = 0,
        sections = listOf(ScanSection(0f, 200f, 0.5f)),
        intensity = IntensityProfile(
            sigLo = 0.2f, sigHi = 0.8f, dynamics = dynamics, tilt = tilt,
            tempo = tempo, character = character,
            curve = floatArrayOf(0.5f, 0.5f), curveRateHz = 1f,
        ),
        key = key,
    )

    private fun profile(
        id: String,
        artist: String = "Artist $id",
        genres: List<String> = emptyList(),
        scan: TrackScan? = null,
    ) = DjProfile.profileOf(item(id, artist, genres), scan)

    // ── Similarity ─────────────────────────────────────────────────────────

    @Test
    fun `a track like the seed scores above one that only shares its genre`() {
        val seed = profile("seed", genres = listOf("House"), scan = scan(bpm = 124f, character = 0.7f))
        val twin = profile("twin", genres = listOf("House"), scan = scan(bpm = 126f, character = 0.72f))
        val ballad = profile("ballad", genres = listOf("House"), scan = scan(bpm = 72f, character = 0.15f))

        val near = DjSimilarity.score(seed, twin)
        val far = DjSimilarity.score(seed, ballad)
        assertTrue(near > far, "a 126 BPM banger should beat a 72 BPM ballad: $near vs $far")
    }

    @Test
    fun `half time is the same tempo, not the opposite of it`() {
        // 174 against 87 is the most natural transition in a drum and bass set, and a
        // radio that scored it as a mismatch would refuse the obvious move.
        val seed = profile("seed", genres = listOf("Drum and Bass"), scan = scan(bpm = 174f))
        val halved = profile("half", genres = listOf("Drum and Bass"), scan = scan(bpm = 87f))
        val awkward = profile("odd", genres = listOf("Drum and Bass"), scan = scan(bpm = 133f))

        assertTrue(
            DjSimilarity.score(seed, halved) > DjSimilarity.score(seed, awkward),
            "half time should read as a match",
        )
    }

    @Test
    fun `genre carries more weight than any single measured axis`() {
        // The whole complaint DJ Radio answers is music that "sounds random", and two
        // tracks can measure the same and still be a string quartet and a techno
        // record. So a same-genre track with a different tempo must beat a
        // tempo-matched track from another genre entirely.
        val seed = profile("seed", genres = listOf("Jazz"), scan = scan(bpm = 100f, character = 0.4f))
        val sameGenre = profile("jazz", genres = listOf("Jazz"), scan = scan(bpm = 132f, character = 0.4f))
        val sameTempo = profile("edm", genres = listOf("Techno"), scan = scan(bpm = 100f, character = 0.4f))

        assertTrue(
            DjSimilarity.score(seed, sameGenre) > DjSimilarity.score(seed, sameTempo),
            "genre should outrank a tempo match across a genre boundary",
        )
    }

    @Test
    fun `an unscanned candidate is ranked, not excluded`() {
        val seed = profile("seed", genres = listOf("Rock"), scan = scan())
        val scanned = profile("a", genres = listOf("Rock"), scan = scan())
        val bare = profile("b", genres = listOf("Rock"))

        val withScan = DjSimilarity.score(seed, scanned)
        val without = DjSimilarity.score(seed, bare)
        assertTrue(without > 0f, "an unscanned track is still a candidate")
        assertTrue(withScan > without, "a checked track should lead an unchecked one")
    }

    @Test
    fun `harmonic mode separates two candidates that are otherwise identical`() {
        val cMajor = MusicalKey(0, MusicalMode.MAJOR, 0.9f)
        val gMajor = MusicalKey(7, MusicalMode.MAJOR, 0.9f)   // one step round the wheel
        val fSharp = MusicalKey(6, MusicalMode.MAJOR, 0.9f)   // as far away as it gets

        val seed = profile("seed", genres = listOf("House"), scan = scan(key = cMajor))
        val near = profile("near", genres = listOf("House"), scan = scan(key = gMajor))
        val far = profile("far", genres = listOf("House"), scan = scan(key = fSharp))

        // Off, the key is not consulted at all and the two are a draw.
        assertEquals(
            DjSimilarity.score(seed, near, harmonic = false),
            DjSimilarity.score(seed, far, harmonic = false),
            0.0001f,
        )
        assertTrue(
            DjSimilarity.score(seed, near, harmonic = true) >
                DjSimilarity.score(seed, far, harmonic = true),
            "with Harmonic DJ mode on, a compatible key should win",
        )
    }

    @Test
    fun `a terser tag on one side is not a disagreement`() {
        // "Rock" and "Alternative Rock" is one librarian being briefer than another.
        assertEquals(1f, DjSimilarity.genreOverlap(listOf("rock"), listOf("alternative", "rock")))
        assertEquals(0f, DjSimilarity.genreOverlap(listOf("rock"), listOf("techno")))
    }

    @Test
    fun `an untagged track is not evidence against itself`() {
        val neither = DjSimilarity.genreOverlap(emptyList(), emptyList())
        val oneSide = DjSimilarity.genreOverlap(listOf("rock"), emptyList())
        val mismatch = DjSimilarity.genreOverlap(listOf("rock"), listOf("techno"))
        assertTrue(oneSide > mismatch, "not knowing should beat knowing it is wrong")
        assertTrue(neither > mismatch)
    }

    @Test
    fun `genre tokens survive the spellings libraries actually use`() {
        assertEquals(listOf("electronic", "downtempo"), DjProfile.splitGenre("Electronic/Downtempo"))
        assertEquals(listOf("alternative", "rock"), DjProfile.splitGenre("Alternative Rock"))
        // Stop words and short fragments are dropped, so "The Music" is not a genre.
        assertEquals(emptyList(), DjProfile.splitGenre("The Music"))
    }

    // ── The set ────────────────────────────────────────────────────────────

    private fun buildFrom(
        seed: MaItem,
        seedScan: TrackScan?,
        pool: List<Pair<MaItem, TrackScan?>>,
        count: Int,
        strictness: Float,
        harmonic: Boolean = false,
        recentArtists: List<String> = emptyList(),
    ): List<MaItem> {
        val scans = pool.toMap()
        return DjSetBuilder.build(
            seed = DjProfile.profileOf(seed, seedScan),
            pool = pool.map { it.first },
            profileOf = { DjProfile.profileOf(it, scans[it]) },
            count = count,
            strictness = strictness,
            harmonic = harmonic,
            recentArtists = recentArtists,
        )
    }

    @Test
    fun `a tight set leads with the closest track and leaves the outlier last`() {
        val seed = item("seed", genres = listOf("House"))
        val pool = listOf(
            item("folk", genres = listOf("Folk")) to scan(bpm = 84f, character = 0.2f),
            item("house", genres = listOf("House")) to scan(bpm = 124f, character = 0.7f),
        )
        val picked = buildFrom(seed, scan(bpm = 126f, character = 0.72f), pool, 2, strictness = 0.8f)
        assertEquals(listOf("house", "folk"), picked.map { it.itemId })
    }

    @Test
    fun `it never stalls, however tight the bar`() {
        // The bar comes down until something clears it. A radio that went quiet
        // because nothing was similar enough has failed at the only job it has.
        val seed = item("seed", genres = listOf("House"))
        val pool = listOf(
            item("a", genres = listOf("Bluegrass")) to null,
            item("b", genres = listOf("Opera")) to null,
        )
        val picked = buildFrom(seed, scan(), pool, 2, strictness = 1f)
        assertEquals(2, picked.size, "a maximum-strictness set still has to play something")
    }

    @Test
    fun `no track is served twice in one batch`() {
        val seed = item("seed", genres = listOf("House"))
        val pool = (1..6).map { item("t$it", genres = listOf("House")) to scan() }
        val picked = buildFrom(seed, scan(), pool, 5, strictness = 0.5f)
        assertEquals(picked.size, picked.map { it.itemId }.distinct().size)
    }

    @Test
    fun `the pool can hand the same track over twice and still fill a batch`() {
        // Two rungs of the ladder routinely answer with the same song. Collapsing
        // those must not also collapse the batch.
        val seed = item("seed", genres = listOf("House"))
        val one = item("dup", genres = listOf("House"))
        val pool = listOf(one to scan(), one to scan(), item("other", genres = listOf("House")) to scan())
        val picked = buildFrom(seed, scan(), pool, 3, strictness = 0.5f)
        assertEquals(listOf("dup", "other"), picked.map { it.itemId }.sorted())
    }

    @Test
    fun `an artist run is broken rather than extended`() {
        // Two in a row reads as a DJ making a point. Four reads as a broken shuffle —
        // so once the run is at the limit, someone else goes next even though the
        // same act is the closest match to itself.
        val seed = item("seed", artist = "Boards", genres = listOf("Ambient"))
        val pool = listOf(
            item("more-boards", artist = "Boards", genres = listOf("Ambient")) to scan(),
            item("someone-else", artist = "Ulrich", genres = listOf("Ambient")) to scan(),
        )
        val picked = buildFrom(
            seed, scan(), pool, 1, strictness = 0.5f,
            recentArtists = listOf("Boards", "Boards"),
        )
        assertEquals("someone-else", picked.single().itemId)
    }

    @Test
    fun `with no seed at all it still opens the set`() {
        val pool = listOf(item("a") to null, item("b") to null)
        val picked = DjSetBuilder.build(
            seed = null,
            pool = pool.map { it.first },
            profileOf = { DjProfile.profileOf(it, null) },
            count = 2,
            strictness = 0.9f,
            harmonic = false,
        )
        assertEquals(2, picked.size)
    }

    @Test
    fun `a tighter set asks for a wider pool to choose from`() {
        val loose = DjSetBuilder.poolSizeFor(10, 0f)
        val tight = DjSetBuilder.poolSizeFor(10, 1f)
        assertTrue(tight > loose, "rejecting more means needing more: $loose vs $tight")
        assertTrue(tight <= 120, "and still one request's worth")
    }

    @Test
    fun `strictness moves the bar and stays inside the unit range`() {
        assertTrue(DjSimilarity.thresholdFor(0f) < DjSimilarity.thresholdFor(1f))
        assertTrue(DjSimilarity.thresholdFor(0f) > 0f, "even 'surprise me' has a floor")
        assertTrue(DjSimilarity.thresholdFor(1f) <= 1f)
    }

    @Test
    fun `an empty pool is an empty set rather than a crash`() {
        assertEquals(emptyList(), buildFrom(item("seed"), null, emptyList(), 5, 0.5f))
    }

    @Test
    fun `profiles read the scan and the tags together`() {
        val p = DjProfile.profileOf(
            item("x", artist = "Röyksopp, Susanne Sundfør", genres = listOf("Electronic/Downtempo")),
            scan(bpm = 118f, character = 0.55f, tilt = 1f),
        )
        assertEquals("Röyksopp", p.artist)
        assertEquals(listOf("electronic", "downtempo"), p.genres)
        assertEquals(118f, p.bpm)
        assertEquals(0.55f, p.energy)
        // tilt +1 is fully bass-heavy, which is brightness 0.
        assertNotNull(p.brightness)
        assertEquals(0f, p.brightness!!, 0.0001f)
        assertTrue(p.scanned)
    }
}
