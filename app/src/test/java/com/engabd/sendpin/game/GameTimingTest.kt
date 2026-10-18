package com.engabd.sendpin.game

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The clock arithmetic behind the game's feel: the lead and lag the chart is written
 * with, how a touch is timed, when an early tap's light is released, and the sign of
 * the calibration. Each of these was a way for the game to be consistently early or
 * late without anything looking broken.
 */
class GameTimingTest {

    // ── Lead and lag ─────────────────────────────────────────────────────────

    @Test
    fun `lag comes off the lead because the frame is older than the newest audio`() {
        // The frame describes audio 120 ms older than the newest sample, which is heard
        // 120 ms sooner than the lead says.
        assertEquals(180L, GameTiming.effectiveLeadMs(300L, 120f))
    }

    @Test
    fun `a lag longer than the lead never goes negative`() {
        // Music Assistant after a stream start: seconds of lag.
        assertEquals(0L, GameTiming.effectiveLeadMs(300L, 5_000f))
    }

    @Test
    fun `two ways of describing the same moment chart the same time`() {
        // A raw lead of 1000 ms with the frame 800 ms behind is the same instant as a
        // lead of 200 ms with no lag. Using the raw lead put every note 800 ms late.
        assertEquals(GameTiming.effectiveLeadMs(1_000L, 800f), GameTiming.effectiveLeadMs(200L, 0f))
    }

    @Test
    fun `an unknown lead charts in analysis time whatever the lag`() {
        assertEquals(0L, GameTiming.effectiveLeadMs(0L, 250f))
    }

    // ── Touch and display time ───────────────────────────────────────────────

    @Test
    fun `a tap is timed from its own stamp not from when the handler ran`() {
        // The event was stamped 40 ms before the handler saw it.
        assertEquals(4_960L, GameTiming.tapGameMs(5_000L, 100_000L, 99_960L))
    }

    @Test
    fun `the two clock bases never meet`() {
        // Uptime and the game clock are hours apart (deep sleep, pauses); only the
        // event's age crosses over.
        assertEquals(5_000L - 16L, GameTiming.tapGameMs(5_000L, 7_200_016L, 7_200_000L))
    }

    @Test
    fun `an event from the future or the distant past is clamped`() {
        assertEquals(5_000L, GameTiming.tapGameMs(5_000L, 100_000L, 100_020L))
        assertEquals(4_750L, GameTiming.tapGameMs(5_000L, 100_000L, 50_000L))
    }

    @Test
    fun `a display frame belongs to its vsync`() {
        // The callback ran 6 ms after the frame time.
        assertEquals(994L, GameTiming.frameGameMs(1_000L, 106_000_000L, 100_000_000L))
        assertEquals(950L, GameTiming.frameGameMs(1_000L, 900_000_000L, 100_000_000L)) // clamped to 50
    }

    @Test
    fun `display latency is one vsync of the panel`() {
        assertEquals(17L, GameTiming.displayLatencyMs(60f))
        assertEquals(11L, GameTiming.displayLatencyMs(90f))
        assertEquals(8L, GameTiming.displayLatencyMs(120f))
        assertEquals(17L, GameTiming.displayLatencyMs(0f)) // unknown rate falls back to 60 Hz
    }

    // ── Early taps ───────────────────────────────────────────────────────────

    @Test
    fun `an early tap waits so the light and the beat arrive together`() {
        // 250 ms early, lamp pipeline 100 ms: send in 150 ms.
        assertEquals(150L, GameTiming.flashDelayMs(250L))
    }

    @Test
    fun `a tap on or after the beat goes out at once`() {
        assertEquals(0L, GameTiming.flashDelayMs(0L))
        assertEquals(0L, GameTiming.flashDelayMs(-40L))
        // Earlier than now by less than the pipeline: it cannot be earlier than now.
        assertEquals(0L, GameTiming.flashDelayMs(80L))
    }

    @Test
    fun `the held flash becomes visible exactly on the note`() {
        val early = 175L
        val delay = GameTiming.flashDelayMs(early)
        assertEquals(early, delay + GameTiming.LIGHT_PIPELINE_MS)
    }

    // ── Calibration sign ─────────────────────────────────────────────────────

    @Test
    fun `the lead is added to the beat time not subtracted`() {
        // Analysed at 10 000, next beat in 100 ms, heard 200 ms after that.
        assertEquals(10_300L, GameTiming.audibleBeatMs(10_000L, 0.1f, 200L, 0f))
        // And the lag comes off the lead, as in the game's chart.
        assertEquals(10_150L, GameTiming.audibleBeatMs(10_000L, 0.1f, 200L, 150f))
    }

    @Test
    fun `the calibration pulse and the game's note agree on the audible beat`() {
        // The chart writes a beat at frame time + time to next beat + effective lead.
        val frame = 42_000L
        val toNext = 0.35f
        val lead = 180L
        val lag = 60f
        val note = frame + (toNext * 1000f).toLong() + GameTiming.effectiveLeadMs(lead, lag)
        assertEquals(note, GameTiming.audibleBeatMs(frame, toNext, lead, lag))
    }

    @Test
    fun `a tap after the beat is positive and before it is negative`() {
        assertEquals(40f, GameTiming.beatDeltaMs(1_040L, 1_000L, 500f), 0.01f)
        assertEquals(-40f, GameTiming.beatDeltaMs(960L, 1_000L, 500f), 0.01f)
        // Nearest beat, not the reference one.
        assertEquals(-10f, GameTiming.beatDeltaMs(1_490L, 1_000L, 500f), 0.01f)
        assertEquals(30f, GameTiming.beatDeltaMs(4_030L, 1_000L, 500f), 0.01f)
    }

    @Test
    fun `a tap on the audible beat measures zero`() {
        val beat = GameTiming.audibleBeatMs(10_000L, 0.1f, 200L, 0f)
        assertEquals(0f, GameTiming.beatDeltaMs(beat, beat, 500f), 0.01f)
        // The old, subtracted lead put the pulse 400 ms away from where the game
        // writes the same beat, so a perfectly timed player read as 100 ms off.
        val wrong = 10_000L + (100f - 200f).mod(500f).toLong()
        assertTrue(kotlin.math.abs(GameTiming.beatDeltaMs(beat, wrong, 500f)) > 50f)
    }

    @Test
    fun `a late player is judged back onto the beat`() {
        // Stored offset is how late the taps run; the judged clock comes back by it.
        assertEquals(1_000L, GameTiming.judgedMs(1_040L, 40))
        assertEquals(1_000L, GameTiming.judgedMs(960L, -40))
    }

    @Test
    fun `a calibrated player lands centred`() {
        // Twelve taps all 40 ms late: calibration stores +40, and replaying the same
        // taps through the game's judging puts every one on the beat.
        val measured = GameTiming.median(List(12) { 40L }).toInt()
        assertEquals(40, measured)
        assertEquals(0L, GameTiming.judgedMs(1_040L, measured) - 1_000L)
    }

    @Test
    fun `the suggestion only adds what the current offset has not absorbed`() {
        assertEquals(35, GameTiming.suggestedOffsetMs(0, listOf(30L, 40L, 35L)))
        // Already compensating 40 ms and still 5 ms early: back off to 35.
        assertEquals(35, GameTiming.suggestedOffsetMs(40, listOf(-5L, -5L, -5L)))
        assertEquals(300, GameTiming.suggestedOffsetMs(290, listOf(80L, 90L, 85L)))
        assertEquals(-300, GameTiming.suggestedOffsetMs(-290, listOf(-80L, -90L, -85L)))
    }

    @Test
    fun `median averages the middle pair and is zero when empty`() {
        assertEquals(0L, GameTiming.median(emptyList()))
        assertEquals(3L, GameTiming.median(listOf(9L, 1L, 3L)))
        assertEquals(25L, GameTiming.median(listOf(10L, 40L, 20L, 30L)))
    }

    @Test
    fun `the histogram buckets early to late and pools the tails`() {
        val bins = GameTiming.histogram(listOf(-200L, -60L, 0L, 10L, 70L, 300L))
        assertContentEquals(intArrayOf(1, 0, 1, 2, 1, 0, 1), bins)
    }

    // ── Pausable clock bridge ────────────────────────────────────────────────

    @Test
    fun `game time converts to real time and back across a pause`() {
        var real = 1_000L
        val clock = PausableClock { real }
        clock.reset()
        real = 1_500L
        clock.pause()
        real = 2_500L // a second paused
        clock.resume()
        real = 2_700L
        assertEquals(1_700L, clock.now()) // 2_700 real, less the second spent paused
        assertEquals(2_700L, clock.toSource(clock.now()))
        assertEquals(clock.now(), clock.fromSource(2_700L))
    }

    // ── Onset buffer ─────────────────────────────────────────────────────────

    private fun onset(at: Long) = GameOnset(at, 0f, true, 0.8f, false, 0f, false, 0f)

    @Test
    fun `onsets drain oldest first and only once`() {
        val buffer = GameOnsetBuffer()
        buffer.push(onset(1))
        buffer.push(onset(2))
        buffer.push(onset(3))
        assertEquals(listOf(1L, 2L, 3L), buffer.drain().map { it.atMs })
        assertTrue(buffer.drain().isEmpty())
    }

    @Test
    fun `a full buffer drops the oldest`() {
        val buffer = GameOnsetBuffer(capacity = 4)
        for (i in 1L..10L) buffer.push(onset(i))
        assertEquals(listOf(7L, 8L, 9L, 10L), buffer.drain().map { it.atMs })
    }

    @Test
    fun `two threads can push while the game drains`() {
        val buffer = GameOnsetBuffer(capacity = 10_000)
        val pushers = List(2) { t ->
            Thread { repeat(1_000) { buffer.push(onset(t * 10_000L + it)) } }
        }
        pushers.forEach { it.start() }
        val seen = ArrayList<GameOnset>()
        while (pushers.any { it.isAlive }) seen += buffer.drain()
        pushers.forEach { it.join() }
        seen += buffer.drain()
        assertEquals(2_000, seen.size, "an onset was lost or duplicated")
    }
}
