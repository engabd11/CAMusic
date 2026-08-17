package com.engabd.sendpin.hue

import com.engabd.sendpin.audio.AnalysisFrame
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Does the chain compose the way its callers assume — in order, and as a
 * no-op with nothing enabled — independent of any one layer's own logic.
 */
class LightShowLayerTest {

    private fun contextOf() = LayerContext(
        frame = AnalysisFrame(),
        structure = null,
        scan = null,
        positions = emptyMap(),
        topology = RoomTopology.CLUSTER,
        trackPositionS = -1f,
        dt = 0.016f,
    )

    /** Adds [delta] to every channel's red component, clamped to 1f. */
    private class AddRed(override val id: String, private val delta: Float) : LightShowLayer {
        override fun apply(base: Map<Int, Rgb>, context: LayerContext): Map<Int, Rgb> =
            base.mapValues { (_, rgb) -> rgb.copy(first = (rgb.first + delta).coerceAtMost(1f)) }
    }

    @Test
    fun `an empty chain returns the base unchanged`() {
        val base = mapOf(1 to Triple(0.2f, 0.3f, 0.4f))
        val out = LayerChain(emptyList()).apply(base, contextOf())
        assertEquals(base, out)
    }

    @Test
    fun `LayerChain EMPTY is the same no-op`() {
        val base = mapOf(1 to Triple(0.2f, 0.3f, 0.4f))
        assertEquals(base, LayerChain.EMPTY.apply(base, contextOf()))
    }

    @Test
    fun `layers apply in order, each on top of the last`() {
        val base = mapOf(1 to Triple(0.0f, 0f, 0f))
        val chain = LayerChain(listOf(AddRed("a", 0.3f), AddRed("b", 0.3f)))
        val out = chain.apply(base, contextOf())
        assertEquals(0.6f, out.getValue(1).first, 1e-5f)
    }

    @Test
    fun `later layers see earlier layers' output, not the original base`() {
        val base = mapOf(1 to Triple(0.9f, 0f, 0f))
        // Two layers that would each individually clamp at 1.0; composed,
        // the second sees the first's clamped result, not 0.9 + 0.3 + 0.3.
        val chain = LayerChain(listOf(AddRed("a", 0.3f), AddRed("b", 0.3f)))
        val out = chain.apply(base, contextOf())
        assertEquals(1f, out.getValue(1).first, 1e-5f)
    }
}
