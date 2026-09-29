package com.engabd.sendpin.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatSafeCodecSelectorTest {

    @Test
    fun `platform decoders are recognised`() {
        assertTrue(FloatSafeCodecSelector.isPlatformDecoder("c2.android.flac.decoder"))
        assertTrue(FloatSafeCodecSelector.isPlatformDecoder("OMX.google.flac.decoder"))
    }

    @Test
    fun `vendor decoders are not`() {
        assertFalse(FloatSafeCodecSelector.isPlatformDecoder("c2.sec.flac.decoder"))
        assertFalse(FloatSafeCodecSelector.isPlatformDecoder("c2.qti.flac.decoder"))
        assertFalse(FloatSafeCodecSelector.isPlatformDecoder("OMX.qcom.audio.decoder.flac"))
    }
}
