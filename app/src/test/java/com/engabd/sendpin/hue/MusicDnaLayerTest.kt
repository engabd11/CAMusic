package com.engabd.sendpin.hue

import com.engabd.sendpin.audio.AnalysisFrame
import com.engabd.sendpin.audio.IntensityProfile
import com.engabd.sendpin.audio.MusicalKey
import com.engabd.sendpin.audio.MusicalMode
import com.engabd.sendpin.audio.ScanSection
import com.engabd.sendpin.audio.TrackScan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Does the per-track fingerprint actually vary with the track — a different
 * key reads as a different colour, a new section reads as a new colour —
 * and does the layer stay out of the way entirely before a scan exists.
 */
class MusicDnaLayerTest {

    private fun scanOf(
        bpm: Float = 120f,
        key: MusicalKey? = MusicalKey(tonic = 0, mode = MusicalMode.MAJOR, confidence = 1f),
        sections: List<ScanSection> = listOf(ScanSection(0f, 10f, 0.5f), ScanSection(10f, 20f, 0.5f)),
    ): TrackScan = TrackScan(
        durationS = 20f,
        bpm = bpm,
        confidence = 0.8f,
        beats = FloatArray(0),
        accents = FloatArray(0),
        downbeat = 0,
        sections = sections,
        intensity = IntensityProfile(
            sigLo = 0.2f, sigHi = 0.8f, dynamics = 0.6f, tilt = 0f, tempo = 0.5f, character = 0.5f,
            curve = floatArrayOf(0.7f), curveRateHz = 1f,
        ),
        key = key,
    )

    private fun contextOf(
        scan: TrackScan?,
        posS: Float,
        dt: Float = 0f,
        brightness: Float = 1f,
    ) = LayerContext(
        frame = AnalysisFrame(),
        structure = null,
        scan = scan,
        positions = emptyMap(),
        topology = RoomTopology.CLUSTER,
        trackPositionS = posS,
        dt = dt,
        brightness = brightness,
    )

    /** A saturated red, so a hue shift is visible in the raw RGB output. */
    private val redBase = mapOf(1 to Triple(1f, 0f, 0f))

    @Test
    fun `no scan is a no-op`() {
        val base = mapOf(1 to Triple(0.3f, 0.4f, 0.5f))
        val out = MusicDnaLayer().apply(base, contextOf(scan = null, posS = -1f))
        assertEquals(base, out)
    }

    @Test
    fun `different keys produce different colours for the same input`() {
        val outC = MusicDnaLayer().apply(
            redBase,
            contextOf(scanOf(key = MusicalKey(0, MusicalMode.MAJOR, 1f)), posS = 5f),
        )
        val outG = MusicDnaLayer().apply(
            redBase,
            contextOf(scanOf(key = MusicalKey(7, MusicalMode.MAJOR, 1f)), posS = 5f),
        )
        assertNotEquals(outC.getValue(1), outG.getValue(1))
    }

    @Test
    fun `major and minor at the same tonic still differ`() {
        val major = MusicDnaLayer().apply(
            redBase,
            contextOf(scanOf(key = MusicalKey(0, MusicalMode.MAJOR, 1f)), posS = 5f),
        )
        val minor = MusicDnaLayer().apply(
            redBase,
            contextOf(scanOf(key = MusicalKey(0, MusicalMode.MINOR, 1f)), posS = 5f),
        )
        assertNotEquals(major.getValue(1), minor.getValue(1))
    }

    @Test
    fun `crossing a section boundary changes the applied colour`() {
        val scan = scanOf()
        val layer = MusicDnaLayer()
        // Same layer instance (so the fingerprint cache carries over), same
        // scan, dt = 0 so the tempo-driven drift can't be what moves it —
        // only the section index changes between the two calls.
        val before = layer.apply(redBase, contextOf(scan, posS = 5f))
        val after = layer.apply(redBase, contextOf(scan, posS = 15f))
        assertNotEquals(before.getValue(1), after.getValue(1))
    }

    @Test
    fun `a scan with no key yet still fingerprints the track`() {
        // An analyser-version-1 scan predates key detection. Music DNA
        // should still do something (tempo, sections) rather than no-op.
        val out = MusicDnaLayer().apply(redBase, contextOf(scanOf(key = null), posS = 5f))
        assertNotEquals(redBase.getValue(1), out.getValue(1))
    }

    @Test
    fun `an unsure key anchors the hue less firmly than a clean read`() {
        // Same tonic, same everything — only how much detectKey trusted it
        // differs. A fifth-apart coin flip should not steer the room as hard as
        // an unambiguous read.
        fun hueOf(confidence: Float): Float {
            val out = MusicDnaLayer().apply(
                redBase,
                contextOf(scanOf(key = MusicalKey(4, MusicalMode.MAJOR, confidence)), posS = 5f),
            )
            return rgbToHsv(out.getValue(1))[0]
        }

        // Base is pure red (hue 0) and the anchor is above it, so a stronger
        // pull means a larger hue.
        assertTrue(hueOf(1f) > hueOf(0.1f), "a confident key should pull hue further than an unsure one")
    }

    @Test
    fun `a zero-confidence key pulls exactly as weakly as no key at all`() {
        // The anchor hue still comes from the tonic — a zero-confidence key is
        // still a guess at E, not an absence — but the *weight* behind it must
        // land on the no-key weight rather than falling off a step as
        // confidence approaches zero.
        val out = MusicDnaLayer().apply(
            redBase,
            contextOf(scanOf(key = MusicalKey(4, MusicalMode.MAJOR, 0f)), posS = 5f),
        )
        assertEquals(
            blendHue(current = 0f, target = 4f / 12f, weight = 0.15f),
            rgbToHsv(out.getValue(1))[0],
            1e-4f,
            "at zero confidence the hue should be blended at the no-key weight",
        )
    }

    @Test
    fun `the brightness arc lifts within the ceiling, never above it`() {
        // The arc's top end is a >1x multiplier by design, so a full-value input
        // is exactly the case that used to escape the user's setting.
        val out = MusicDnaLayer().apply(redBase, contextOf(scanOf(), posS = 5f, brightness = 0.4f))
        val v = out.getValue(1).let { maxOf(it.first, it.second, it.third) }
        assertTrue(v <= 0.4f + 1e-5f, "the arc must not lift a lamp above a 40% ceiling, was $v")
    }
}
