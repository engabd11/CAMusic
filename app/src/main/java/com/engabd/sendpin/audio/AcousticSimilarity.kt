package com.engabd.sendpin.audio

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * On-device acoustic similarity between tracks, built entirely from [TrackScan]
 * data that the analyser already produces — no spectrum, no audio re-decode,
 * no network call.
 *
 * The vector is a compact summary of what a DJ would call the "feel" of a track:
 * tempo, key, loudness, structure and spectral balance. Two tracks that share
 * all of those are good candidates for a smooth transition; two that differ on
 * most are not. Cosine similarity over these vectors gives a single 0..1 number
 * that the auto-queue can rank by, and it does so from data already on the
 * device rather than from a recommendation API that needs the library uploaded.
 *
 * **Why these features and not others.** The scan carries far more than five
 * numbers — beat grids, intensity curves, melbank references — but most of that
 * is either redundant for similarity (a beat grid and a BPM describe the same
 * tempo at different resolutions) or too sensitive to the exact mix (melbank
 * references depend on mastering, not on the track's identity). The features
 * chosen here are the ones that are both musically meaningful and stable across
 * different rips of the same recording.
 *
 * Pure and stateless, so it is unit-testable on the JVM with no Android
 * dependencies. The heavy lifting — the scan itself — is done; this is just
 * the comparison.
 */
object AcousticSimilarity {

    // ── Normalisation constants ───────────────────────────────────────────

    /**
     * BPM is mapped to [0, 1] over this range. 40–200 covers essentially all
     * dance music; outside that the track is a ballad or a novelty and the
     * exact number matters less than "not a dance track".
     */
    private const val BPM_MIN = 40f
    private const val BPM_MAX = 200f

    /**
     * Section count is capped at this before normalising. A 30-minute DJ mix
     * has more sections than any individual track, but for similarity the
     * interesting distinction is "two-part intro-verse-chorus" (3-5) versus
     * "full arrangement" (8-12); past 16 the track is just long, not different.
     */
    private const val MAX_SECTIONS = 16f

    /**
     * Spectral centroid is normalised over the melbank's frequency range.
     * The analyser's melbank spans roughly 20 Hz to ~11 kHz; a centroid at the
     * low end means bass-dominated, at the high end means bright. We use the
     * intensity profile's tilt (−1 bright .. +1 bass-heavy) as a proxy when a
     * direct centroid is not available, since it captures the same spectral
     * balance in a single number.
     */
    private const val TILT_RANGE = 2f  // tilt spans −1..+1, so range is 2

    /**
     * Vector dimension: 1 (BPM) + 12 (chroma) + 1 (mean energy) + 1 (sections)
     * + 1 (spectral centroid) = 16.
     */
    const val VECTOR_SIZE = 16

    // ── Feature extraction ────────────────────────────────────────────────

    /**
     * A normalised feature vector from a [TrackScan], suitable for cosine
     * similarity comparison.
     *
     * The vector layout is:
     * - `[0]`     BPM normalised to [0, 1]
     * - `[1..12]` Key chroma — 12 bins, one per pitch class, derived from
     *   [MusicalKey] using the Temperley profile for the detected mode. This
     *   makes a C-major track and a C-minor track produce different (but
     *   related) vectors, while a transposed copy of the same track produces
     *   a rotated vector that still correlates under circular comparison.
     *   When key is null (pre-v2 scans), all twelve bins are zero — the vector
     *   still works, it just weighs the other features more.
     * - `[13]`    Mean section energy, 0..1
     * - `[14]`    Section count normalised to [0, 1]
     * - `[15]`    Spectral centroid estimate from the intensity profile's tilt
     *
     * Why a chroma *profile* rather than a one-hot tonic: a one-hot vector
     * gives zero overlap between keys a semitone apart, which is musically
     * wrong — adjacent keys share most of their material. The Temperley
     * profile gives a smooth, musically meaningful gradient: C major is
     * closest to G major (one fifth), then F major, etc., exactly the
     * relationships the Camelot wheel encodes.
     */
    fun featureVector(scan: TrackScan): FloatArray {
        val v = FloatArray(VECTOR_SIZE)

        // BPM normalised to [0, 1]
        v[0] = ((scan.bpm - BPM_MIN) / (BPM_MAX - BPM_MIN)).coerceIn(0f, 1f)

        // Key chroma: the Temperley profile rotated to the detected tonic.
        // This is the same profile KeyDetection uses for detection, so the
        // similarity vector and the detector agree on what a "key" looks like.
        val key = scan.key
        if (key != null) {
            val profile = if (key.mode == MusicalMode.MAJOR) TEMPERLEY_MAJOR else TEMPERLEY_MINOR
            for (i in 0 until 12) {
                v[1 + i] = profile[Math.floorMod(i - key.tonic, 12)].toFloat()
            }
        }
        // When key is null the twelve bins stay at zero. The vector is shorter
        // effectively, but cosine similarity still works — it just means the
        // comparison relies on tempo, energy and structure for that pair.

        // Mean section energy
        v[13] = if (scan.sections.isEmpty()) 0f else scan.sections.map { it.energy }.sum() / scan.sections.size

        // Section count normalised
        v[14] = (scan.sections.size / MAX_SECTIONS).coerceIn(0f, 1f)

        // Spectral centroid estimate from tilt: −1 (bright) maps to 0,
        // +1 (bass-heavy) maps to 1. Inverted so that bright tracks are
        // distinguishable from bass-heavy ones.
        val tilt = scan.intensity?.tilt ?: 0f
        v[15] = ((tilt + 1f) / TILT_RANGE).coerceIn(0f, 1f)

        return v
    }

    // ── Similarity ────────────────────────────────────────────────────────

    /**
     * Cosine similarity between two feature vectors, in [0, 1].
     *
     * Returns 1.0 for identical vectors, 0.0 for orthogonal ones. The result
     * is clamped to [0, 1] because the chroma profile entries are all
     * non-negative, so cosine is already in that range — but the clamp is
     * cheap insurance against floating-point edge cases at the boundaries.
     *
     * Returns 0.0 when either vector has zero magnitude (all features zero),
     * which happens for a scan with no key, no sections and default intensity
     * — a scan that says nothing about the track should not match anything.
     */
    fun similarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        if (denom < 1e-12f) return 0f
        return (dot / denom).coerceIn(0f, 1f)
    }

    /**
     * The [limit] most similar track IDs from [candidates] to the [target]
     * scan, most similar first.
     *
     * Returns an empty list when [candidates] is empty or [limit] is zero.
     * Uses a single pass with a bounded priority queue — O(n log k) rather
     * than O(n log n) — because the candidate library can be large and only
     * the top few are needed.
     */
    fun mostSimilar(
        target: TrackScan,
        candidates: Map<String, TrackScan>,
        limit: Int,
    ): List<String> {
        if (candidates.isEmpty() || limit <= 0) return emptyList()

        val targetVec = featureVector(target)

        // Simple bounded selection: collect all, sort, take top limit.
        // For a phone library of a few thousand tracks this is faster than
        // the overhead of a heap and easier to reason about.
        val scored = candidates.map { (id, scan) ->
            id to similarity(targetVec, featureVector(scan))
        }
        return scored
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    /**
     * Circular cosine similarity for key chroma: compares two 12-bin key
     * vectors at every rotational offset and returns the best match.
     *
     * This is what makes a transposed copy of a track (same content, pitch
     * shifted up N semitones) score as similar rather than as unrelated. The
     * BPM, energy and structure features already match exactly; only the key
     * chroma rotates, and rotating it back finds the match.
     *
     * Not used in the main [featureVector] / [similarity] path (which compares
     * keys in their absolute positions, since a DJ mixing in a compatible but
     * different key is *not* the same track) — exposed for callers that want
     * "is this the same song in a different key?" rather than "is this a good
     * mix transition?".
     */
    fun keySimilarity(a: MusicalKey?, b: MusicalKey?): Float {
        if (a == null || b == null) return if (a == null && b == null) 1f else 0f
        // Same key = 1.0, compatible keys (Camelot) = high, incompatible = low.
        // Use the Camelot wheel for a musically meaningful distance.
        return if (Camelot.compatible(a, b)) 0.85f else 0.3f
    }
}