package com.engabd.sendpin.hue

import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.PI

/** How many hues the room holds at once, spread across it by `colourSpread`. */
private const val SONG_COLOURS = 5

/**
 * Minimum separation between a new hue and the ones already showing, as a
 * fraction of the wheel. Roughly 65°: enough that a change reads as a *new*
 * colour rather than a shade of the last one.
 */
private const val MIN_HUE_SEP = 0.18f

/** Attempts to find a well-separated hue before settling for the best found. */
private const val HUE_TRIES = 12

/** Saturation and value of a generated colour. Vivid, but not eye-searing. */
private const val SONG_SAT = 0.92f
private const val SONG_VAL = 1.0f

/**
 * The synesthetic chromatic rainbow (C=red, D~yellow, ..., B~violet). Index 0
 * = C. A plain chromatic wheel keeps adjacent semitones adjacent in hue, so a
 * key and its neighbours give a coherent, smoothly-drifting palette — exactly
 * what syncoV2's offline chroma-based palette does.
 */
private val PITCH_HUE = FloatArray(12) { it / 12f }

/**
 * The Song colour source: fresh colours drawn from the music's live harmony.
 *
 * Deliberately simple, but not random. syncoV2 derives its Song palette from
 * the track's chroma — its actual key and pitch-class energy — which gives hues
 * that match the music. The live analyzer now supplies a 12-bin chroma, so this
 * biases new hues toward the strongest pitch classes. A chroma peak must reach
 * [PEAK_FRAC] of the strongest class to earn its own anchor; otherwise only the
 * dominant pitch class drives the colour.
 */
class SongPalette(seed: Long? = null) {

    private val rng = if (seed != null) Random(seed) else Random()
    private val hues = FloatArray(SONG_COLOURS)

    init {
        reset()
    }

    /** Start over with a fresh set of hues. */
    fun reset() {
        for (i in hues.indices) hues[i] = -1f  // mark unset so the first fill spreads
        for (i in hues.indices) hues[i] = pickHue()
    }

    /**
     * Advance the palette for a beat, and return the colours to render.
     *
     * @param peak true on a highlighted beat, which turns two colours over
     *   instead of one so the moment lands.
     * @param chroma optional 12-bin pitch-class energy; when present the new hue
     *   is biased toward the music's current harmony, not purely random.
     */
    fun onBeat(peak: Boolean, chroma: FloatArray? = null) {
        rotate(chroma)
        if (peak) rotate(chroma)
    }

    /** The current colours, oldest first, ready for [Palette]. */
    fun colors(): List<Rgb> = hues.map { hsvToRgbSong(it, SONG_SAT, SONG_VAL) }

    private fun rotate(chroma: FloatArray?) {
        // Shift down and append, so the room turns over gradually rather than
        // every lamp changing at once.
        for (i in 0 until hues.size - 1) hues[i] = hues[i + 1]
        hues[hues.size - 1] = pickHue(chroma)
    }

    /**
     * A hue as far as reasonably possible from the ones already showing,
     * biased toward the live chroma when one is provided.
     */
    private fun pickHue(chroma: FloatArray? = null): Float {
        val anchor = chromaAnchor(chroma)
        var best = rng.nextFloat()
        var bestGap = -1f
        repeat(HUE_TRIES) {
            // If a chroma anchor exists, spend most of the search near it,
            // but allow one outlier pick so the room doesn't get stuck in one
            // key for an entire song.
            val candidate = if (anchor != null && it < HUE_TRIES - 2) {
                (anchor + rng.nextGaussian(0.08f)).mod1()
            } else {
                rng.nextFloat()
            }
            var gap = 1f
            for (h in hues) {
                if (h < 0f) continue  // unset
                gap = min(gap, hueDistance(candidate, h))
            }
            if (gap > bestGap) {
                bestGap = gap
                best = candidate
            }
            if (gap >= MIN_HUE_SEP) return candidate
        }
        return best
    }

    /**
     * Map a 12-bin chroma vector to a dominant hue. Only pitch classes that
     * reach [PEAK_FRAC] of the strongest class get their own hue slot; if none
     * do, only the dominant class drives the colour.
     */
    private fun chromaAnchor(chroma: FloatArray?): Float? {
        if (chroma == null || chroma.size != 12) return null
        val c = chroma.copyOf()
        var mx = c[0]
        for (v in c) if (v > mx) mx = v
        if (mx <= 1e-9f) return null
        // Normalise and find peaks above the fraction threshold.
        val threshold = mx * PEAK_FRAC
        var total = 0f
        var weighted = 0f
        for (i in c.indices) {
            if (c[i] >= threshold) {
                val w = c[i] / mx
                total += w
                weighted += w * PITCH_HUE[i]
            }
        }
        return if (total > 1e-9f) weighted / total else {
            var idx = 0
            for (i in c.indices) if (c[i] > c[idx]) idx = i
            PITCH_HUE[idx]
        }
    }

    private companion object {
        private const val PEAK_FRAC = 0.45f

        /** Distance on the hue wheel, 0..0.5. */
        fun hueDistance(a: Float, b: Float): Float {
            val d = abs(a - b) % 1f
            return min(d, 1f - d)
        }

        /** Wrap into [0, 1). */
        fun Float.mod1(): Float {
            val m = this % 1f
            return if (m < 0f) m + 1f else m
        }

        /**
         * Local HSV to RGB. The engine has one of these too, but it is private
         * to that file and this is three lines of arithmetic — sharing it would
         * mean widening its visibility for no gain.
         */
        fun hsvToRgbSong(h: Float, s: Float, v: Float): Rgb {
            val i = (h * 6f).toInt()
            val f = h * 6f - i
            val p = v * (1f - s)
            val q = v * (1f - s * f)
            val t = v * (1f - s * (1f - f))
            return when (i % 6) {
                0 -> Triple(v, t, p)
                1 -> Triple(q, v, p)
                2 -> Triple(p, v, t)
                3 -> Triple(p, q, v)
                4 -> Triple(t, p, v)
                else -> Triple(v, p, q)
            }
        }
    }
}

/** Gaussian-ish perturbation around zero, scaled by [sigma]. */
private fun Random.nextGaussian(sigma: Float): Float {
    val u1 = nextFloat()
    val u2 = nextFloat()
    val r = sqrt(-2f * ln(u1.coerceAtLeast(1e-7f)))
    val theta = 2f * PI * u2
    return (sigma * r * cos(theta)).toFloat()
}
