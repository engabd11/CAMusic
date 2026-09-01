package com.engabd.sendpin.audio

import kotlin.test.Test
import kotlin.test.assertEquals

class TransitionBridgeTest {

    private fun track(bpm: Float = 0f, key: MusicalKey? = null, energy: Float = 0.5f) =
        TransitionBridge.Track(bpm = bpm, key = key, energy = energy)

    private val cMajor = MusicalKey(0, MusicalMode.MAJOR, 0.9f)
    private val gMajor = MusicalKey(7, MusicalMode.MAJOR, 0.9f)  // compatible with C
    private val cSharpMajor = MusicalKey(1, MusicalMode.MAJOR, 0.9f)  // incompatible with C

    @Test
    fun `no bridge at start of set`() {
        assertEquals(
            TransitionBridge.BridgeType.NONE,
            TransitionBridge.bridge(null, track(bpm = 120f, key = cMajor)),
        )
    }

    @Test
    fun `no bridge when keys mix`() {
        assertEquals(
            TransitionBridge.BridgeType.NONE,
            TransitionBridge.bridge(track(bpm = 120f, key = cMajor), track(bpm = 120f, key = gMajor)),
        )
    }

    @Test
    fun `pad bridge for incompatible keys`() {
        assertEquals(
            TransitionBridge.BridgeType.PAD,
            TransitionBridge.bridge(track(bpm = 120f, key = cMajor), track(bpm = 120f, key = cSharpMajor)),
        )
    }

    @Test
    fun `swell bridge for large tempo jump`() {
        assertEquals(
            TransitionBridge.BridgeType.SWELL,
            TransitionBridge.bridge(track(bpm = 100f), track(bpm = 140f)),
        )
    }

    @Test
    fun `no swell for half-time match`() {
        // 90 and 180 BPM are a half/double match, no bridge needed
        assertEquals(
            TransitionBridge.BridgeType.NONE,
            TransitionBridge.bridge(track(bpm = 90f), track(bpm = 180f)),
        )
    }

    @Test
    fun `thunderclap for large energy drop`() {
        assertEquals(
            TransitionBridge.BridgeType.THUNDERCLAP,
            TransitionBridge.bridge(track(energy = 0.9f), track(energy = 0.3f)),
        )
    }

    @Test
    fun `no bridge for small energy change`() {
        assertEquals(
            TransitionBridge.BridgeType.NONE,
            TransitionBridge.bridge(track(energy = 0.6f), track(energy = 0.5f)),
        )
    }

    @Test
    fun `energy cliff takes priority over key incompatibility`() {
        // Both an energy cliff and incompatible keys: thunderclap wins
        assertEquals(
            TransitionBridge.BridgeType.THUNDERCLAP,
            TransitionBridge.bridge(
                track(bpm = 120f, key = cMajor, energy = 0.9f),
                track(bpm = 120f, key = cSharpMajor, energy = 0.2f),
            ),
        )
    }

    @Test
    fun `no bridge when keys are null`() {
        assertEquals(
            TransitionBridge.BridgeType.NONE,
            TransitionBridge.bridge(track(bpm = 120f), track(bpm = 120f)),
        )
    }

    @Test
    fun `no bridge for low-confidence keys`() {
        val uncertain = MusicalKey(1, MusicalMode.MAJOR, 0.2f)
        assertEquals(
            TransitionBridge.BridgeType.NONE,
            TransitionBridge.bridge(track(bpm = 120f, key = cMajor), track(bpm = 120f, key = uncertain)),
        )
    }

    @Test
    fun `durations are positive for non-NONE types`() {
        for (type in TransitionBridge.BridgeType.entries) {
            if (type != TransitionBridge.BridgeType.NONE) {
                assert(TransitionBridge.durationS(type) > 0f)
            } else {
                assertEquals(0f, TransitionBridge.durationS(type))
            }
        }
    }
}