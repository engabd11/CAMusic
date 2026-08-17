package com.engabd.sendpin.audio

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Times [LocalPlayer]'s end-of-track fade to land on a beat instead of an
 * arbitrary N seconds before the end.
 *
 * This is **not** a crossfade in the DJ sense — [LocalPlayer] has one `ExoPlayer`
 * and one output, so the outgoing and incoming tracks never play at once (see
 * [LocalPlayer.fadeSeconds]'s own doc). What a single sequential fade *can* do is
 * choose where it ends: cutting to silence mid-beat is what makes a fade sound
 * like the "wrong" duration was picked, even when the number itself is fine.
 * Landing the fade's end on the beat closest to the intended window fixes that,
 * using the beat grid a track's offline scan already has (built for Hue Light
 * Sync's [TempoTracker], reused here for a different purpose).
 */
object BeatAlignedFade {

    /**
     * The fade window (seconds) to use for a track ending at [durationS], given
     * [defaultSeconds] as the target and [beats] its scanned beat timestamps
     * (ascending, track-relative seconds).
     *
     * Falls back to [defaultSeconds] unchanged when there's no scan, or when the
     * nearest beat to the default fade point would produce an implausible window
     * (within half a second of the very end, or more than 50% off the target) —
     * better an honest fixed fade than a window so short or long it reads as a
     * bug rather than a beat match.
     */
    fun windowSeconds(durationS: Float, beats: FloatArray, defaultSeconds: Int): Int {
        if (beats.isEmpty() || defaultSeconds <= 0 || durationS <= 0f) return defaultSeconds
        val target = durationS - defaultSeconds
        val nearestBeat = beats.filter { it in 0f..durationS }.minByOrNull { abs(it - target) }
            ?: return defaultSeconds
        val aligned = durationS - nearestBeat
        val lo = defaultSeconds * 0.5f
        val hi = defaultSeconds * 1.5f
        if (aligned < 0.5f || aligned !in lo..hi) return defaultSeconds
        return aligned.roundToInt().coerceIn(1, 12)
    }
}
