package com.engabd.sendpin.hue

import com.engabd.sendpin.audio.AnalysisFrame
import com.engabd.sendpin.audio.SongPhase
import com.engabd.sendpin.audio.StructureState
import com.engabd.sendpin.audio.TrackScan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Does the room's colour temperature actually follow the song's arc — drop
 * warmer than steady, no instant snap on a phase change — and does the
 * end-of-track fade behave like the independent, position-driven nudge it's
 * meant to be rather than a fifth structure phase.
 */
class EmotionalArcLayerTest {

    private fun contextOf(
        structure: StructureState?,
        scan: TrackScan? = null,
        posS: Float = -1f,
        dt: Float = 0.05f,
    ) = LayerContext(
        frame = AnalysisFrame(),
        structure = structure,
        scan = scan,
        positions = emptyMap(),
        topology = RoomTopology.CLUSTER,
        trackPositionS = posS,
        dt = dt,
    )

    private val redBase = mapOf(1 to Triple(1f, 0f, 0f))

    /**
     * One BUILDING frame, which is the evidence the layer waits for that this
     * track has an arc at all — without it `STEADY` means "nothing classified
     * yet" and the layer deliberately stays out of the way.
     */
    private fun prime(layer: EmotionalArcLayer) {
        layer.apply(redBase, contextOf(StructureState(phase = SongPhase.BUILDING)))
    }

    private fun settle(layer: EmotionalArcLayer, phase: SongPhase, frames: Int = 200): Rgb {
        prime(layer)
        var out = redBase.getValue(1)
        repeat(frames) { out = layer.apply(redBase, contextOf(StructureState(phase = phase))).getValue(1) }
        return out
    }

    @Test
    fun `a sustained drop settles warmer than a sustained steady section`() {
        // Starting from pure red (h=0): a cool pull (steady's target) drags
        // hue toward blue and raises the blue channel; a hot pull (drop's
        // target) sits almost on top of red already and barely moves it.
        // The blue channel is therefore a clean, low-risk proxy for "how
        // far this settled toward cool".
        val drop = settle(EmotionalArcLayer(), SongPhase.DROP)
        val steady = settle(EmotionalArcLayer(), SongPhase.STEADY)
        assertTrue(drop.third < steady.third, "drop ($drop) should read less cool than steady ($steady)")
    }

    @Test
    fun `a track that only ever reports STEADY is left alone`() {
        // STEADY is StructureTracker's "everything else" and StructureState's
        // default, so it is the only phase ambient, classical or spoken-word
        // content ever reports. Reading it as "a calm section" would tint the
        // whole track blue for its whole length.
        val layer = EmotionalArcLayer()
        var out = redBase
        repeat(400) { out = layer.apply(redBase, contextOf(StructureState(phase = SongPhase.STEADY))) }
        assertEquals(redBase, out, "with no build, drop or breakdown ever seen, the layer must pass through")
    }

    @Test
    fun `once the track has shown an arc, STEADY does read cool`() {
        val layer = EmotionalArcLayer()
        prime(layer)
        var out = redBase
        repeat(400) { out = layer.apply(redBase, contextOf(StructureState(phase = SongPhase.STEADY))) }
        assertNotEquals(redBase, out, "after a build, the calm between the loud parts should read cool")
    }

    @Test
    fun `reset forgets both the temperature and the track's arc`() {
        val layer = EmotionalArcLayer()
        val hot = settle(layer, SongPhase.DROP)
        assertNotEquals(redBase.getValue(1), hot, "sanity: the drop should have moved the colour")

        layer.reset()
        // A new track: STEADY only, and no arc established. Both the smoothed
        // temperature and the "this track has structure" evidence must be gone,
        // or the previous song's drop warms this one.
        var out = redBase
        repeat(400) { out = layer.apply(redBase, contextOf(StructureState(phase = SongPhase.STEADY))) }
        assertEquals(redBase, out, "nothing from the previous track may survive a reset")
    }

    @Test
    fun `a breakdown pulls colour temperature down over several seconds, not one frame`() {
        val layer = EmotionalArcLayer()
        prime(layer)
        val afterOneFrame = layer.apply(redBase, contextOf(StructureState(phase = SongPhase.BREAKDOWN))).getValue(1)
        var afterMany = afterOneFrame
        repeat(200) {
            afterMany = layer.apply(redBase, contextOf(StructureState(phase = SongPhase.BREAKDOWN))).getValue(1)
        }
        assertTrue(
            afterMany.third > afterOneFrame.third + 0.05f,
            "the cool shift should keep deepening well past the first frame: $afterOneFrame -> $afterMany",
        )
    }

    @Test
    fun `the end-of-track warm nudge only activates near the very end`() {
        val scan = TrackScan(
            durationS = 100f, bpm = 0f, confidence = 0f,
            beats = FloatArray(0), accents = FloatArray(0), downbeat = 0,
            sections = emptyList(), intensity = null,
        )
        val base = mapOf(1 to Triple(0.2f, 0.8f, 0.2f))

        val midLayer = EmotionalArcLayer()
        var midOut = base
        repeat(300) { midOut = midLayer.apply(base, contextOf(structure = null, scan = scan, posS = 50f)) }

        val endLayer = EmotionalArcLayer()
        var endOut = base
        repeat(300) { endOut = endLayer.apply(base, contextOf(structure = null, scan = scan, posS = 99f)) }

        // Well before the fade window and with no structure at all, the
        // target temperature is exactly zero — the layer's own no-op floor
        // — so this is exact equality, not a tolerance.
        assertEquals(base, midOut, "well before the fade window, the layer should be a no-op")
        assertNotEquals(base, endOut, "near the end of the track, the warm nudge should be visible")
    }
}
