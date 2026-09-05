package com.engabd.sendpin.game

import com.engabd.sendpin.audio.AnalysisFrame
import com.engabd.sendpin.audio.BeatGrid
import com.engabd.sendpin.audio.TrackScan
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The light engine's beat clock, published once per analysis frame for the game.
 *
 * The engine already knows where the beats are — a pre-scanned grid adopted from
 * [TrackScan] when one landed in time, the causal [BeatGrid] otherwise — and the
 * game used to maintain a second, weaker copy of that knowledge: its own tempo
 * EMA and phase loop, with a wider BPM range and no octave guard. Two clocks
 * answering the same question means a chart that can disagree with the very light
 * show its hits are supposed to unlock, so the game now charts against *this* —
 * the same object the engine rendered from that frame — and falls back to its own
 * PLL only when the engine has no grid at all (capture/cast feeds, unlocked tempo).
 *
 * [frameTAudioS] is the analysis timestamp [grid] was computed from; beat times
 * are converted into the game's audible wall clock relative to it, so no track
 * position ever crosses this boundary.
 */
data class GameChartSource(
    /** The engine's grid for this frame; null when it has no lock yet. */
    val grid: BeatGrid?,
    /**
     * The adopted track scan, when the engine is using it this frame. Carries the
     * exact beat timestamps, per-beat accents, downbeat phase and metre the chart
     * is transcribed from. Null when no scan was adopted (or it arrived too late
     * in the track for the engine to trust it — the same call the lights make).
     */
    val scan: TrackScan?,
    /** The `tAudio` seconds [grid] describes. */
    val frameTAudioS: Float,
)

enum class NoteKind { KICK, SNARE, HAT, MELODY }

/**
 * Which part of the room a struck note answers in.
 *
 * The old game opened one gate over the whole room, so a kick hit and a hat hit
 * lit it identically. The band is the note's answer: bass frequencies live on
 * the floor and treble at the ceiling the way the engine already stacks them
 * ([heightBandLower]), so a kick lights the low lamps and a hat the high ones,
 * with the rest of the room at a sympathy level rather than dark. [COLOR] is
 * the melody lane's reward — a hue rotation with a gentler brightness lift,
 * because the Hue guide book is explicit that a colour transition may be faster
 * than a brightness one and sudden brightness in peripheral vision is what
 * reads as harsh.
 */
enum class GameBand { BASS, FULL, TOP, COLOR }

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
 *
 * [scaled] rebuilds the windows at a difficulty multiplier: Easy widens them (a
 * forgiving first session, and the right setting while a chart is still
 * scan-less), Expert narrows them (viable now the chart is exact from bar one
 * rather than a live estimate's guess).
 */
enum class Judgement(val label: String, val points: Int, val windowMs: Long) {
    PERFECT("Perfect", 100, 60L),
    GREAT("Great", 60, 115L),
    GOOD("Good", 30, 175L),
    MISS("Miss", 0, 0L);

    companion object {
        /** The widest window any tap can still be judged in. */
        val HIT_WINDOW_MS = GOOD.windowMs

        fun forDelta(deltaMs: Long): Judgement = forDelta(deltaMs, 1f)

        /** [scale] multiplies every window; 1f is the shipped Normal. */
        fun forDelta(deltaMs: Long, scale: Float): Judgement = when {
            deltaMs <= PERFECT.windowMs * scale -> PERFECT
            deltaMs <= GREAT.windowMs * scale -> GREAT
            deltaMs <= GOOD.windowMs * scale -> GOOD
            else -> MISS
        }

        /** Widest window at a given difficulty scale, for reaping. */
        fun hitWindowMs(scale: Float): Long = (GOOD.windowMs * scale).toLong()
    }
}

/**
 * Difficulty multipliers for the judgement windows, by setting name. Kept beside
 * [Judgement] so the screen's picker and the generator's reaping cannot drift.
 */
val DIFFICULTY_SCALE = mapOf("easy" to 1.5f, "normal" to 1f, "expert" to 0.66f)

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

    /**
     * True while the chart is being written from the engine's grid rather than
     * from this class's own PLL. The engine's grid is the better clock whenever
     * it exists — scan-adopted grids are exact — so this is the *primary* state,
     * and the PLL below is the fallback that keeps the game alive on feeds the
     * engine cannot grid (capture, cast, an unlocked tempo).
     */
    private var engineLocked = false

    /**
     * Which scan the engine-path bookkeeping ([nextScanBeat]) is indexed into,
     * as an identity of its beat array. A new scan (new track, re-scan) invalidates
     * the index even when the object happens to be reused.
     */
    private var scanIdentity: Long? = null

    /** Next index into the adopted scan's [TrackScan.beats] still to chart. */
    private var nextScanBeat = 0

    /** Bar position of the next extrapolated beat when charting without a scan. */
    private var engineBeatInBar = 0

    /** The accent of the last scan beat charted, so dead air takes two beats to call. */
    private var prevScanAccent = 1f

    /** The next beat index, counted from [anchorMs], that has not been charted yet. */
    private var nextBeat = 0

    /** Consecutive onsets that landed near where the grid said they would. */
    private var agreement = 0

    /**
     * True once the chart has a clock to be written against — the engine's, or
     * this class's own PLL when the engine has none.
     */
    val locked: Boolean
        get() = engineLocked || (haveAnchor && periodMs > 0f && agreement >= LOCK_AGREEMENTS)

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
     *
     * [chart] is the light engine's beat clock for this frame (see
     * [GameChartSource]). When it carries a grid the game charts against *it* —
     * the same beats the room is about to light, exact from bar one when a scan
     * was adopted — and the local PLL below only stays warm for the frame where
     * the engine's grid disappears. Null, or a gridless source, and everything
     * works exactly as it did before this parameter existed.
     */
    fun onFrame(
        frame: AnalysisFrame,
        nowMs: Long,
        leadMs: Long = 0L,
        chart: GameChartSource? = null,
    ) {
        val audibleNow = nowMs + leadMs

        trackCharacter(frame)

        val engineGrid = chart?.grid
        if (engineGrid != null && engineGrid.scheduleStrength > 0f && engineGrid.bpm > 0f) {
            // The engine has a beat we can trust. Chart against it, and keep the
            // fallback clock fed so a feed that loses its grid (a capture source
            // starting, a scan that stops covering the position) hands over to a
            // PLL that never stopped listening.
            engineLocked = true
            trackTempo(frame)
            trackPhase(frame, audibleNow)
            chartFromEngine(chart, engineGrid, audibleNow)
            return
        }
        if (engineLocked) {
            // The engine's grid just went away. The PLL takes over, but from a
            // re-anchored start: its old anchor describes beats it has not been
            // following, and a chart written from it would disagree with itself.
            engineLocked = false
            haveAnchor = false
            agreement = 0
            scanIdentity = null
            nextScanBeat = 0
        }

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
     *
     * [windowScale] widens or narrows the search window with the judgement
     * difficulty, so Easy finds a note slightly further from its moment and
     * Expert slightly closer — the same windows the judgement then uses, or a
     * tap could take a note it was about to be denied scoring.
     */
    fun take(lane: Int, nowMs: Long, windowScale: Float = 1f): GameNote? {
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
        if (bestDelta > Judgement.hitWindowMs(windowScale)) return null
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
        engineLocked = false
        scanIdentity = null
        nextScanBeat = 0
        pastScanBeats = 0
        prevScanAccent = 1f
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

    /** Beats consumed without charting because they fell in the past. Diagnostic only. */
    private var pastScanBeats = 0

    /** How many scan beats were skipped as already-past since the last [reset]. */
    val skippedPastBeats: Int get() = pastScanBeats

    /**
     * Chart from the light engine's beat clock — the primary path.
     *
     * Two sources, in order of preference:
     *
     * - **The adopted scan's exact beat timestamps.** Every beat is a wall-clock
     *   instant already: track-seconds converted once, relative to this frame's
     *   `tAudio`, which is what keeps a seek from re-writing the chart's past.
     * - **The causal grid's own prediction** ([BeatGrid.nextBeatT]) when no scan
     *   covers the position — the same extrapolation the room's beat pulses ride.
     *
     * Both write onto the same board the PLL path does, so the game does not care
     * which clock a track ended up on.
     */
    private fun chartFromEngine(chart: GameChartSource, grid: BeatGrid, audibleNow: Long) {
        val scan = chart.scan
        if (scan != null && scan.beats.size >= 2) {
            // Identity is the beat array's reference: a new track or a re-scan
            // allocates a new array, which is what invalidates the index even
            // when the TrackScan object itself is cached and reused.
            val identity = System.identityHashCode(scan.beats).toLong() and 0xFFFFFFFFL
            if (scanIdentity != identity) {
                scanIdentity = identity
                nextScanBeat = 0
                prevScanAccent = 1f
            }
            val horizon = audibleNow + lookAheadMs
            // Skip every beat already past the hit window — the catch-up after a
            // seek or a track change. Charting them would spawn notes already
            // past the line, which the player can only read as unfair.
            while (nextScanBeat < scan.beats.size &&
                scan.audibleBeatMs(nextScanBeat, chart.frameTAudioS, audibleNow) <
                audibleNow - Judgement.HIT_WINDOW_MS
            ) {
                nextScanBeat++
                pastScanBeats++
            }
            // Chart everything inside the lookahead.
            while (nextScanBeat < scan.beats.size) {
                val t = scan.audibleBeatMs(nextScanBeat, chart.frameTAudioS, audibleNow)
                if (t > horizon) break
                chartScanBeat(scan, nextScanBeat, t)
                nextScanBeat++
            }
            return
        }

        // No scan (or one the engine did not adopt): extrapolate from the causal
        // grid. Same board, same lane rules, different clock — the grid's own
        // phase carries the bar position, measured metre included.
        val period = grid.periodS * 1000f
        if (period <= 0f) return
        var t = audibleNow + (grid.timeToNextBeat * 1000f).roundToLong()
        var inBar = grid.beatInBar
        var guard = 0
        while (guard++ < MAX_BEATS_PER_FRAME) {
            if (t > audibleNow + lookAheadMs) break
            if (t >= audibleNow - Judgement.HIT_WINDOW_MS) {
                chartTemplateBeat(t, inBar, grid.beatsPerBar, grid.accent.coerceIn(0f, 1f), grid.accentNow.coerceIn(0f, 1f))
            }
            t += period.roundToLong()
            inBar = (inBar + 1).mod(grid.beatsPerBar.coerceAtLeast(1))
        }
    }

    /**
     * One scan beat's worth of notes — a transcription, not a template.
     *
     * The scan already measured what this beat did: how hard it hit
     * ([TrackScan.accents]), what kind of section it sits in, and (with stems)
     * how much of that section is drums versus voice. The chart follows it — a
     * heavy beat is a kick, a light one a snare, quiet sections thin out, and
     * the melody lane fires on vocal presence rather than a hash coin-flip.
     */
    private fun chartScanBeat(scan: TrackScan, index: Int, timeMs: Long) {
        val accent = scan.accents.getOrNull(index)?.coerceIn(0f, 1f) ?: 1f
        val bar = scan.beatsPerBar.coerceAtLeast(1)
        val inBar = (index - scan.downbeat).mod(bar)
        val downbeat = inBar == 0
        // Half a beat later, for the offbeat hats. The next beat's timestamp gives
        // the real local tempo; the last beat of the track has no next, so it
        // extrapolates from its predecessor rather than reading out of bounds.
        val gapMs = if (index + 1 < scan.beats.size) {
            (scan.beats[index + 1] - scan.beats[index]) * 1000f
        } else if (index > 0) {
            (scan.beats[index] - scan.beats[index - 1]) * 1000f
        } else {
            500f
        }
        val half = (gapMs / 2f).roundToLong().coerceAtLeast(60L)
        val posS = scan.beats[index]
        val section = scan.sectionAt(posS)
        val stems = scan.stems?.sections?.getOrNull(scan.sections.indexOf(section))

        // Dead air takes two consecutive near-silent beats to call. One is a rest
        // — a rest is part of the music and stays playable; two is a gap, and
        // charting into it puts notes where nothing is happening.
        if (accent < DEAD_AIR_ACCENT && prevScanAccent < DEAD_AIR_ACCENT) {
            prevScanAccent = accent
            return
        }
        prevScanAccent = accent

        // The pulse lane follows the measured accent, not a fixed kick/snare
        // pattern: the light reward already scales with note intensity, so a
        // beat that hit hard pays out the way it sounded.
        val heavy = downbeat || accent >= 0.5f
        add(if (heavy) LANE_KICK else LANE_SNARE, timeMs, if (heavy) NoteKind.KICK else NoteKind.SNARE, accent, downbeat)

        // Offbeat hats, gated by the section's own low-band energy (the drum
        // proxy) rather than a global EMA — a sparse verse stays sparse.
        val drumEnergy = stems?.bass ?: section?.energy ?: accent
        if (drumEnergy > HAT_THRESHOLD && chance(index, 11) < 0.35f + drumEnergy) {
            add(LANE_HAT, timeMs + half, NoteKind.HAT, drumEnergy.coerceAtMost(1f), false)
        }

        // Melody lane on vocal presence (mid-channel stem energy), chorus-weighted
        // by keeping it to one bar position — an event, not a fifth of the noise.
        val vocal = stems?.vocals ?: 0f
        if (vocal > MELODY_THRESHOLD && inBar == 2 && chance(index, 23) < 0.55f) {
            add(LANE_MELODY, timeMs, NoteKind.MELODY, vocal.coerceAtMost(1f), false)
        }

        // Fills only into real builds: the next section is genuinely louder than
        // this one. A build into a chorus, not every fourth bar.
        val nextSection = scan.sections.firstOrNull { it.startS > posS }
        if (nextSection != null && section != null &&
            nextSection.energy > section.energy + FILL_SECTION_JUMP &&
            inBar == bar - 1 &&
            chance(index, 37) < 0.3f
        ) {
            add(LANE_HAT, timeMs + half, NoteKind.HAT, 0.8f, false)
            add(LANE_SNARE, timeMs + half + (half / 2f).roundToLong(), NoteKind.SNARE, 0.9f, false)
        }
    }

    /**
     * One extrapolated beat's worth of notes, from the causal grid.
     *
     * The template the PLL path has always used — the backbeat is where it always
     * is so the chart is readable — but with the grid's own measured accents
     * driving intensity, and the grid's metre deciding where the bar lands, so a
     * waltz no longer plays a 4/4 pattern over every bar.
     */
    private fun chartTemplateBeat(timeMs: Long, inBar: Int, beatsPerBar: Int, accent: Float, accentNow: Float) {
        val bar = beatsPerBar.coerceAtLeast(1)
        val downbeat = inBar == 0
        val half = (periodMs / 2f).roundToLong().coerceAtLeast(60L)
        val pulseAccent = if (accent > 0f) accent else accentNow

        if (downbeat || pulseAccent >= 0.5f) {
            add(LANE_KICK, timeMs, NoteKind.KICK, pulseAccent, downbeat)
        } else {
            add(LANE_SNARE, timeMs, NoteKind.SNARE, pulseAccent, false)
        }

        if (hatLevel > HAT_THRESHOLD && chance(timeMs.toInt(), 11) < 0.35f + hatLevel) {
            add(LANE_HAT, timeMs + half, NoteKind.HAT, hatLevel, false)
        }
        if (melodyLevel > MELODY_THRESHOLD && inBar == 2 && chance(timeMs.toInt(), 23) < 0.55f) {
            add(LANE_MELODY, timeMs, NoteKind.MELODY, melodyLevel, false)
        }
        if (energyLevel > FILL_THRESHOLD && inBar == bar - 1 && chance(timeMs.toInt(), 37) < 0.3f) {
            val quarter = (periodMs / 4f).roundToLong()
            add(LANE_HAT, timeMs + half, NoteKind.HAT, 0.8f, false)
            add(LANE_SNARE, timeMs + half + quarter, NoteKind.SNARE, 0.9f, false)
        }
    }

    /**
     * Audible wall-clock ms of scan beat [index].
     *
     * The scan's beats are track-seconds; the engine path knows this frame's
     * `tAudio` ([frameTAudioS]) and the wall time it corresponds to ([nowMs],
     * already in audible time). The track-seconds delta converts once, relative —
     * the same relative conversion the engine's own delay queue performs, which
     * is what keeps a seek from re-writing the chart's past.
     */
    private fun TrackScan.audibleBeatMs(index: Int, frameTAudioS: Float, nowMs: Long): Long =
        nowMs + ((beats[index] - frameTAudioS) * 1000f).roundToLong()

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

/**
 * A scan beat quieter than this is near-silence. Two in a row is dead air — the
 * gap the chart leaves empty rather than note — and one is a rest, which stays
 * playable. 0.08 sits under the quietest verse accent a real scan reports.
 */
private const val DEAD_AIR_ACCENT = 0.08f

/**
 * How much louder the next section must be than the current one before the chart
 * writes a fill into its boundary. 0.2 on the scan's 0..1 section energy: a build
 * into a chorus, not just the next section being marginally up.
 */
private const val FILL_SECTION_JUMP = 0.2f

/**
 * Closest two notes in one lane may be.
 */
private const val MIN_GAP_MS = 110L

private const val FALLBACK_MIN_GAP_MS = 150L

/** The band of the room each note kind answers in — the hit-to-lamp mapping. */
internal fun bandOf(kind: NoteKind): GameBand = when (kind) {
    NoteKind.KICK -> GameBand.BASS
    NoteKind.SNARE -> GameBand.FULL
    NoteKind.HAT -> GameBand.TOP
    NoteKind.MELODY -> GameBand.COLOR
}
