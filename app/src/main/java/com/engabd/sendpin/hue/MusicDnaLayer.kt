package com.engabd.sendpin.hue

import com.engabd.sendpin.audio.MusicalMode
import com.engabd.sendpin.audio.TrackScan
import kotlin.math.PI
import kotlin.math.sin

/**
 * Layer 1 — Music DNA: a deterministic visual fingerprint per track.
 *
 * Tempo sets a slow colour drift's speed, key sets its anchor hue, the
 * intensity profile shapes a brightness floor, and section boundaries step
 * the anchor as the track moves through its own structure. Same fingerprint,
 * every time the song plays — see `docs/creative-light-shows.md`.
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
    private var wavePhase = 0f

    override fun apply(base: Map<Int, Rgb>, context: LayerContext): Map<Int, Rgb> {
        val scan = context.scan ?: return base
        if (scan !== cachedScan) {
            cachedScan = scan
            fingerprint = fingerprintOf(scan)
            wavePhase = 0f
        }
        val fp = fingerprint ?: return base

        wavePhase = (wavePhase + context.dt * fp.waveHz) % 1f
        val drift = sin(wavePhase * 2f * PI.toFloat()) * WAVE_HUE_AMPLITUDE

        val sectionIndex = sectionIndexAt(scan, context.trackPositionS)
        val hue = wrap1(fp.baseHue + sectionIndex * HUE_STEP_PER_SECTION + drift)

        val arc = scan.intensitySignalAt(context.trackPositionS) ?: 0.7f
        val level = BRIGHTNESS_MIN + (BRIGHTNESS_MAX - BRIGHTNESS_MIN) * arc.coerceIn(0f, 1f)

        // The arc's top end lifts above 1.0 deliberately — a loud chorus should
        // read louder than the engine alone made it. It must still not lift
        // above what the user asked for: the ceiling is where the engine already
        // left off, so that is the clamp, not 1f.
        val ceiling = context.brightness.coerceIn(0f, 1f)

        return base.mapValues { (_, rgb) ->
            val (h, s, v) = rgbToHsv(rgb)
            val blendedHue = blendHue(h, hue, fp.hueBlendWeight)
            val sat = (s * fp.saturationMul).coerceIn(0f, 1f)
            hsvToRgb(blendedHue, sat, (v * level).coerceIn(0f, ceiling))
        }
    }

    private fun sectionIndexAt(scan: TrackScan, posS: Float): Int {
        if (posS < 0f || scan.sections.isEmpty()) return 0
        return scan.sections.indexOfLast { it.startS <= posS }.coerceAtLeast(0)
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
                    hueBlendWeight = HUE_BLEND_WEIGHT,
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

        private const val DEFAULT_WAVE_HZ = 0.5f
        private const val NEUTRAL_HUE = 0.58f

        /** How strongly the fingerprint's hue pulls the engine's own colour. */
        private const val HUE_BLEND_WEIGHT = 0.35f
        private const val HUE_BLEND_WEIGHT_NO_KEY = 0.15f

        private const val MINOR_SATURATION_MUL = 0.85f

        /** Hue step per section boundary crossed, turns. ~22 degrees. */
        private const val HUE_STEP_PER_SECTION = 0.06f

        /** Bound on the tempo-driven drift around the anchor hue, turns. */
        private const val WAVE_HUE_AMPLITUDE = 0.04f

        /** Brightness-arc clamp band, so this reads as a floor, not a lock. */
        private const val BRIGHTNESS_MIN = 0.85f
        private const val BRIGHTNESS_MAX = 1.15f
    }
}
