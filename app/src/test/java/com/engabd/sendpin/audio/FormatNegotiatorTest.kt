package com.engabd.sendpin.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The advertised format list is a promise: Music Assistant may only stream a
 * format that appears in it, and will happily send whatever depth is listed. So
 * the list has to describe what the engine *renders*, not what would be nice —
 * advertising 24-bit while the renderer truncates to 16 just spends bandwidth on
 * bits thrown away on arrival.
 */
class FormatNegotiatorTest {

    @Test
    fun `defaults to 16-bit`() {
        val formats = FormatNegotiator.supportedFormats()
        assertTrue(formats.isNotEmpty())
        assertTrue(formats.all { it.bitDepth == 16 }, "advertised: $formats")
    }

    @Test
    fun `bit-perfect advertises 24-bit for the lossless codecs`() {
        val formats = FormatNegotiator.supportedFormats(bitPerfect = true)
        val lossless = formats.filter { it.codec != "opus" }
        assertTrue(lossless.isNotEmpty())
        assertTrue(lossless.all { it.bitDepth == 24 }, "advertised: $lossless")
    }

    /**
     * Opus decodes to 16-bit whatever we claim, so asking the server for 24 there
     * is a number with nothing behind it.
     */
    @Test
    fun `opus stays 16-bit even under bit-perfect`() {
        val opus = FormatNegotiator.supportedFormats(bitPerfect = true).filter { it.codec == "opus" }
        assertTrue(opus.isNotEmpty())
        assertTrue(opus.all { it.bitDepth == 16 }, "advertised: $opus")
    }

    @Test
    fun `single-codec mode carries the same depth rule`() {
        assertTrue(
            FormatNegotiator.supportedFormats(codec = "flac", bitPerfect = true).all { it.bitDepth == 24 }
        )
        assertTrue(
            FormatNegotiator.supportedFormats(codec = "flac").all { it.bitDepth == 16 }
        )
        assertTrue(
            FormatNegotiator.supportedFormats(codec = "opus", bitPerfect = true).all { it.bitDepth == 16 }
        )
    }

    /**
     * "Play at original quality" hands off to Navidrome when MA would have to
     * convert. A 24-bit file is a conversion only while we're asking for 16.
     */
    @Test
    fun `24-bit source counts as untouched only under bit-perfect`() {
        assertFalse(FormatNegotiator.canStreamUntouched(48_000, 24))
        assertTrue(FormatNegotiator.canStreamUntouched(48_000, 24, bitPerfect = true))
        // Rate still has to be one we advertise, whatever the depth.
        assertFalse(FormatNegotiator.canStreamUntouched(192_000, 24, bitPerfect = true))
    }

    @Test
    fun `hi-res rates are only offered when asked for`() {
        val standard = FormatNegotiator.supportedFormats(preferHiRes = false).map { it.sampleRate }.toSet()
        assertEquals(setOf(48_000, 44_100), standard)
        val hires = FormatNegotiator.supportedFormats(preferHiRes = true).map { it.sampleRate }.toSet()
        assertTrue(96_000 in hires && 88_200 in hires)
    }

    /** 48 kHz first is what grouped/multi-room sync was validated against. */
    @Test
    fun `48k leads the list`() {
        assertEquals(48_000, FormatNegotiator.supportedFormats().first().sampleRate)
    }
}
