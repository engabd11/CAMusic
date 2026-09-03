package com.engabd.sendpin.game

import com.engabd.sendpin.audio.AnalysisFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The chart generator's two promises: notes land *ahead* of the music, and a note is
 * resolved exactly once.
 *
 * The first is the whole reason this class was rewritten. The old generator spawned
 * a note when it detected a beat and landed it a lookahead later, which put every
 * note late by the lookahead — an input lag no calibration could remove. A test that
 * only checked "notes appear" would have passed against that too, so the assertions
 * here are about *when* the notes are, relative to the beat that caused them.
 */
class NoteGeneratorTest {

    private val periodMs = 500L // 120 BPM
    private val bpm = 120f

    private fun beatFrame() = AnalysisFrame(
        energy = 0.6f,
        beat = true,
        beatStrength = 0.8f,
        bassBeat = true,
        bassStrength = 0.8f,
        tempoBpm = bpm,
    )

    private fun quietFrame() = AnalysisFrame(energy = 0.6f, tempoBpm = bpm)

    /**
     * Feed [beats] beats of a steady 120 BPM, 50 frames a second, from [startMs].
     *
     * Reaps on every step by default, because that is what the game does — the
     * generator is a store the view model advances, not something that retires its
     * own notes, and a test that skipped the reap would be measuring a state the
     * game never reaches. Pass [reap] = false to look at the unreaped board.
     */
    private fun NoteGenerator.playSteady(
        startMs: Long,
        beats: Int,
        leadMs: Long = 0L,
        reap: Boolean = true,
    ): Long {
        var t = startMs
        val step = 20L
        val end = startMs + beats * periodMs
        while (t < end) {
            val onBeat = (t - startMs) % periodMs < step
            onFrame(if (onBeat) beatFrame() else quietFrame(), t, leadMs)
            if (reap) reap(t)
            t += step
        }
        return t
    }

    @Test
    fun `notes are charted ahead of the music, not behind it`() {
        val gen = NoteGenerator()
        val end = gen.playSteady(startMs = 10_000L, beats = 8)

        val notes = gen.active()
        assertTrue(notes.isNotEmpty(), "nothing charted after eight beats of a steady tempo")
        // The defining property: everything still on the board is in the future,
        // give or take the window a note is still hittable in after its moment. A
        // reactive generator would have a board full of notes already past — every
        // one of them spawned a full lookahead after the drum that caused it.
        val floor = end - Judgement.HIT_WINDOW_MS
        assertTrue(
            notes.all { it.triggerTimeMs >= floor },
            "notes charted in the past: ${notes.map { it.triggerTimeMs - end }}",
        )
        // And most of the board is genuinely ahead, not clustered on the line.
        assertTrue(
            notes.count { it.triggerTimeMs > end + periodMs } >= 2,
            "nothing charted more than a beat ahead",
        )
    }

    @Test
    fun `the grid locks onto a steady tempo`() {
        val gen = NoteGenerator()
        gen.playSteady(startMs = 0L, beats = 8)
        assertTrue(gen.locked, "never locked on a perfectly steady 120 BPM")
        assertEquals(120, gen.bpm.toInt(), "wrong tempo: ${gen.bpm}")
    }

    @Test
    fun `charted notes sit on the beat grid`() {
        val gen = NoteGenerator()
        // Long enough that the pre-lock fallback notes — which are reactive and
        // deliberately off-grid, being the best that can be done before a tempo is
        // known — have all been reaped, leaving only what the locked grid wrote.
        gen.playSteady(startMs = 0L, beats = 16)

        // Every note is on a beat or a quarter-beat subdivision of it: the offbeat
        // hats and the fills are the only subdivisions the chart writes.
        val quarter = periodMs / 4
        assertTrue(gen.active().isNotEmpty(), "nothing left to check")
        for (note in gen.active()) {
            val offGrid = ((note.triggerTimeMs % quarter) + quarter) % quarter
            assertTrue(
                offGrid <= 2 || offGrid >= quarter - 2,
                "note at ${note.triggerTimeMs} is ${offGrid}ms off the subdivision grid",
            )
        }
    }

    @Test
    fun `the lead shifts the whole chart into audible time`() {
        val without = NoteGenerator()
        without.playSteady(startMs = 0L, beats = 8)
        val with = NoteGenerator()
        with.playSteady(startMs = 0L, beats = 8, leadMs = 300L)

        val a = without.active().minOf { it.triggerTimeMs }
        val b = with.active().minOf { it.triggerTimeMs }
        // Not an exact 300 — the two grids anchor on their own onsets and the chart
        // is quantised to beats — but the lead has to move it later by roughly that,
        // never earlier. This is what stops a Bluetooth speaker's latency making the
        // whole game feel early.
        assertTrue(b > a, "the lead did not push the chart later: $a then $b")
    }

    @Test
    fun `a note can only be taken once`() {
        val gen = NoteGenerator()
        val end = gen.playSteady(startMs = 0L, beats = 8)
        val target = gen.active().minByOrNull { it.triggerTimeMs }
        assertNotNull(target)

        val first = gen.take(target.lane, target.triggerTimeMs)
        assertEquals(target.id, first?.id)
        // The bug this replaced: the old generator kept its own copy, so the next
        // published frame handed the same note straight back and it could be scored
        // over and over.
        assertNull(
            gen.take(target.lane, target.triggerTimeMs),
            "the same note was taken twice",
        )
        assertTrue(gen.active().none { it.id == target.id })
        assertTrue(end > 0)
    }

    @Test
    fun `a tap outside the hit window takes nothing`() {
        val gen = NoteGenerator()
        gen.playSteady(startMs = 0L, beats = 8)
        val target = gen.active().minByOrNull { it.triggerTimeMs }
        assertNotNull(target)

        val early = target.triggerTimeMs - Judgement.HIT_WINDOW_MS - 50L
        assertNull(gen.take(target.lane, early), "a tap half a beat early scored")
        assertTrue(gen.active().any { it.id == target.id }, "the note was consumed anyway")
    }

    @Test
    fun `notes past the window are reaped exactly once`() {
        val gen = NoteGenerator()
        gen.playSteady(startMs = 0L, beats = 8, reap = false)
        val charted = gen.active().size
        assertTrue(charted > 0)

        val last = gen.active().maxOf { it.triggerTimeMs }
        val missed = gen.reap(last + Judgement.HIT_WINDOW_MS + 1)
        assertEquals(charted, missed.size, "not everything was reaped")
        assertTrue(gen.active().isEmpty())
        assertTrue(gen.reap(last + 10_000L).isEmpty(), "reaped the same notes twice")
    }

    @Test
    fun `the revision only moves when the board does`() {
        val gen = NoteGenerator()
        gen.playSteady(startMs = 0L, beats = 8)
        val before = gen.revision
        // A frame that charts nothing new — no onset, and the horizon already full.
        gen.onFrame(quietFrame(), 4_000L)
        assertEquals(before, gen.revision, "an empty frame bumped the revision")
    }

    @Test
    fun `an unlocked tempo still produces something to play`() {
        // No tempo estimate at all: the fallback has to keep the game alive rather
        // than showing an empty board for the whole track.
        val gen = NoteGenerator()
        var t = 0L
        repeat(40) {
            val onset = it % 10 == 0
            gen.onFrame(
                AnalysisFrame(energy = 0.5f, bassBeat = onset, bassStrength = 0.7f),
                t,
            )
            t += 20L
        }
        assertTrue(!gen.locked, "locked with no tempo estimate")
        assertTrue(gen.active().isNotEmpty(), "the fallback charted nothing")
    }

    @Test
    fun `reset clears the board`() {
        val gen = NoteGenerator()
        gen.playSteady(startMs = 0L, beats = 8)
        assertTrue(gen.active().isNotEmpty())
        gen.reset()
        assertTrue(gen.active().isEmpty())
        assertTrue(!gen.locked)
    }
}
