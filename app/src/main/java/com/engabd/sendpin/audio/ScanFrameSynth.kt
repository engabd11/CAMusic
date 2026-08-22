package com.engabd.sendpin.audio

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * An [AnalysisFrame] built from a [TrackScan] and a playhead, for a player whose PCM
 * this phone never sees.
 *
 * ## Why this exists, and what it honestly is
 *
 * When a Music Assistant queue is targeted at a *remote* speaker, no audio is decoded
 * on this phone at all — the tap is fed by nothing, and the light show falls to its
 * idle pattern while the UI cheerfully reports "Reacting to the beat". There is no
 * PCM to be had and there never will be. What there *is* is the same offline analysis
 * syncoV2 drives its whole show from: a beat grid, section boundaries, an intensity
 * curve and an absolute per-band loudness reference.
 *
 * [TrackScan]'s own doc says it is deliberately *not* syncoV2's `TrackMap`, because
 * that carries per-frame playback features "so a player it cannot tap can have its
 * frames replayed at it" — and "we are the player". For a remote speaker we are not,
 * so this is the one case that premise does not cover. The result is therefore a
 * **beat-and-structure show, not a spectrum show**, and the table below says exactly
 * which fields are real and which are modelled. Nothing here pretends to follow a
 * hi-hat it cannot hear.
 *
 * | Field | Source | Honesty |
 * |---|---|---|
 * | `tAudio`, `tempoBpm` | the playhead, `scan.bpm` | exact |
 * | `beat`, `beatStrength` | `scan.gridAt(...)` | exact — the grid *is* the scan |
 * | `energy` | `scan.intensitySignalAt(...)` shaped per beat | section-level truth |
 * | `melbankRef` | `scan.melbankRef` | exact |
 * | `bands`, `melbank`, `flux` | `melbankRef` shaped by the beat envelope | **modelled** |
 * | `bassBeat`/`bassStrength` | the same beat | modelled: scan onsets are broadband |
 * | `midBeat`/`midStrength` | — | **neutral**: nothing to say |
 * | `pan` | — | **must stay empty**; see below |
 * | `chroma` | `scan.key` | per-track constant, honestly derived |
 *
 * `pan` being empty is load-bearing rather than a gap. Its own doc
 * ([AnalysisFrame.pan]) says empty is how callers know not to use it, and that is
 * what makes `SpatialWaves` and `GestureTracker` degrade correctly instead of
 * reacting to a stereo field that was invented here.
 *
 * Pure — no Android, no coroutines, no clock of its own. The driver
 * ([com.engabd.sendpin.hue.ScanFrameSource]) supplies the position and the cadence.
 */
class ScanFrameSynth(private val options: Options = Options()) {

    data class Options(
        /**
         * Shape a spectrum out of [TrackScan.melbankRef] rather than leaving it empty.
         *
         * On: every melbank-driven rung of the show (Extreme's per-lamp brightness in
         * particular) gets per-lamp variation that is at least *track-appropriate* —
         * a bass-heavy record lights its bass lamps harder. Off: those rungs do
         * nothing at all, which reads as half the show missing. Neither is a lie
         * exactly; this is the one that looks like a show.
         */
        val spectrum: Boolean = true,
        /** How fast the per-beat envelope falls away, in nepers per second. */
        val decayPerS: Float = 6f,
    )

    data class Output(
        val frame: AnalysisFrame,
        val grid: BeatGrid?,
        val sectionEnergy: Float,
    )

    private var prevPosS: Float? = null
    private var envelope = 0f

    fun reset() {
        prevPosS = null
        envelope = 0f
    }

    /**
     * Advance to [posS] and build the frame for it.
     *
     * [dt] is the wall-clock step since the last call, used only for the envelope
     * decay — the grid is driven by absolute position, so a dropped tick shifts
     * nothing.
     */
    fun step(scan: TrackScan, posS: Float, dt: Float): Output {
        val previous = prevPosS
        // A jump — a seek, a skip, a fresh track — invalidates the envelope but not
        // the grid, which is absolute.
        if (previous != null && (posS < previous || posS - previous > SEEK_JUMP_S)) envelope = 0f
        val grid = scan.gridAt(posS, previous)
        prevPosS = posS

        envelope = max(envelope * exp(-options.decayPerS * max(0f, dt)), 0f)
        if (grid?.predictedBeat == true) envelope = max(envelope, grid.accentNow.coerceIn(0f, 1f))

        val sectionEnergy = scan.intensitySignalAt(posS)
            ?: scan.sectionAt(posS)?.energy
            ?: NEUTRAL_ENERGY
        // The show's own floor plus what the beat adds: a quiet passage still glows,
        // a chorus beat still hits. Both halves are section-scaled so a verse never
        // reaches a chorus's peak.
        val energy = (sectionEnergy * (BASE_SHARE + BEAT_SHARE * envelope)).coerceIn(0f, 1f)

        val melbank =
            if (!options.spectrum || scan.melbankRef.isEmpty()) FloatArray(0)
            else shapeMelbank(scan.melbankRef, energy)

        return Output(
            frame = AnalysisFrame(
                bands = bandsFrom(melbank, energy),
                energy = energy,
                beat = grid?.predictedBeat == true,
                beatStrength = if (grid?.predictedBeat == true) grid.accentNow.coerceIn(0f, 1f) else 0f,
                // Modelled as the envelope's own slope: it is the only thing here that
                // knows a transient just happened.
                flux = envelope,
                tAudio = posS,
                bassFlux = envelope,
                bassBeat = grid?.predictedBeat == true,
                bassStrength = if (grid?.predictedBeat == true) grid.accentNow.coerceIn(0f, 1f) else 0f,
                // Nothing in a scan separates a snare from a kick, so the mid detector
                // says nothing rather than repeating the bass one.
                midFlux = 0f,
                midBeat = false,
                midStrength = 0f,
                melbank = melbank,
                // Deliberately empty. See the class doc.
                pan = FloatArray(0),
                centroid = centroidOf(scan.melbankRef),
                melbankRef = scan.melbankRef,
                melbankRefLive = FloatArray(0),
                tempoBpm = scan.bpm,
                chroma = chromaOf(scan.key),
            ),
            grid = grid,
            sectionEnergy = sectionEnergy,
        )
    }

    /**
     * The absolute per-band reference, pulsed by the beat envelope.
     *
     * Normalised to its own peak first, because [AnalysisFrame.melbank] is defined as
     * per-bin normalised and the absolute reference is carried separately in
     * `melbankRef` — handing the raw reference over as the melbank would double-count
     * it wherever both are read.
     */
    private fun shapeMelbank(ref: FloatArray, energy: Float): FloatArray {
        val peak = ref.max()
        if (peak <= 0f) return FloatArray(0)
        return FloatArray(ref.size) { i -> min(1f, (ref[i] / peak) * energy) }
    }

    private fun bandsFrom(melbank: FloatArray, energy: Float): Map<String, Float> {
        if (melbank.isEmpty()) return BANDS.keys.associateWith { energy }
        val perBand = LinkedHashMap<String, Float>(BANDS.size)
        val edges = BANDS.keys.toList()
        val perSlice = max(1, melbank.size / edges.size)
        edges.forEachIndexed { index, name ->
            val from = (index * perSlice).coerceAtMost(melbank.lastIndex)
            val to = ((index + 1) * perSlice).coerceAtMost(melbank.size)
            var sum = 0f
            for (i in from until to) sum += melbank[i]
            perBand[name] = if (to > from) sum / (to - from) else energy
        }
        return perBand
    }

    /**
     * Spectral centroid of the track's own reference curve — a per-track constant, and
     * an honest one: it says where this record's weight sits, which is all a scan can
     * know without hearing the moment.
     */
    private fun centroidOf(ref: FloatArray): Float {
        if (ref.isEmpty()) return 0f
        var weighted = 0f
        var total = 0f
        for (i in ref.indices) {
            weighted += i * ref[i]
            total += ref[i]
        }
        return if (total <= 0f) 0f else weighted / total / ref.size
    }

    /**
     * A pitch-class vector for the track's detected key.
     *
     * Not live chroma and not claiming to be — it is the key's own triad, held
     * constant, which is what lets the Song colour scheme sit in the right region of
     * the wheel for this record instead of picking hues at random.
     */
    private fun chromaOf(key: MusicalKey?): FloatArray {
        if (key == null) return FloatArray(0)
        val out = FloatArray(12)
        val root = ((key.tonic % 12) + 12) % 12
        val third = (root + if (key.mode == MusicalMode.MINOR) 3 else 4) % 12
        val fifth = (root + 7) % 12
        out[root] = 1f
        out[third] = 0.7f
        out[fifth] = 0.85f
        return out
    }

    private companion object {
        /** A position step larger than this is a seek, not playback. */
        const val SEEK_JUMP_S = 1.5f

        /** What the section curve contributes with no beat under it. */
        const val BASE_SHARE = 0.45f

        /** What a full-strength beat adds on top. */
        const val BEAT_SHARE = 0.55f

        /** Used when the scan has no intensity profile — a mid-loud song. */
        const val NEUTRAL_ENERGY = 0.55f
    }
}
