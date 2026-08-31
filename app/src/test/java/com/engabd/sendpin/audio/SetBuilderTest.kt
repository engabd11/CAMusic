package com.engabd.sendpin.audio

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SetBuilderTest {

    private fun candidate(
        id: String,
        energy: Float,
        bpm: Float = 120f,
        durationS: Float = 240f,
        key: MusicalKey? = null,
    ) = SetBuilder.Candidate(id, durationS, bpm, key, energy)

    /** Ten tracks spread evenly across the energy range. */
    private fun spread(): List<SetBuilder.Candidate> =
        (0..9).map { candidate("t$it", energy = it / 9f) }

    // ── the curves ────────────────────────────────────────────────────────

    @Test
    fun `warm-up climbs and wind-down falls`() {
        assertTrue(
            SetBuilder.Curve.WARM_UP.targetAt(0f) < SetBuilder.Curve.WARM_UP.targetAt(1f),
        )
        assertTrue(
            SetBuilder.Curve.WIND_DOWN.targetAt(0f) > SetBuilder.Curve.WIND_DOWN.targetAt(1f),
        )
    }

    @Test
    fun `the arc peaks in the middle and lands at both ends`() {
        val mid = SetBuilder.Curve.ARC.targetAt(0.5f)
        assertTrue(mid > SetBuilder.Curve.ARC.targetAt(0f))
        assertTrue(mid > SetBuilder.Curve.ARC.targetAt(1f))
    }

    @Test
    fun `no curve asks for an energy the library cannot contain`() {
        // A curve that demands 0.0 or 1.0 can only be satisfied by the two most
        // extreme tracks someone owns, which is how an "energy arc" degenerates
        // into "the quietest song you have, then the loudest".
        for (curve in SetBuilder.Curve.entries) {
            for (i in 0..10) {
                val want = curve.targetAt(i / 10f)
                assertTrue("$curve at ${i / 10f} = $want", want in 0.2f..0.95f)
            }
        }
    }

    // ── the ordering ──────────────────────────────────────────────────────

    @Test
    fun `a warm-up set ends louder than it begins`() {
        val set = SetBuilder.build(spread(), SetBuilder.Curve.WARM_UP, targetS = 1_800f)

        assertTrue(set.size >= 4)
        assertTrue(set.last().energy > set.first().energy)
    }

    @Test
    fun `a wind-down set ends quieter than it begins`() {
        val set = SetBuilder.build(spread(), SetBuilder.Curve.WIND_DOWN, targetS = 1_800f)

        assertTrue(set.size >= 4)
        assertTrue(set.last().energy < set.first().energy)
    }

    @Test
    fun `the set lands near the length that was asked for`() {
        // Ten four-minute tracks; ask for twenty minutes.
        val set = SetBuilder.build(spread(), SetBuilder.Curve.PEAK, targetS = 1_200f)
        val actual = SetBuilder.durationOf(set)

        // Within one track of the target, and never wildly over it.
        assertTrue("got ${actual}s", abs(actual - 1_200f) <= 240f)
    }

    @Test
    fun `no track is used twice`() {
        val set = SetBuilder.build(spread(), SetBuilder.Curve.ARC, targetS = 3_600f)

        assertEquals(set.size, set.map { it.id }.toSet().size)
    }

    @Test
    fun `the seed opens the set and is never picked again`() {
        val pool = spread()
        val seed = pool[3]

        val set = SetBuilder.build(pool, SetBuilder.Curve.WARM_UP, targetS = 1_800f, seed = seed)

        assertEquals(seed.id, set.first().id)
        assertEquals(1, set.count { it.id == seed.id })
    }

    @Test
    fun `a seed that is not in the pool is ignored rather than inserted`() {
        val set = SetBuilder.build(
            spread(),
            SetBuilder.Curve.PEAK,
            targetS = 900f,
            seed = candidate("not-in-library", 0.5f),
        )

        assertTrue(set.none { it.id == "not-in-library" })
    }

    // ── scoring ───────────────────────────────────────────────────────────

    @Test
    fun `a compatible key beats an incompatible one, all else equal`() {
        val cMajor = MusicalKey(0, MusicalMode.MAJOR, 1f)
        val gMajor = MusicalKey(7, MusicalMode.MAJOR, 1f)   // one step round the wheel
        val fSharp = MusicalKey(6, MusicalMode.MAJOR, 1f)   // the far side of it
        val previous = candidate("prev", 0.5f, key = cMajor)

        val good = SetBuilder.score(previous, candidate("a", 0.5f, key = gMajor), SetBuilder.Curve.PEAK, 0.5f)
        val bad = SetBuilder.score(previous, candidate("b", 0.5f, key = fSharp), SetBuilder.Curve.PEAK, 0.5f)

        assertTrue(good > bad)
    }

    @Test
    fun `half time counts as a tempo match`() {
        val previous = candidate("prev", 0.5f, bpm = 174f)

        val halved = SetBuilder.score(previous, candidate("a", 0.5f, bpm = 87f), SetBuilder.Curve.PEAK, 0.5f)
        val unrelated = SetBuilder.score(previous, candidate("b", 0.5f, bpm = 128f), SetBuilder.Curve.PEAK, 0.5f)

        assertTrue(halved > unrelated)
    }

    @Test
    fun `energy outweighs key and tempo together`() {
        // A track that sits on the curve beats one that mixes perfectly but is in
        // the wrong place in the set. This is the weighting the whole feature rests
        // on, so it is pinned rather than left to the constants.
        val cMajor = MusicalKey(0, MusicalMode.MAJOR, 1f)
        val previous = candidate("prev", 0.8f, bpm = 120f, key = cMajor)
        val onCurve = candidate("on", SetBuilder.Curve.PEAK.targetAt(0.5f), bpm = 200f)
        val perfectMix = candidate("mix", 0.1f, bpm = 120f, key = cMajor)

        assertTrue(
            SetBuilder.score(previous, onCurve, SetBuilder.Curve.PEAK, 0.5f) >
                SetBuilder.score(previous, perfectMix, SetBuilder.Curve.PEAK, 0.5f),
        )
    }

    @Test
    fun `an unscanned tempo or key simply scores nothing extra`() {
        val previous = candidate("prev", 0.5f, bpm = 120f, key = MusicalKey(0, MusicalMode.MAJOR, 1f))
        val unknown = candidate("a", 0.5f, bpm = 0f, key = null)

        // Not an error, not excluded - it just cannot earn the harmony or tempo
        // terms, so it loses to a track that can.
        val s = SetBuilder.score(previous, unknown, SetBuilder.Curve.PEAK, 0.5f)
        assertTrue(s > 0f)
        assertTrue(s < SetBuilder.score(previous, previous, SetBuilder.Curve.PEAK, 0.5f))
    }

    @Test
    fun `the last slot is left empty rather than padded with a bad fit`() {
        // Ten tracks, 40 minutes of music, asked for 30. A warm-up climbs through
        // the loud end of the library and then has only quiet leftovers - which it
        // used to append, finishing the climb on the quietest track owned.
        val set = SetBuilder.build(spread(), SetBuilder.Curve.WARM_UP, targetS = 1_800f)

        assertTrue(set.last().energy > 0.6f)
        // And it genuinely stopped short rather than running out of tracks.
        assertTrue(set.size < 10)
    }

    // ── edges ─────────────────────────────────────────────────────────────

    @Test
    fun `an empty library or a zero-length target builds nothing`() {
        assertTrue(SetBuilder.build(emptyList(), SetBuilder.Curve.PEAK, 1_800f).isEmpty())
        assertTrue(SetBuilder.build(spread(), SetBuilder.Curve.PEAK, 0f).isEmpty())
    }

    @Test
    fun `a target longer than the library returns the whole library, once`() {
        val set = SetBuilder.build(spread(), SetBuilder.Curve.ARC, targetS = 100_000f)

        assertEquals(10, set.size)
        assertEquals(10, set.map { it.id }.toSet().size)
    }
}
