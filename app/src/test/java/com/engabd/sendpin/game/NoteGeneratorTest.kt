package com.engabd.sendpin.game

import com.engabd.sendpin.audio.AnalysisFrame
import com.engabd.sendpin.audio.BeatGrid
import com.engabd.sendpin.audio.ScanSection
import com.engabd.sendpin.audio.TrackScan
import kotlin.math.roundToLong
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

    // ── Engine-grid path (A1) ────────────────────────────────────────────────

    /** A synthetic scan: [bpm] BPM, flat accents, [beatsPerBar], [downbeat]. */
    private fun fakeScan(
        bpm: Float = 120f,
        beats: Int = 32,
        beatsPerBar: Int = 4,
        downbeat: Int = 0,
        accents: FloatArray = FloatArray(beats) { 0.8f },
    ): TrackScan {
        val period = 60f / bpm
        return TrackScan(
            durationS = beats * period,
            bpm = bpm,
            confidence = 0.9f,
            beats = FloatArray(beats) { it * period },
            accents = accents,
            downbeat = downbeat,
            sections = listOf(ScanSection(0f, beats * period, 0.7f)),
            intensity = null,
        )
    }

    /**
     * Play [frames] frames at 50 fps against an engine grid locked at [bpm],
     * with the scan attached. [frameTAudioS] advances with real time so the
     * audible conversion is exercised exactly as the engine publishes it.
     */
    private fun NoteGenerator.playWithEngine(
        startMs: Long,
        frames: Int,
        bpm: Float = 120f,
        scan: TrackScan = fakeScan(bpm),
        reap: Boolean = true,
    ): Long {
        var t = startMs
        val step = 20L
        val periodMs = (60_000f / bpm).toLong()
        repeat(frames) { i ->
            val tAudio = (t - startMs) / 1000f
            val chart = GameChartSource(
                grid = BeatGrid(
                    bpm = bpm,
                    confidence = 1f,
                    locked = true,
                    periodS = 60f / bpm,
                    phase = (t % periodMs) / periodMs.toFloat(),
                    timeToNextBeat = (periodMs - (t % periodMs)) / 1000f,
                    nextBeatT = tAudio + (periodMs - (t % periodMs)) / 1000f,
                    scheduleStrength = 1f,
                ),
                scan = scan,
                frameTAudioS = tAudio,
            )
            onFrame(quietFrame(), t, 0L, chart)
            if (reap) reap(t)
            t += step
        }
        return t
    }

    @Test
    fun `an engine grid charts from its scan, not the PLL`() {
        val gen = NoteGenerator()
        val end = gen.playWithEngine(startMs = 10_000L, frames = 120) // ~2.4 s

        assertTrue(gen.locked, "the engine path never reported locked")
        val notes = gen.active()
        assertTrue(notes.isNotEmpty(), "the engine path charted nothing")

        // Every note lands on the scan's beat grid or a subdivision of it (the
        // offbeat hats sit at +half, fills add +quarter) — the defining property
        // of the engine path. The PLL path would place notes on its own anchor,
        // which no scan beat is required to agree with.
        for (note in notes) {
            val off = note.triggerTimeMs - 10_000L
            assertTrue(
                off % 250L == 0L,
                "note at ${note.triggerTimeMs} is not on the scan's quarter-beat grid (off=$off)",
            )
        }
        assertTrue(end > 10_000L)
    }

    @Test
    fun `engine path beats land on the scan's metre and downbeat`() {
        val gen = NoteGenerator()
        // A waltz, with the downbeat on beat 2 of the recorded pattern — the case
        // the old chart, hardcoded to 4/4 from its own anchor, could not express.
        val scan = fakeScan(bpm = 120f, beatsPerBar = 3, downbeat = 2)
        gen.playWithEngine(startMs = 0L, frames = 150, scan = scan)

        val downbeats = gen.active().filter { it.downbeat }.map { it.triggerTimeMs }
        assertTrue(downbeats.isNotEmpty(), "no downbeat notes charted")
        val period = 500L // 120 BPM
        val first = downbeats.min()
        for (t in downbeats) {
            val off = (t - first).mod(period * 3)
            assertTrue(off == 0L, "downbeat at $t is not on the 3-beat bar grid")
        }
    }

    @Test
    fun `a scan accent drives the note intensity`() {
        val gen = NoteGenerator()
        val accents = FloatArray(32) { if (it % 4 == 0) 1.0f else 0.3f }
        val scan = fakeScan(bpm = 120f, accents = accents)
        gen.playWithEngine(startMs = 0L, frames = 150, scan = scan)

        val kicked = gen.active().filter { it.lane == LANE_KICK }
        assertTrue(kicked.isNotEmpty(), "no kick notes charted")
        // Downbeats get the accent verbatim; the light reward scales with it.
        val onAccent = kicked.filter { it.intensity > 0.9f }
        assertTrue(onAccent.isNotEmpty(), "accented beats did not chart with their accent")
    }

    @Test
    fun `dead air charts nothing`() {
        val gen = NoteGenerator()
        // A run of near-silent beats in the middle: two consecutive accents under
        // the dead-air floor.
        val accents = FloatArray(32) { when (it) {
            in 12..15 -> 0.03f
            else -> 0.8f
        } }
        val scan = fakeScan(bpm = 120f, accents = accents)
        gen.playWithEngine(startMs = 0L, frames = 150, scan = scan)

        val silentBeats = setOf(12, 13, 14, 15)
        for (note in gen.active()) {
            // Reconstruct which beat this note came from by its time: the chart
            // is on the grid, so the reverse lookup is exact.
            val beat = ((note.triggerTimeMs - 0L) / 500L).toInt()
            assertTrue(
                beat !in silentBeats || note.triggerTimeMs % 500L !in 0L..2L,
                "a note was charted inside dead air (beat $beat)",
            )
        }
    }

    @Test
    fun `losing the engine grid hands over to the PLL without a stale chart`() {
        val gen = NoteGenerator()
        val end = gen.playWithEngine(startMs = 0L, frames = 60)
        assertTrue(gen.locked)

        // The engine's grid disappears (capture source starting, scan stops
        // covering): the very next frame must not chart from a stale anchor.
        gen.onFrame(quietFrame(), end, 0L, GameChartSource(grid = null, scan = null, frameTAudioS = 0f))
        assertTrue(
            gen.active().all { it.triggerTimeMs >= end - Judgement.HIT_WINDOW_MS },
            "notes from the stale engine chart survived the handover",
        )
        // And the PLL must be re-anchoring, not claiming a lock it does not have.
        gen.onFrame(beatFrame(), end + 20L, 0L)
        gen.onFrame(beatFrame(), end + 40L, 0L)
    }

    @Test
    fun `the PLL path is byte-identical when no engine grid exists`() {
        // Same feed, no chart parameter: the generator must behave exactly as
        // before the engine path existed. This is the revert test for A1.
        val legacy = NoteGenerator()
        val modern = NoteGenerator()
        var t = 0L
        repeat(120) {
            val f = if (it % 25 < 2) beatFrame() else quietFrame()
            legacy.onFrame(f, t)
            modern.onFrame(f, t, 0L, null)
            legacy.reap(t)
            modern.reap(t)
            t += 20L
        }
        assertEquals(
            legacy.active().map { it.id to it.triggerTimeMs },
            modern.active().map { it.id to it.triggerTimeMs },
            "the null-chart path diverged from the legacy PLL",
        )
    }

    // ── Band mapping (A3) ────────────────────────────────────────────────────

    @Test
    fun `note kinds map to the room bands the show stacks`() {
        // The engine stacks bass on the floor and treble at the ceiling; the
        // game's answer must use the same vocabulary or the two disagree about
        // what "low" means.
        assertEquals(GameBand.BASS, bandOf(NoteKind.KICK))
        assertEquals(GameBand.FULL, bandOf(NoteKind.SNARE))
        assertEquals(GameBand.TOP, bandOf(NoteKind.HAT))
        assertEquals(GameBand.COLOR, bandOf(NoteKind.MELODY))
    }

    @Test
    fun `every note kind has a band`() {
        // Exhaustive when-expression — this is a compile guarantee, but the test
        // documents the contract and fails loudly if a kind is added without a
        // band decision.
        for (kind in NoteKind.entries) {
            assertTrue(bandOf(kind) in GameBand.entries, "no band for $kind")
        }
    }
}
