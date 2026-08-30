package com.engabd.sendpin.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tap has two jobs and must not compromise either.
 *
 * It has to hand the audio through untouched — this processor sits in the sink's
 * chain for *all* local playback, Light Sync on or off, so a bug here is silence
 * or corruption on every track. And it has to feed the analyzer at the rate the
 * analyzer thinks it is being fed at.
 *
 * The rate is what these tests exist for. The resampler used to compare an `Int`
 * counter against a `Float` ratio, so at 48 kHz (ratio 2.177) it first passed at
 * 3 and decimated by three — 16 kHz into an analyzer configured for 22050. Every
 * band edge was off by 1.378x and every envelope constant ran ~28% slow. It came
 * out right at 44.1 kHz alone, where the ratio is exactly 2.0, which is why
 * casual listening never showed it up.
 */
class AudioAnalysisTapTest {

    /** Frames the analyzer should produce per second, from its own geometry. */
    private val expectedFramesPerSecond = ANALYSIS_SAMPLE_RATE.toDouble() / ANALYSIS_HOP  // 50.0

    private fun pcm16(sampleRate: Int, channels: Int, seconds: Double): ByteBuffer {
        val frames = (sampleRate * seconds).toInt()
        val buf = ByteBuffer.allocateDirect(frames * channels * 2).order(ByteOrder.nativeOrder())
        for (i in 0 until frames) {
            // A 220 Hz tone, loud enough to clear the analyzer's noise gate.
            val v = (sin(2.0 * PI * 220.0 * i / sampleRate) * 12000).toInt().toShort()
            for (c in 0 until channels) buf.putShort(v)
        }
        buf.flip()
        return buf
    }

    private fun configured(sampleRate: Int, channels: Int, encoding: Int = C.ENCODING_PCM_16BIT): AudioAnalysisTap {
        val tap = AudioAnalysisTap()
        tap.configure(AudioProcessor.AudioFormat(sampleRate, channels, encoding))
        tap.flush()  // promotes the pending format, as the sink does
        return tap
    }

    /**
     * Push [input] through in sink-sized chunks, draining output as the sink would.
     *
     * [paced] yields between chunks. ExoPlayer feeds the sink at roughly real
     * time — the AudioTrack stops accepting once its buffer is full — so a test
     * that blasts seconds of audio in one go is not the regime the ring is sized
     * for, and would overrun it before the analysis thread had run at all. Tests
     * that care about analysis output pace; tests that only care about the
     * pass-through do not need to.
     */
    private fun pump(
        tap: AudioAnalysisTap,
        input: ByteBuffer,
        chunkBytes: Int = 8192,
        paced: Boolean = false,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        while (input.hasRemaining()) {
            val n = minOf(chunkBytes, input.remaining())
            val slice = input.slice().limit(n) as ByteBuffer
            tap.queueInput(slice)
            input.position(input.position() + n - slice.remaining())
            val o = tap.getOutput()
            val bytes = ByteArray(o.remaining())
            o.get(bytes)
            out.write(bytes)
            if (paced) Thread.sleep(1)
        }
        return out.toByteArray()
    }

    /** Wait until the analysis thread stops producing, then report the count. */
    private fun settle(counter: AtomicInteger, timeoutMs: Long = 5_000): Int {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = -1
        var stableFor = 0
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
            val now = counter.get()
            if (now == last) {
                stableFor += 25
                if (stableFor >= 200 && now > 0) return now
            } else {
                stableFor = 0
                last = now
            }
        }
        return counter.get()
    }

    @Test
    fun `analysis frame rate is independent of source sample rate`() {
        // The whole point: 44.1, 48 and 96 kHz must all drive the analyzer at
        // ~50 frames/sec. Before the fix, 48 kHz gave ~36 and 96 kHz ~43.
        val seconds = 2.0
        for (rate in listOf(44_100, 48_000, 88_200, 96_000)) {
            val tap = configured(rate, 2)
            val frames = AtomicInteger()
            tap.onFrame = { frames.incrementAndGet() }
            tap.setActive(true)
            try {
                pump(tap, pcm16(rate, 2, seconds), paced = true)
                val got = settle(frames)
                val expected = (expectedFramesPerSecond * seconds).roundToInt()  // 100
                assertEquals(0L, tap.droppedSamples, "at $rate Hz the ring overran, so the count below is meaningless")
                assertTrue(
                    abs(got - expected) <= 2,
                    "at $rate Hz got $got analysis frames over ${seconds}s, expected ~$expected",
                )
            } finally {
                tap.setActive(false)
            }
        }
    }

    @Test
    fun `audio passes through byte for byte with analysis on`() {
        val tap = configured(48_000, 2)
        tap.setActive(true)
        try {
            val input = pcm16(48_000, 2, 0.25)
            val expected = ByteArray(input.remaining()).also { input.duplicate().get(it) }
            val actual = pump(tap, input)
            assertTrue(expected.contentEquals(actual), "tap altered the audio while analysing")
        } finally {
            tap.setActive(false)
        }
    }

    @Test
    fun `audio passes through byte for byte with analysis off`() {
        val tap = configured(48_000, 2)
        val input = pcm16(48_000, 2, 0.25)
        val expected = ByteArray(input.remaining()).also { input.duplicate().get(it) }
        val actual = pump(tap, input)
        assertTrue(expected.contentEquals(actual), "tap altered the audio while inactive")
    }

    @Test
    fun `mono and float sources are handled`() {
        // Both encodings the sink can hand over, and a channel count of one.
        val tap = configured(48_000, 1)
        val frames = AtomicInteger()
        tap.onFrame = { frames.incrementAndGet() }
        tap.setActive(true)
        try {
            pump(tap, pcm16(48_000, 1, 1.0))
            assertTrue(settle(frames) >= 45, "mono 16-bit produced too few frames")
        } finally {
            tap.setActive(false)
        }

        val floatTap = configured(48_000, 2, C.ENCODING_PCM_FLOAT)
        val floatFrames = AtomicInteger()
        floatTap.onFrame = { floatFrames.incrementAndGet() }
        floatTap.setActive(true)
        try {
            val frameCount = 48_000
            val buf = ByteBuffer.allocateDirect(frameCount * 2 * 4).order(ByteOrder.nativeOrder())
            for (i in 0 until frameCount) {
                val v = (sin(2.0 * PI * 220.0 * i / 48_000) * 0.35).toFloat()
                buf.putFloat(v); buf.putFloat(v)
            }
            buf.flip()
            pump(floatTap, buf)
            assertTrue(settle(floatFrames) >= 45, "float PCM produced too few frames")
        } finally {
            floatTap.setActive(false)
        }
    }

    @Test
    fun `stays in the chain for pcm regardless of whether analysis is on`() {
        // isActive() is asked once, when the sink configures the pipeline. If it
        // reported false while Light Sync was off, no later toggle could put the
        // tap back and sync would never work mid-song.
        val tap = configured(48_000, 2)
        assertTrue(tap.isActive, "tap should stay in the chain while inactive")
        tap.setActive(true)
        assertTrue(tap.isActive, "tap should stay in the chain while active")
        tap.setActive(false)
    }

    @Test
    fun `non pcm encoding is declined`() {
        val tap = AudioAnalysisTap()
        val out = tap.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_MP3))
        assertEquals(AudioProcessor.AudioFormat.NOT_SET, out, "compressed audio should not be tapped")
    }

    @Test
    fun `restarting analysis does not replay the previous session's audio`() {
        // Toggling Light Sync off can leave unread samples in the ring. The next
        // session must start from live audio, not finish the last one's.
        val tap = configured(48_000, 2)
        tap.setActive(true)
        pump(tap, pcm16(48_000, 2, 1.0))  // unpaced: deliberately leaves a backlog
        tap.setActive(false)

        val frames = AtomicInteger()
        tap.onFrame = { frames.incrementAndGet() }
        tap.setActive(true)
        try {
            Thread.sleep(300)
            assertEquals(0, frames.get(), "stale audio from the previous session was analysed")
        } finally {
            tap.setActive(false)
        }
    }

    @Test
    fun `flush resets the analyzer so a seek starts clean`() {
        // A seek or a track change must drop the analyzer's history — its AGC
        // peaks, its onset thresholds, its audio clock — or the new position is
        // measured against the old one's loudness.
        //
        // Asserted through the reset callback rather than by counting frames
        // after a sleep. The frame-counting version of this test was racy by
        // construction: whether stale hops get analysed depends on exactly where
        // the consumer thread is when the flush lands, so it failed
        // intermittently while testing nothing the code guarantees. The callback
        // is the actual contract — it fires when the analyzer is reset, and
        // always before the first frame of the new audio.
        val tap = configured(48_000, 2)
        val resets = AtomicInteger()
        tap.onAnalysisReset = { resets.incrementAndGet() }
        tap.setActive(true)
        try {
            // One reset for the session start.
            val deadline = System.currentTimeMillis() + 2_000
            while (resets.get() < 1 && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertEquals(1, resets.get(), "no reset at session start")

            pump(tap, pcm16(48_000, 2, 0.5), paced = true)
            tap.flush()

            val flushDeadline = System.currentTimeMillis() + 2_000
            while (resets.get() < 2 && System.currentTimeMillis() < flushDeadline) Thread.sleep(10)
            assertEquals(2, resets.get(), "flush did not reset the analyzer")
        } finally {
            tap.setActive(false)
        }
    }

    /**
     * The consumer's own clock, which is what makes a bursty producer harmless.
     *
     * [AudioAnalysisTap.analyseExternal]'s caller is
     * [com.engabd.sendpin.audio.SendspinNativeEngine]'s producer thread, which fills
     * its native ring as fast as the decoder will go and only paces itself once the
     * ring is at target — so every `stream/start`, and Music Assistant sends one
     * between every track, dumps seconds of audio in one go. The loop used to drain
     * that flat out, which handed [com.engabd.sendpin.hue.DirectLightSync]'s 60 Hz
     * render loop a hundred-odd frames it could only sample two or three of, and then
     * nothing at all until the next chunk.
     *
     * Three seconds in one write is well past anything the local path can produce, so
     * this is the MA case exactly.
     */
    @Test
    fun `a burst is analysed at the rate it plays, not the rate it arrives`() {
        val tap = AudioAnalysisTap()
        val frames = AtomicInteger()
        tap.onFrame = { frames.incrementAndGet() }
        tap.setActive(true)
        try {
            val seconds = 3.0
            val burst = pcm16(48_000, 2, seconds)
            val expected = (expectedFramesPerSecond * seconds).roundToInt()  // 150
            tap.analyseExternal(burst, 48_000, 2, C.ENCODING_PCM_16BIT, 0L)

            // A quarter of a second in. Unpaced, all 150 are already through; paced,
            // a burst this size sits inside the servo's hold band, so ~12 hops have
            // been read. The bound is deliberately slack in between — this asserts
            // that the loop is rate-limited at all, not what the constants happen to
            // be.
            Thread.sleep(250)
            val early = frames.get()
            assertTrue(early > 0, "nothing was analysed at all")
            assertTrue(early < expected * 2 / 3, "the whole burst was drained at once ($early of $expected in 250ms)")

            // And it all arrives: catching up is done by running faster, never by
            // skipping hops. The analyzer's tAudio is its own sample counter, so a
            // dropped hop would slide the beat grid away from the track.
            val got = settle(frames)
            assertEquals(0L, tap.droppedSamples, "the ring overran, so the count below is meaningless")
            assertTrue(
                abs(got - expected) <= 2,
                "got $got analysis frames from a ${seconds}s burst, expected ~$expected",
            )
        } finally {
            tap.setActive(false)
        }
    }

    /**
     * The backlog a burst leaves behind is *reported*, because that is what makes
     * holding it correct.
     *
     * [AudioLead] says how long until the newest sample handed over is heard. A
     * frame drawn from the far side of a three-second burst describes audio heard
     * three seconds before that, so [com.engabd.sendpin.hue.FrameDelayQueue]'s hold
     * has to come down by exactly this much or the room runs that far behind — which
     * is what the Music Assistant player looked like next to the local one, since
     * only its producer bursts.
     */
    @Test
    fun `the lag of each frame is reported and unwinds as the burst plays`() {
        val tap = AudioAnalysisTap()
        val lags = java.util.concurrent.ConcurrentLinkedQueue<Float>()
        val frames = AtomicInteger()
        tap.onFrame = {
            lags.add(tap.analysisLagS)
            frames.incrementAndGet()
        }
        tap.setActive(true)
        try {
            val seconds = 3.0
            tap.analyseExternal(pcm16(48_000, 2, seconds), 48_000, 2, C.ENCODING_PCM_16BIT, 0L)
            settle(frames)

            val seen = lags.toList()
            assertTrue(seen.size > 100, "too few frames to judge the lag (${seen.size})")
            // Once the write has landed, a frame is most of the burst from the end of
            // it — which is exactly the hold that has to come off. (Not the *first*
            // frame: the consumer thread is already running, so it may get a hop away
            // before the producer has finished writing.)
            val peak = seen.max()
            assertTrue(peak > seconds.toFloat() - 0.5f, "the deepest lag reported was ${peak}s, expected ~$seconds")
            // And the last frame is at the end of the burst, with nothing behind it.
            assertTrue(seen.last() < 0.05f, "last frame reported a lag of ${seen.last()}s, expected ~0")
            // From the peak on it only unwinds: each frame consumes a hop and nothing
            // adds to the ring. A rise there would mean hops were being skipped.
            val unwinding = seen.drop(seen.indexOf(peak))
            assertTrue(
                unwinding.zipWithNext().all { (a, b) -> b <= a + 1e-6f },
                "the lag went back up without a producer to explain it",
            )
        } finally {
            tap.setActive(false)
        }
    }

    /**
     * A producer that feeds in real time leaves nothing to compensate for, so the
     * local player's hold is the one it always had. This is the arm of the same
     * behaviour that must *not* change.
     *
     * Fed at true real time rather than through [pump]'s `paced` flag, which sleeps a
     * flat millisecond per chunk — enough to keep the ring from overrunning, which is
     * all it was for, but some forty times faster than playback and so itself a burst.
     */
    @Test
    fun `a real-time producer keeps the lag near zero`() {
        val tap = configured(48_000, 2)
        var worst = 0f
        tap.onFrame = { if (tap.analysisLagS > worst) worst = tap.analysisLagS }
        tap.setActive(true)
        try {
            val chunkMs = 20L
            val input = pcm16(48_000, 2, 1.0)
            val chunkBytes = (48_000 * chunkMs / 1000).toInt() * 2 * 2
            while (input.hasRemaining()) {
                val n = minOf(chunkBytes, input.remaining())
                val slice = input.slice().limit(n) as ByteBuffer
                tap.queueInput(slice)
                input.position(input.position() + n - slice.remaining())
                tap.getOutput()
                Thread.sleep(chunkMs)
            }
            Thread.sleep(300)
            assertTrue(worst < 0.2f, "a real-time producer built a backlog of ${worst}s")
        } finally {
            tap.setActive(false)
        }
    }
}
