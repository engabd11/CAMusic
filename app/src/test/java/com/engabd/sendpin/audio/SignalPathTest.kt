package com.engabd.sendpin.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [SignalPath] is an `object` — one piece of mutable state shared by every test
 * in this class — so it is reset before and after each one. Without that, the
 * order tests happen to run in becomes part of what they assert, which is
 * exactly the kind of thing that passes locally and fails in CI for no reason
 * anyone can see from the diff.
 *
 * The four cases below are the whole point of Part B: [SignalPath.State.sink]
 * used to be read from a processor sitting behind media3's own 16-bit
 * converter, so it could never report anything but 16-bit. It is derived now,
 * from the same rule `DefaultAudioSink.shouldUseFloatOutput` uses, so these
 * assert it actually tracks the decode.
 */
class SignalPathTest {

    @BeforeTest
    fun reset() {
        SignalPath.clear()
        SignalPath.onFloatOutput(false)
        SignalPath.onExclusive(false)
    }

    @AfterTest
    fun cleanUp() {
        SignalPath.clear()
        SignalPath.onFloatOutput(false)
        SignalPath.onExclusive(false)
    }

    private fun pcmFormat(sampleRate: Int, encoding: Int, channels: Int = 2): Format =
        Format.Builder()
            .setSampleMimeType(androidx.media3.common.MimeTypes.AUDIO_RAW)
            .setSampleRate(sampleRate)
            .setPcmEncoding(encoding)
            .setChannelCount(channels)
            .build()

    @Test
    fun `24-bit decode with hi-res off truncates and keeps the processors`() {
        SignalPath.onFloatOutput(false)
        SignalPath.onDecoderOutput(pcmFormat(96_000, C.ENCODING_PCM_24BIT))

        val state = SignalPath.state.value
        assertEquals(16, state.sink.bitDepth)
        assertFalse(state.sink.isFloat)
        assertTrue(state.truncating, "24-bit decode with hi-res off must truncate")
        assertFalse(state.floatEngaged)
        assertFalse(state.processorsBypassed)
    }

    @Test
    fun `24-bit decode with hi-res on engages float and bypasses the processors`() {
        SignalPath.onFloatOutput(true)
        SignalPath.onDecoderOutput(pcmFormat(96_000, C.ENCODING_PCM_24BIT))

        val state = SignalPath.state.value
        assertTrue(state.floatEngaged)
        assertTrue(state.sink.isFloat)
        assertEquals(32, state.sink.bitDepth)
        assertFalse(state.truncating, "float carries at least as many bits as a 24-bit decode")
        assertTrue(state.processorsBypassed)
    }

    /**
     * The asymmetric case: turning high-resolution output on does *not* mean the
     * equaliser goes away for every file. `shouldUseFloatOutput` also tests the
     * decoded encoding, so a plain 16-bit file keeps every processor running
     * exactly as before, whatever the setting says.
     */
    @Test
    fun `16-bit decode with hi-res on does not engage float and keeps the EQ`() {
        SignalPath.onFloatOutput(true)
        SignalPath.onDecoderOutput(pcmFormat(44_100, C.ENCODING_PCM_16BIT))

        val state = SignalPath.state.value
        assertFalse(state.floatEngaged, "a 16-bit decode has nothing for the float path to carry")
        assertFalse(state.processorsBypassed)
        assertEquals(16, state.sink.bitDepth)
        assertFalse(state.truncating)
    }

    @Test
    fun `exclusive mode bypasses the processors at any depth`() {
        SignalPath.onFloatOutput(false)
        SignalPath.onExclusive(true)
        SignalPath.onDecoderOutput(pcmFormat(44_100, C.ENCODING_PCM_16BIT))

        assertTrue(SignalPath.state.value.processorsBypassed, "exclusive mode bypasses regardless of float")
        assertFalse(SignalPath.state.value.floatEngaged, "exclusive alone does not imply the float path engaged")
    }

    @Test
    fun `a decoder that hands over fewer bits than the file declares is named as such`() {
        // The case someone turning high-resolution output on and seeing no change is
        // actually in: the platform's FLAC decoder is asked for more and declines.
        // Nothing on the Output card can fix it, so the card has to say so.
        SignalPath.onFloatOutput(true)
        SignalPath.onSourceFormat(
            Format.Builder()
                .setSampleMimeType(androidx.media3.common.MimeTypes.AUDIO_FLAC)
                .setSampleRate(96_000)
                .setPcmEncoding(C.ENCODING_PCM_24BIT)
                .setChannelCount(2)
                .build(),
        )
        SignalPath.onDecoderOutput(pcmFormat(96_000, C.ENCODING_PCM_16BIT))

        val state = SignalPath.state.value
        assertTrue(state.decoderLostBits, "24-bit file decoded to 16-bit must be reported")
        // Not `truncating`: that one is this app's own doing and is fixable from the
        // Output card. This one is the platform's and is not.
        assertFalse(state.truncating)
        assertFalse(state.floatEngaged, "a 16-bit decode has nothing for float to carry")
        with(SignalPath) {
            val why = state.explain(isBluetooth = false)
            assertTrue(why != null && "decoder handed over" in why, "got: $why")
        }
    }

    @Test
    fun `a decode matching the file declares no loss`() {
        SignalPath.onFloatOutput(true)
        SignalPath.onSourceFormat(pcmFormat(96_000, C.ENCODING_PCM_24BIT))
        SignalPath.onDecoderOutput(pcmFormat(96_000, C.ENCODING_PCM_24BIT))

        assertFalse(SignalPath.state.value.decoderLostBits)
    }

    @Test
    fun `nothing observed yet stays unknown rather than guessing`() {
        val state = SignalPath.state.value
        assertFalse(state.source.known)
        assertFalse(state.decoded.known)
        assertFalse(state.sink.known, "sink must not invent a number before decoded is known")
        assertFalse(state.truncating)
        assertFalse(state.floatEngaged)
        assertFalse(state.processorsBypassed)
        assertNull(state.decoded.bitDepth)
    }
}
