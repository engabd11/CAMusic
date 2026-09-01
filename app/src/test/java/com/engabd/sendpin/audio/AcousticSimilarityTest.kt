package com.engabd.sendpin.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [AcousticSimilarity].
 *
 * The tests cover the four properties the feature is built to deliver:
 * - identical scans produce similarity 1.0
 * - transposed tracks (same content, different key) produce high similarity
 * - musically unrelated tracks produce low similarity
 * - empty candidates return an empty result
 *
 * All tests are pure JVM — [AcousticSimilarity] depends only on [TrackScan]
 * data classes, no Android APIs.
 */
class AcousticSimilarityTest {

    /**
     * A minimal scan with enough fields populated to produce a non-zero
     * feature vector. The beat/section arrays are small but realistic in
     * shape.
     */
    private fun scan(
        bpm: Float = 120f,
        key: MusicalKey? = MusicalKey(tonic = 0, mode = MusicalMode.MAJOR, confidence = 1f),
        sectionEnergies: List<Float> = listOf(0.5f, 0.7f, 0.6f, 0.8f),
        tilt: Float? = null,
        durationS: Float = 180f,
    ): TrackScan {
        val sections = sectionEnergies.mapIndexed { i, e ->
            ScanSection(
                startS = i * 45f,
                endS = (i + 1) * 45f,
                energy = e,
            )
        }
        val intensity = if (tilt != null) {
            IntensityProfile(
                sigLo = 0.4f,
                sigHi = 0.8f,
                dynamics = 0.4f,
                tilt = tilt,
                tempo = 0.5f,
                character = 0.5f,
                curve = FloatArray(10) { 0.5f },
                curveRateHz = 2f,
            )
        } else {
            null
        }
        return TrackScan(
            durationS = durationS,
            bpm = bpm,
            confidence = 0.9f,
            beats = FloatArray(0),
            accents = FloatArray(0),
            downbeat = 0,
            sections = sections,
            intensity = intensity,
            key = key,
        )
    }

    @Test
    fun `identical scans produce similarity 1_0`() {
        val a = scan()
        val vecA = AcousticSimilarity.featureVector(a)
        val vecB = AcousticSimilarity.featureVector(scan())
        val sim = AcousticSimilarity.similarity(vecA, vecB)
        assertEquals(1.0f, sim, 1e-5f, "identical scans should be perfectly similar")
    }

    @Test
    fun `a scan compared to itself is exactly 1_0`() {
        val a = scan(bpm = 128f, key = MusicalKey(5, MusicalMode.MAJOR, 0.9f))
        val vec = AcousticSimilarity.featureVector(a)
        val sim = AcousticSimilarity.similarity(vec, vec)
        assertEquals(1.0f, sim, 1e-5f)
    }

    @Test
    fun `transposed tracks produce high similarity`() {
        // Same track in C major (tonic 0) and D major (tonic 2) — the content
        // is identical, just pitch-shifted. BPM, energy and structure all match
        // exactly; only the key chroma rotates. Cosine similarity of the full
        // vector should still be high because the non-key features are
        // identical and the Temperley profiles for adjacent keys overlap.
        val original = scan(key = MusicalKey(0, MusicalMode.MAJOR, 1f))
        val transposed = scan(key = MusicalKey(2, MusicalMode.MAJOR, 1f))
        val sim = AcousticSimilarity.similarity(
            AcousticSimilarity.featureVector(original),
            AcousticSimilarity.featureVector(transposed),
        )
        assertTrue(
            sim > 0.85f,
            "transposed tracks should have high similarity, got $sim",
        )
    }

    @Test
    fun `a fifth-up transposition stays high`() {
        // A fifth (7 semitones) is the most compatible key change on the
        // Camelot wheel. The Temperley profiles overlap heavily at this
        // distance, so the key component stays high and the overall
        // similarity should remain above 0.85.
        val original = scan(key = MusicalKey(0, MusicalMode.MAJOR, 1f))
        val fifthUp = scan(key = MusicalKey(7, MusicalMode.MAJOR, 1f))
        val sim = AcousticSimilarity.similarity(
            AcousticSimilarity.featureVector(original),
            AcousticSimilarity.featureVector(fifthUp),
        )
        assertTrue(sim > 0.85f, "fifth-up transposition should be highly similar, got $sim")
    }

    @Test
    fun `unrelated tracks produce low similarity`() {
        // Different BPM, different key, different energy profile, different
        // spectral tilt — these are musically unrelated tracks.
        val clubTrack = scan(
            bpm = 128f,
            key = MusicalKey(0, MusicalMode.MAJOR, 1f),
            sectionEnergies = listOf(0.3f, 0.9f, 0.5f, 0.9f, 0.4f),
            tilt = 0.8f,  // bass-heavy
        )
        val ballad = scan(
            bpm = 72f,
            key = MusicalKey(6, MusicalMode.MINOR, 1f),  // F# minor — far from C major
            sectionEnergies = listOf(0.2f, 0.3f, 0.25f),
            tilt = -0.8f,  // bright
        )
        val sim = AcousticSimilarity.similarity(
            AcousticSimilarity.featureVector(clubTrack),
            AcousticSimilarity.featureVector(ballad),
        )
        assertTrue(
            sim < 0.7f,
            "unrelated tracks should have low similarity, got $sim",
        )
    }

    @Test
    fun `empty candidates returns empty`() {
        val target = scan()
        val result = AcousticSimilarity.mostSimilar(target, emptyMap(), limit = 5)
        assertTrue(result.isEmpty(), "empty candidates should return empty list")
    }

    @Test
    fun `zero limit returns empty`() {
        val target = scan()
        val candidates = mapOf("a" to scan())
        val result = AcousticSimilarity.mostSimilar(target, candidates, limit = 0)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `mostSimilar returns IDs ordered by descending similarity`() {
        val target = scan(bpm = 120f, key = MusicalKey(0, MusicalMode.MAJOR, 1f))
        val close = scan(bpm = 122f, key = MusicalKey(0, MusicalMode.MAJOR, 1f))
        val far = scan(bpm = 72f, key = MusicalKey(6, MusicalMode.MINOR, 1f), tilt = -0.8f)

        val candidates = mapOf(
            "far" to far,
            "close" to close,
        )
        val result = AcousticSimilarity.mostSimilar(target, candidates, limit = 2)
        assertEquals(2, result.size)
        assertEquals("close", result[0], "most similar should come first")
        assertEquals("far", result[1])
    }

    @Test
    fun `mostSimilar respects limit`() {
        val target = scan()
        val candidates = (1..10).associate { i -> "track_$i" to scan(bpm = 120f + i) }
        val result = AcousticSimilarity.mostSimilar(target, candidates, limit = 3)
        assertEquals(3, result.size)
    }

    @Test
    fun `null key in both scans does not break similarity`() {
        // A pre-v2 scan has no key. The chroma bins are zero, so the vector
        // relies on the other four features. Two such scans should still
        // compare without error and produce a reasonable result.
        val a = scan(key = null)
        val b = scan(key = null, bpm = 121f)
        val sim = AcousticSimilarity.similarity(
            AcousticSimilarity.featureVector(a),
            AcousticSimilarity.featureVector(b),
        )
        assertTrue(sim > 0.9f, "two null-key scans with near-identical features should be very similar, got $sim")
    }

    @Test
    fun `feature vector has the expected dimension`() {
        val vec = AcousticSimilarity.featureVector(scan())
        assertEquals(AcousticSimilarity.VECTOR_SIZE, vec.size)
    }

    @Test
    fun `zero-magnitude vectors produce zero similarity`() {
        // An all-zero vector: no key, no sections, no intensity, BPM at the
        // floor so it normalises to 0.
        val empty = TrackScan(
            durationS = 0f,
            bpm = 40f,
            confidence = 0f,
            beats = FloatArray(0),
            accents = FloatArray(0),
            downbeat = 0,
            sections = emptyList(),
            intensity = null,
            key = null,
        )
        val vec = AcousticSimilarity.featureVector(empty)
        val sim = AcousticSimilarity.similarity(vec, vec)
        assertEquals(0f, sim, 1e-6f, "all-zero vectors should produce zero similarity")
    }

    @Test
    fun `different vector sizes produce zero similarity`() {
        val a = FloatArray(16)
        val b = FloatArray(8)
        assertEquals(0f, AcousticSimilarity.similarity(a, b))
    }
}