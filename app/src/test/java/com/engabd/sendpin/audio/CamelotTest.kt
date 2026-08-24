package com.engabd.sendpin.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CamelotTest {

    private fun key(tonic: Int, mode: MusicalMode) = MusicalKey(tonic, mode, confidence = 1f)

    @Test
    fun `wheel numbers match the standard Camelot chart`() {
        // C major = 8B, A minor = 8A, G major = 9B, E minor = 9A, D major = 10B.
        assertEquals(8, Camelot.wheelNumber(0, MusicalMode.MAJOR))
        assertEquals(8, Camelot.wheelNumber(9, MusicalMode.MINOR))
        assertEquals(9, Camelot.wheelNumber(7, MusicalMode.MAJOR))
        assertEquals(9, Camelot.wheelNumber(4, MusicalMode.MINOR))
        assertEquals(10, Camelot.wheelNumber(2, MusicalMode.MAJOR))
    }

    @Test
    fun `same key is compatible`() {
        assertTrue(Camelot.compatible(key(0, MusicalMode.MAJOR), key(0, MusicalMode.MAJOR)))
    }

    @Test
    fun `adjacent wheel position, same mode, is compatible`() {
        // C major (8B) and G major (9B) are one step apart.
        assertTrue(Camelot.compatible(key(0, MusicalMode.MAJOR), key(7, MusicalMode.MAJOR)))
    }

    @Test
    fun `relative major and minor are compatible`() {
        // C major (8B) and A minor (8A) share a wheel position.
        assertTrue(Camelot.compatible(key(0, MusicalMode.MAJOR), key(9, MusicalMode.MINOR)))
    }

    @Test
    fun `two wheel steps apart is not compatible`() {
        // C major (8B) and D major (10B) are two steps apart.
        assertFalse(Camelot.compatible(key(0, MusicalMode.MAJOR), key(2, MusicalMode.MAJOR)))
    }

    @Test
    fun `same tonic, different mode, is not automatically compatible`() {
        // C major is 8B; C minor's relative major is D# major, so C minor is 5A —
        // sharing a tonic name is not the same as sharing a wheel position.
        assertFalse(Camelot.compatible(key(0, MusicalMode.MAJOR), key(0, MusicalMode.MINOR)))
    }

    @Test
    fun `identical bpm matches`() {
        assertTrue(Camelot.bpmMatch(120f, 120f))
    }

    @Test
    fun `bpm within tolerance matches`() {
        assertTrue(Camelot.bpmMatch(120f, 126f)) // +5%
        assertFalse(Camelot.bpmMatch(120f, 132f)) // +10%
    }

    @Test
    fun `half and double time match`() {
        assertTrue(Camelot.bpmMatch(90f, 180f))
        assertTrue(Camelot.bpmMatch(180f, 90f))
    }

    @Test
    fun `zero or negative bpm never matches`() {
        assertFalse(Camelot.bpmMatch(0f, 120f))
        assertFalse(Camelot.bpmMatch(120f, 0f))
    }
}
