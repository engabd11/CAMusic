package com.engabd.sendpin.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Krumhansl-Kessler key detection: does a synthetic chroma vector shaped
 * like a real key's tone profile come back as that key, and does a thin or
 * ambiguous one come back honest about it rather than confidently guessing?
 */
class KeyDetectionTest {

    // Krumhansl-Kessler profiles, tonic at index 0. Kept as the test's own
    // copy rather than reaching into the private ones in KeyDetection.kt —
    // this way the test is checking the algorithm's behaviour against a
    // known-correct shape, not merely echoing whatever the implementation
    // currently holds.
    private val majorProfile = floatArrayOf(
        6.35f, 2.23f, 3.48f, 2.33f, 4.38f, 4.09f, 2.52f, 5.19f, 2.39f, 3.66f, 2.29f, 2.88f,
    )
    private val minorProfile = floatArrayOf(
        6.33f, 2.68f, 3.52f, 5.38f, 2.60f, 3.53f, 2.54f, 4.75f, 3.98f, 2.69f, 3.34f, 3.17f,
    )

    private fun rotate(profile: FloatArray, tonic: Int): FloatArray =
        FloatArray(12) { i -> profile[Math.floorMod(i - tonic, 12)] }

    @Test
    fun `a clean major profile is recovered exactly`() {
        val key = detectKey(rotate(majorProfile, 7))  // G major
        assertEquals(7, key?.tonic)
        assertEquals(MusicalMode.MAJOR, key?.mode)
    }

    @Test
    fun `a clean minor profile is recovered exactly`() {
        val key = detectKey(rotate(minorProfile, 9))  // A minor
        assertEquals(9, key?.tonic)
        assertEquals(MusicalMode.MINOR, key?.mode)
    }

    @Test
    fun `every tonic rotation is recovered, not just one`() {
        for (tonic in 0 until 12) {
            val key = detectKey(rotate(majorProfile, tonic))
            assertEquals(tonic, key?.tonic, "tonic $tonic")
            assertEquals(MusicalMode.MAJOR, key?.mode, "tonic $tonic")
        }
    }

    @Test
    fun `a silent or near-silent chroma vector yields no key`() {
        assertNull(detectKey(FloatArray(12)))
        assertNull(detectKey(FloatArray(12) { 1e-9f }))
    }

    @Test
    fun `wrong-length input yields no key`() {
        assertNull(detectKey(FloatArray(11)))
        assertNull(detectKey(FloatArray(13)))
    }

    @Test
    fun `an ambiguous blend of two keys a fifth apart is less confident than a clean read`() {
        val clean = detectKey(rotate(majorProfile, 0))!!

        // C major and G major share six of seven scale tones — the textbook
        // case this correlation method cannot call cleanly.
        val blended = FloatArray(12) { i -> rotate(majorProfile, 0)[i] + rotate(majorProfile, 7)[i] }
        val ambiguous = detectKey(blended)!!

        assertTrue(
            ambiguous.confidence < clean.confidence,
            "a blend of two keys should read less confident than a clean one: " +
                "${ambiguous.confidence} vs ${clean.confidence}",
        )
    }
}
