package com.engabd.sendpin.audio

import kotlin.test.Test
import kotlin.test.assertEquals

class BeatAlignedFadeTest {

    @Test
    fun `no scan falls back to the default window`() {
        assertEquals(6, BeatAlignedFade.windowSeconds(durationS = 200f, beats = floatArrayOf(), defaultSeconds = 6))
    }

    @Test
    fun `a beat near the default fade point is adopted`() {
        // 120 BPM - beats every 0.5s. Default fade wants to start at 194s; the
        // nearest beat is 194.0s exactly, so the window should land there too.
        val beats = (0..399).map { it * 0.5f }.toFloatArray()
        assertEquals(6, BeatAlignedFade.windowSeconds(durationS = 200f, beats = beats, defaultSeconds = 6))
    }

    @Test
    fun `a beat that would make the window implausibly short is rejected`() {
        // Only beat near the end is 0.2s from the finish - adopting it would fade
        // for 0.2s, not 6s. Falls back rather than producing a near-instant cut.
        val beats = floatArrayOf(10f, 50f, 199.8f)
        assertEquals(6, BeatAlignedFade.windowSeconds(durationS = 200f, beats = beats, defaultSeconds = 6))
    }

    @Test
    fun `a beat outside the plausible range is rejected`() {
        // Nearest beat to the target (194s) is 150s - a 50s fade is not a fade,
        // it is most of the song. Falls back to the fixed window instead.
        val beats = floatArrayOf(10f, 150f)
        assertEquals(6, BeatAlignedFade.windowSeconds(durationS = 200f, beats = beats, defaultSeconds = 6))
    }

    @Test
    fun `the aligned window is still bounded to a sane fade length`() {
        val beats = floatArrayOf(191.6f) // 8.4s before the end - within the 3-9s band for a 6s default
        assertEquals(8, BeatAlignedFade.windowSeconds(durationS = 200f, beats = beats, defaultSeconds = 6))
    }

    @Test
    fun `zero default disables fading entirely, scan or not`() {
        assertEquals(0, BeatAlignedFade.windowSeconds(durationS = 200f, beats = floatArrayOf(194f), defaultSeconds = 0))
    }
}
