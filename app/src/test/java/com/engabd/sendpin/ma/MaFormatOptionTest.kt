package com.engabd.sendpin.ma

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Picking the `preferred_sendspin_format` option that stands for a codec setting.
 *
 * Music Assistant declares the options as `codec:rate:depth:channels`, built from this
 * client's own advertised `supported_formats` in the order it advertised them
 * (`sendspin/player.py: format_to_option_value`). This used to match them as
 * `flac_48000_16`, so nothing but `automatic` ever matched, the save was refused as an
 * undeclared value, and the codec setting never left the phone.
 */
class MaFormatOptionTest {

    /** As a phone advertising FLAC-then-PCM at 48/44.1/96 kHz has MA declare them. */
    private val options = listOf(
        "automatic",
        "flac:48000:24:2",
        "flac:44100:24:2",
        "flac:96000:24:2",
        "pcm:48000:24:2",
        "pcm:96000:24:2",
        "opus:48000:16:2",
    )

    @Test
    fun `a codec resolves to a declared option`() {
        assertEquals("flac:48000:24:2", MaRepository.matchFormatOption(options, "flac"))
        assertEquals("pcm:48000:24:2", MaRepository.matchFormatOption(options, "pcm"))
        assertEquals("opus:48000:16:2", MaRepository.matchFormatOption(options, "opus"))
    }

    /**
     * The client's first choice, not the server's biggest number. With no override the
     * server already plays the client's first compatible format, so this writes down
     * what is in use; pinning 96 kHz would instead hand every source over upsampled.
     */
    @Test
    fun `the first option for the codec wins, not the highest rate`() {
        assertEquals("flac:48000:24:2", MaRepository.matchFormatOption(options, "flac"))
    }

    @Test
    fun `automatic is matched exactly`() {
        assertEquals("automatic", MaRepository.matchFormatOption(options, "automatic"))
    }

    /** A server that lists bare codec names is still answered by the exact-match pass. */
    @Test
    fun `a bare codec option still matches`() {
        assertEquals("flac", MaRepository.matchFormatOption(listOf("automatic", "flac"), "flac"))
    }

    /** Nothing declared for it — the save would be rejected, so don't make it. */
    @Test
    fun `an undeclared codec picks nothing`() {
        assertNull(MaRepository.matchFormatOption(options, "alac"))
        assertNull(MaRepository.matchFormatOption(emptyList(), "flac"))
    }

    /** The old shape, so a regression back to underscores fails here rather than silently. */
    @Test
    fun `the underscore shape was never what the server declares`() {
        assertNull(MaRepository.matchFormatOption(listOf("automatic"), "flac"))
    }
}
