package com.engabd.sendpin.audio

import java.util.Random
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Musical structure: builds, drops, breakdowns — and no false triggers.
 *
 * The false-trigger half matters as much as the detection half. A drop
 * detonates a full-field swell, so a tracker that fires on any loud bar turns a
 * steady verse into a strobe.
 *
 * Ported from syncoV2 `tests/test_structure.py`.
 */
class StructureTrackerTest {

    private val dt = 0.02f

    private fun frame(
        energy: Float,
        centroid: Float,
        bass: Float,
        beat: Boolean = false,
        flux: Float = 0f,
    ) = AnalysisFrame(
        bands = mapOf("sub_bass" to bass, "bass" to bass, "high" to centroid),
        energy = energy,
        centroid = centroid,
        beat = beat,
        flux = flux,
    )

    @Test
    fun `a build resolves into exactly one drop`() {
        val st = StructureTracker(dt)
        var sawBuilding = false
        var buildPeak = 0f
        var drops = 0

        // Steady.
        for (i in 0 until (3f / dt).toInt()) {
            st.update(frame(0.4f, 0.3f, 0.6f, beat = i % 20 == 0))
        }
        // Build: energy and brightness climbing, bass thinning out.
        val n = (4f / dt).toInt()
        for (i in 0 until n) {
            val p = i.toFloat() / n
            val s = st.update(
                frame(0.4f + 0.2f * p, 0.3f + 0.5f * p, 0.6f - 0.3f * p, beat = i % 20 == 0)
            )
            sawBuilding = sawBuilding || s.phase == SongPhase.BUILDING
            buildPeak = max(buildPeak, s.buildProgress)
        }
        // Drop: bass slams back, broadband surge.
        for (i in 0 until (0.3f / dt).toInt()) {
            val s = st.update(frame(0.95f, 0.5f, 0.95f, beat = true))
            if (s.dropNow) {
                drops++
                assertEquals(SongPhase.DROP, s.phase, "dropNow without the DROP phase")
            }
        }

        assertTrue(sawBuilding, "never entered the building phase")
        assertTrue(buildPeak > 0.4f, "build only reached $buildPeak")
        assertEquals(1, drops, "expected one drop edge, not a burst")
    }

    @Test
    fun `steady music never false triggers`() {
        val st = StructureTracker(dt)
        val rng = Random(0)
        var falseDrops = 0
        var maxBuild = 0f
        for (i in 0 until (20f / dt).toInt()) {
            val e = 0.5f + 0.08f * (rng.nextFloat() * 2f - 1f)
            val b = 0.6f + 0.1f * (rng.nextFloat() * 2f - 1f)
            val s = st.update(frame(e, 0.35f, b, beat = i % 24 == 0))
            if (s.dropNow) falseDrops++
            maxBuild = max(maxBuild, s.buildProgress)
        }
        assertEquals(0, falseDrops, "a steady loop produced $falseDrops drops")
        assertTrue(maxBuild < 0.2f, "a steady loop read as a build ($maxBuild)")
    }

    @Test
    fun `lookahead makes drop imminent predictive`() {
        val st = StructureTracker(dt)
        // A rising ramp in the upcoming frames signals a drop before the local
        // build heuristic would have any reason to.
        val future = (0 until 6).map { frame(0.2f + 0.1f * it, 0.3f, 0.3f) }
        val s = st.update(frame(0.3f, 0.3f, 0.3f), lookahead = future)
        assertTrue(s.dropImminent, "lookahead did not predict the drop")
    }

    @Test
    fun `a drop holds a refractory instead of retriggering`() {
        // Without this, the sustained loudness after a drop reads as a fresh
        // drop every frame and the swell never resolves.
        val st = StructureTracker(dt)
        for (i in 0 until (3f / dt).toInt()) st.update(frame(0.4f, 0.3f, 0.6f))
        val n = (4f / dt).toInt()
        for (i in 0 until n) {
            val p = i.toFloat() / n
            st.update(frame(0.4f + 0.2f * p, 0.3f + 0.5f * p, 0.6f - 0.3f * p))
        }
        var drops = 0
        for (i in 0 until (1.4f / dt).toInt()) {
            if (st.update(frame(0.95f, 0.5f, 0.95f, beat = true)).dropNow) drops++
        }
        assertEquals(1, drops, "the refractory did not hold: $drops drops in 1.4 s")
    }

    @Test
    fun `a collapse in energy reads as a breakdown`() {
        val st = StructureTracker(dt)
        for (i in 0 until (10f / dt).toInt()) st.update(frame(0.8f, 0.4f, 0.7f))
        var sawBreakdown = false
        for (i in 0 until (5f / dt).toInt()) {
            if (st.update(frame(0.1f, 0.2f, 0.1f)).breakdown) sawBreakdown = true
        }
        assertTrue(sawBreakdown, "energy collapsing to an eighth never read as a breakdown")
    }

    @Test
    fun `silence stays steady`() {
        val st = StructureTracker(dt)
        for (i in 0 until (10f / dt).toInt()) {
            val s = st.update(frame(0f, 0f, 0f))
            assertEquals(SongPhase.STEADY, s.phase, "silence produced ${s.phase}")
            assertTrue(!s.dropNow, "silence produced a drop")
        }
    }

    @Test
    fun `reset clears the arc`() {
        val st = StructureTracker(dt)
        val n = (4f / dt).toInt()
        for (i in 0 until n) {
            val p = i.toFloat() / n
            st.update(frame(0.4f + 0.2f * p, 0.3f + 0.5f * p, 0.6f - 0.3f * p))
        }
        st.reset()
        val s = st.update(frame(0.4f, 0.3f, 0.6f))
        assertEquals(0f, s.buildProgress, "build tension survived reset")
        assertEquals(SongPhase.STEADY, s.phase)
    }

    @Test
    fun `state stays in range`() {
        val st = StructureTracker(dt)
        val rng = Random(3)
        for (i in 0 until (30f / dt).toInt()) {
            val s = st.update(
                frame(rng.nextFloat(), rng.nextFloat(), rng.nextFloat(), beat = i % 17 == 0)
            )
            assertTrue(s.buildProgress in 0f..1f, "buildProgress ${s.buildProgress}")
            assertTrue(s.sectionLevel in 0f..1f, "sectionLevel ${s.sectionLevel}")
        }
    }
}
