package com.engabd.sendpin.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The advertised format list is everything this phone can play, and nothing
 * narrowed: Music Assistant's "Preferred audio format" offers each advertised
 * format by name, and its "Sample rate selection" picks among the rates of the
 * codec listed first. A rate or codec missing here is an option taken away from
 * the listener on the server.
 */
class FormatNegotiatorTest {

    @Test
    fun `every codec is advertised whatever comes first`() {
        for (first in listOf(null, "flac", "pcm", "opus")) {
            val codecs = FormatNegotiator.supportedFormats(192_000, first).map { it.codec }.toSet()
            assertEquals(setOf("flac", "pcm", "opus"), codecs, "first=$first")
        }
    }

    /** The first codec is what Automatic and Sample rate selection work from. */
    @Test
    fun `the chosen codec leads the list`() {
        assertEquals("opus", FormatNegotiator.supportedFormats(192_000, "opus").first().codec)
        assertEquals("pcm", FormatNegotiator.supportedFormats(192_000, "PCM").first().codec)
        assertEquals("flac", FormatNegotiator.supportedFormats(192_000).first().codec)
        assertEquals("pcm", FormatNegotiator.supportedFormats(192_000, preferFlac = false).first().codec)
    }

    @Test
    fun `lossless codecs carry every rate the device opens`() {
        val formats = FormatNegotiator.supportedFormats(192_000)
        for (codec in listOf("flac", "pcm")) {
            assertEquals(
                setOf(44_100, 48_000, 88_200, 96_000, 176_400, 192_000),
                formats.filter { it.codec == codec }.map { it.sampleRate }.toSet(),
            )
        }
        assertEquals(listOf(48_000), formats.filter { it.codec == "opus" }.map { it.sampleRate })
    }

    /** Advertising a rate is a commitment: the server may pick it. */
    @Test
    fun `the device ceiling trims the top`() {
        val rates = FormatNegotiator.supportedFormats(96_000).map { it.sampleRate }.toSet()
        assertEquals(setOf(44_100, 48_000, 88_200, 96_000), rates)
    }

    /** The client's default format, streamed under Automatic with no session rate. */
    @Test
    fun `48k leads each codec`() {
        val formats = FormatNegotiator.supportedFormats(192_000)
        for (codec in listOf("flac", "pcm", "opus")) {
            assertEquals(48_000, formats.first { it.codec == codec }.sampleRate)
        }
    }

    /** The engine renders int16, so a deeper format would be truncated on arrival. */
    @Test
    fun `everything is 16-bit stereo`() {
        assertTrue(FormatNegotiator.supportedFormats(192_000).all { it.bitDepth == 16 && it.channels == 2 })
    }

    @Test
    fun `untouched means an advertised rate at 16 bits or fewer`() {
        assertTrue(FormatNegotiator.canStreamUntouched(44_100, 16, 96_000))
        assertTrue(FormatNegotiator.canStreamUntouched(96_000, 16, 96_000))
        assertFalse(FormatNegotiator.canStreamUntouched(192_000, 16, 96_000))
        assertFalse(FormatNegotiator.canStreamUntouched(48_000, 24, 192_000))
    }
}
