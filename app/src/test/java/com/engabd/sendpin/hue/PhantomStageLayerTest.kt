package com.engabd.sendpin.hue

import com.engabd.sendpin.audio.AnalysisFrame
import com.engabd.sendpin.audio.SectionStems
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Do the five instrument groups land on distinct, stable lamps when there
 * are enough of them, does a cluster room correctly get nothing at all, and
 * does an onset actually brighten the lamp it's supposed to.
 */
class PhantomStageLayerTest {

    private fun contextOf(
        positions: Map<Int, Vec3>,
        topology: RoomTopology,
        frame: AnalysisFrame = AnalysisFrame(),
        brightness: Float = 1f,
        stems: SectionStems? = null,
    ) = LayerContext(
        frame = frame,
        structure = null,
        scan = null,
        positions = positions,
        topology = topology,
        trackPositionS = -1f,
        dt = 0.02f,
        brightness = brightness,
        stems = stems,
    )

    private val black: Rgb = Triple(0f, 0f, 0f)

    @Test
    fun `each instrument gets a distinct channel when there are at least five, well-separated lamps`() {
        // Placed exactly at each instrument's own target region, so the
        // assignment is unambiguous by construction — each lamp is trivially
        // the nearest one to its own matching region.
        val positions = mapOf(
            1 to Vec3(0.10f, 0.5f, 0.10f), // BASS
            2 to Vec3(0.5f, 0.5f, 0.55f), // DRUMS
            3 to Vec3(0.9f, 0.5f, 0.4f), // GUITAR
            4 to Vec3(0.5f, 0.3f, 0.5f), // VOCALS
            5 to Vec3(0.5f, 0.5f, 0.9f), // SYNTHS
        )
        val base = positions.keys.associateWith { black }
        val out = PhantomStageLayer().apply(base, contextOf(positions, RoomTopology.FIELD))
        // Five different target regions, five different fixed hues — with no
        // beat active, each output is just that instrument's persistent glow,
        // so five distinct colours means five distinct assignments.
        assertEquals(5, out.values.toSet().size, "every lamp should carry a different instrument's colour")
    }

    @Test
    fun `a lone quiet channel still ends up assigned to something`() {
        val positions = mapOf(1 to Vec3(0.10f, 0.5f, 0.10f))
        val base = mapOf(1 to black)
        val out = PhantomStageLayer().apply(base, contextOf(positions, RoomTopology.FIELD))
        assertTrue(out.getValue(1) != black, "the one lamp should get some persistent glow")
    }

    @Test
    fun `a cluster topology is a no-op`() {
        val positions = mapOf(1 to Vec3(0.5f, 0.5f, 0.5f), 2 to Vec3(0.51f, 0.5f, 0.5f))
        val base = mapOf(1 to Triple(0.4f, 0.4f, 0.4f) as Rgb, 2 to Triple(0.1f, 0.2f, 0.3f) as Rgb)
        val out = PhantomStageLayer().apply(base, contextOf(positions, RoomTopology.CLUSTER))
        assertEquals(base, out)
    }

    @Test
    fun `an empty room is a no-op`() {
        val base = mapOf(1 to black)
        val out = PhantomStageLayer().apply(base, contextOf(emptyMap(), RoomTopology.FIELD))
        assertEquals(base, out)
    }

    @Test
    fun `a bass onset measurably brightens the bass-assigned lamp`() {
        // The only lamp, sitting exactly at BASS's target region, so BASS —
        // first in claim order — is guaranteed to get it.
        val positions = mapOf(1 to Vec3(0.10f, 0.5f, 0.10f))
        val base = mapOf(1 to black)
        val layer = PhantomStageLayer()

        val quiet = layer.apply(base, contextOf(positions, RoomTopology.FIELD, AnalysisFrame(bassBeat = false)))
        val hit = layer.apply(
            base,
            contextOf(positions, RoomTopology.FIELD, AnalysisFrame(bassBeat = true, bassStrength = 1.5f)),
        )

        fun brightness(rgb: Rgb) = maxOf(rgb.first, rgb.second, rgb.third)
        assertTrue(
            brightness(hit.getValue(1)) > brightness(quiet.getValue(1)),
            "a bass onset should brighten the bass-assigned lamp: ${quiet.getValue(1)} -> ${hit.getValue(1)}",
        )
    }

    @Test
    fun `the glow scales with the user's brightness ceiling instead of ignoring it`() {
        val positions = mapOf(1 to Vec3(0.10f, 0.5f, 0.10f))
        val base = mapOf(1 to black)
        val hit = AnalysisFrame(bassBeat = true, bassStrength = 1.5f)

        fun brightness(rgb: Rgb) = maxOf(rgb.first, rgb.second, rgb.third)
        val full = brightness(
            PhantomStageLayer().apply(base, contextOf(positions, RoomTopology.FIELD, hit, brightness = 1f))
                .getValue(1),
        )
        val dimmed = brightness(
            PhantomStageLayer().apply(base, contextOf(positions, RoomTopology.FIELD, hit, brightness = 0.2f))
                .getValue(1),
        )

        assertTrue(dimmed < full, "the same onset at a 20% ceiling should be dimmer than at 100%: $dimmed vs $full")
        assertTrue(dimmed <= 0.2f + 1e-5f, "nothing may sit above the ceiling the user set, was $dimmed")
    }

    @Test
    fun `a zero ceiling leaves the room dark rather than glowing`() {
        val positions = mapOf(1 to Vec3(0.10f, 0.5f, 0.10f))
        val base = mapOf(1 to black)
        val out = PhantomStageLayer().apply(
            base,
            contextOf(positions, RoomTopology.FIELD, AnalysisFrame(bassBeat = true, bassStrength = 1.5f), 0f),
        )
        assertEquals(black, out.getValue(1), "at a zero ceiling the layer must add no light at all")
    }

    // ── Real stems ────────────────────────────────────────────────────────

    private fun brightness(rgb: Rgb) = maxOf(rgb.first, rgb.second, rgb.third)

    @Test
    fun `real vocal stem energy brightens the vocals lamp without a mid-band frame signal`() {
        // Two lamps, exactly at BASS's and VOCALS' own target regions — with
        // only one lamp, BASS (first in ASSIGNMENT_ORDER) would claim it
        // regardless of distance, same as the "lone quiet channel" test above,
        // so this needs a second lamp to actually pin channel 2 to VOCALS.
        val positions = mapOf(
            1 to Vec3(0.10f, 0.5f, 0.10f), // BASS
            2 to Vec3(0.5f, 0.3f, 0.5f), // VOCALS
        )
        val base = mapOf(1 to black, 2 to black)
        val layer = PhantomStageLayer()

        // The frame carries no "mid" band at all, so the frequency-band proxy
        // this would otherwise fall back to reads as silent. Real stems must be
        // what is actually driving the lamp here, not a coincidence.
        val noStems = layer.apply(base, contextOf(positions, RoomTopology.FIELD))
        val withStems = layer.apply(
            base,
            contextOf(positions, RoomTopology.FIELD, stems = SectionStems(vocals = 1f, stereoWidth = 0f, bass = 0f)),
        )

        assertTrue(
            brightness(withStems.getValue(2)) > brightness(noStems.getValue(2)),
            "real vocal stem energy should brighten the vocals lamp: " +
                "${noStems.getValue(2)} -> ${withStems.getValue(2)}",
        )
    }

    @Test
    fun `real bass stem energy gives the bass lamp a held level, not just onset flashes`() {
        // No bass onset this frame — the existing flash mechanism has nothing to
        // say. A held stem level is the only thing that could brighten this lamp.
        val positions = mapOf(1 to Vec3(0.10f, 0.5f, 0.10f)) // BASS's own target region
        val base = mapOf(1 to black)
        val layer = PhantomStageLayer()

        val noStems = layer.apply(base, contextOf(positions, RoomTopology.FIELD, AnalysisFrame(bassBeat = false)))
        val withStems = layer.apply(
            base,
            contextOf(
                positions, RoomTopology.FIELD, AnalysisFrame(bassBeat = false),
                stems = SectionStems(vocals = 0f, stereoWidth = 0f, bass = 1f),
            ),
        )

        assertTrue(
            brightness(withStems.getValue(1)) > brightness(noStems.getValue(1)),
            "a held bass stem level should brighten the bass lamp even with no onset this frame",
        )
    }

    @Test
    fun `with no stems the layer behaves exactly as the frequency-band proxy always did`() {
        // A regression guard: LayerContext.stems defaulting to null (an
        // unscanned track, or the setting off) must reproduce today's proxy
        // behaviour byte for byte, not something merely similar to it.
        val positions = mapOf(1 to Vec3(0.5f, 0.3f, 0.5f))
        val base = mapOf(1 to black)
        val frame = AnalysisFrame(bands = mapOf("mid" to 0.6f))

        val explicitNull = PhantomStageLayer().apply(base, contextOf(positions, RoomTopology.FIELD, frame, stems = null))
        val defaulted = PhantomStageLayer().apply(base, contextOf(positions, RoomTopology.FIELD, frame))
        assertEquals(defaulted, explicitNull)
    }
}
