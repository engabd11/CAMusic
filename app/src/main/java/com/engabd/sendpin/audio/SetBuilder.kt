package com.engabd.sendpin.audio

import kotlin.math.abs

/**
 * Builds a listening set with a shape to it.
 *
 * The app already knows the tempo, the key and the per-section energy of every
 * track it has scanned — a whole DJ's worth of information — and until now all of
 * it fed the light show and a small ranking bonus inside `LocalRadio`. This turns
 * it into the thing that information is actually for: forty-five minutes that
 * starts where you are, goes somewhere, and comes back.
 *
 * Pure and stateless. It takes candidates and hands back an order; fetching the
 * scans, filling the queue and saving the playlist all belong to the caller.
 */
object SetBuilder {

    /** How a set's energy should move from start to finish. */
    enum class Curve(val label: String, val blurb: String) {
        WARM_UP("Warm-up", "Starts easy and climbs. For putting a room together."),
        PEAK("Peak", "Straight in near the top and holds it."),
        WIND_DOWN("Wind-down", "Begins where the evening is and lets it fall away."),
        ARC("Arc", "Builds to a peak in the middle, then lands."),
        ;

        /**
         * The energy this curve wants at [t], 0..1 through the set, itself 0..1.
         *
         * Deliberately shallow at the ends of [ARC] and [WARM_UP]: a curve that
         * demands 0.0 at the first track and 1.0 at the last can only be satisfied
         * by the two most extreme tracks in the library, which is how an
         * "energy arc" ends up meaning "the quietest song you own, then the loudest".
         */
        fun targetAt(t: Float): Float = when (this) {
            WARM_UP -> 0.35f + 0.5f * t
            PEAK -> 0.8f - 0.1f * t
            WIND_DOWN -> 0.75f - 0.5f * t
            ARC -> {
                val x = (t - 0.5f) * 2f          // -1..1
                0.85f - 0.45f * (x * x)
            }
        }
    }

    /**
     * A track the builder may choose, with everything it needs already resolved.
     *
     * A flat record rather than a `TrackScan` plus an id, because half of what the
     * builder wants — an overall energy, a usable key — is derived from a scan and
     * deriving it inside the loop would do it once per comparison.
     */
    data class Candidate(
        val id: String,
        val durationS: Float,
        /** 0 when unknown, which excludes the track from tempo scoring but not from the set. */
        val bpm: Float = 0f,
        val key: MusicalKey? = null,
        /** Mean section energy, 0..1. */
        val energy: Float = 0.5f,
    ) {
        companion object {
            /**
             * Read a candidate off a scan.
             *
             * Energy is the mean of the section energies rather than the peak: a
             * track with one loud chorus is not a loud track, and a set built on
             * peaks lurches.
             */
            fun of(id: String, scan: TrackScan): Candidate = Candidate(
                id = id,
                durationS = scan.durationS,
                bpm = scan.bpm,
                key = scan.key,
                energy = scan.sections
                    .takeIf { it.isNotEmpty() }
                    ?.map { it.energy }
                    ?.average()
                    ?.toFloat()
                    ?.coerceIn(0f, 1f)
                    ?: 0.5f,
            )
        }
    }

    /**
     * How well [next] follows [previous] at position [t] through the set.
     *
     * Three terms, weighted in the order a DJ would rank them: how close the track
     * sits to the energy the curve wants there, whether the keys mix, and whether
     * the tempo does. Energy dominates because it is what the listener actually
     * feels; key and tempo are what stop two adjacent tracks fighting.
     *
     * [previous] is null for the first pick, where there is nothing to mix against
     * and only the curve has an opinion.
     */
    fun score(previous: Candidate?, next: Candidate, curve: Curve, t: Float): Float {
        val want = curve.targetAt(t)
        // 1 when the track sits exactly on the curve, 0 when it is a full unit away
        // - and *squared*, which is what actually makes the curve the curve. Linear,
        // a perfect key and tempo (0.4 between them) outvoted an energy gap of 0.65,
        // so a wind-down set would happily mix a banger in because it was in the
        // right key. Squaring leaves near-misses cheap, so among tracks that all fit
        // the shape the key and the tempo still decide, and makes a track in the
        // wrong place unaffordable however well it mixes.
        val energyGap = abs(next.energy - want).coerceIn(0f, 1f)
        val energyFit = (1f - energyGap) * (1f - energyGap)

        var harmony = 0f
        var tempo = 0f
        if (previous != null) {
            val a = previous.key
            val b = next.key
            if (a != null && b != null && Camelot.compatible(a, b)) harmony = 1f
            if (Camelot.bpmMatch(previous.bpm, next.bpm)) tempo = 1f
        }

        return 0.6f * energyFit + 0.25f * harmony + 0.15f * tempo
    }

    /**
     * Choose an order for [candidates] that runs for about [targetS] seconds.
     *
     * Greedy: at each position, take the best-scoring track not already used. Greedy
     * is the right shape here rather than a compromise — a set is consumed in order,
     * so a globally optimal ordering that puts a worse track *next* is worse to
     * listen to, and the alternative is a search over permutations for a result
     * nobody could tell apart.
     *
     * Stops once adding another track would overshoot [targetS] by more than half
     * that track's length, so a 45-minute set lands near 45 rather than always over.
     *
     * @param seed the track to start from, if it is in [candidates]. Its own energy
     *   is not scored — it is where the listener already is.
     */
    fun build(
        candidates: List<Candidate>,
        curve: Curve,
        targetS: Float,
        seed: Candidate? = null,
    ): List<Candidate> {
        if (candidates.isEmpty() || targetS <= 0f) return emptyList()

        val pool = candidates.associateBy { it.id }.toMutableMap()
        val out = mutableListOf<Candidate>()
        var elapsed = 0f

        seed?.let { s ->
            pool.remove(s.id)?.let { out.add(it); elapsed += it.durationS }
        }

        while (pool.isNotEmpty()) {
            val t = (elapsed / targetS).coerceIn(0f, 1f)
            val previous = out.lastOrNull()
            val best = pool.values.maxByOrNull { score(previous, it, curve, t) } ?: break

            // Would this overshoot more than it fills? Then the set is done.
            if (elapsed + best.durationS > targetS + best.durationS / 2f) break

            // Near the end, a bad fit is worse than a short set. Without this the
            // last slot gets padded with whatever is left over - a warm-up would
            // climb all the way up and then finish on the quietest track in the
            // library, purely because it was the only one not used yet.
            //
            // Only near the end, and that matters: early on there is a whole set
            // still to come and taking the best available is right even when the
            // library is small. A target longer than the library should still hand
            // back the whole library.
            if (elapsed >= targetS * TAIL_FRACTION &&
                abs(best.energy - curve.targetAt(t)) > MAX_TAIL_ENERGY_GAP
            ) {
                break
            }

            pool.remove(best.id)
            out.add(best)
            elapsed += best.durationS
            if (elapsed >= targetS) break
        }
        return out
    }

    /**
     * How far into a set the tail-quality rule starts to apply. See [build].
     */
    private const val TAIL_FRACTION = 0.8f

    /**
     * How far a track may sit from the curve and still be worth ending a set on.
     *
     * A third of the range: far enough that an ordinary library is never cut
     * short, close enough that the last track still belongs where it is.
     */
    private const val MAX_TAIL_ENERGY_GAP = 0.35f

    /** Total running time of a built set, in seconds. */
    fun durationOf(set: List<Candidate>): Float = set.sumOf { it.durationS.toDouble() }.toFloat()
}
