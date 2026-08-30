package com.engabd.sendpin.hue

import com.engabd.sendpin.audio.AnalysisFrame
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The renderer that existed only as an enum value.
 *
 * `graphReactive` was declared on Extreme's params and never read, so it fell
 * through to the ordinary music path — where it sets `beatThreshold = 99` and
 * zeroes its beat and bass gains, leaving the most aggressive rung the *least*
 * reactive one after Subtle.
 */
class ExtremeTest {

    private val dt = 1f / 60f

    private fun channels(n: Int) = (0 until n).map { i ->
        EntertainmentChannel(
            channelId = i,
            position = ChannelPosition(
                x = -1f + 2f * i / max(1, n - 1),
                y = 0f,
                z = i.toFloat() / max(1, n - 1),
            ),
        )
    }

    /** A frame whose melbank energy sits in one narrow region of the spectrum. */
    private fun bandedFrame(hotBin: Int, hot: Float = 0.9f, quiet: Float = 0.05f) = AnalysisFrame(
        bands = mapOf("sub_bass" to 0.6f, "bass" to 0.6f, "low_mid" to 0.3f, "mid" to 0.3f, "high" to 0.3f),
        energy = 0.7f,
        melbank = FloatArray(16) { if (it == hotBin) hot else quiet },
        salience = 1f,
        onsetWidth = 1f,
    )

    private fun brightness(c: Rgb) = max(c.first, max(c.second, c.third))

    @Test
    fun `extreme reacts to a hit at all`() {
        // The regression that matters: on the music path Extreme's beatThreshold
        // of 99 meant a kick produced no flash whatsoever.
        val eng = SyncoEngine(channels(6)).apply { mode = SyncMode.EXTREME }
        repeat(120) { eng.render(bandedFrame(2, hot = 0.2f), dt) }
        val resting = eng.render(bandedFrame(2, hot = 0.2f), dt).values.map(::brightness).average()
        // A sudden spike in one band is a fresh attack.
        val hit = eng.render(bandedFrame(2, hot = 1.0f), dt).values.map(::brightness).average()
        assertTrue(hit > resting * 1.2f, "Extreme did not react to a hit: $resting -> $hit")
    }

    @Test
    fun `extreme separates instruments across the room`() {
        // "The song is a graph": a low-band hit and a high-band hit should light
        // different parts of the room, not the whole thing equally.
        val eng = SyncoEngine(channels(8)).apply { mode = SyncMode.EXTREME }
        repeat(120) { eng.render(bandedFrame(0, hot = 0.1f), dt) }

        val low = eng.render(bandedFrame(0, hot = 1f), dt)
        repeat(60) { eng.render(bandedFrame(0, hot = 0.1f), dt) }
        val high = eng.render(bandedFrame(15, hot = 1f), dt)

        // The brightest lamp should not be the same one for both.
        val lowPeak = low.maxByOrNull { brightness(it.value) }?.key
        val highPeak = high.maxByOrNull { brightness(it.value) }?.key
        assertTrue(
            lowPeak != highPeak,
            "a bass hit and a treble hit both peaked at lamp $lowPeak, no spatial separation",
        )
    }

    @Test
    fun `extreme glows on a held tone without strobing`() {
        // No fresh attack means no flash: a sustained pad should sit still.
        val eng = SyncoEngine(channels(6)).apply { mode = SyncMode.EXTREME }
        repeat(120) { eng.render(bandedFrame(6, hot = 0.8f), dt) }
        val samples = (0 until 60).map {
            eng.render(bandedFrame(6, hot = 0.8f), dt).values.map(::brightness).average()
        }
        val swing = samples.max() - samples.min()
        assertTrue(swing < 0.05f, "a held tone swung by $swing")
        assertTrue(samples.last() > 0.02f, "a held tone produced no glow at all")
    }

    @Test
    fun `extreme stays dark on silence`() {
        val eng = SyncoEngine(channels(6)).apply { mode = SyncMode.EXTREME }
        val silence = AnalysisFrame()
        repeat(120) {
            for (c in eng.render(silence, dt).values) {
                assertTrue(brightness(c) < 0.05f, "silence lit Extreme to ${brightness(c)}")
            }
        }
    }

    @Test
    fun `extreme output stays in range`() {
        val eng = SyncoEngine(channels(5)).apply { mode = SyncMode.EXTREME }
        val rnd = java.util.Random(5)
        for (i in 0 until 400) {
            val f = AnalysisFrame(
                bands = mapOf("sub_bass" to rnd.nextFloat(), "bass" to rnd.nextFloat(), "high" to rnd.nextFloat()),
                energy = rnd.nextFloat(),
                melbank = FloatArray(16) { rnd.nextFloat() },
                salience = rnd.nextFloat(),
                onsetWidth = 1f,
            )
            for (c in eng.render(f, dt).values) {
                assertTrue(c.first in 0f..1f && c.second in 0f..1f && c.third in 0f..1f, "out of range: $c")
                assertTrue(!c.first.isNaN(), "NaN from Extreme")
            }
        }
    }
}
