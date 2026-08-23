package com.engabd.sendpin.hue

import com.engabd.sendpin.audio.MusicalMode
import com.engabd.sendpin.audio.TrackScan
import kotlin.math.PI
import kotlin.math.sin

/**
 * Layer 1 — Music DNA: a deterministic visual fingerprint per track.
 *
 * Tempo sets a slow colour drift's speed, key sets its anchor hue, the
 * intensity profile shapes a brightness floor, and the *kind* of section the
 * playhead is in steps the anchor as the track moves through its own
 * structure. Same fingerprint, every time the song plays — see
 * `docs/creative-light-shows.md`.
 *
 * "Every time" is meant literally, and used not to be true. The drift phase was
 * integrated from `dt`, so it depended on when playback started and on whether
 * the track had been seeked; and the anchor stepped by section *index*, so it
 * accumulated down the track — a twelve-section track walked most of the way
 * round the wheel and wrapped onto its own opening colour. Both are now read
 * from the track position and the section's [ScanSection.label], which are
 * properties of the song rather than of this particular play of it.
 *
 * A no-op until [LayerContext.scan] exists: the whole premise is a *known*
 * fingerprint, and approximating one from a track still being learned live
 * would drift as more of it is heard, then jump the moment the real scan
 * lands. Passing `base` through unchanged until then matches how the rest of
 * the direct path already treats "no scan yet" (see [DirectLightSync]'s
 * `activeScan == null` guards).
 */
class MusicDnaLayer : LightShowLayer {
    override val id = "music_dna"

    private var cachedScan: TrackScan? = null
    private var fingerprint: Fingerprint? = null

    /**
     * Where the playhead was last frame, so the section lookup can usually step
     * rather than search. Sections are in ascending order and the playhead
     * nearly always moves forward by a frame, so the answer is nearly always
     * "the same section" or "the next one".
     */
    private var sectionCursor = 0
    private var cursorPosS = -1f

    private val hsv = FloatArray(3)

    override fun reset() {
        // The fingerprint goes with the scan it was computed from: on a track
        // change the next `apply` will be handed a different scan anyway. The
        // drift phase is no longer state at all — it is a function of the track
        // position — which is exactly what makes a seek land where the song says
        // rather than where this object happened to have got to.
        cachedScan = null
        fingerprint = null
        sectionCursor = 0
        cursorPosS = -1f
    }

    override fun apply(
        base: Map<Int, Rgb>,
        context: LayerContext,
        out: MutableMap<Int, Rgb>,
    ): Map<Int, Rgb> {
        val scan = context.scan ?: return base
        if (scan !== cachedScan) {
            cachedScan = scan
            fingerprint = fingerprintOf(scan)
            sectionCursor = 0
            cursorPosS = -1f
        }
        val fp = fingerprint ?: return base

        // Phase from the playhead, not from accumulated dt. Two plays of the
        // same second of the same song are then the same colour, which is what
        // "fingerprint" has to mean to be worth the name.
        val posS = context.trackPositionS
        val drift = if (posS >= 0f) {
            sin(posS * fp.waveHz * 2f * PI.toFloat()) * WAVE_HUE_AMPLITUDE
        } else {
            0f
        }

        val hue = wrap1(fp.baseHue + sectionLabelAt(scan, posS) * HUE_STEP_PER_SECTION + drift)

        val arc = scan.intensitySignalAt(posS) ?: 0.7f
        val level = BRIGHTNESS_MIN + (BRIGHTNESS_MAX - BRIGHTNESS_MIN) * arc.coerceIn(0f, 1f)

        // The arc's top end lifts above 1.0 deliberately — a loud chorus should
        // read louder than the engine alone made it. It must still not lift
        // above what the user asked for: the ceiling is where the engine already
        // left off, so that is the clamp, not 1f.
        val ceiling = context.brightness.coerceIn(0f, 1f)

        for ((id, rgb) in base) {
            rgbToHsvInto(rgb, hsv)
            val blendedHue = blendHue(hsv[0], hue, fp.hueBlendWeight)
            val sat = (hsv[1] * fp.saturationMul).coerceIn(0f, 1f)
            out[id] = hsvToRgb(blendedHue, sat, (hsv[2] * level).coerceIn(0f, ceiling))
        }
        return out
    }

    /**
     * The *label* of the section at [posS] — which kind of section it is, not
     * how many have gone by.
     *
     * Stepping by label is what makes a returning chorus return to its own
     * colour. Stepping by index, which is what this did before sections carried
     * labels, made every section a new hue and walked `HUE_STEP_PER_SECTION` per
     * boundary until it wrapped: on a twelve-section track the outro landed
     * within a few degrees of the intro, having gone the long way round, so the
     * anchor stopped meaning anything about half way through.
     *
     * A scan written before labelling has 0 for every section, which correctly
     * reads as "no repetition found" and leaves the anchor where the key put it.
     */
    private fun sectionLabelAt(scan: TrackScan, posS: Float): Int {
        val sections = scan.sections
        if (posS < 0f || sections.isEmpty()) return 0
        // Walk from wherever the last frame ended up. Backwards only happens on
        // a seek, and starting over then costs one scan of a list of about ten.
        if (posS < cursorPosS) sectionCursor = 0
        cursorPosS = posS
        while (sectionCursor + 1 < sections.size && sections[sectionCursor + 1].startS <= posS) {
            sectionCursor++
        }
        return sections[sectionCursor].label
    }

    internal data class Fingerprint(
        val waveHz: Float,
        val baseHue: Float,
        val hueBlendWeight: Float,
        val saturationMul: Float,
    )

    internal companion object {
        /**
         * Wave cycle rate from tempo: "120 BPM = one cycle per 2s", i.e. a
         * quarter of the beat rate. A slow drift around the anchor hue, not a
         * sweep through the whole wheel — see [WAVE_HUE_AMPLITUDE].
         */
        internal fun fingerprintOf(scan: TrackScan): Fingerprint {
            val waveHz = if (scan.bpm > 0f) scan.bpm / 240f else DEFAULT_WAVE_HZ
            val key = scan.key
            return if (key != null) {
                Fingerprint(
                    waveHz = waveHz,
                    // Chromatic pitch-class wheel, matching the convention
                    // [SongPalette] already established for the Song colour
                    // scheme: adjacent semitones stay adjacent in hue.
                    baseHue = key.tonic / 12f,
                    // Weighted by how sure the detector is. [detectKey] measures
                    // confidence as the gap between the best and second-best
                    // correlation precisely because two keys a fifth apart
                    // scoring alike is this method's standard failure, and a
                    // coin-flip between them has no business anchoring the room
                    // as firmly as a clean read. At zero confidence this lands
                    // exactly on the no-key weight below, so an unresolvable key
                    // degrades into "no key" rather than falling off a step.
                    hueBlendWeight = lerp(
                        HUE_BLEND_WEIGHT_NO_KEY,
                        HUE_BLEND_WEIGHT,
                        key.confidence.coerceIn(0f, 1f),
                    ),
                    saturationMul = if (key.mode == MusicalMode.MINOR) MINOR_SATURATION_MUL else 1f,
                )
            } else {
                // No key yet (an analyser-version-1 scan pending re-analysis, or
                // one the correlation genuinely could not resolve). Still a
                // per-track fingerprint via tempo and structure — just a
                // lighter touch on hue, since there is nothing real behind it.
                Fingerprint(
                    waveHz = waveHz,
                    baseHue = NEUTRAL_HUE,
                    hueBlendWeight = HUE_BLEND_WEIGHT_NO_KEY,
                    saturationMul = 1f,
                )
            }
        }

        private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

        private const val DEFAULT_WAVE_HZ = 0.5f
        private const val NEUTRAL_HUE = 0.58f

        /**
         * How strongly the fingerprint's hue pulls the engine's own colour, at
         * full and at zero key confidence respectively. The floor is not zero:
         * tempo and section structure still make the fingerprint the track's
         * own, and the anchor hue is only a lighter touch when the key behind it
         * is a guess.
         */
        private const val HUE_BLEND_WEIGHT = 0.35f
        private const val HUE_BLEND_WEIGHT_NO_KEY = 0.15f

        private const val MINOR_SATURATION_MUL = 0.85f

        /**
         * Hue step per section *kind*, turns. ~22 degrees.
         *
         * Bounded in practice by how many kinds of section a track has, which is
         * three or four on the corpus this was measured against — so the anchor
         * moves within about a quarter turn of the key's hue and stays
         * recognisably that key's colour, instead of touring the wheel.
         */
        private const val HUE_STEP_PER_SECTION = 0.06f

        /** Bound on the tempo-driven drift around the anchor hue, turns. */
        private const val WAVE_HUE_AMPLITUDE = 0.04f

        /** Brightness-arc clamp band, so this reads as a floor, not a lock. */
        private const val BRIGHTNESS_MIN = 0.85f
        private const val BRIGHTNESS_MAX = 1.15f
    }
}
