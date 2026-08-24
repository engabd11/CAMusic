package com.engabd.sendpin.audio

/**
 * The Camelot wheel — the DJ convention for "which keys mix well together" — built
 * on [MusicalKey] with no new stored data: a key already scanned carries a tonic and
 * a mode, which is everything the wheel position needs.
 *
 * Pure and stateless, for Harmonic DJ mode's auto-queue ranking.
 */
object Camelot {

    /**
     * Wheel position, 1-12, independent of the major/minor letter.
     *
     * Moving up a fifth (+7 semitones) advances the wheel by exactly one position —
     * that adjacency is the whole reason the wheel is useful, so this is the one
     * piece of arithmetic every other function here is built on. A minor key's
     * position is its relative major's: relative major = minor tonic + 3 semitones.
     */
    fun wheelNumber(tonic: Int, mode: MusicalMode): Int {
        val majorTonic = if (mode == MusicalMode.MAJOR) tonic else (tonic + 3).mod(12)
        return (7 * (majorTonic + 1)).mod(12) + 1
    }

    private fun letter(mode: MusicalMode) = if (mode == MusicalMode.MAJOR) 'B' else 'A'

    /**
     * Two keys a DJ would call mixable: the same key, one step around the wheel in
     * either direction with the same mode, or the relative major/minor (same wheel
     * position, the other mode).
     */
    fun compatible(a: MusicalKey, b: MusicalKey): Boolean {
        if (a.tonic == b.tonic && a.mode == b.mode) return true
        val na = wheelNumber(a.tonic, a.mode)
        val nb = wheelNumber(b.tonic, b.mode)
        if (letter(a.mode) == letter(b.mode)) {
            val diff = (na - nb).mod(12)
            if (diff == 0 || diff == 1 || diff == 11) return true
        } else if (na == nb) {
            return true
        }
        return false
    }

    /**
     * Same tempo within [tolerancePct], or exactly half/double time — a DJ mixing
     * a 90 BPM track against a 180 BPM one is routine, not a mismatch.
     */
    fun bpmMatch(a: Float, b: Float, tolerancePct: Float = 0.06f): Boolean {
        if (a <= 0f || b <= 0f) return false
        var ratio = b / a
        if (ratio > 1.5f) ratio /= 2f else if (ratio < 0.67f) ratio *= 2f
        return kotlin.math.abs(ratio - 1f) <= tolerancePct
    }
}
