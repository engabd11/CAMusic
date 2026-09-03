package com.engabd.sendpin.audio

import com.engabd.sendpin.ma.MaItem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two decisions the DJ Radio button now makes before a set starts: what
 * "before sleep" means, and which six songs to offer as its opening.
 *
 * Both are pure, for the same reason the rest of DJ Radio's decisions are — a
 * picker that offers six songs by the same artist, or a "workout" brief that puts a
 * lullaby top of the list, is a bug that can be stated without an Android runtime,
 * a server or a player.
 */
class DjMoodTest {

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
    ) = TrackScan(
        durationS = 200f,
        bpm = bpm,
        confidence = 0.9f,
        beats = FloatArray(64) { it * (60f / bpm) },
        accents = FloatArray(64) { 0.5f },
        downbeat = 0,
        sections = listOf(ScanSection(0f, 200f, 0.5f)),
        intensity = IntensityProfile(
            sigLo = 0.2f, sigHi = 0.8f, dynamics = 0.5f, tilt = tilt,
            tempo = tempo, character = character,
            curve = floatArrayOf(0.5f, 0.5f), curveRateHz = 1f,
        ),
        key = null,
    )

    private fun profile(
        id: String,
        artist: String = "Artist $id",
        genres: List<String> = emptyList(),
        scan: TrackScan? = null,
    ) = DjProfile.profileOf(item(id, artist, genres), scan)

    // ── Moods ──────────────────────────────────────────────────────────────

    @Test
    fun `the open brief accepts everything`() {
        val banger = profile("a", scan = scan(bpm = 174f, character = 0.95f, tempo = 0.95f))
        val lullaby = profile("b", scan = scan(bpm = 62f, character = 0.08f, tempo = 0.05f))
        assertEquals(1f, DjMood.ANYTHING.fit(banger))
        assertEquals(1f, DjMood.ANYTHING.fit(lullaby))
    }

    @Test
    fun `before sleep prefers the quiet slow one`() {
        val banger = profile("a", genres = listOf("Techno"), scan = scan(bpm = 174f, character = 0.95f, tempo = 0.95f))
        val lullaby = profile("b", genres = listOf("Ambient"), scan = scan(bpm = 62f, character = 0.08f, tempo = 0.05f))
        assertTrue(
            DjMood.SLEEP.fit(lullaby) > DjMood.SLEEP.fit(banger),
            "a slow quiet ambient track should answer 'before sleep' better than a techno banger",
        )
    }

    @Test
    fun `workout prefers the fast loud one`() {
        val banger = profile("a", genres = listOf("Drum & Bass"), scan = scan(bpm = 174f, character = 0.95f, tempo = 0.95f))
        val lullaby = profile("b", genres = listOf("Ambient"), scan = scan(bpm = 62f, character = 0.08f, tempo = 0.05f))
        assertTrue(DjMood.WORKOUT.fit(banger) > DjMood.WORKOUT.fit(lullaby))
    }

    /**
     * The graded window, which is what stops a small library having nothing to
     * play: a track just outside the brief should be worse than one inside it and
     * still be worth more than nothing.
     */
    @Test
    fun `just outside the window still scores something`() {
        // SLEEP wants energy 0..0.32 and pace 0..0.36.
        val justOutside = profile("a", scan = scan(bpm = 60f, character = 0.40f, tempo = 0.42f))
        val fit = DjMood.SLEEP.fit(justOutside)
        assertTrue(fit > 0f, "a track a little outside the window is not evidence of nothing")
        assertTrue(fit < 1f, "…but it is not a perfect answer either")
    }

    @Test
    fun `an unscanned track is discounted rather than failed`() {
        val tagged = profile("a", genres = listOf("Ambient"))
        val fit = DjMood.SLEEP.fit(tagged)
        assertTrue(fit > 0f, "genre alone still answers a brief")
        assertTrue(fit < 1f, "nothing has measured it, and the score says so")
    }

    @Test
    fun `a mood with no axis to read falls back to the middle`() {
        // No scan and no genres: nothing to judge on at all.
        assertEquals(0.5f, DjMood.WORKOUT.fit(profile("a")))
    }

    @Test
    fun `ids round-trip`() {
        DjMood.entries.forEach { assertEquals(it, DjMood.byId(it.id)) }
        assertNull(DjMood.byId("not-a-mood"))
    }

    // ── The six ────────────────────────────────────────────────────────────

    @Test
    fun `the picker returns the asked-for count`() {
        val pool = (1..40).map { item("t$it", artist = "Artist $it", genres = listOf("Genre ${it % 7}")) }
        val picked = DjSeeds.pick(pool, { profile(it.itemId, genres = it.genres) }, count = 6, random = Random(7))
        assertEquals(6, picked.size)
        assertEquals(6, picked.map { it.itemId }.distinct().size)
    }

    @Test
    fun `a pool smaller than the ask comes back whole`() {
        val pool = (1..3).map { item("t$it") }
        val picked = DjSeeds.pick(pool, { profile(it.itemId) }, count = 6, random = Random(1))
        assertEquals(3, picked.size)
    }

    @Test
    fun `the six are spread across the library rather than clustered`() {
        // Thirty tracks of one genre and three of another. A plain random six would
        // almost always be six of the first; the spread has to reach the other two.
        val bulk = (1..30).map { item("bulk$it", artist = "Bulk", genres = listOf("Techno")) }
        val rare = (1..3).map { item("rare$it", artist = "Rare $it", genres = listOf("Folk")) }
        val picked = DjSeeds.pick(bulk + rare, { profile(it.itemId, artist = it.subtitle!!, genres = it.genres) }, count = 6, random = Random(3))
        assertTrue(
            picked.any { it.itemId.startsWith("rare") },
            "a farthest-point spread must reach the corner of the library the bulk is not in",
        )
    }

    @Test
    fun `the six avoid repeating an artist while there is a choice`() {
        val sameArtist = (1..20).map { item("s$it", artist = "One Act", genres = listOf("Rock")) }
        val others = (1..10).map { item("o$it", artist = "Act $it", genres = listOf("Rock")) }
        val picked = DjSeeds.pick(
            sameArtist + others,
            { profile(it.itemId, artist = it.subtitle!!, genres = it.genres) },
            count = 6,
            random = Random(11),
        )
        val artists = picked.map { it.subtitle }
        assertEquals(artists.size, artists.distinct().size, "six seeds, six acts, when the library has them")
    }

    @Test
    fun `a brief steers which six are offered`() {
        val slow = (1..10).map { item("slow$it", artist = "Slow $it", genres = listOf("Ambient")) }
        val fast = (1..10).map { item("fast$it", artist = "Fast $it", genres = listOf("Techno")) }
        val scans = buildMap {
            slow.forEach { put(it.itemId, scan(bpm = 60f, character = 0.10f, tempo = 0.08f)) }
            fast.forEach { put(it.itemId, scan(bpm = 172f, character = 0.95f, tempo = 0.95f)) }
        }
        val picked = DjSeeds.pick(
            slow + fast,
            { DjProfile.profileOf(it, scans[it.itemId]) },
            count = 6,
            mood = DjMood.SLEEP,
            random = Random(5),
        )
        assertTrue(
            picked.count { it.itemId.startsWith("slow") } >= 4,
            "'before sleep' should mostly offer the slow half: got ${picked.map { it.itemId }}",
        )
    }

    @Test
    fun `blank ids never reach the picker`() {
        val pool = listOf(item(""), item("real"))
        val picked = DjSeeds.pick(pool, { profile(it.itemId) }, count = 6, random = Random(2))
        assertEquals(listOf("real"), picked.map { it.itemId })
    }

    @Test
    fun `the note says what makes a seed different`() {
        val note = DjSeeds.note(profile("a", genres = listOf("Techno"), scan = scan(bpm = 128f, character = 0.75f)))
        assertNotNull(note)
        assertTrue(note.contains("128 BPM"), "the tempo is half of what makes two seeds different: $note")
        assertTrue(note.contains("Techno", ignoreCase = true), "so is the genre: $note")
    }

    @Test
    fun `a track the library says nothing about gets no note`() {
        assertNull(DjSeeds.note(profile("a")))
    }

    // ── The brief reaching the set ─────────────────────────────────────────

    /**
     * The mood is a third of a pick's score, not all of it — see
     * [DjSetBuilder]. The set it builds should therefore live in the brief's
     * corner of the library without the transitions inside it becoming arbitrary.
     */
    @Test
    fun `a briefed set is built from what answers the brief`() {
        val slow = (1..12).map { item("slow$it", artist = "Slow $it", genres = listOf("Ambient")) }
        val fast = (1..12).map { item("fast$it", artist = "Fast $it", genres = listOf("Techno")) }
        val scans = buildMap {
            slow.forEach { put(it.itemId, scan(bpm = 60f, character = 0.10f, tempo = 0.08f)) }
            fast.forEach { put(it.itemId, scan(bpm = 172f, character = 0.95f, tempo = 0.95f)) }
        }
        val built = DjSetBuilder.build(
            seed = null,
            pool = slow + fast,
            profileOf = { DjProfile.profileOf(it, scans[it.itemId]) },
            count = 6,
            strictness = 0.5f,
            harmonic = false,
            mood = DjMood.SLEEP,
        )
        assertEquals(6, built.size)
        assertTrue(
            built.count { it.itemId.startsWith("slow") } >= 5,
            "a 'before sleep' set should be slow tracks: got ${built.map { it.itemId }}",
        )
    }

    @Test
    fun `no brief leaves the set exactly as it was`() {
        val pool = (1..8).map { item("t$it", genres = listOf("Rock")) }
        val profileOf = { i: MaItem -> profile(i.itemId, genres = i.genres) }
        val without = DjSetBuilder.build(null, pool, profileOf, 4, 0.5f, harmonic = false)
        val open = DjSetBuilder.build(null, pool, profileOf, 4, 0.5f, harmonic = false, mood = DjMood.ANYTHING)
        assertEquals(without.map { it.itemId }, open.map { it.itemId })
    }
}
