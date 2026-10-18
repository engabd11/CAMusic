package com.engabd.sendpin.hue.ambience

import com.engabd.sendpin.audio.AnalysisFrame
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * What a bundled bed is going to do, worked out once and kept.
 *
 * ## Light before sound
 *
 * Live analysis can only ever react to a sound that is already playing, so a light
 * driven by it is, at best, level with the thunder. Real lightning is not level with its
 * thunder: you see it, and some seconds later you hear it. The bundled beds are fixed
 * files, so nothing needs to be discovered in real time; this is the same analysis run
 * over the whole file ahead of time, and the show reads it **ahead of the playhead**.
 * A script hears about a thunderclap three seconds before the speaker plays it and can
 * put the flash where physics puts it.
 *
 * What it holds, both measured by the same [AmbienceBedAnalyser] the live path uses, so
 * a user's own clip (analysed live) and a bundled bed (analysed here) get the same
 * detector and the same thresholds:
 *
 * - the **onsets**: every moment a band crossed its trigger, back-dated to where the
 *   event began, with strength, low-end share and pan;
 * - a **slow level envelope** (level, rumble, rain, pan) every [envStepS], which the
 *   session replays into the bed track so scripts get the recording's mood with no
 *   analysis latency and no gaps.
 *
 * The file loops (`REPEAT_MODE_ONE`), so every lookup is wrap-aware: a window that runs
 * past the end continues from the start, and an onset in the first seconds of the next
 * pass is visible before the current one has finished.
 *
 * Immutable once built. The session reads it from the render thread only.
 */
class AmbienceBedTimeline internal constructor(
    /** Length of the loop, in seconds. */
    val durationS: Double,
    /** Spacing of the level envelope. */
    val envStepS: Float,
    private val onsetAtS: DoubleArray,
    private val onsetFlags: IntArray,
    private val onsetLowStrength: FloatArray,
    private val onsetBroadStrength: FloatArray,
    private val onsetHighStrength: FloatArray,
    private val onsetLowShare: FloatArray,
    private val onsetPan: FloatArray,
    private val level: FloatArray,
    private val rumble: FloatArray,
    private val rain: FloatArray,
    private val pan: FloatArray,
) {

    /** Called once per onset by [forEachOnset]. */
    fun interface OnsetVisitor {
        /** [index] reads the onset's fields; [atMedia] is its unwrapped position. */
        fun visit(index: Int, atMedia: Double)
    }

    val onsetCount: Int get() = onsetAtS.size

    /**
     * Visit every onset whose position falls in `[fromMedia, toMedia)`.
     *
     * The window is in *unwrapped* media time: pass `0..D` for the first loop, `D..2D`
     * for the second, or a window that straddles the seam. [OnsetVisitor.visit] is given
     * the position in the same unwrapped terms, so a caller counting loops can compare
     * it directly with its own clock. In order, with no allocation.
     */
    fun forEachOnset(fromMedia: Double, toMedia: Double, visitor: OnsetVisitor) {
        if (onsetAtS.isEmpty() || durationS <= 0.0 || toMedia <= fromMedia) return
        var pass = floor(fromMedia / durationS).toInt().coerceAtLeast(0)
        while (pass * durationS < toMedia) {
            val base = pass * durationS
            val lo = max(fromMedia - base, 0.0)
            val hi = min(toMedia - base, durationS)
            var i = firstAtOrAfter(lo)
            while (i < onsetAtS.size && onsetAtS[i] < hi) {
                visitor.visit(i, base + onsetAtS[i])
                i++
            }
            pass++
        }
    }

    private fun firstAtOrAfter(s: Double): Int {
        var lo = 0
        var hi = onsetAtS.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (onsetAtS[mid] < s) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Copy onset [index] into the reusable [onset] and [sample] a script's `react` takes. */
    fun fill(index: Int, atMedia: Double, onset: BedOnset, sample: BedSample) {
        onset.clear()
        val f = onsetFlags[index]
        if (f and LOW != 0) { onset.low = true; onset.lowStrength = onsetLowStrength[index] }
        if (f and BROAD != 0) { onset.broadband = true; onset.broadbandStrength = onsetBroadStrength[index] }
        if (f and HIGH != 0) { onset.high = true; onset.highStrength = onsetHighStrength[index] }
        onset.lowShare = onsetLowShare[index]
        onset.pan = onsetPan[index]
        onset.atS = atMedia
        sample.tS = atMedia
        sample.pan = onsetPan[index]
    }

    /**
     * The slow envelope at unwrapped media time [media], written into [into].
     *
     * Linear between steps and continuous across the loop seam. Only the fields a script
     * draws with are filled: [BedSample.level], [BedSample.rumble], [BedSample.rain] and
     * [BedSample.pan].
     */
    fun levelsAt(media: Double, into: BedSample) {
        val n = level.size
        if (n == 0 || durationS <= 0.0) return
        var pos = (media % durationS)
        if (pos < 0) pos += durationS
        val x = pos / envStepS
        val i0 = floor(x).toInt().coerceIn(0, n - 1)
        val i1 = (i0 + 1) % n
        val f = (x - floor(x)).toFloat()
        into.tS = media
        into.level = level[i0] + (level[i1] - level[i0]) * f
        into.rumble = rumble[i0] + (rumble[i1] - rumble[i0]) * f
        into.rain = rain[i0] + (rain[i1] - rain[i0]) * f
        into.pan = pan[i0] + (pan[i1] - pan[i0]) * f
    }

    fun write(out: DataOutputStream) {
        out.writeInt(MAGIC)
        out.writeInt(VERSION)
        out.writeDouble(durationS)
        out.writeFloat(envStepS)
        out.writeInt(onsetAtS.size)
        for (i in onsetAtS.indices) {
            out.writeDouble(onsetAtS[i])
            out.writeInt(onsetFlags[i])
            out.writeFloat(onsetLowStrength[i])
            out.writeFloat(onsetBroadStrength[i])
            out.writeFloat(onsetHighStrength[i])
            out.writeFloat(onsetLowShare[i])
            out.writeFloat(onsetPan[i])
        }
        out.writeInt(level.size)
        for (i in level.indices) {
            out.writeFloat(level[i]); out.writeFloat(rumble[i])
            out.writeFloat(rain[i]); out.writeFloat(pan[i])
        }
    }

    /** Accumulates analysis frames into a timeline. Analysis-thread or offline only. */
    class Builder(private val hopS: Double = 441.0 / 22_050.0, private val envStepS: Float = ENV_STEP_S) {
        private val analyser = AmbienceBedAnalyser(hopS)

        private class Onset(
            val atS: Double, val flags: Int, val low: Float, val broad: Float, val high: Float,
            val lowShare: Float, val pan: Float,
        )

        private val onsets = ArrayList<Onset>()
        private val sums = ArrayList<FloatArray>()      // level, rumble, rain, pan, count
        private var lastS = 0.0

        /** One analysed hop; [atS] is the position of the audio it describes. */
        fun consume(frame: AnalysisFrame, atS: Double) {
            val (s, o) = analyser.consume(frame, atS)
            lastS = max(lastS, atS)
            val bin = (atS / envStepS).toInt().coerceAtLeast(0)
            while (sums.size <= bin) sums.add(FloatArray(5))
            val b = sums[bin]
            b[0] += s.level; b[1] += s.rumble; b[2] += s.rain; b[3] += s.pan; b[4] += 1f

            var flags = 0
            if (o.low) flags = flags or LOW
            if (o.broadband) flags = flags or BROAD
            if (o.high) flags = flags or HIGH
            if (flags != 0) {
                onsets.add(
                    Onset(
                        atS = max(0.0, o.atS), flags = flags,
                        low = o.lowStrength, broad = o.broadbandStrength, high = o.highStrength,
                        lowShare = o.lowShare, pan = o.pan,
                    ),
                )
            }
        }

        /**
         * Finish, for a file of [durationS] seconds.
         *
         * A bin the file never reached is carried from the one before it, so the
         * envelope has no holes for the session to hold across.
         */
        fun build(durationS: Double = lastS + hopS): AmbienceBedTimeline {
            val sorted = onsets.sortedBy { it.atS }
            val n = sorted.size
            val bins = max(1, sums.size)
            val lv = FloatArray(bins); val ru = FloatArray(bins)
            val rn = FloatArray(bins); val pn = FloatArray(bins)
            for (i in 0 until bins) {
                val b = sums.getOrNull(i)
                if (b != null && b[4] > 0f) {
                    lv[i] = b[0] / b[4]; ru[i] = b[1] / b[4]; rn[i] = b[2] / b[4]; pn[i] = b[3] / b[4]
                } else if (i > 0) {
                    lv[i] = lv[i - 1]; ru[i] = ru[i - 1]; rn[i] = rn[i - 1]; pn[i] = pn[i - 1]
                }
            }
            return AmbienceBedTimeline(
                durationS = durationS,
                envStepS = envStepS,
                onsetAtS = DoubleArray(n) { sorted[it].atS },
                onsetFlags = IntArray(n) { sorted[it].flags },
                onsetLowStrength = FloatArray(n) { sorted[it].low },
                onsetBroadStrength = FloatArray(n) { sorted[it].broad },
                onsetHighStrength = FloatArray(n) { sorted[it].high },
                onsetLowShare = FloatArray(n) { sorted[it].lowShare },
                onsetPan = FloatArray(n) { sorted[it].pan },
                level = lv, rumble = ru, rain = rn, pan = pn,
            )
        }
    }

    companion object {
        const val LOW = 1
        const val BROAD = 2
        const val HIGH = 4

        /** 10 Hz is finer than any script follows and small enough to cache cheaply. */
        const val ENV_STEP_S = 0.1f

        /**
         * Bump when [AmbienceBedAnalyser] is retuned, so a cache built by the old detector
         * is rebuilt rather than trusted.
         */
        const val VERSION = 1

        private const val MAGIC = 0x43425431   // "CBT1"

        /**
         * A timeline built by hand, with a flat envelope at [level]: for tests, and for
         * anything that wants to describe a recording without decoding one.
         */
        internal fun synthetic(durationS: Double, onsets: List<SyntheticOnset>, level: Float = 0.5f): AmbienceBedTimeline {
            val sorted = onsets.sortedBy { it.atS }
            val n = sorted.size
            val bins = maxOf(1, (durationS / ENV_STEP_S).toInt())
            return AmbienceBedTimeline(
                durationS = durationS,
                envStepS = ENV_STEP_S,
                onsetAtS = DoubleArray(n) { sorted[it].atS },
                onsetFlags = IntArray(n) { sorted[it].flags },
                onsetLowStrength = FloatArray(n) { sorted[it].strength },
                onsetBroadStrength = FloatArray(n) { sorted[it].strength },
                onsetHighStrength = FloatArray(n) { sorted[it].strength },
                onsetLowShare = FloatArray(n) { sorted[it].lowShare },
                onsetPan = FloatArray(n) { sorted[it].pan },
                level = FloatArray(bins) { level },
                rumble = FloatArray(bins) { level },
                rain = FloatArray(bins) { level },
                pan = FloatArray(bins),
            )
        }

        /** Null for anything that is not a current timeline, so a stale cache is rebuilt. */
        fun read(inp: DataInputStream): AmbienceBedTimeline? {
            if (inp.readInt() != MAGIC || inp.readInt() != VERSION) return null
            val duration = inp.readDouble()
            val step = inp.readFloat()
            val n = inp.readInt()
            if (n < 0 || n > 1_000_000) return null
            val at = DoubleArray(n); val fl = IntArray(n)
            val ls = FloatArray(n); val bs = FloatArray(n); val hs = FloatArray(n)
            val sh = FloatArray(n); val pa = FloatArray(n)
            for (i in 0 until n) {
                at[i] = inp.readDouble(); fl[i] = inp.readInt()
                ls[i] = inp.readFloat(); bs[i] = inp.readFloat(); hs[i] = inp.readFloat()
                sh[i] = inp.readFloat(); pa[i] = inp.readFloat()
            }
            val m = inp.readInt()
            if (m < 0 || m > 10_000_000) return null
            val lv = FloatArray(m); val ru = FloatArray(m); val rn = FloatArray(m); val pn = FloatArray(m)
            for (i in 0 until m) {
                lv[i] = inp.readFloat(); ru[i] = inp.readFloat()
                rn[i] = inp.readFloat(); pn[i] = inp.readFloat()
            }
            return AmbienceBedTimeline(duration, step, at, fl, ls, bs, hs, sh, pa, lv, ru, rn, pn)
        }
    }
}

/** One onset of a hand-made timeline; see [AmbienceBedTimeline.synthetic]. */
internal class SyntheticOnset(
    val atS: Double,
    val flags: Int = AmbienceBedTimeline.LOW,
    val strength: Float = 0.8f,
    val lowShare: Float = 0.8f,
    val pan: Float = 0f,
)
