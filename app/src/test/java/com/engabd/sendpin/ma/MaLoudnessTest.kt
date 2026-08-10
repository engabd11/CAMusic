package com.engabd.sendpin.ma

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What Music Assistant did to the *level*, which the quality card could never say.
 *
 * MA measures a track and decides a correction, and both are in `streamdetails` under
 * names that look nothing like ReplayGain — so a carefully mastered record being
 * pulled to a target LUFS read identically to one left alone.
 */
class MaLoudnessTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun queue(body: String) =
        MaParse.queues(json.parseToJsonElement("[$body]"), null).single()

    @Test
    fun `a correction is read off the stream details`() {
        val q = queue(
            """
            {
              "queue_id": "p1",
              "current_item": {
                "queue_item_id": "q1",
                "streamdetails": {
                  "loudness": -7.4,
                  "loudness_album": -8.1,
                  "volume_normalization_gain_correct": -3.2,
                  "volume_normalization_mode": "dynamic",
                  "target_loudness": -14.0
                }
              }
            }
            """
        )
        assertEquals(-3.2f, q.loudness.gainCorrect)
        assertEquals(-8.1f, q.loudness.loudnessAlbum)
        assertEquals("dynamic", q.loudness.mode)
        val summary = q.loudness.summary!!
        // What was done leads; the measurement supports it.
        assertTrue(summary.startsWith("Music Assistant normalised -3.2 dB"), summary)
        assertTrue("-8.1 LUFS" in summary, summary)
    }

    @Test
    fun `a measurement with no correction says so rather than going quiet`() {
        val q = queue(
            """
            {
              "queue_id": "p1",
              "current_item": {
                "streamdetails": {"loudness": -9.5, "volume_normalization_mode": "disabled"}
              }
            }
            """
        )
        val summary = q.loudness.summary!!
        assertTrue("-9.5 LUFS" in summary, summary)
        assertTrue("left alone" in summary, summary)
    }

    @Test
    fun `a correction too small to hear is not announced as one`() {
        // MA reports a gain correction on every track; 0.02 dB is arithmetic, not a
        // decision, and saying "normalised +0.0 dB" would be worse than saying nothing.
        val l = MaLoudness(loudness = -10f, gainCorrect = 0.02f, mode = "dynamic")
        assertEquals("Measured -10.0 LUFS - no correction applied", l.summary)
    }

    @Test
    fun `a server that reports nothing produces no line at all`() {
        val q = queue("""{"queue_id": "p1", "current_item": {"streamdetails": {}}}""")
        assertTrue(q.loudness.empty)
        assertNull(q.loudness.summary)
    }

    @Test
    fun `no stream details at all is still an empty reading, not a crash`() {
        val q = queue("""{"queue_id": "p1"}""")
        assertTrue(q.loudness.empty)
        assertNull(q.loudness.summary)
    }

    @Test
    fun `the correction alone is enough to say something`() {
        val l = MaLoudness(gainCorrect = -5.5f)
        assertEquals("Music Assistant normalised -5.5 dB", l.summary)
    }
}
