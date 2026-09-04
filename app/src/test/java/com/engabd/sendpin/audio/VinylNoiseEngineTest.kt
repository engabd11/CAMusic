package com.engabd.sendpin.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VinylNoiseEngineTest {

    @Test
    fun `crackle polarity holds through one impulse's decay`() {
        // Regression test for the exact bug LoFiProcessor's own crackle used
        // to have before it was rebuilt on this shared engine: the sign was
        // re-rolled every sample of the decay instead of once at the trigger,
        // which turns a click into a burst of white noise - it reads as
        // static, not as a click.
        //
        // A trigger sample is identifiable directly from Channel's public
        // timing state: crackleCounter is reset to exactly 0 on the sample a
        // new impulse fires - that alone (not the sign the implementation
        // happens to pick) marks a new "episode". The expected sign for an
        // episode is then read from the actual *output*, at the first
        // nonzero sample after the boundary, and every later nonzero sample
        // in the same episode must match it - so a reintroduced bug (sign
        // re-rolled independently of crackleCounter) would flip the output's
        // sign between consecutive decay samples and fail this, rather than
        // the test merely restating whatever the current implementation
        // already does.
        val engine = VinylNoiseEngine()
        val sampleRate = 44_100
        val channel = engine.channels[0]
        var sawNonzero = false
        var expectedSign = 0f
        var haveExpected = false
        repeat(200_000) {
            val sample = engine.crackle(channel = 0, intensity = 1f, sampleRate = sampleRate)
            if (channel.crackleCounter == 0) haveExpected = false
            if (sample != 0f) {
                sawNonzero = true
                if (!haveExpected) {
                    expectedSign = if (sample > 0f) 1f else -1f
                    haveExpected = true
                } else {
                    assertTrue(
                        (sample > 0f) == (expectedSign > 0f),
                        "sign flipped mid-decay: expected sign $expectedSign, got $sample",
                    )
                }
            }
        }
        assertTrue(sawNonzero, "expected at least one crackle impulse over 200k samples at full intensity")
    }

    @Test
    fun `pop polarity holds through one impulse's decay`() {
        val engine = VinylNoiseEngine()
        val sampleRate = 44_100
        val channel = engine.channels[0]
        var sawNonzero = false
        var expectedSign = 0f
        var haveExpected = false
        repeat(500_000) {
            val sample = engine.pop(channel = 0, intensity = 1f, sampleRate = sampleRate)
            if (channel.popCounter == 0) haveExpected = false
            if (sample != 0f) {
                sawNonzero = true
                if (!haveExpected) {
                    expectedSign = if (sample > 0f) 1f else -1f
                    haveExpected = true
                } else {
                    assertTrue(
                        (sample > 0f) == (expectedSign > 0f),
                        "sign flipped mid-decay: expected sign $expectedSign, got $sample",
                    )
                }
            }
        }
        assertTrue(sawNonzero, "expected at least one pop impulse over 500k samples at full intensity")
    }

    @Test
    fun `hiss is silent at zero intensity and present at full intensity`() {
        // `== 0f` rather than assertEquals(0f, ...): hissAmplitude(0f) is exactly
        // 0f, but multiplying it by a negative internal noise state legitimately
        // produces -0.0f (audibly identical to 0.0f). assertEquals routes through
        // boxed Float.equals(), which - unlike statically-typed Float `==` - treats
        // -0.0f and 0.0f as unequal, which would fail this for the wrong reason.
        val engine = VinylNoiseEngine().apply { configureFilters(44_100) }
        repeat(1_000) { assertTrue(engine.hiss(channel = 0, intensity = 0f) == 0f) }

        var sawNonzero = false
        repeat(1_000) {
            if (engine.hiss(channel = 0, intensity = 1f) != 0f) sawNonzero = true
        }
        assertTrue(sawNonzero, "expected nonzero hiss at full intensity")
    }

    @Test
    fun `rumble is silent at zero intensity and present at full intensity`() {
        // See the note in the hiss test above: `== 0f`, not assertEquals, to
        // treat the legitimate -0.0f case the same as 0.0f.
        val engine = VinylNoiseEngine().apply { configureFilters(44_100) }
        repeat(1_000) { assertTrue(engine.rumble(intensity = 0f) == 0f) }

        var sawNonzero = false
        repeat(1_000) {
            if (engine.rumble(intensity = 1f) != 0f) sawNonzero = true
        }
        assertTrue(sawNonzero, "expected nonzero rumble at full intensity")
    }

    @Test
    fun `left and right channels decorrelate at full intensity`() {
        // Same invariant VinylNoiseProcessorTest pins at the processor level:
        // independent per-channel impulse trains should not stay in lockstep
        // even though both draw from the same session RNG.
        val engine = VinylNoiseEngine().apply { resize(2); configureFilters(44_100) }
        var identical = 0
        var total = 0
        repeat(20_000) {
            val l = engine.crackle(0, 1f, 44_100) + engine.hiss(0, 1f)
            val r = engine.crackle(1, 1f, 44_100) + engine.hiss(1, 1f)
            if (l == r) identical++
            total++
        }
        assertTrue(identical < total, "left and right should not be identical on every sample once decorrelated")
    }

    @Test
    fun `resize grows the channel array and reset shrinks it back`() {
        val engine = VinylNoiseEngine()
        assertEquals(2, engine.channels.size)
        engine.resize(6)
        assertEquals(6, engine.channels.size)
        engine.onReset()
        assertEquals(2, engine.channels.size)
    }
}
