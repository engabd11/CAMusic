package com.engabd.sendpin.audio

import com.engabd.sendpin.audio.KeyDetection.MusicalKey
import com.engabd.sendpin.audio.KeyDetection.MusicalMode

/**
 * A normalised fingerprint for comparing two tracks by their offline scans.
 *
 * Kept flat and small on purpose: every track with a scan gets one in memory, so
 * a library of tens of thousands still fits on a phone.
 */
data class SonicFingerprint(
    val bpm: Float,
    val keyTonic: Int?,
    val keyMode: String?,
    val energy: Float,
    val dynamics: Float,
    val spectralCentroid: Float,
) {
    companion object {
        /** Best-effort fingerprint from a completed scan. Missing or empty fields degrade gracefully. */
        fun from(scan: TrackScan): SonicFingerprint {
            val key = scan.key
            val bpm = scan.bpm
            val profile = scan.intensity
            return SonicFingerprint(
                bpm = bpm?.takeIf { it > 0f } ?: 120f,
                keyTonic = key?.tonic,
                keyMode = key?.mode?.name,
                energy = profile?.character?.coerceIn(0f, 1f) ?: 0.5f,
                dynamics = profile?.dynamics?.coerceIn(0f, 1f) ?: 0.5f,
                spectralCentroid = profile?.tilt?.coerceIn(-1f, 1f) ?: 0f,
            )
        }
    }
}

/**
 * Distance between two track fingerprints, 0 (identical) to 1 (unrelated).
 *
 * Weights: tempo > key > energy > dynamics > spectral balance.
 */
object SonicSimilarity {
    fun distance(a: SonicFingerprint, b: SonicFingerprint): Float {
        var d = 0f
        d += tempoDistance(a.bpm, b.bpm) * 0.30f
        d += keyDistance(a.keyTonic, a.keyMode, b.keyTonic, b.keyMode) * 0.25f
        d += kotlin.math.abs(a.energy - b.energy) * 0.20f
        d += kotlin.math.abs(a.dynamics - b.dynamics) * 0.15f
        d += kotlin.math.abs(a.spectralCentroid - b.spectralCentroid) * 0.10f
        return d.coerceIn(0f, 1f)
    }

    fun similarity(a: SonicFingerprint, b: SonicFingerprint): Float = 1f - distance(a, b)

    private fun tempoDistance(a: Float, b: Float): Float {
        val min = kotlin.math.max(1f, kotlin.math.min(a, b))
        val max = kotlin.math.max(a, b)
        val ratio = if (min > 0f) max / min else 1f
        val octaveCorrected = if (ratio > 1.5f) ratio / 2f else ratio
        return kotlin.math.abs(octaveCorrected - 1f).coerceIn(0f, 1f)
    }

    private fun keyDistance(t1: Int?, m1: String?, t2: Int?, m2: String?): Float {
        if (t1 == null || t2 == null) return 0.5f
        val tonicDiff = kotlin.math.min(
            kotlin.math.abs(t1 - t2),
            12 - kotlin.math.abs(t1 - t2),
        )
        val modePenalty = if (m1?.uppercase() == m2?.uppercase()) 0f else 0.2f
        return (tonicDiff / 6f + modePenalty).coerceIn(0f, 1f)
    }
}
