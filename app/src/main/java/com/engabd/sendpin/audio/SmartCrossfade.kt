package com.engabd.sendpin.audio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * One transition, planned in full before it starts.
 *
 * Three numbers, and each of them is a decision the fixed-seconds version was
 * making badly:
 *
 *  - **[handOverAtMs]** — where in the outgoing track the hand-over fires. A fixed
 *    crossfade puts this at `duration − N`, which on a track with a long fade-out
 *    or a silent run-out means the "mix" is the new song coming up under nothing at
 *    all. That is why a crossfade can be six seconds long and still sound like a cut.
 *  - **[windowS]** — how long the overlap runs, in seconds and fractional, because a
 *    bar is 2.0 s at 120 BPM and 1.85 s at 130 and neither is an integer.
 *  - **[incomingStartMs]** — where to drop the needle on the next track, so its own
 *    dead air is not what the outgoing track is being mixed into.
 */
data class MixPlan(
    val handOverAtMs: Long,
    val windowS: Float,
    val incomingStartMs: Long = 0L,
    /** A scan shaped this, rather than it being the fixed fallback. */
    val smart: Boolean = false,
) {
    val windowMs: Long get() = (windowS * 1000f).toLong()
}

/**
 * Where two tracks should actually be joined, read off the offline analysis.
 *
 * ## What the fixed crossfade gets wrong
 *
 * "Overlap the last N seconds" is a statement about file lengths, and a transition
 * is a statement about music. The two disagree in three specific ways, and all
 * three of them are audible as the same complaint — *it sounds like a cut*:
 *
 *  1. **The outgoing track has already finished.** A fade-out, a ring-out, a run-out
 *     groove or a couple of seconds of encoder padding all mean the last N seconds
 *     of the file are not the last N seconds of the song. Crossfading over them
 *     mixes the new track into silence, which is a cut with extra steps.
 *  2. **The join lands mid-bar.** Two grids meeting off the beat is heard as a
 *     stumble even when both tracks are at full level throughout.
 *  3. **The incoming track starts with silence.** Same as (1), from the other end.
 *
 * Every one of those is answered by data the scan already carries for Light Sync —
 * the intensity curve, the beat grid, the bar length. Nothing new is measured here.
 *
 * ## What it does not do
 *
 * It does not skip intros. Trimming is capped — [MAX_HEAD_TRIM_S] at the front,
 * [maxTailTrimS] at the back — and only ever removes what is genuinely below
 * [SILENCE_FLOOR]; a quiet ambient opening is part of the song and stays. It does not time-stretch or beat-match tempo — the two tracks keep
 * their own clocks, and the alignment is of the *join*, not of the whole overlap.
 *
 * Pure and stateless, like [Camelot] and [DjSimilarity]: the decisions here are
 * arithmetic and a wrong one should be something a test can state.
 */
object SmartCrossfade {

    /**
     * Below this on the scan's intensity curve, the track is not making music.
     *
     * The curve is normalised to a percentile of the track's own range and smoothed
     * over ~1.4 s, so this is not "digital silence" — it is "nothing is happening
     * here", which is the useful question. Low enough that a quiet outro still
     * counts as music, high enough to catch a fade that has run to nothing.
     */
    const val SILENCE_FLOOR = 0.05f

    /**
     * The most the *front* of a track will be trimmed by, seconds.
     *
     * Tighter than the tail's cap, and deliberately: dead air at the end of a song
     * is between songs, but dead air at the start is where the song starts. Skipping
     * into a track is the more noticeable mistake of the two, so the front gets a
     * flat few seconds and no more.
     */
    const val MAX_HEAD_TRIM_S = 6f

    /**
     * The most the *tail* will be trimmed by: a fraction of the track, to a ceiling.
     *
     * A flat number could not do this job. Real fade-outs run ten and fifteen
     * seconds, so a cap short enough to protect a thirty-second interlude is one
     * that leaves a four-minute song still mixing into its own fade — which is the
     * failure this whole class exists to remove. A share of the track's own length
     * scales with what is at risk: you can lose the end of a song, but never more
     * than a sixth of it.
     */
    fun maxTailTrimS(durationS: Float): Float = min(durationS * TAIL_TRIM_SHARE, MAX_TAIL_TRIM_S)

    private const val TAIL_TRIM_SHARE = 0.15f
    private const val MAX_TAIL_TRIM_S = 20f

    /** Shortest and longest overlap the planner will produce. */
    const val MIN_WINDOW_S = 0.8f
    const val MAX_WINDOW_S = 12f

    /**
     * The plan for leaving [outgoing] and arriving at [incoming], or null when this
     * boundary should not be crossfaded at all.
     *
     * [durationMs] is the outgoing track's real length as the player reports it,
     * which is more trustworthy than the scan's own `durationS` — the scan may have
     * been made from a different transcode of the same song.
     *
     * [seconds] is the listener's setting: the whole answer in standard mode, and in
     * smart mode the target the bar count is chosen to sit closest to. It stays in
     * the picture either way, because a slider that stops meaning anything the
     * moment you turn on the clever mode is a slider that lies.
     */
    fun plan(
        outgoing: TrackScan?,
        incoming: TrackScan?,
        durationMs: Long,
        seconds: Int,
        smart: Boolean,
    ): MixPlan? {
        if (seconds <= 0 || durationMs <= 0) return null
        val standard = standardPlan(durationMs, seconds) ?: return null
        if (!smart || outgoing == null) return standard

        val endS = musicEndsAtS(outgoing, durationMs / 1000f)
        val windowS = windowFor(outgoing, seconds)
        val target = endS - windowS
        // Too near the front to be an ending: something is wrong with the scan, or
        // the track really is that short. Either way the fixed plan is safer.
        if (target < windowS) return standard

        val snapped = snapToDownbeat(outgoing, target)
        val handOverAtMs = (snapped * 1000f).toLong()
        // The tail has to be long enough to be worth hearing. A hand-over that lands
        // within a breath of the end is the cut it was supposed to replace.
        if (endS * 1000f - handOverAtMs < MIN_WINDOW_S * 1000f) return standard
        if (handOverAtMs <= 0 || handOverAtMs >= durationMs) return standard

        val incomingStartMs = incoming?.let { (musicStartsAtS(it) * 1000f).toLong() } ?: 0L
        return MixPlan(
            handOverAtMs = handOverAtMs,
            windowS = windowS,
            incomingStartMs = incomingStartMs,
            smart = true,
        )
    }

    /** "Overlap the last [seconds] seconds", with the too-short-track guard. */
    fun standardPlan(durationMs: Long, seconds: Int): MixPlan? {
        if (seconds <= 0 || durationMs <= 0) return null
        val window = seconds * 1000L
        // A track shorter than three windows would spend most of its life in a
        // transition. The same guard the sequential fade has always used.
        if (durationMs < window * 3) return null
        return MixPlan(handOverAtMs = durationMs - window, windowS = seconds.toFloat())
    }

    /**
     * The overlap length for [scan]: whole bars, as close to [seconds] as a whole
     * number of them gets.
     *
     * Bars rather than seconds because that is the unit a transition is actually
     * measured in — two tracks overlapping for a bar and a half is the stumble that
     * a bar or two bars is not. At 120 BPM in 4/4 a bar is exactly two seconds, so
     * the default setting and the musical answer agree; at 128 they differ by a
     * sixth of a second and the musical one wins. Ties go to the shorter mix.
     *
     * Falls back to [seconds] where the grid is not worth trusting — see
     * [TrackScan.gridUsable].
     */
    fun windowFor(scan: TrackScan, seconds: Int): Float {
        val bar = barSeconds(scan) ?: return seconds.toFloat().coerceIn(MIN_WINDOW_S, MAX_WINDOW_S)
        // Ties round *down*. Two and a half bars asked for should come back as two,
        // not three: a mix longer than the one requested is the more intrusive of
        // the two errors, and the shorter one is always still a whole bar.
        val bars = ((seconds / bar) + 0.4999f).toInt().coerceAtLeast(1)
        return (bars * bar).coerceIn(MIN_WINDOW_S, MAX_WINDOW_S)
    }

    /** One bar in seconds, or null when the scan has no tempo worth using. */
    fun barSeconds(scan: TrackScan): Float? {
        if (!scan.gridUsable || scan.bpm <= 0f) return null
        val beat = 60f / scan.bpm
        return beat * scan.beatsPerBar.coerceAtLeast(1)
    }

    /**
     * When the music stops, in track seconds.
     *
     * The last point on the intensity curve still above [SILENCE_FLOOR], which on a
     * track that ends cleanly is its own end and on one that fades out is where the
     * fade became inaudible. Trimming is capped at [maxTailTrimS], so a scan that
     * misreads a quiet ending cannot lop a chunk off the song.
     *
     * [durationS] is passed rather than taken from the scan because the player knows
     * the real length and the scan may not — see [plan].
     */
    fun musicEndsAtS(scan: TrackScan, durationS: Float): Float {
        val profile = scan.intensity ?: return durationS
        val curve = profile.curve
        val rate = profile.curveRateHz
        if (curve.isEmpty() || rate <= 0f) return durationS
        // A scan that stopped early knows nothing about the end of the track, so it
        // has no business claiming the music stopped where its own data ran out.
        if (!scan.complete) return durationS
        var i = curve.size - 1
        while (i >= 0 && curve[i] < SILENCE_FLOOR) i--
        if (i < 0) return durationS
        val endS = (i + 1) / rate
        return endS.coerceIn(max(0f, durationS - maxTailTrimS(durationS)), durationS)
    }

    /**
     * When the music starts, in track seconds — the mirror of [musicEndsAtS], capped
     * at [MAX_HEAD_TRIM_S] so an intro is never mistaken for padding.
     */
    fun musicStartsAtS(scan: TrackScan): Float {
        val profile = scan.intensity ?: return 0f
        val curve = profile.curve
        val rate = profile.curveRateHz
        if (curve.isEmpty() || rate <= 0f) return 0f
        var i = 0
        while (i < curve.size && curve[i] < SILENCE_FLOOR) i++
        if (i >= curve.size) return 0f
        return (i / rate).coerceIn(0f, MAX_HEAD_TRIM_S)
    }

    /**
     * [atS] moved back to the nearest downbeat at or before it.
     *
     * A downbeat rather than any beat because the ear counts bars: a transition
     * starting on the one is heard as deliberate and the same transition starting on
     * the three is heard as early, even though both are "on the beat". Falls back to
     * the nearest plain beat where the metre is not known, and to [atS] itself where
     * there is no usable grid at all — an unaligned join is worse than a wrong one
     * only in theory, and inventing a downbeat is how a waltz gets mixed in 4/4.
     *
     * Never moves by more than one bar: past that, the grid is disagreeing with the
     * request rather than refining it.
     */
    fun snapToDownbeat(scan: TrackScan, atS: Float): Float {
        val beats = scan.beats
        if (!scan.gridUsable || beats.isEmpty()) return atS
        val bar = scan.beatsPerBar.coerceAtLeast(1)
        val tolerance = barSeconds(scan) ?: return atS

        var best = Float.NaN
        var bestBeat = Float.NaN
        for (i in beats.indices) {
            val t = beats[i]
            if (t > atS) break
            if (bestBeat.isNaN() || t > bestBeat) bestBeat = t
            if (Math.floorMod(i - scan.downbeat, bar) == 0) {
                if (best.isNaN() || t > best) best = t
            }
        }
        if (!best.isNaN() && abs(atS - best) <= tolerance) return best
        if (!bestBeat.isNaN() && abs(atS - bestBeat) <= tolerance) return bestBeat
        return atS
    }

    /**
     * Whether the hand-over planned in [plan] is due at [positionMs].
     *
     * A range rather than an instant: the playhead is sampled, so an equality test
     * would miss the moment and never fire. The upper bound is the window itself —
     * past that the transition is late enough that starting it would run past the
     * end of the track.
     */
    fun shouldHandOver(positionMs: Long, plan: MixPlan): Boolean =
        positionMs >= plan.handOverAtMs && positionMs < plan.handOverAtMs + plan.windowMs

    /** Whether the tail deck should be rolled up now, [prerollMs] ahead of the swap. */
    fun shouldArm(positionMs: Long, plan: MixPlan, prerollMs: Long): Boolean =
        positionMs >= plan.handOverAtMs - prerollMs &&
            positionMs < plan.handOverAtMs + plan.windowMs

    /**
     * The incoming track's level, [positionMs] into it, having entered at [fromMs].
     *
     * `sin` against the tail's `cos`, so the two sum to constant power — see
     * [CrossfadeDeck.handOver]. [fromMs] matters because a smart plan can drop the
     * needle past a silent lead-in: measuring the ramp from zero would then have the
     * new track already at full volume before its first note.
     */
    fun fadeInAt(positionMs: Long, fromMs: Long, windowS: Float): Float {
        if (windowS <= 0f) return 1f
        val into = positionMs - fromMs
        if (into <= 0L) return 0f
        val window = windowS * 1000f
        if (into >= window) return 1f
        val x = (into / window).coerceIn(0f, 1f)
        return kotlin.math.sin(x * (Math.PI / 2).toFloat()).coerceIn(0f, 1f)
    }

    /** The outgoing tail's level, the same fraction through the same window. */
    fun fadeOutAt(fraction: Float): Float =
        kotlin.math.cos(fraction.coerceIn(0f, 1f) * (Math.PI / 2).toFloat()).coerceIn(0f, 1f)

    /**
     * How long a stream needs rolling before the swap, given how far ahead the plan
     * was made.
     *
     * A local file is decoding within a couple of hundred milliseconds; a track being
     * pulled from a server across a house has to open a connection, get past the
     * headers and fill a buffer first. The old two seconds was enough for the former
     * and not reliably for the latter — and a deck that is not making sound when the
     * hand-over fires produces exactly the cut this is all here to remove. So the
     * pre-roll is generous, and [CrossfadeDeck.ready] is what actually gates the
     * swap: this only decides how much of a head start it gets to become ready in.
     */
    fun prerollFor(local: Boolean): Long = if (local) LOCAL_PREROLL_MS else STREAM_PREROLL_MS

    const val LOCAL_PREROLL_MS = 3_000L
    const val STREAM_PREROLL_MS = 8_000L

    /**
     * Past this far beyond the planned point, give up on the hand-over.
     *
     * The deck can still be buffering when the swap is due — a slow server, a phone
     * that just came off Wi-Fi. Waiting a little is right; waiting into the end of
     * the track is not, because the crossfade would then run off the end and the
     * track would stop mid-mix. At the deadline the transition is abandoned and the
     * boundary plays out gapless, which is a worse mix and a perfectly good join.
     */
    fun handOverDeadlineMs(plan: MixPlan): Long =
        plan.handOverAtMs + min(plan.windowMs, LATE_HANDOVER_GRACE_MS)

    private const val LATE_HANDOVER_GRACE_MS = 2_500L
}
