package com.engabd.sendpin.hue

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Auto picks a rung from what the music *is*, not from how loud it happens to be.
 *
 * That distinction is the whole design. Every track's energy is normalised to its
 * own peak before the picker sees it, so a lofi chorus and an EDM drop both
 * arrive at 1.0. Judging on loudness alone therefore sends every song to the top
 * of the ladder — which is exactly the complaint Auto exists to fix. Character
 * (how percussive, how constantly active, how fast, how bass-weighted) survives
 * that normalisation and is what decides the ceiling.
 *
 * Ported from syncoV2 `tests/test_auto_intensity_picker.py`.
 */
class AutoIntensityPickerTest {

    private val dt = 1f / 50f
    private val all = SyncMode.entries.toList()

    /** Run [seconds] of a described kind of music and report where Auto settles. */
    private fun settle(
        seconds: Float,
        energy: Float,
        salience: Float,
        bpm: Float,
        beatEvery: Int,
        onsetWidth: Float,
        flux: Float,
        centroid: Float,
        allowed: List<SyncMode> = all,
        picker: AutoIntensityPicker = AutoIntensityPicker(),
    ): SyncMode {
        var out = SyncMode.HIGH
        val frames = (seconds / dt).toInt()
        for (i in 0 until frames) {
            out = picker.update(
                dt = dt,
                energy = energy,
                salience = salience,
                bpm = bpm,
                beat = beatEvery > 0 && i % beatEvery == 0,
                allowed = allowed,
                onsetWidth = onsetWidth,
                centroid = centroid,
                flux = flux,
            )
        }
        return out
    }

    /** A sparse, tonal, slow track — normalised to its own peak like anything else. */
    private fun chill(allowed: List<SyncMode> = all) = settle(
        seconds = 60f, energy = 0.9f, salience = 0.9f, bpm = 82f,
        beatEvery = 37, onsetWidth = 0.05f, flux = 0.04f, centroid = 0.7f,
        allowed = allowed,
    )

    /** Fast, broadband, relentless, bass-heavy. */
    private fun banger(allowed: List<SyncMode> = all) = settle(
        seconds = 60f, energy = 0.9f, salience = 0.9f, bpm = 140f,
        beatEvery = 12, onsetWidth = 0.30f, flux = 0.34f, centroid = 0.25f,
        allowed = allowed,
    )

    @Test
    fun `a chill track never reaches the top even at full loudness`() {
        // Both tracks below are pinned at energy 0.9 and salience 0.9 — as loud
        // as the analyzer can report. Only character separates them.
        val picked = chill()
        assertTrue(
            LADDER_ORDER.indexOf(picked) <= LADDER_ORDER.indexOf(SyncMode.MEDIUM),
            "a sparse tonal track reached $picked at full loudness",
        )
    }

    @Test
    fun `a banger reaches the top of the ladder`() {
        val picked = banger()
        assertTrue(
            LADDER_ORDER.indexOf(picked) >= LADDER_ORDER.indexOf(SyncMode.HIGH),
            "a fast broadband track only reached $picked",
        )
    }

    @Test
    fun `character separates two equally loud tracks`() {
        // The headline property, stated directly.
        assertTrue(
            LADDER_ORDER.indexOf(banger()) > LADDER_ORDER.indexOf(chill()),
            "loudness-identical tracks were not separated by character",
        )
    }

    @Test
    fun `a narrow selection is rescaled, not clipped`() {
        // The enabled set is a palette rather than a range: a heavy track should
        // still reach the top of whatever the user allowed.
        val narrow = listOf(SyncMode.SUBTLE, SyncMode.MEDIUM)
        assertEquals(SyncMode.MEDIUM, banger(narrow), "a banger should top out a narrow selection")
        // …and a chill track should still sit low within the same selection.
        assertEquals(SyncMode.SUBTLE, chill(narrow), "a chill track should stay low in the same selection")
    }

    @Test
    fun `only enabled rungs are ever returned`() {
        for (allowed in listOf(
            listOf(SyncMode.HIGH),
            listOf(SyncMode.SUBTLE, SyncMode.EXTREME),
            listOf(SyncMode.MEDIUM, SyncMode.HIGH, SyncMode.INTENSE),
        )) {
            assertTrue(banger(allowed) in allowed, "picked outside the enabled set")
            assertTrue(chill(allowed) in allowed, "picked outside the enabled set")
        }
    }

    @Test
    fun `an empty selection falls back rather than crashing`() {
        val picked = settle(
            seconds = 10f, energy = 0.6f, salience = 0.6f, bpm = 120f,
            beatEvery = 25, onsetWidth = 0.2f, flux = 0.2f, centroid = 0.4f,
            allowed = emptyList(),
        )
        assertTrue(picked in DEFAULT_AUTO_LEVELS, "empty selection gave $picked")
    }

    @Test
    fun `silence does not climb the ladder`() {
        // A quiet intro or a gap between tracks must not push Auto upward.
        //
        // Note what this does *not* claim: that silence walks the rung back down
        // to Subtle. It does not, and should not — the picker opens mid-ladder
        // from a seeded signal, and the hysteresis dead-band deliberately holds a
        // rung against a dip so a quiet bar cannot drop the room out of the song.
        // Silence is just the extreme of that dip. What matters is that it never
        // pushes upward, and never poisons the character estimate into earning a
        // ceiling it has heard no music to justify.
        val picker = AutoIntensityPicker()
        val opening = picker.update(
            dt = dt, energy = 0f, salience = 0f, bpm = 0f, beat = false, allowed = all,
            onsetWidth = 0f, centroid = 0.5f, flux = 0f,
        )
        var highest = opening
        for (i in 0 until (30f / dt).toInt()) {
            val now = picker.update(
                dt = dt, energy = 0f, salience = 0f, bpm = 0f, beat = false, allowed = all,
                onsetWidth = 0f, centroid = 0.5f, flux = 0f,
            )
            if (LADDER_ORDER.indexOf(now) > LADDER_ORDER.indexOf(highest)) highest = now
        }
        assertEquals(opening, highest, "silence climbed from $opening to $highest")
        assertTrue(
            LADDER_ORDER.indexOf(highest) < LADDER_ORDER.indexOf(SyncMode.INTENSE),
            "silence reached $highest",
        )
    }

    @Test
    fun `switches are not rushed`() {
        // A dwell floor keeps the room from flicking between rungs bar to bar.
        val picker = AutoIntensityPicker()
        settle(
            seconds = 40f, energy = 0.9f, salience = 0.9f, bpm = 140f,
            beatEvery = 12, onsetWidth = 0.30f, flux = 0.34f, centroid = 0.25f,
            picker = picker,
        )
        // Now alternate the input hard every frame and count how often it moves.
        var switches = 0
        var last = picker.level
        for (i in 0 until 500) {  // 10 s
            val hot = i % 2 == 0
            val now = picker.update(
                dt = dt,
                energy = if (hot) 1f else 0.05f,
                salience = if (hot) 1f else 0.05f,
                bpm = 140f,
                beat = hot,
                allowed = all,
                onsetWidth = if (hot) 0.30f else 0.02f,
                centroid = 0.25f,
                flux = if (hot) 0.34f else 0.01f,
            )
            if (now != last) switches++
            last = now
        }
        assertTrue(switches <= 4, "Auto switched $switches times in 10 s of alternating input")
    }

    @Test
    fun `an unknown track opens mid-ladder rather than at the top`() {
        // Before the warm-up has run, character is neutral by design so the
        // opening bars of something unidentified cannot earn a high ceiling.
        val picker = AutoIntensityPicker()
        val first = picker.update(
            dt = dt, energy = 1f, salience = 1f, bpm = 150f, beat = true,
            allowed = all, onsetWidth = 0.3f, centroid = 0.2f, flux = 0.34f,
        )
        assertTrue(first != SyncMode.EXTREME, "first frame jumped straight to Extreme")
    }

    @Test
    fun `reset clears the choice`() {
        val picker = AutoIntensityPicker()
        settle(
            seconds = 30f, energy = 0.9f, salience = 0.9f, bpm = 140f,
            beatEvery = 12, onsetWidth = 0.30f, flux = 0.34f, centroid = 0.25f,
            picker = picker,
        )
        assertTrue(picker.level != null, "precondition: a rung should have been picked")
        picker.reset()
        assertEquals(null, picker.level, "the rung survived reset")
    }

    /**
     * A realistic percussive track: onsets a couple of times a second, and
     * *between* them the onset width collapses, as it does on real material.
     *
     * This is the shape the flat live estimator could not handle. Averaging
     * width over every frame buries the drum hits under the silence between
     * them, and since attack is the heaviest of the four character terms, the
     * whole score — and with it the band's ceiling — comes out too low.
     */
    private fun realisticPercussive(
        allowed: List<SyncMode>,
        seconds: Float = 60f,
        beatEvery: Int = 25,       // ~2 onsets/second at 50 fps
        hitWidth: Float = 0.28f,   // a drum hit is broadband
        betweenWidth: Float = 0.03f,
    ): SyncMode {
        val picker = AutoIntensityPicker()
        var out = SyncMode.SUBTLE
        for (i in 0 until (seconds / dt).toInt()) {
            val onset = i % beatEvery == 0
            out = picker.update(
                dt = dt,
                energy = if (onset) 0.85f else 0.45f,
                salience = 0.8f,
                bpm = 128f,
                beat = onset,
                allowed = allowed,
                // The value only means anything on a frame that has an onset.
                onsetWidth = if (onset) hitWidth else betweenWidth,
                centroid = 0.3f,
                flux = if (onset) 0.34f else 0.06f,
            )
        }
        return out
    }

    @Test
    fun `a percussive track does not sit on the lowest enabled rung`() {
        // The reported failure, reproduced: with the default three rungs the
        // picker used to settle on Subtle for real percussive music and stay
        // there. Measuring attack where an onset actually happened is what
        // lifts the character score back to something the music has earned.
        val picked = realisticPercussive(DEFAULT_AUTO_LEVELS)
        assertTrue(
            picked != SyncMode.SUBTLE,
            "a 128 BPM percussive track settled on the lowest rung",
        )
    }

    @Test
    fun `a percussive track still outranks a tonal one on the same rungs`() {
        // The fix must not simply push everything up — the discrimination is
        // the point. A sustained tonal track has no broadband onsets to sample,
        // so its attack term stays genuinely low.
        val drums = realisticPercussive(DEFAULT_AUTO_LEVELS)
        val pads = realisticPercussive(
            DEFAULT_AUTO_LEVELS, beatEvery = 100, hitWidth = 0.05f, betweenWidth = 0.02f,
        )
        assertTrue(
            LADDER_ORDER.indexOf(drums) > LADDER_ORDER.indexOf(pads),
            "drums ($drums) did not outrank pads ($pads)",
        )
    }

    @Test
    fun `song character is bounded and ordered`() {
        assertEquals(0f, songCharacter(0f, 0f, 0f, 0f))
        assertEquals(1f, songCharacter(1f, 1f, 1f, 1f))
        assertTrue(songCharacter(1f, 1f, 1f, 1f) > songCharacter(0.5f, 0.5f, 0.5f, 0.5f))
        // Out-of-range inputs are clamped rather than blowing the score out.
        assertTrue(songCharacter(5f, 5f, 5f, 5f) <= 1f)
        assertTrue(songCharacter(-5f, -5f, -5f, -5f) >= 0f)
    }

    private companion object {
        val LADDER_ORDER = listOf(
            SyncMode.SUBTLE, SyncMode.MEDIUM, SyncMode.HIGH, SyncMode.INTENSE, SyncMode.EXTREME,
        )
    }
}
