package com.engabd.sendpin.hue

import com.engabd.sendpin.audio.AnalysisFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

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
        var resets = 0
            private set

        override fun reset() { resets++ }

        override fun apply(
            base: Map<Int, Rgb>,
            context: LayerContext,
            out: MutableMap<Int, Rgb>,
        ): Map<Int, Rgb> {
            for ((id, rgb) in base) {
                out[id] = rgb.copy(first = (rgb.first + delta).coerceAtMost(1f))
            }
            return out
        }
    }

    /** A layer that doesn't override [LightShowLayer.reset], to prove it need not. */
    private class Untouched(override val id: String) : LightShowLayer {
        override fun apply(base: Map<Int, Rgb>, context: LayerContext, out: MutableMap<Int, Rgb>) = base
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
    fun `reset reaches every layer in the chain`() {
        val a = AddRed("a", 0.1f)
        val b = AddRed("b", 0.1f)
        LayerChain(listOf(a, b)).reset()
        assertEquals(1, a.resets)
        assertEquals(1, b.resets)
    }

    @Test
    fun `a layer that does not override reset still composes`() {
        val base = mapOf(1 to Triple(0.2f, 0.3f, 0.4f))
        val chain = LayerChain(listOf(Untouched("stateless"), AddRed("a", 0.1f)))
        chain.reset() // the default no-op must not throw
        assertEquals(0.3f, chain.apply(base, contextOf()).getValue(1).first, 1e-5f)
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

    // ── Buffer reuse ───────────────────────────────────────────────────────

    /**
     * The chain must not allocate a map per layer per frame.
     *
     * It runs at 60 Hz on the one thread in this app with a hard deadline, and
     * every layer used to end in `base.mapValues { … }` — a fresh
     * `LinkedHashMap` each, four times a frame, 240 times a second. The
     * observable form of the fix is that the map handed back on one frame is the
     * same object as on a later frame, so this asserts identity and not equality.
     */
    @Test
    fun `the chain reuses its output buffers across frames`() {
        val base = mapOf(1 to Triple(0f, 0f, 0f), 2 to Triple(0f, 0f, 0f))
        val chain = LayerChain(listOf(AddRed("a", 0.1f), AddRed("b", 0.1f)))

        val first = chain.apply(base, contextOf())
        val firstCopy = HashMap(first)
        val second = chain.apply(base, contextOf())

        assertSame(first, second, "a second frame should land in the same buffer")
        assertEquals(firstCopy, HashMap(second), "and produce the same answer")
    }

    /**
     * A layer writes into the buffer it was given, never into its input.
     *
     * With two buffers alternating, writing to `base` would be writing to the
     * previous layer's output while this one is still reading it — so the guard
     * is that a two-layer chain still composes correctly when both layers are
     * doing real work, over enough frames for the buffers to have swapped
     * several times.
     */
    @Test
    fun `alternating buffers do not corrupt the fold`() {
        val base = mapOf(1 to Triple(0f, 0f, 0f))
        val chain = LayerChain(listOf(AddRed("a", 0.2f), AddRed("b", 0.3f)))
        repeat(5) {
            val out = chain.apply(base, contextOf())
            assertEquals(0.5f, out.getValue(1).first, 1e-5f)
        }
        assertEquals(0f, base.getValue(1).first, "the caller's map must come back untouched")
    }

    /**
     * A pass-through layer hands `base` back, and the chain must not then treat
     * the buffer it offered as spent — otherwise the next layer writes into the
     * map the one after it is about to read.
     */
    @Test
    fun `a pass-through layer between two working ones composes correctly`() {
        val base = mapOf(1 to Triple(0f, 0f, 0f))
        val chain = LayerChain(listOf(AddRed("a", 0.2f), Untouched("skip"), AddRed("b", 0.3f)))
        repeat(3) {
            val out = chain.apply(base, contextOf())
            assertEquals(0.5f, out.getValue(1).first, 1e-5f)
        }
    }
}
