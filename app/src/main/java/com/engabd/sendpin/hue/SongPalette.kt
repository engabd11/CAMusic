package com.engabd.sendpin.hue

import java.util.Random
import kotlin.math.abs
import kotlin.math.min

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
 * The Song colour source: fresh random colours, changing with the music.
 *
 * Deliberately simple. syncoV2 derives this from the track's chroma — its actual
 * key and harmony — which needs a pitch-class projection the live analyzer does
 * not compute, and which is a lot of machinery for something the room reads as
 * "the colours keep changing". This does the thing that reads, directly.
 *
 * A ring of [SONG_COLOURS] hues, so the palette still spreads across the room
 * the way every other scheme does rather than flooding it with one colour. Each
 * qualifying beat retires the oldest hue and introduces a new one, so the room
 * evolves continuously and never repeats — a peak swaps two at once, so the big
 * moments visibly turn the room over.
 *
 * New hues are chosen away from the ones already showing, because uniform random
 * picks cluster: without a separation rule roughly a third of changes land close
 * enough to the previous colour to read as no change at all.
 *
 * Not thread-safe; owned by the engine's render loop.
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
     */
    fun onBeat(peak: Boolean) {
        rotate()
        if (peak) rotate()
    }

    /** The current colours, oldest first, ready for [Palette]. */
    fun colors(): List<Rgb> = hues.map { hsvToRgbSong(it, SONG_SAT, SONG_VAL) }

    private fun rotate() {
        // Shift down and append, so the room turns over gradually rather than
        // every lamp changing at once.
        for (i in 0 until hues.size - 1) hues[i] = hues[i + 1]
        hues[hues.size - 1] = pickHue()
    }

    /** A hue as far as reasonably possible from the ones already showing. */
    private fun pickHue(): Float {
        var best = rng.nextFloat()
        var bestGap = -1f
        repeat(HUE_TRIES) {
            val candidate = rng.nextFloat()
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

    private companion object {
        /** Distance on the hue wheel, 0..0.5. */
        fun hueDistance(a: Float, b: Float): Float {
            val d = abs(a - b) % 1f
            return min(d, 1f - d)
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
