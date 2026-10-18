package com.engabd.sendpin.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The bs2b stage: what each preset does to a hard-panned tone at low and high
 * frequency, that a centred signal keeps its level, and that Off is a wire.
 */
class CrossfeedTest {

    private val rate = 44_100

    private fun db(x: Double) = 20.0 * log10(x)

    /** Left-only sine through [preset]; returns (left amplitude, right amplitude) once settled. */
    private fun panned(preset: Crossfeed.Preset, freq: Double, rate: Int = this.rate): Pair<Double, Double> {
        val xf = Crossfeed().apply { configure(rate, preset) }
        val frame = FloatArray(2)
        var peakL = 0.0
        var peakR = 0.0
        val n = rate
        for (i in 0 until n) {
            frame[0] = sin(2.0 * PI * freq * i / rate).toFloat()
            frame[1] = 0f
            xf.process(frame)
            if (i > n / 2) {
                peakL = maxOf(peakL, abs(frame[0].toDouble()))
                peakR = maxOf(peakR, abs(frame[1].toDouble()))
            }
        }
        return peakL to peakR
    }

    // -- Frequency response -------------------------------------------------

    @Test
    fun `low frequencies cross over at about the preset level`() {
        val expected = mapOf(
            Crossfeed.Preset.DEFAULT to -4.5,
            Crossfeed.Preset.CHU_MOY to -6.0,
            Crossfeed.Preset.JAN_MEIER to -9.5,
        )
        for ((preset, feed) in expected) {
            for (r in listOf(44_100, 48_000)) {
                val (l, rr) = panned(preset, 60.0, r)
                assertEquals(feed, db(rr / l), 0.6, "$preset at $r Hz")
            }
        }
    }

    @Test
    fun `high frequencies do not cross over`() {
        for (preset in listOf(Crossfeed.Preset.DEFAULT, Crossfeed.Preset.CHU_MOY, Crossfeed.Preset.JAN_MEIER)) {
            val (l, r) = panned(preset, 10_000.0)
            assertTrue(db(r / l) < -22.0, "$preset leaks ${db(r / l)} dB at 10 kHz")
        }
    }

    @Test
    fun `the feed rolls off between the bass and the treble`() {
        var previous = 0.0
        for (f in listOf(100.0, 700.0, 2_000.0, 6_000.0)) {
            val (l, r) = panned(Crossfeed.Preset.DEFAULT, f)
            val level = db(r / l)
            assertTrue(level < previous - 0.5 || f == 100.0, "$f Hz: $level after $previous")
            previous = level
        }
    }

    @Test
    fun `a higher feed level leaks less into the other ear`() {
        val a = panned(Crossfeed.Preset.DEFAULT, 100.0).let { db(it.second / it.first) }
        val b = panned(Crossfeed.Preset.CHU_MOY, 100.0).let { db(it.second / it.first) }
        val c = panned(Crossfeed.Preset.JAN_MEIER, 100.0).let { db(it.second / it.first) }
        // A higher feed level is a bigger gap between direct and crossfed: less crossover.
        assertTrue(a > b && b > c, "$a, $b, $c")
    }

    @Test
    fun `a centred signal keeps its level`() {
        val xf = Crossfeed().apply { configure(rate, Crossfeed.Preset.DEFAULT) }
        val frame = FloatArray(2)
        var peak = 0.0
        for (i in 0 until rate) {
            val v = sin(2.0 * PI * 200.0 * i / rate).toFloat()
            frame[0] = v; frame[1] = v
            xf.process(frame)
            if (i > rate / 2) peak = maxOf(peak, abs(frame[0].toDouble()))
            assertEquals(frame[0], frame[1], 1e-6f)
        }
        assertEquals(0.0, db(peak), 0.3)
    }

    @Test
    fun `an impulse on the left reaches the right smaller and spread out`() {
        val xf = Crossfeed().apply { configure(rate, Crossfeed.Preset.DEFAULT) }
        val frame = FloatArray(2)
        frame[0] = 1f
        xf.process(frame)
        val firstL = frame[0]
        val firstR = frame[1]
        assertTrue(firstL > 0f && firstR > 0f && firstR < firstL, "L=$firstL R=$firstR")

        var tail = 0.0
        for (i in 0 until 200) {
            frame[0] = 0f; frame[1] = 0f
            xf.process(frame)
            tail += frame[1]
        }
        // The low-pass keeps feeding for a while after the click: that tail is the feed.
        assertTrue(tail > firstR, "tail $tail vs first $firstR")
    }

    // -- Bypass and gating -----------------------------------------------------

    private fun dsp(channels: Int, encoding: Int, preset: Crossfeed.Preset): LocalDsp = LocalDsp().apply {
        configure(AudioProcessor.AudioFormat(rate, channels, encoding))
        flush()
        setCrossfeed(preset)
    }

    private fun shorts(frames: Int, channels: Int, left: Int, other: Int): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(frames * channels * 2).order(ByteOrder.nativeOrder())
        for (i in 0 until frames) {
            for (c in 0 until channels) {
                val a = if (c == 0) left else other
                buf.putShort(((sin(2.0 * PI * 120.0 * i / rate) * a).toInt()).toShort())
            }
        }
        return buf.apply { flip() }
    }

    private fun bytesOf(b: ByteBuffer): ByteArray = ByteArray(b.remaining()).also { b.duplicate().get(it) }

    @Test
    fun `off is bit exact on 16 bit stereo`() {
        val p = dsp(2, C.ENCODING_PCM_16BIT, Crossfeed.Preset.OFF)
        val input = shorts(4_000, 2, 12_000, 7_000)
        val expected = bytesOf(input)
        p.queueInput(input)
        assertContentEquals(expected, bytesOf(p.output))
    }

    @Test
    fun `off is bit exact on float stereo`() {
        val p = dsp(2, C.ENCODING_PCM_FLOAT, Crossfeed.Preset.OFF)
        val buf = ByteBuffer.allocateDirect(2_000 * 2 * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until 2_000) {
            buf.putFloat(sin(2.0 * PI * 330.0 * i / rate).toFloat() * 0.7f)
            buf.putFloat(-0.123f * i / 2_000f)
        }
        buf.flip()
        val expected = bytesOf(buf)
        p.queueInput(buf)
        assertContentEquals(expected, bytesOf(p.output))
    }

    @Test
    fun `switching crossfeed back off returns to bit exact`() {
        val p = dsp(2, C.ENCODING_PCM_16BIT, Crossfeed.Preset.DEFAULT)
        p.queueInput(shorts(2_000, 2, 10_000, 0))
        p.setCrossfeed(Crossfeed.Preset.OFF)
        val input = shorts(2_000, 2, 9_000, 4_000)
        val expected = bytesOf(input)
        p.queueInput(input)
        assertContentEquals(expected, bytesOf(p.output))
    }

    @Test
    fun `on, a left only signal reaches the right channel`() {
        val p = dsp(2, C.ENCODING_PCM_16BIT, Crossfeed.Preset.DEFAULT)
        p.queueInput(shorts(8_000, 2, 12_000, 0))
        val out = p.output.order(ByteOrder.nativeOrder())
        var peakR = 0
        while (out.remaining() >= 4) {
            out.short
            peakR = maxOf(peakR, abs(out.short.toInt()))
        }
        assertTrue(peakR > 3_000, "right peak $peakR")
    }

    @Test
    fun `mono and surround streams are left alone`() {
        for (channels in listOf(1, 6)) {
            val p = dsp(channels, C.ENCODING_PCM_16BIT, Crossfeed.Preset.JAN_MEIER)
            val input = shorts(3_000, channels, 11_000, 5_000)
            val expected = bytesOf(input)
            p.queueInput(input)
            assertContentEquals(expected, bytesOf(p.output), "$channels channels")
        }
    }

    @Test
    fun `a stored choice that is not a preset is off`() {
        assertEquals(Crossfeed.Preset.OFF, Crossfeed.Preset.of("something-from-the-future"))
        assertEquals(Crossfeed.Preset.OFF, Crossfeed.Preset.of(null))
        assertEquals(Crossfeed.Preset.CHU_MOY, Crossfeed.Preset.of("chu_moy"))
    }

    // -- Which output, which setting ------------------------------------------

    private val headphones = OutputKey("bt:AA:BB", "Buds")
    private val usb = OutputKey("usb:Dongle", "Dongle")

    @Test
    fun `only headphones get crossfeed`() {
        val profiles = EqProfiles(crossfeed = Crossfeed.Preset.CHU_MOY.id)
        assertEquals(Crossfeed.Preset.CHU_MOY, CrossfeedSelection.effective(profiles, headphones))
        assertEquals(Crossfeed.Preset.CHU_MOY, CrossfeedSelection.effective(profiles, usb))
        assertEquals(Crossfeed.Preset.CHU_MOY, CrossfeedSelection.effective(profiles, OutputKey("wired", "Wired")))
        assertEquals(Crossfeed.Preset.OFF, CrossfeedSelection.effective(profiles, OutputKeys.SPEAKER))
        assertEquals(Crossfeed.Preset.OFF, CrossfeedSelection.effective(profiles, OutputKey("hdmi", "HDMI")))
        assertEquals(Crossfeed.Preset.OFF, CrossfeedSelection.effective(profiles, null))
    }

    @Test
    fun `each output keeps its own choice when curves are per output`() {
        var profiles = EqProfiles(perOutput = true)
        profiles = profiles.withCrossfeed(headphones, Crossfeed.Preset.JAN_MEIER)
        profiles = profiles.withCrossfeed(usb, Crossfeed.Preset.DEFAULT)

        assertEquals(Crossfeed.Preset.JAN_MEIER, CrossfeedSelection.effective(profiles, headphones))
        assertEquals(Crossfeed.Preset.DEFAULT, CrossfeedSelection.effective(profiles, usb))
        // An output with no choice of its own follows the shared one, which is off.
        assertEquals(Crossfeed.Preset.OFF, CrossfeedSelection.effective(profiles, OutputKey("wired", "Wired")))
    }

    @Test
    fun `one shared choice when curves are not per output`() {
        val profiles = EqProfiles(perOutput = false).withCrossfeed(headphones, Crossfeed.Preset.CHU_MOY)
        assertEquals(Crossfeed.Preset.CHU_MOY, CrossfeedSelection.effective(profiles, usb))
        assertEquals(emptyMap(), profiles.crossfeedByOutput)
    }

    @Test
    fun `the choice survives being saved and read back`() {
        val profiles = EqProfiles(perOutput = true)
            .withCrossfeed(headphones, Crossfeed.Preset.JAN_MEIER)
        val back = EqProfiles.decode(EqProfiles.encode(profiles))
        assertEquals(profiles, back)
        assertNotEquals(EqProfiles(), back)
    }

    @Test
    fun `profiles saved before crossfeed existed read back with it off`() {
        val old = """{"saved":[],"perOutput":true,"byOutput":{},"outputNames":{}}"""
        val profiles = EqProfiles.decode(old)!!
        assertEquals(Crossfeed.Preset.OFF, CrossfeedSelection.effective(profiles, headphones))
    }
}
