package com.engabd.sendpin.audio

import kotlin.math.sqrt

/**
 * Musical key detection: Krumhansl-Kessler major/minor key-profile
 * correlation over a whole-track chroma vector.
 *
 * The profiles are the tone weights from Krumhansl & Kessler's 1982
 * probe-tone study — how strongly each pitch class is felt to belong to a
 * key once the tonic is fixed at index 0. Correlating a track's own
 * accumulated 12-bin chroma (see [chromaProjection]) against all 24
 * rotations (12 tonics x major/minor) is the standard way to turn "how much
 * energy landed on each pitch class over the whole track" into "what key is
 * this" — the same idea [SongPalette] already applies per-frame for a much
 * simpler dominant-pitch-class hue, just correlated against real key shapes
 * instead of taken as a raw peak.
 *
 * Pure and stateless, and deliberately its own file rather than folded into
 * [TrackAnalysis] — same split as [SyncoPalette] out of `SyncoEngine.kt`:
 * this has nothing to do with the offline extractor's STFT plumbing.
 */

enum class MusicalMode { MAJOR, MINOR }

data class MusicalKey(val tonic: Int, val mode: MusicalMode, val confidence: Float)

private val MAJOR_PROFILE = doubleArrayOf(
    6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88,
)
private val MINOR_PROFILE = doubleArrayOf(
    6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17,
)

/** Below this total energy the chroma vector is too thin to trust a key from. */
private const val MIN_CHROMA_ENERGY = 1e-6

/**
 * A confident correlation gap, in the -1..1 range [correlation] returns, on
 * the far side of which [MusicalKey.confidence] saturates to 1.0. Not a
 * measured constant — a clean win in this method is usually a few hundredths
 * to a few tenths, so this is a starting point for retuning against real
 * tracks, the same way [SyncoModes]' tunables are.
 */
private const val CONFIDENT_GAP = 0.3

/**
 * The best-fit tonic and mode for a 12-bin pitch-class energy vector
 * (0=C .. 11=B, as produced by [chromaProjection] and accumulated over a
 * whole track), or null when there is not enough tonal signal to trust —
 * a near-silent or purely percussive track, for instance.
 *
 * [MusicalKey.confidence] is the gap between the best and second-best
 * correlation, not the correlation itself: two keys a fifth apart scoring
 * almost identically is a common ambiguity for this method, and should read
 * as uncertain even when the top score itself is high.
 */
internal fun detectKey(chroma: FloatArray): MusicalKey? {
    if (chroma.size != 12) return null
    var energy = 0.0
    for (v in chroma) energy += v.toDouble()
    if (energy < MIN_CHROMA_ENERGY) return null

    val vec = DoubleArray(12) { chroma[it].toDouble() }

    var bestScore = -2.0
    var secondBest = -2.0
    var bestTonic = 0
    var bestMode = MusicalMode.MAJOR

    for (tonic in 0 until 12) {
        for (mode in MusicalMode.entries) {
            val profile = if (mode == MusicalMode.MAJOR) MAJOR_PROFILE else MINOR_PROFILE
            val score = correlation(vec, rotateToTonic(profile, tonic))
            if (score > bestScore) {
                secondBest = bestScore
                bestScore = score
                bestTonic = tonic
                bestMode = mode
            } else if (score > secondBest) {
                secondBest = score
            }
        }
    }

    val gap = (bestScore - secondBest).coerceIn(0.0, 2.0)
    val confidence = (gap / CONFIDENT_GAP).coerceIn(0.0, 1.0).toFloat()
    return MusicalKey(tonic = bestTonic, mode = bestMode, confidence = confidence)
}

/** [profile] (tonic at index 0) shifted so its tonic sits at pitch class [tonic]. */
private fun rotateToTonic(profile: DoubleArray, tonic: Int): DoubleArray =
    DoubleArray(12) { i -> profile[(i - tonic + 12) % 12] }

/** Pearson correlation of two equal-length vectors; 0 when either is flat. */
private fun correlation(a: DoubleArray, b: DoubleArray): Double {
    val meanA = a.average()
    val meanB = b.average()
    var cov = 0.0
    var varA = 0.0
    var varB = 0.0
    for (i in a.indices) {
        val da = a[i] - meanA
        val db = b[i] - meanB
        cov += da * db
        varA += da * da
        varB += db * db
    }
    val denom = sqrt(varA * varB)
    return if (denom > 1e-12) cov / denom else 0.0
}
