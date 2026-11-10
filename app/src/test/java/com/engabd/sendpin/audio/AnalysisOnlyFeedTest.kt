package com.engabd.sendpin.audio

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The light listener's half of [SendspinNativeEngine.writeChunk]: a decoded chunk goes to
 * the analysis tap with the lead of the group timeline plus the user's speaker offset.
 *
 * [SendspinNativeEngine.publishToLightSync] is the whole of what the listener does with a
 * chunk: it has no output to write to, so a chunk handed to it reaches the tap and the
 * lead and nothing else. (The engine itself needs a Context and a Looper and cannot be
 * built on the JVM; there, the listener's branch skips `nativeOutput.write` and never
 * starts the Oboe stream.)
 */
class AnalysisOnlyFeedTest {

    private fun pcm16(sampleRate: Int, channels: Int, seconds: Double): ByteArray {
        val frames = (sampleRate * seconds).toInt()
        val out = ByteArray(frames * channels * 2)
        var i = 0
        for (f in 0 until frames) {
            val v = (sin(2.0 * PI * 220.0 * f / sampleRate) * 12000).toInt()
            repeat(channels) {
                out[i++] = (v and 0xFF).toByte()
                out[i++] = ((v shr 8) and 0xFF).toByte()
            }
        }
        return out
    }

    @Test
    fun `the listener's chunk reaches the tap with the group's lead plus the offset`() {
        val lead = AudioLead()
        val tap = AudioAnalysisTap(lead)
        val frames = CountDownLatch(5)
        val centroid = AtomicReference(0f)
        tap.onFrame = { centroid.set(it.centroid); frames.countDown() }
        tap.setActive(true)
        try {
            val now = 10_000_000L
            val pcm = pcm16(48_000, 2, 0.5)
            SendspinNativeEngine.publishToLightSync(
                tap, lead, pcm, 0, pcm.size, 48_000, 2,
                mediaTimeUs = 250_000L,
                presentationUs = now + 1_200_000L,
                outputLatencyUs = 0L, // no output in listener mode
                offsetMs = 150,
                nowUs = { now },
            )
            // Due 1.2 s from now on the group timeline, plus 150 ms of speaker offset.
            assertEquals(1_350_000L, lead.leadUs)
            assertEquals(250_000L, lead.mediaTimeUs)
            assertTrue(frames.await(5, TimeUnit.SECONDS), "the tap never analysed the chunk")
            // A 220 Hz tone reads as a low centroid. Read byte-swapped, as `wrap` alone
            // did, the same bytes are broadband noise several kHz up.
            assertTrue(centroid.get() < 0.2f, "220 Hz tone read with centroid ${centroid.get()} (x5 kHz): byte order is wrong")
        } finally {
            tap.setActive(false)
        }
    }

    @Test
    fun `the reported lead is clamped and unchanged without an offset`() {
        // The audible path passes no offset, and gets exactly what it always had.
        assertEquals(1_013_000L, SendspinNativeEngine.reportedLeadUs(2_000_000L, 1_000_000L, 13_000L))
        // Late audio leads by nothing, and a negative offset cannot take it below that.
        assertEquals(0L, SendspinNativeEngine.reportedLeadUs(500_000L, 1_000_000L, 0L, offsetMs = -300))
        // A stream head's whole read-ahead is not a lead anyone can act on.
        assertEquals(4_000_000L, SendspinNativeEngine.reportedLeadUs(20_000_000L, 0L, 0L, offsetMs = 500))
    }
}
