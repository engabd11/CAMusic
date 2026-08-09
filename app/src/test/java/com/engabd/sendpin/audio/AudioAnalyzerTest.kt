package com.engabd.sendpin.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The analyzer has to tell instruments apart by frequency, not just notice that
 * something got louder.
 *
 * This is where a wrong spectrum actually shows. Broadband onset detection —
 * and therefore tempo locking — survives a badly packed FFT, because a kick
 * produces flux wherever its energy lands. What does not survive is anything
 * frequency-resolved: which lights are assigned the bass role, whether a hi-hat
 * is allowed to trigger a kick flash, and how the melbank maps across the room.
 * These tests fail against the old packing and pass against the correct one.
 *
 * Modelled on syncoV2 `tests/test_analyzer.py` and `tests/test_fast_onsets.py`.
 */
class AudioAnalyzerTest {

    private val sr = ANALYSIS_SAMPLE_RATE

    /** Repeated decaying bursts at [freq], i.e. a drum at a fixed pitch. */
    private fun burstTrack(freq: Float, bpm: Float, seconds: Float, decayS: Float = 0.03f): FloatArray {
        val sig = FloatArray((sr * seconds).toInt())
        val period = 60f / bpm
        val envLen = (0.1f * sr).toInt()
        var bt = 0.25f
        while (bt < seconds) {
            val i = (bt * sr).toInt()
            for (k in 0 until min(envLen, sig.size - i)) {
                val env = exp(-k.toFloat() / (decayS * sr))
                sig[i + k] += (sin(2.0 * PI * freq * k / sr) * env).toFloat()
            }
            bt += period
        }
        var peak = 0f
        for (v in sig) peak = maxOf(peak, abs(v))
        if (peak > 0f) for (i in sig.indices) sig[i] = sig[i] / peak * 0.9f
        return sig
    }

    private fun analyse(sig: FloatArray): List<AnalysisFrame> {
        val a = AudioAnalyzer()
        val out = ArrayList<AnalysisFrame>()
        val hop = FloatArray(ANALYSIS_HOP)
        for (k in 0 until sig.size / ANALYSIS_HOP) {
            System.arraycopy(sig, k * ANALYSIS_HOP, hop, 0, ANALYSIS_HOP)
            out.add(a.push(hop))
        }
        return out
    }

    @Test
    fun `kicks drive bass beats`() {
        // 60 Hz thumps are unambiguously bass. If these don't register as bass
        // onsets, no light ever gets a kick flash.
        val frames = analyse(burstTrack(freq = 60f, bpm = 120f, seconds = 12f))
        val bassBeats = frames.count { it.bassBeat }
        assertTrue(bassBeats >= 8, "a 12 s kick track produced only $bassBeats bass beats")
    }

    @Test
    fun `hi-hats do not drive bass beats`() {
        // The counterpart, and the one the packing bug breaks: an 8 kHz tick has
        // no low-frequency content at all, so the bass-share gate must reject it.
        // With the spectrum mismapped, treble energy lands in the low bands and
        // every hi-hat fires a kick flash.
        val frames = analyse(burstTrack(freq = 8000f, bpm = 120f, seconds = 12f, decayS = 0.01f))
        val bassBeats = frames.count { it.bassBeat }
        val allBeats = frames.count { it.beat }
        assertTrue(allBeats >= 8, "precondition: the hi-hat track should produce onsets, got $allBeats")
        assertTrue(bassBeats <= 2, "an 8 kHz hi-hat track produced $bassBeats bass beats")
    }

    /** A steady pure tone — the sharpest probe of absolute frequency mapping. */
    private fun tone(freq: Float, seconds: Float = 3f): AnalysisFrame {
        val sig = FloatArray((sr * seconds).toInt()) { i -> (sin(2.0 * PI * freq * i / sr) * 0.6).toFloat() }
        return analyse(sig).last()
    }

    @Test
    fun `the spectral centroid reports a tone's true frequency`() {
        // The test that pins the FFT's frequency mapping down in absolute terms.
        // Ordering-based checks are not enough: the old packing stretched every
        // frequency by exactly 2x, which preserves the order of any two tones and
        // so slips past "bass reads lower than treble". Here 1500 Hz has to come
        // back as 1500 Hz, and under the bug it read 3000.
        for (freq in listOf(300f, 1000f, 1500f, 3000f)) {
            val centroidHz = tone(freq).centroid * 5000f
            assertTrue(
                abs(centroidHz - freq) / freq < 0.05f,
                "a $freq Hz tone reported a centroid of $centroidHz Hz",
            )
        }
    }

    @Test
    fun `a tone lands in the band that contains it`() {
        // The consequence of the above, and what the show actually depends on:
        // band levels decide which lights take the bass role and whether the mid
        // attack machine sees a guitar. Under the 2x stretch a 1500 Hz tone —
        // squarely mid, where vocals and guitars live — was classified as high.
        val expected = listOf(
            100f to "bass",
            300f to "low_mid",
            1000f to "mid",
            1500f to "mid",
            3000f to "high",
        )
        for ((freq, band) in expected) {
            val bands = tone(freq).bands
            val loudest = bands.maxByOrNull { it.value }?.key
            assertTrue(loudest == band, "a $freq Hz tone was loudest in '$loudest', expected '$band'")
        }
    }

    @Test
    fun `silence produces no beats and zero salience`() {
        // The "only audio moves lights" invariant starts here. If silence
        // produced onsets, the room would strobe between tracks.
        val frames = analyse(FloatArray((sr * 4f).toInt()))
        assertTrue(frames.none { it.beat }, "silence produced beats")
        assertTrue(frames.none { it.bassBeat }, "silence produced bass beats")
        assertTrue(frames.all { it.salience == 0f }, "silence produced non-zero salience")
        assertTrue(frames.all { it.energy == 0f }, "silence produced non-zero energy")
    }

    @Test
    fun `a sustained tone glows without repeatedly triggering`() {
        // A held pad is not a rhythm. The onset detector should fire at the
        // attack and then settle, or a string section becomes a strobe.
        val sig = FloatArray((sr * 8f).toInt()) { i -> (sin(2.0 * PI * 220.0 * i / sr) * 0.6).toFloat() }
        val frames = analyse(sig)
        val secondHalf = frames.drop(frames.size / 2)
        val beats = secondHalf.count { it.beat }
        assertTrue(beats <= 5, "a steady tone produced $beats onsets in its second half")
    }

    @Test
    fun `output stays in range on real input`() {
        // Everything downstream assumes 0..1 and would clip a bulb otherwise.
        for (f in analyse(burstTrack(60f, 128f, 6f))) {
            assertTrue(f.energy in 0f..1f, "energy ${f.energy}")
            assertTrue(f.salience in 0f..1f, "salience ${f.salience}")
            assertTrue(f.centroid in 0f..1f, "centroid ${f.centroid}")
            assertTrue(f.onsetWidth in 0f..1f, "onsetWidth ${f.onsetWidth}")
            for ((name, v) in f.bands) assertTrue(v in 0f..1f, "band $name = $v")
            for (m in f.melbank) assertTrue(m in 0f..1f, "melbank bin $m")
            assertTrue(!f.energy.isNaN() && !f.salience.isNaN(), "NaN in the frame")
        }
    }

    @Test
    fun `stereo analysis matches mono for an identical pair`() {
        // The mono-parity invariant. Everything except pan must come out exactly
        // as the (L+R)/2 downmix would, or turning on stereo silently changes
        // every other feature and the whole port drifts.
        val sig = burstTrack(60f, 120f, 4f)
        val mono = AudioAnalyzer()
        val stereo = AudioAnalyzer()
        val hop = FloatArray(ANALYSIS_HOP)
        for (k in 0 until sig.size / ANALYSIS_HOP) {
            System.arraycopy(sig, k * ANALYSIS_HOP, hop, 0, ANALYSIS_HOP)
            val a = mono.push(hop)
            val b = stereo.pushStereo(hop, hop)
            assertTrue(a.energy == b.energy, "energy diverged at hop $k")
            assertTrue(a.beat == b.beat && a.bassBeat == b.bassBeat, "onsets diverged at hop $k")
            assertTrue(a.salience == b.salience, "salience diverged at hop $k")
        }
    }

    @Test
    fun `pan follows the side the sound is on`() {
        val sr = ANALYSIS_SAMPLE_RATE
        val n = (sr * 3f).toInt()
        val tone = FloatArray(n) { i -> (sin(2.0 * PI * 1000.0 * i / sr) * 0.6).toFloat() }
        val silent = FloatArray(n)

        fun panOf(l: FloatArray, r: FloatArray): Float {
            val a = AudioAnalyzer()
            val hl = FloatArray(ANALYSIS_HOP)
            val hr = FloatArray(ANALYSIS_HOP)
            var last = AnalysisFrame()
            for (k in 0 until n / ANALYSIS_HOP) {
                System.arraycopy(l, k * ANALYSIS_HOP, hl, 0, ANALYSIS_HOP)
                System.arraycopy(r, k * ANALYSIS_HOP, hr, 0, ANALYSIS_HOP)
                last = a.pushStereo(hl, hr)
            }
            // Average across the bins carrying the tone.
            val p = last.pan
            return if (p.isEmpty()) 0f else p.average().toFloat()
        }

        val left = panOf(tone, silent)
        val right = panOf(silent, tone)
        val centre = panOf(tone, tone)
        assertTrue(left < -0.3f, "a hard-left tone reported pan $left")
        assertTrue(right > 0.3f, "a hard-right tone reported pan $right")
        assertTrue(abs(centre) < 0.1f, "a centred tone reported pan $centre")
    }

    @Test
    fun `silence leaves pan unknown rather than centred`() {
        // Empty means "no idea", which is different from "dead centre" — the
        // engine has to be able to tell those apart.
        val a = AudioAnalyzer()
        val hop = FloatArray(ANALYSIS_HOP)
        var last = AnalysisFrame()
        repeat(50) { last = a.pushStereo(hop, hop) }
        assertTrue(last.pan.isEmpty(), "silence reported a pan of ${last.pan.toList()}")
    }

    @Test
    fun `reset clears state between tracks`() {
        val a = AudioAnalyzer()
        val sig = burstTrack(60f, 120f, 6f)
        val hop = FloatArray(ANALYSIS_HOP)
        for (k in 0 until sig.size / ANALYSIS_HOP) {
            System.arraycopy(sig, k * ANALYSIS_HOP, hop, 0, ANALYSIS_HOP)
            a.push(hop)
        }
        a.reset()
        // Salience is a decaying-peak ratio, so a stale loud reference would
        // make the opening of the next track read as quiet.
        val quiet = a.push(FloatArray(ANALYSIS_HOP))
        assertTrue(quiet.salience == 0f, "salience ${quiet.salience} survived reset")
        assertTrue(quiet.tAudio == 0f, "the audio clock survived reset")
    }
}
