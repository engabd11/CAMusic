package com.engabd.sendpin.audio

import androidx.media3.common.audio.AudioProcessor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TapRenderersFactoryTest {

    @Test
    fun `lo-fi's crush stage runs before vinyl noise, and wow-flutter before lo-fi`() {
        // Pins the chain-order fix: vinyl hiss/crackle must be layered on
        // after lo-fi's bitcrush/quantize stage, not before it, or the noise
        // gets quantised along with the music and turns into digital
        // "zipper" artifacts instead of a soft analog texture. See
        // TapRenderersFactory's class doc.
        val dsp = LocalDsp()
        val wowFlutter = WowFlutterProcessor()
        val loFi = LoFiProcessor()
        val vinylNoise = VinylNoiseProcessor()
        val oldRadio = OldRadioProcessor()
        val tap = AudioAnalysisTap(AudioLead())

        val ordered = TapRenderersFactory.orderedProcessors(
            aaudioBitperfect = false,
            dsp = dsp,
            wowFlutter = wowFlutter,
            loFi = loFi,
            vinylNoise = vinylNoise,
            oldRadio = oldRadio,
            tap = tap,
            sonic = null,
        )

        val wowFlutterIndex = ordered.indexOf(wowFlutter)
        val loFiIndex = ordered.indexOf(loFi)
        val vinylNoiseIndex = ordered.indexOf(vinylNoise)
        val oldRadioIndex = ordered.indexOf(oldRadio)
        val tapIndex = ordered.indexOf(tap)

        assertTrue(wowFlutterIndex < loFiIndex, "wow/flutter should run before lo-fi's bitcrusher")
        assertTrue(loFiIndex < vinylNoiseIndex, "lo-fi's crush stage should run before vinyl noise")
        assertTrue(vinylNoiseIndex < oldRadioIndex, "vinyl noise should run before old radio")
        assertTrue(oldRadioIndex < tapIndex, "old radio should run before the analysis tap")
    }

    @Test
    fun `aaudio bitperfect drops every coloration processor but keeps the tap and sonic`() {
        val dsp = LocalDsp()
        val wowFlutter = WowFlutterProcessor()
        val loFi = LoFiProcessor()
        val vinylNoise = VinylNoiseProcessor()
        val oldRadio = OldRadioProcessor()
        val tap = AudioAnalysisTap(AudioLead())
        val sonic = androidx.media3.common.audio.SonicAudioProcessor()

        val ordered = TapRenderersFactory.orderedProcessors(
            aaudioBitperfect = true,
            dsp = dsp,
            wowFlutter = wowFlutter,
            loFi = loFi,
            vinylNoise = vinylNoise,
            oldRadio = oldRadio,
            tap = tap,
            sonic = sonic,
        )

        val expectedRemoved = listOf<AudioProcessor>(dsp, wowFlutter, loFi, vinylNoise, oldRadio, tap)
        for (processor in expectedRemoved) {
            assertTrue(processor !in ordered, "$processor should be dropped when aaudioBitperfect is on")
        }
        assertEquals(listOf(sonic), ordered)
    }

    @Test
    fun `null processors are simply filtered out`() {
        val loFi = LoFiProcessor()
        val ordered = TapRenderersFactory.orderedProcessors(
            aaudioBitperfect = false,
            dsp = null,
            wowFlutter = null,
            loFi = loFi,
            vinylNoise = null,
            oldRadio = null,
            tap = null,
            sonic = null,
        )
        assertEquals(listOf(loFi), ordered)
    }
}
