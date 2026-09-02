package com.engabd.sendpin.hue.ambience

import com.engabd.sendpin.audio.AnalysisFrame

/**
 * Something the recording just did.
 *
 * Detection is shared because it is generic — "louder in the bottom end than this
 * recording has been lately" is the same arithmetic whether the sound was a thunderclap
 * or a mortar. What the onset *means* is not generic at all, so that stays with the
 * script: only `ThunderstormScript` knows that a loud, dull, low onset is a strike
 * several kilometres out and should wash the room in dim blue rather than crack it
 * white.
 *
 * Mutable and reused. One instance is filled per analysis hop and read synchronously by
 * the script; nothing retains it. That keeps a 50 Hz path allocation-free without
 * anybody having to think about it.
 */
class BedOnset {

    /** A broadband event: a bang, a burst, a shell breaking. */
    @JvmField var broadband: Boolean = false
    @JvmField var broadbandStrength: Float = 0f

    /** A low-end event: thunder, a mortar, the thump of a big shell. */
    @JvmField var low: Boolean = false
    @JvmField var lowStrength: Float = 0f

    /** An upper event: a crackle, a spit, a spark. */
    @JvmField var high: Boolean = false
    @JvmField var highStrength: Float = 0f

    /**
     * How much of this moment's energy is in the low end, 0..1.
     *
     * The distance cue, and the one number that makes a recorded storm legible. Air
     * absorbs treble far faster than bass, so a strike overhead arrives broadband and
     * bright while one several kilometres out has had everything above a few hundred
     * hertz stripped out of it. A script reads this to decide whether it is drawing a
     * white crack or a dim blue sheet — the same judgement the scripted path made from
     * a distance it had invented, made instead from the recording's own timbre.
     */
    @JvmField var lowShare: Float = 0f

    /** Where in the stereo field it happened, -1 hard left to +1 hard right. */
    @JvmField var pan: Float = 0f

    /**
     * Media position the event **began**, which is earlier than the frame reporting it.
     *
     * An onset is only believed once it has lasted a few hops (see the note on
     * confirmation in [AmbienceBedAnalyser]), and the analyzer's own filters add a hop
     * or so on top. Stamping an event where it was confirmed rather than where it
     * started would put every flash reliably behind its clap — so the delay is measured
     * and given back here, and a script has nothing to compensate for.
     */
    @JvmField var atS: Double = 0.0

    fun clear() {
        broadband = false; broadbandStrength = 0f
        low = false; lowStrength = 0f
        high = false; highStrength = 0f
    }
}

/**
 * Turns [AnalysisFrame]s into a [BedSample] and a [BedOnset].
 *
 * Owned by [AmbienceSession] and touched only on the analysis thread.
 *
 * ## Why the analyzer's own onsets are not simply forwarded
 *
 * [AnalysisFrame] already carries three SuperFlux onset streams, and they are good —
 * they are what the music show beats to. They are also tuned for *music*, where the
 * question is "was that a drum hit" against a dense, loud, roughly stationary
 * background. Weather is neither dense nor stationary, and the difference shows up as
 * three separate problems that all have to be solved here rather than there:
 *
 * 1. **Rain is broadband noise**, and broadband noise produces a steady trickle of
 *    small flux peaks. The music detector is right to call them onsets; a storm would
 *    be wrong to call them thunder.
 * 2. **Not every clap has an attack.** A strike overhead is a transient and a flux
 *    detector finds it easily. One several kilometres out arrives as a swell over most
 *    of a second — there is no edge to detect, and the room still has to answer it.
 * 3. **The absolute levels are not stable.** The analyzer's band AGC has a ~70 s
 *    half-life, so the same rain reads at 0.9 before the first thunderclap of a track
 *    and 0.15 for a minute afterwards. Any fixed threshold is meaningless against that.
 *
 * So the detector here is a Schmitt trigger on a **ratio** — how much louder a band is
 * than the quiet level this recording has settled at recently — which is immune to (3),
 * survives (2) because a swell crosses a ratio just as a transient does, and defeats (1)
 * because rain's own fluctuation never approaches the ratio a thunderclap does.
 *
 * ## Two normalisations, on purpose
 *
 * [BedSample.rumble] and friends are normalised against a *ceiling* as well as a floor,
 * and the detector deliberately does not use them. They answer different questions.
 * "Is this an event" is a question about the quiet level (a ratio over the floor); "how
 * far through this event are we" is a question about the loud level, because what the
 * room should draw is the clap's own envelope relative to its own peak. Conflating them
 * gave a room that either flashed at rain or dimmed through the middle of a roll.
 */
class AmbienceBedAnalyser {

    private val sample = BedSample()
    private val onset = BedOnset()

    private val lowBand = Track()
    private val highBand = Track()
    private val broad = Track()

    private var seeded = false

    fun reset() {
        lowBand.reset(); highBand.reset(); broad.reset()
        seeded = false
        onset.clear()
    }

    /**
     * Read [frame], describing audio at media position [atS].
     *
     * Returns the pair the caller should act on; both are reused, so a caller that wants
     * to keep either must copy it. [AmbienceBedTrack.append] does exactly that.
     */
    fun consume(frame: AnalysisFrame, atS: Double): Pair<BedSample, BedOnset> {
        val s = sample
        s.tS = atS
        s.energy = frame.energy
        s.sub = frame.bands["sub_bass"] ?: 0f
        s.bass = frame.bands["bass"] ?: 0f
        s.lowMid = frame.bands["low_mid"] ?: 0f
        s.mid = frame.bands["mid"] ?: 0f
        s.high = frame.bands["high"] ?: 0f
        s.centroid = frame.centroid
        s.pan = weightedPan(frame)

        // The three signals the whole of this file works on. Low is where thunder and a
        // shell's thump live; upper is rain, crackle and spray; broad is everything, and
        // is what a bang moves that a swell does not.
        val lowIn = 0.5f * s.sub + 0.5f * s.bass
        val highIn = 0.35f * s.mid + 0.65f * s.high
        val broadIn = s.energy

        if (!seeded) {
            // Seeded from the first frame rather than from zero. Starting the floors at
            // zero makes the first second of any bed read as one enormous onset, which
            // on a storm is a full-brightness flash the moment the effect is tapped.
            lowBand.seed(lowIn); highBand.seed(highIn); broad.seed(broadIn)
            seeded = true
        }

        s.rumble = lowBand.update(lowIn)
        s.rain = highBand.update(highIn)
        s.level = broad.update(broadIn)

        onset.clear()
        onset.pan = s.pan
        onset.lowShare = (lowBand.smoothed / (lowBand.smoothed + highBand.smoothed + 1e-4f))
            .coerceIn(0f, 1f)

        if (lowBand.fired()) {
            onset.low = true
            onset.lowStrength = lowBand.strength()
            onset.atS = atS - lowBand.startedAgoS()
        }
        if (broad.fired()) {
            onset.broadband = true
            onset.broadbandStrength = broad.strength()
            onset.atS = atS - broad.startedAgoS()
        }
        if (highBand.fired()) {
            onset.high = true
            onset.highStrength = highBand.strength()
            onset.atS = atS - highBand.startedAgoS()
        }

        return s to onset
    }

    /**
     * Where the loudest part of this frame sits in the stereo field.
     *
     * Energy-weighted across the melbank rather than averaged. A clap on the left over
     * rain spread across both channels averages to very nearly the middle, which would
     * throw away the one cue that tells the room which side to light.
     */
    private fun weightedPan(frame: AnalysisFrame): Float {
        val pan = frame.pan
        val mel = frame.melbank
        if (pan.isEmpty() || mel.isEmpty()) return sample.pan
        val n = minOf(pan.size, mel.size)
        var num = 0f
        var den = 0f
        var i = 0
        while (i < n) {
            val w = mel[i] * mel[i]      // squared: follow the loud bin, not the average
            num += pan[i] * w
            den += w
            i++
        }
        return if (den <= 1e-6f) sample.pan else (num / den).coerceIn(-1f, 1f)
    }

    /**
     * One band: smoothed, floored, ceilinged, and armed.
     *
     * All four jobs together because they share the same input and the same hop, and
     * splitting them would mean four objects agreeing about a value that only exists
     * for the length of one call.
     */
    private class Track {
        /** Fast EMA. Kills the frame-to-frame jitter an FFT band always has. */
        var smoothed = 0f
            private set

        /**
         * Half-second EMA — what this band was doing a moment ago.
         *
         * The difference between [smoothed] and this is the whole of "did something just
         * start", as distinct from "is something still going". A thunderclap is five to
         * ten times louder than the instant before it; the fourth second of that same
         * clap's roll is, whatever its absolute level, almost exactly as loud as the
         * third. Without this the detector flashed several times part-way through every
         * long roll, because a decaying tail is still far above the rain and its own
         * ripple keeps crossing any fixed ratio.
         */
        private var medium = 0f

        /** The quiet level this recording has settled at. Detection measures against it. */
        private var floor = 0f

        /** The loud level. Only the display normalisation uses it. */
        private var ceil = 0f

        /** Schmitt state: true while an event is in progress. */
        private var armed = false
        private var justFired = false
        private var peakRatio = 1f

        /** Hops since the trigger tripped, while an event is still being confirmed. */
        private var pending = -1

        fun reset() {
            smoothed = 0f; medium = 0f; floor = 0f; ceil = 0f
            armed = false; justFired = false; peakRatio = 1f; pending = -1
        }

        fun seed(x: Float) {
            smoothed = x; medium = x; floor = x; ceil = x + MIN_SPAN
        }

        /** Advance by one hop and return the 0..1 display level. */
        fun update(x: Float): Float {
            smoothed += (x - smoothed) * SMOOTH
            val v = smoothed

            val ratio = v / floor.coerceAtLeast(MIN_FLOOR)
            // How much louder than a moment ago. Both conditions have to hold: loud
            // *for this recording*, and louder than it just was.
            val surge = v / medium.coerceAtLeast(MIN_FLOOR)
            justFired = false
            if (!armed) {
                if (ratio > TRIGGER_RATIO && surge > SURGE_RATIO && v > MIN_LEVEL) {
                    armed = true
                    pending = 0
                    peakRatio = ratio
                }
            } else {
                peakRatio = maxOf(peakRatio, ratio)
                if (ratio < RELEASE_RATIO || v <= MIN_LEVEL) {
                    // Fell away before it could be confirmed: rain wobbling, not
                    // thunder. Nothing is reported, and the room does not flash.
                    armed = false
                    pending = -1
                } else if (pending >= 0) {
                    pending++
                    if (pending >= CONFIRM_HOPS) {
                        // It lasted. Report it now, dated back to when it began.
                        justFired = true
                        pending = -1
                    }
                }
            }

            // Updated after the test, so a frame is compared against the past rather
            // than against a past that already includes it.
            medium += (v - medium) * MEDIUM
            // The floor climbs slowly enough that a roll lasting eight seconds cannot
            // raise it to its own level and make the next clap look quiet, and falls
            // fast enough that the room is ready again shortly after one has died.
            floor += (v - floor) * (if (v > floor) FLOOR_RISE else FLOOR_FALL)
            // The ceiling grabs a new peak almost at once and lets it go over many
            // seconds, so an event is drawn against its own height rather than against
            // whatever the loudest thing in the last minute happened to be.
            ceil += (v - ceil) * (if (v > ceil) CEIL_RISE else CEIL_FALL)

            val span = (ceil - floor).coerceAtLeast(MIN_SPAN)
            return ((v - floor) / span).coerceIn(0f, 1f)
        }

        fun fired() = justFired

        /**
         * How long before *now* the event that just fired actually began, in seconds.
         *
         * The confirmation window plus the smoothing filter's own group delay. Both are
         * known exactly, so the event can be stamped where it happened rather than
         * where it was noticed.
         */
        fun startedAgoS(): Double = CONFIRM_HOPS * HOP_S + SMOOTH_LAG_S

        /** How big the event was, 0..1, from how far over the floor it went. */
        fun strength() = ((peakRatio - TRIGGER_RATIO) / STRENGTH_SPAN).coerceIn(0f, 1f)
    }

    private companion object {
        /** Per hop at ~50 Hz. ~40 ms, so a sharp attack still trips within two hops. */
        const val SMOOTH = 0.45f

        /** ~0.5 s. The "a moment ago" the surge test measures against. */
        const val MEDIUM = 0.04f

        /**
         * How much louder than a moment ago an event has to be.
         *
         * Deliberately modest. Even the slowest thing these effects care about — a
         * distant strike swelling over a third of a second — more than doubles against a
         * half-second reference, while a roll decaying smoothly never reaches 1.1.
         */
        const val SURGE_RATIO = 1.35f

        /** ~6 s to rise, ~0.7 s to fall. */
        const val FLOOR_RISE = 0.0035f
        const val FLOOR_FALL = 0.050f

        /** ~60 ms to rise, ~10 s to fall. */
        const val CEIL_RISE = 0.30f
        const val CEIL_FALL = 0.0020f

        /**
         * How much louder than the quiet level counts as an event, and how much quieter
         * ends one.
         *
         * The gap between them is what stops a clap hovering at the threshold from
         * firing forty times on its way past. Measured against a synthetic storm whose
         * rain fluctuates by about a third: a ratio of 1.6 is comfortably above anything
         * rain does and comfortably below any real clap.
         */
        const val TRIGGER_RATIO = 1.60f
        const val RELEASE_RATIO = 1.20f

        /**
         * Hops an event must survive before it is believed.
         *
         * The one thing that separates thunder from rain, and it is not loudness — a
         * gust of rain can be momentarily as loud as a distant roll. It is *duration*.
         * Thunder lasts seconds; a noise fluctuation lasts a hop or two and is gone.
         * Waiting three hops before believing an onset threw out almost every false
         * strike a broadband hiss was producing and cost nothing real, because nothing
         * that matters to any of these effects is over in sixty milliseconds.
         *
         * Affordable because the analysis tap runs a sink buffer ahead of the speaker —
         * a couple of hundred milliseconds — and the light frame is only rendered a
         * tenth of a second ahead of the ear. The confirmation fits inside that slack,
         * so the flash still lands on the clap.
         */
        const val CONFIRM_HOPS = 3

        /** One analysis hop, in seconds. ~20 ms; see `ANALYSIS_HOP`. */
        const val HOP_S = 441.0 / 22_050.0

        /** Group delay of the smoothing filter above, ~one hop at SMOOTH = 0.45. */
        const val SMOOTH_LAG_S = 0.022

        /** Ratio over the trigger at which an event counts as being as big as they get. */
        const val STRENGTH_SPAN = 1.4f

        /** Guards against a divide by nothing, and against silence reading as an event. */
        const val MIN_FLOOR = 0.02f
        const val MIN_LEVEL = 0.05f

        /** Floor on the display span, so perfectly steady audio reads as steady. */
        const val MIN_SPAN = 0.10f
    }
}

/**
 * Convert an analysis pan, -1 left to +1 right, into a room azimuth in turns.
 *
 * `SpatialWaves.azimuthOf` puts 0 at room-right and runs anticlockwise, so hard right is
 * 0 and hard left is half a turn. Shared rather than repeated in each script, because
 * two scripts disagreeing about which way is left is the kind of bug nobody ever sees
 * directly — it just makes one effect feel wrong.
 */
fun azimuthOfPan(pan: Float): Float = ((1f - pan.coerceIn(-1f, 1f)) * 0.25f).coerceIn(0f, 0.5f)
