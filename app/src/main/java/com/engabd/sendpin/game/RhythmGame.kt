package com.engabd.sendpin.game

import com.engabd.sendpin.audio.AnalysisFrame
import kotlin.math.abs
import kotlin.math.roundToLong

enum class NoteKind { KICK, SNARE, HAT, MELODY }

/** Lane indices, named so the chart reads as music rather than as integers. */
const val LANE_KICK = 0
const val LANE_SNARE = 1
const val LANE_HAT = 2
const val LANE_MELODY = 3

data class GameNote(
    /** Stable identity, so the UI can key an animation to a note across frames. */
    val id: Long,
    val lane: Int,
    /** When this note should be struck, on the same clock [NoteGenerator.onFrame] is fed. */
    val triggerTimeMs: Long,
    val kind: NoteKind,
    val intensity: Float,
    /** True on the note that falls on a bar's downbeat — the UI accents these. */
    val downbeat: Boolean = false,
)

/**
 * How well a tap landed.
 *
 * The windows are wider than an arcade game's on purpose. The note times come from
 * a live tempo estimate of audio nobody charted by hand, and the player is tapping
 * a sheet of glass against a room's worth of speaker latency — a 20 ms Perfect
 * window would only ever be measuring how wrong the estimate was.
 */
enum class Judgement(val label: String, val points: Int, val windowMs: Long) {
    PERFECT("Perfect", 100, 60L),
    GREAT("Great", 60, 115L),
    GOOD("Good", 30, 175L),
    MISS("Miss", 0, 0L);

    companion object {
        /** The widest window any tap can still be judged in. */
        val HIT_WINDOW_MS = GOOD.windowMs

        fun forDelta(deltaMs: Long): Judgement = when {
            deltaMs <= PERFECT.windowMs -> PERFECT
            deltaMs <= GREAT.windowMs -> GREAT
            deltaMs <= GOOD.windowMs -> GOOD
            else -> MISS
        }
    }
}

/**
 * Turns the live analysis stream into a chart the player can actually read.
 *
 * ## Why this predicts rather than reacts
 *
 * The obvious implementation — spawn a note whenever a beat is detected, a fixed
 * lookahead into the future — cannot produce a playable game, and the reason is
 * worth writing down because it looks like it should work. A detected onset is
 * already in the past: the note is spawned at *detection* time and lands a
 * lookahead later, so it reaches the hit line long after the drum that caused it
 * was heard. The player is asked to tap in time with a line that is, by
 * construction, never in time with the music. Every note is late by the same
 * amount, so the game feels like it has input lag that no calibration can remove.
 *
 * So the detected onsets are used for what they are actually good for — locking a
 * *phase* — and the notes are scheduled onto beats that have not happened yet.
 * [AnalysisFrame.tempoBpm] gives the period, the onsets give the offset, and the
 * chart is written a beat or three ahead of the music. That is what makes the hit
 * line land on the beat instead of behind it.
 *
 * ## Audible time
 *
 * Everything here is in *audible* milliseconds: the clock the player's ears are on,
 * not the clock the analyser is on. The tap runs ahead of the speaker (see
 * `FrameDelayQueue` for the same correction on the light side), so a beat detected
 * now is heard `leadMs` from now — and the note has to land when it is *heard*.
 * [onFrame] takes that lead and does the conversion once, here, so no caller has to
 * remember to.
 *
 * ## Threading
 *
 * Not synchronised. Every entry point is called from the main thread — the frame
 * collector and the tap handler are both on it — and the mutable list would need a
 * lock the moment that stopped being true.
 */
class NoteGenerator(
    private val lanes: Int = 4,
    /** How far ahead of the music notes are written, and so how long they fall for. */
    val lookAheadMs: Long = 1600L,
) {
    private val notes = ArrayList<GameNote>()
    private var nextId = 1L

    /**
     * Bumped whenever the note list actually changes.
     *
     * The list is mutated in place every frame, so "did anything change" cannot be
     * answered by comparing references, and re-publishing it unconditionally would
     * hand the UI a new list sixty times a second — which is a recomposition of the
     * whole screen per frame for a list that changes a few times a second.
     */
    var revision: Int = 0
        private set

    // ── Beat clock ──────────────────────────────────────────────────────────
    //
    // A beat is at `anchorMs + n * periodMs` for integer n. The onsets move
    // `anchorMs`; the tempo estimate moves `periodMs`; nothing else writes either.

    private var periodMs = 0f
    private var anchorMs = 0L
    private var haveAnchor = false

    /** The next beat index, counted from [anchorMs], that has not been charted yet. */
    private var nextBeat = 0

    /** Consecutive onsets that landed near where the grid said they would. */
    private var agreement = 0

    /** True once the grid has been right often enough to chart against. */
    val locked: Boolean get() = haveAnchor && periodMs > 0f && agreement >= LOCK_AGREEMENTS

    val bpm: Float get() = if (periodMs > 0f) 60_000f / periodMs else 0f

    // Rolling character of the music, so a lane means the same thing for more than
    // one beat. Smoothed hard: these choose which lanes exist in the chart, and a
    // per-frame decision would make the chart flicker between shapes.
    private var hatLevel = 0f
    private var melodyLevel = 0f
    private var energyLevel = 0f

    /** Audible time of the last onset-driven note, for the unlocked fallback. */
    private var lastFallbackMs = 0L

    /**
     * Fold one analysis frame in, and chart whatever it makes possible.
     *
     * [nowMs] is the arrival time on the game's own monotonic clock, and [leadMs]
     * how long until the audio this frame describes is audible.
     */
    fun onFrame(frame: AnalysisFrame, nowMs: Long, leadMs: Long = 0L) {
        val audibleNow = nowMs + leadMs

        trackCharacter(frame)
        trackTempo(frame)
        trackPhase(frame, audibleNow)

        if (locked) chartAhead(audibleNow) else chartFallback(frame, audibleNow)
    }

    /** Every note still in play. */
    fun active(): List<GameNote> = notes

    /**
     * Take the note a tap at [nowMs] in [lane] should be judged against, if any.
     *
     * Removes it, so one note can only be scored once — which the old version could
     * not promise: it filtered the published list, and the next frame republished
     * the note from the generator's own store.
     */
    fun take(lane: Int, nowMs: Long): GameNote? {
        var best: GameNote? = null
        var bestDelta = Long.MAX_VALUE
        for (n in notes) {
            if (n.lane != lane) continue
            val d = abs(n.triggerTimeMs - nowMs)
            if (d < bestDelta) {
                bestDelta = d
                best = n
            }
        }
        val hit = best ?: return null
        if (bestDelta > Judgement.HIT_WINDOW_MS) return null
        notes.remove(hit)
        revision++
        return hit
    }

    /**
     * Remove and return every note that has fallen past the last window it could
     * have been hit in. These are the misses the player never touched.
     */
    fun reap(nowMs: Long): List<GameNote> {
        if (notes.isEmpty()) return emptyList()
        val cutoff = nowMs - Judgement.HIT_WINDOW_MS
        var i = 0
        var missed: ArrayList<GameNote>? = null
        while (i < notes.size) {
            if (notes[i].triggerTimeMs < cutoff) {
                val list = missed ?: ArrayList<GameNote>().also { missed = it }
                list.add(notes.removeAt(i))
            } else {
                i++
            }
        }
        if (missed != null) revision++
        return missed ?: emptyList()
    }

    fun reset() {
        notes.clear()
        revision++
        haveAnchor = false
        periodMs = 0f
        agreement = 0
        nextBeat = 0
        hatLevel = 0f
        melodyLevel = 0f
        energyLevel = 0f
        lastFallbackMs = 0L
    }

    // ── Listening ───────────────────────────────────────────────────────────

    private fun trackCharacter(frame: AnalysisFrame) {
        energyLevel += (frame.energy - energyLevel) * 0.08f
        val hat = if (frame.midBeat) frame.midStrength else 0f
        hatLevel += (hat - hatLevel) * 0.05f
        val bins = frame.melbank
        var top = 0f
        if (bins.isNotEmpty()) {
            val from = bins.size * 2 / 3
            for (i in from until bins.size) if (bins[i] > top) top = bins[i]
        }
        melodyLevel += (top - melodyLevel) * 0.05f
    }

    private fun trackTempo(frame: AnalysisFrame) {
        val bpm = frame.tempoBpm
        if (bpm < MIN_BPM || bpm > MAX_BPM) return
        val p = 60_000f / bpm
        periodMs = if (periodMs <= 0f) p else periodMs + (p - periodMs) * TEMPO_SMOOTHING
    }

    /**
     * Pull the grid onto the beat, and count how often it was already there.
     *
     * A phase-locked loop rather than a hard snap: snapping to every onset makes the
     * grid jitter by however wrong the last detection was, and a chart written from
     * a jittering grid is unplayable in a way that looks like the game not knowing
     * the tempo. Small errors nudge; a large one is a detection landing on a
     * different subdivision than the grid expects, and is ignored rather than
     * allowed to drag the phase off a beat it already has right.
     */
    private fun trackPhase(frame: AnalysisFrame, audibleNow: Long) {
        if (!frame.beat && !frame.bassBeat) return
        if (periodMs <= 0f) return

        if (!haveAnchor) {
            anchorMs = audibleNow
            haveAnchor = true
            nextBeat = 0
            agreement = 1
            return
        }

        val beatsSince = ((audibleNow - anchorMs) / periodMs).roundToLong()
        val predicted = anchorMs + (beatsSince * periodMs).roundToLong()
        val err = audibleNow - predicted
        if (abs(err) <= periodMs * NEAR_FRACTION) {
            anchorMs += (err * PHASE_PULL).roundToLong()
            if (agreement < LOCK_AGREEMENTS * 2) agreement++
        } else if (agreement > 0) {
            // Off by enough that this is a different subdivision, not a correction.
            // One is noise; a run of them means the grid is genuinely wrong and has
            // to be allowed to fall out of lock and re-anchor.
            agreement--
            if (agreement == 0) haveAnchor = false
        }
    }

    // ── Charting ────────────────────────────────────────────────────────────

    /** Write every beat that falls inside the lookahead and has not been written yet. */
    private fun chartAhead(audibleNow: Long) {
        val horizon = audibleNow + lookAheadMs
        // Catch up if the game was paused or the grid re-anchored behind us: start
        // from the first beat still in the future rather than charting a backlog of
        // notes that would spawn already past the line.
        val firstFuture = ((audibleNow - anchorMs) / periodMs).toInt() + 1
        if (nextBeat < firstFuture) nextBeat = firstFuture

        var guard = 0
        while (guard++ < MAX_BEATS_PER_FRAME) {
            val t = anchorMs + (nextBeat * periodMs).roundToLong()
            if (t > horizon) break
            chartBeat(t, nextBeat)
            nextBeat++
        }
    }

    /**
     * One beat's worth of notes.
     *
     * The shape is a drum pattern rather than a random scatter: the backbeat is
     * where it always is, so the chart is *readable* — a player learns the pattern
     * in a bar and then plays it, which is the whole difference between a rhythm
     * game and a reaction test.
     */
    private fun chartBeat(timeMs: Long, index: Int) {
        val inBar = ((index % BEATS_PER_BAR) + BEATS_PER_BAR) % BEATS_PER_BAR
        val downbeat = inBar == 0
        val half = (periodMs / 2f).roundToLong()

        // The pulse: kick on 1 and 3, snare on 2 and 4.
        if (inBar == 0 || inBar == 2) {
            add(LANE_KICK, timeMs, NoteKind.KICK, 0.7f + energyLevel * 0.3f, downbeat)
        } else {
            add(LANE_SNARE, timeMs, NoteKind.SNARE, 0.6f + energyLevel * 0.3f, false)
        }

        // Hats fill the offbeat once the track has them and is busy enough to want
        // the density. Scaled by how strongly this section is actually ticking, so a
        // sparse verse stays sparse and a chorus fills in.
        if (hatLevel > HAT_THRESHOLD && chance(index, 11) < 0.35f + hatLevel) {
            add(LANE_HAT, timeMs + half, NoteKind.HAT, hatLevel, false)
        }

        // A melody accent, sparingly — every other bar at most, so the fourth lane
        // is an event rather than a fifth of the noise.
        if (melodyLevel > MELODY_THRESHOLD && inBar == 2 && chance(index, 23) < 0.55f) {
            add(LANE_MELODY, timeMs, NoteKind.MELODY, melodyLevel, false)
        }

        // Loud sections get a run into the downbeat: the fill that makes a chart
        // feel written rather than generated.
        if (energyLevel > FILL_THRESHOLD && inBar == BEATS_PER_BAR - 1 && chance(index, 37) < 0.3f) {
            val quarter = (periodMs / 4f).roundToLong()
            add(LANE_HAT, timeMs + half, NoteKind.HAT, 0.8f, false)
            add(LANE_SNARE, timeMs + half + quarter, NoteKind.SNARE, 0.9f, false)
        }
    }

    /**
     * What to do before the tempo has locked, or when it never does.
     *
     * Reactive, and so late by exactly the amount described in the class note — but
     * a game that does nothing at all for the first few seconds of every track is
     * worse than one that is briefly loose. Rate-limited so a noisy detector cannot
     * carpet the board.
     */
    private fun chartFallback(frame: AnalysisFrame, audibleNow: Long) {
        if (audibleNow - lastFallbackMs < FALLBACK_MIN_GAP_MS) return
        val t = audibleNow + lookAheadMs
        when {
            frame.bassBeat -> add(LANE_KICK, t, NoteKind.KICK, frame.bassStrength, false)
            frame.beat -> add(LANE_SNARE, t, NoteKind.SNARE, frame.beatStrength, false)
            frame.midBeat -> add(LANE_HAT, t, NoteKind.HAT, frame.midStrength, false)
            else -> return
        }
        lastFallbackMs = audibleNow
    }

    private fun add(lane: Int, timeMs: Long, kind: NoteKind, intensity: Float, downbeat: Boolean) {
        if (lane !in 0 until lanes) return
        // Two notes a player cannot tell apart are one note they will miss half of.
        if (notes.any { it.lane == lane && abs(it.triggerTimeMs - timeMs) < MIN_GAP_MS }) return
        notes.add(
            GameNote(
                id = nextId++,
                lane = lane,
                triggerTimeMs = timeMs,
                kind = kind,
                intensity = intensity.coerceIn(0f, 1f),
                downbeat = downbeat,
            )
        )
        revision++
    }

    /**
     * A stable pseudo-random in 0..1 for beat [index], varied by [salt].
     *
     * Deterministic per beat rather than a running [kotlin.random.Random]: the same
     * beat is considered once and only once either way, but a chart that changed
     * shape depending on when the generator happened to be constructed would make
     * every bug here unreproducible.
     */
    private fun chance(index: Int, salt: Int): Float {
        var h = (index * -0x61c88647) xor (salt * -0x7a143595)
        h = h xor (h ushr 15)
        h *= 0x2545F491
        h = h xor (h ushr 13)
        return ((h ushr 8) and 0xFFFF) / 65535f
    }
}

private const val MIN_BPM = 60f
private const val MAX_BPM = 200f
private const val TEMPO_SMOOTHING = 0.12f

/** How hard an onset pulls the grid's phase toward it, per onset. */
private const val PHASE_PULL = 0.22f

/** Onsets inside this fraction of a period are corrections; further out is a different beat. */
private const val NEAR_FRACTION = 0.3f

/** Onsets that have to agree with the grid before it is charted against. */
private const val LOCK_AGREEMENTS = 3

private const val BEATS_PER_BAR = 4

/** Ceiling on beats charted in one frame, so a re-anchor cannot spin the loop. */
private const val MAX_BEATS_PER_FRAME = 16

private const val HAT_THRESHOLD = 0.12f
private const val MELODY_THRESHOLD = 0.25f
private const val FILL_THRESHOLD = 0.45f

/** Closest two notes in one lane may be. */
private const val MIN_GAP_MS = 110L

private const val FALLBACK_MIN_GAP_MS = 150L
