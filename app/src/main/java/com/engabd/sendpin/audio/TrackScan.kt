package com.engabd.sendpin.audio

import com.engabd.sendpin.hue.PICK_MOOD_TEMPO_W
import com.engabd.sendpin.hue.PICK_MOOD_TILT_W
import kotlin.math.max
import kotlin.math.min

/**
 * What an offline scan of one track knows about it.
 *
 * The live tap has to *learn* each song as it plays: the PLL spends about six
 * seconds finding the beat, the intensity picker twenty warming up, and neither
 * can ever know what is coming. A scan closes that gap — it is the one thing
 * standing between the direct path and syncoV2's show, because syncoV2 reads a
 * precomputed analysis and schedules against it.
 *
 * Deliberately *not* a port of syncoV2's `TrackMap`. That carries per-frame
 * playback features (bands, melbank, pan — some 400 KB a track) so a player it
 * cannot tap can have its frames replayed at it. We are the player: the frames
 * come from the tap, live and free. What cannot be had live is what is here —
 * the grid, the arc, and the two absolute measures a per-track AGC divides out.
 *
 * Times are track seconds. Everything is immutable once built.
 */
data class TrackScan(
    val durationS: Float,
    val bpm: Float,
    /** 0..1 quality of the offline grid. See `TrackAnalysis.gridConfidence`. */
    val confidence: Float,
    /** Beat timestamps, ascending, seconds. */
    val beats: FloatArray,
    /** Onset strength at each beat, 0..1, parallel to [beats]. */
    val accents: FloatArray,
    /** Index into [beats] of the first bar start, `0 until beatsPerBar`. */
    val downbeat: Int,
    val sections: List<ScanSection>,
    val intensity: IntensityProfile?,
    /**
     * Per-melbank-bin absolute loudness, each bin's reference relative to the
     * loudest bin's. Handed to the engine as [AnalysisFrame.melbankRef]; see
     * that field for why no live estimate can produce it.
     */
    val melbankRef: FloatArray = FloatArray(0),
    /**
     * Which build of the analyser produced this.
     *
     * Separate from the *file* format on purpose. The format says whether these bytes
     * can be read; this says whether they are still the best answer available. Improving
     * the analyser used to leave every stored scan silently worse than a fresh one, with
     * no way to tell them apart and nothing short of "Delete analyses" to do about it —
     * and that throws away the good ones too. See [ANALYSER_VERSION].
     */
    val analyserVersion: Int = ANALYSER_VERSION,
    /**
     * How much of the track was actually analysed, in seconds.
     *
     * Usually [durationS]. It is less on a track past [TrackScanner.MAX_TRACK_S], where
     * the decode stops and the grid simply ends — the show falls back to working it out
     * live from there. That used to be invisible: a half-analysed DJ set presented
     * itself as analysed, and the moment the lights started guessing looked like a bug.
     */
    val analysedS: Float = durationS,
    /**
     * The track's musical key, from a key-profile correlation over the
     * tuning-corrected whole-track chroma accumulated during the scan. See
     * [KeyDetection]. Null on a scan from before key detection existed
     * (analyser version 1) — [outdated] is what flags those for re-analysis,
     * not a guess here, and it flags version-2 scans too: their key came from a
     * chroma that could not resolve a semitone across its own bottom octave.
     */
    val key: MusicalKey? = null,
    /**
     * How many beats there are to a bar — 4 for almost everything, 3 for a waltz.
     *
     * Was an assumption rather than a measurement until analyser version 3. The
     * cost of assuming was not subtle: [downbeat] is a phase within this number,
     * so on anything in 3 the "downbeat" landed on a different beat of each bar
     * in turn, and every bar-synced effect in [SyncoEngine] — the phrase counter,
     * the downbeat pulse — walked round the bar instead of sitting on its one.
     *
     * Defaults to 4 for a scan written before it was measured, which is both the
     * right guess and what those scans were already doing.
     */
    val beatsPerBar: Int = 4,
    /**
     * The track's tuning offset from A440, in cents, as measured during the scan.
     *
     * Not used by the show — [key] already has the correction applied — but kept
     * because it is the one number that says *why* a key read came out the way it
     * did. A rip transferred at the wrong speed, a 432 Hz master or a live
     * recording of a piano nobody tuned all land here as a non-zero value, and
     * without it a surprising key looks like a bug in the detector rather than a
     * property of the file.
     */
    val tuningCents: Float = 0f,
) {
    /** The scan covers the whole track, rather than stopping at the analysis cap. */
    val complete: Boolean get() = analysedS >= durationS - 1f

    /** A newer analyser has since been shipped, so re-reading this track would improve it. */
    val outdated: Boolean get() = analyserVersion < ANALYSER_VERSION

    /**
     * The grid is worth scheduling the show against.
     *
     * Below this the scan is still useful — sections, the intensity profile and
     * the melbank reference are all independent of the grid — it just does not
     * claim to know where the beats are, and the causal tracker keeps the floor.
     */
    val gridUsable: Boolean get() = confidence >= MIN_GRID_CONFIDENCE && beats.size >= 8

    /**
     * The authoritative beat grid at track position [posS].
     *
     * [prevPosS] (the previous query) is what makes [BeatGrid.predictedBeat] fire
     * exactly once per beat as the clock sweeps past it. Null outside the
     * analysed span and for a grid that did not earn [gridUsable].
     */
    fun gridAt(posS: Float, prevPosS: Float?): BeatGrid? {
        if (!gridUsable || beats.size < 2) return null
        if (posS < beats.first() - 4f || posS > beats.last() + 4f) return null

        val i = upperBound(beats, posS)  // beats[i-1] <= pos < beats[i]
        val prevB: Float
        val nextB: Float
        val idxPrev: Int
        when {
            i <= 0 -> {
                val period = beats[1] - beats[0]
                prevB = beats[0] - period
                nextB = beats[0]
                idxPrev = -1
            }
            i >= beats.size -> {
                val period = beats[beats.size - 1] - beats[beats.size - 2]
                prevB = beats.last()
                nextB = prevB + period
                idxPrev = beats.size - 1
            }
            else -> {
                prevB = beats[i - 1]
                nextB = beats[i]
                idxPrev = i - 1
            }
        }
        val period = max(1e-3f, nextB - prevB)
        val phase = ((posS - prevB) / period).coerceIn(0f, 1f)
        val crossed = prevPosS != null && prevPosS < posS && upperBound(beats, prevPosS) != i
        val bar = beatsPerBar.coerceAtLeast(1)
        val beatInBar = Math.floorMod(idxPrev - downbeat, bar)
        // The accent of the beat about to land (anticipatory waves rise into it)
        // and of the one that just did (at-beat flashes size to it). Exact per
        // beat, which is the whole point of having seen the track already.
        val accentNext = accentAt(idxPrev + 1)
        val accentNow = accentAt(idxPrev)
        return BeatGrid(
            bpm = 60f / period,
            confidence = confidence,
            locked = true,
            periodS = period,
            phase = phase,
            timeToNextBeat = max(0f, nextB - posS),
            nextBeatT = nextB,
            barPhase = (beatInBar + phase) / bar,
            predictedBeat = crossed,
            accent = accentNext,
            accentNow = accentNow,
            beatInBar = beatInBar,
            beatsPerBar = bar,
            // An offline grid is authoritative, so scheduled pulses drive at
            // full strength. The causal tracker ramps this with its lock quality
            // precisely because it might be wrong; this one is not guessing.
            scheduleStrength = 1f,
        )
    }

    fun sectionAt(posS: Float): ScanSection? = sections.firstOrNull { posS >= it.startS && posS < it.endS }

    /** `(seconds until, that section's energy)` of the next boundary, if any. */
    fun nextBoundary(posS: Float): Pair<Float, Float>? =
        sections.firstOrNull { it.startS > posS }?.let { (it.startS - posS) to it.energy }

    /**
     * The lag-free section-intensity signal at [posS], sampled a touch ahead.
     *
     * The lookahead is not a rounding detail: the picker's rung change drives a
     * brief render ease-in, and sampling early is what makes that peak land *on*
     * the section change rather than a beat after it.
     */
    fun intensitySignalAt(posS: Float): Float? = intensity?.sampleAt(posS + PROFILE_LOOKAHEAD_S)

    private fun accentAt(index: Int): Float {
        if (accents.isEmpty()) return 1f
        return accents[index.coerceIn(0, accents.size - 1)]
    }

    // FloatArray in a data class: identity equals/hashCode would be wrong and
    // surprising, and both are cheap enough at these sizes to do properly.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrackScan) return false
        return durationS == other.durationS && bpm == other.bpm &&
            confidence == other.confidence && downbeat == other.downbeat &&
            beats.contentEquals(other.beats) && accents.contentEquals(other.accents) &&
            sections == other.sections && intensity == other.intensity &&
            melbankRef.contentEquals(other.melbankRef) &&
            analyserVersion == other.analyserVersion && analysedS == other.analysedS &&
            key == other.key && beatsPerBar == other.beatsPerBar &&
            tuningCents == other.tuningCents
    }

    override fun hashCode(): Int {
        var result = durationS.hashCode()
        result = 31 * result + bpm.hashCode()
        result = 31 * result + confidence.hashCode()
        result = 31 * result + beats.contentHashCode()
        result = 31 * result + accents.contentHashCode()
        result = 31 * result + downbeat
        result = 31 * result + sections.hashCode()
        result = 31 * result + (intensity?.hashCode() ?: 0)
        result = 31 * result + melbankRef.contentHashCode()
        result = 31 * result + analyserVersion
        result = 31 * result + analysedS.hashCode()
        result = 31 * result + (key?.hashCode() ?: 0)
        result = 31 * result + beatsPerBar
        result = 31 * result + tuningCents.hashCode()
        return result
    }

    companion object {
        /**
         * What this build's analyser produces, stamped into every scan it writes.
         *
         * Bump it when a change to [TrackAnalysis] or [TrackScanner] would give a
         * *better* answer for the same audio — a retuned grid, a wider analysis window —
         * and not for anything that only changes how a scan is stored or read. Scans
         * below it keep working exactly as they did; they are simply the ones the
         * analysis card can then offer to re-read, so an improvement reaches a library
         * that was analysed months ago without anyone having to delete the lot.
         *
         * 1 — the first version to record its own number, and the first whose scans
         * say how much of the track they cover.
         *
         * 2 — adds [key], a Krumhansl-Kessler musical key read off the whole-track
         * chroma. A version-1 scan simply has no key; this is what flags it for
         * the re-analysis that would give it one.
         *
         * 3 — a rebuilt key path, a measured [beatsPerBar], labelled sections and
         * sub-frame beat times. The key is the big one: version 2 read its chroma
         * off a projection that could not resolve a semitone below ~186 Hz and
         * assumed A440, and measured over real music it agreed with itself on a
         * transposed copy of the same track only 12 % of the time — against a
         * 1-in-12 chance floor. A version-2 scan's key is therefore not merely
         * older, it is close to arbitrary, and re-reading is worth it.
         */
        const val ANALYSER_VERSION = 3

        /**
         * Below this the grid is not served. Matches syncoV2's
         * `trackmap.MIN_MAP_CONFIDENCE`; the constants feeding it are calibrated
         * against the same measurements, so this threshold means the same thing.
         */
        const val MIN_GRID_CONFIDENCE = 0.30f

        /**
         * Sample the intensity curve this far ahead of the playhead. See
         * [intensitySignalAt].
         */
        const val PROFILE_LOOKAHEAD_S = 0.35f

        /** First index whose value is strictly greater than [target]. */
        internal fun upperBound(sorted: FloatArray, target: Float): Int {
            var lo = 0
            var hi = sorted.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (sorted[mid] <= target) lo = mid + 1 else hi = mid
            }
            return lo
        }
    }
}

/**
 * One section of the track — a verse, a chorus, a drop — with how loud it is
 * relative to the track's own peak.
 *
 * [energy] is what lets the show save its range for the chorus instead of
 * spending it on the first loud bar of the intro.
 */
data class ScanSection(
    val startS: Float,
    val endS: Float,
    val energy: Float,
    /**
     * Which *kind* of section this is: sections sharing a label sound alike.
     *
     * Boundaries alone say a track changed at 1:04; they cannot say that what
     * started there is the chorus again. That difference is the whole gap
     * between a show that reacts and a show that recognises — see
     * [MusicDnaLayer], which steps its anchor hue by label precisely so a
     * returning chorus returns to its own colour instead of being handed the
     * next hue along.
     *
     * Assigned in first-appearance order, so 0 is whatever the track opens
     * with. Every section carrying its own label means no repetition was
     * found, which is the honest answer for a through-composed track and is
     * what a scan from before labelling existed reads back as.
     */
    val label: Int = 0,
)

/**
 * Per-song Auto-intensity shaping.
 *
 * Two independent things, and keeping them apart is what makes Auto work:
 *
 * - [character] — how hard this song goes on an **absolute** scale comparable
 *   across tracks, which decides the band of the ladder Auto may use for it.
 * - the window ([sigLo], [sigHi], [dynamics], [curve]) — where each moment sits
 *   *within* that band.
 *
 * Without the first, per-track normalisation makes a lofi chorus and an EDM drop
 * look identical, and Auto hands the top rung to everything.
 */
data class IntensityProfile(
    /** ~p10 of the song's section-intensity curve: the window floor. */
    val sigLo: Float,
    /** ~p95 of it: the window ceiling. */
    val sigHi: Float,
    /** True p95−p10 span — how much the song actually moves, 0..1. */
    val dynamics: Float,
    /** Spectral balance: −1 bright, +1 bass-heavy, 0 neutral. */
    val tilt: Float,
    /** BPM mapped 0 (ballad) .. 1 (club). */
    val tempo: Float,
    /**
     * How hard the song goes, absolute 0..1, from the shared
     * `hue.songCharacter` the live picker also uses.
     */
    val character: Float,
    /**
     * The lag-free section-intensity curve, 0..1, sampled at [curveRateHz].
     *
     * Stored decimated rather than per analysis frame: it is a 1.4 s centred
     * moving average, so anything above a few hertz is describing noise it has
     * already smoothed away, and a whole library's worth of these has to fit on
     * a phone.
     */
    val curve: FloatArray,
    val curveRateHz: Float,
) {
    /** Combined spectral+tempo shift of the picker's operating point. */
    val mood: Float get() = PICK_MOOD_TILT_W * tilt + PICK_MOOD_TEMPO_W * (tempo - 0.5f) * 2f

    /** The curve at track position [posS], linearly interpolated. */
    fun sampleAt(posS: Float): Float? {
        if (curve.isEmpty() || curveRateHz <= 0f) return null
        val x = (posS * curveRateHz).coerceAtLeast(0f)
        val i = x.toInt()
        if (i >= curve.size - 1) return curve.last()
        val f = x - i
        return curve[i] + (curve[i + 1] - curve[i]) * f
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IntensityProfile) return false
        return sigLo == other.sigLo && sigHi == other.sigHi && dynamics == other.dynamics &&
            tilt == other.tilt && tempo == other.tempo && character == other.character &&
            curveRateHz == other.curveRateHz && curve.contentEquals(other.curve)
    }

    override fun hashCode(): Int {
        var result = sigLo.hashCode()
        result = 31 * result + sigHi.hashCode()
        result = 31 * result + dynamics.hashCode()
        result = 31 * result + tilt.hashCode()
        result = 31 * result + tempo.hashCode()
        result = 31 * result + character.hashCode()
        result = 31 * result + curve.contentHashCode()
        result = 31 * result + curveRateHz.hashCode()
        return result
    }
}

/**
 * Why a scan did not produce a usable result, for the UI to show instead of a
 * track that silently never improves.
 */
enum class ScanFailure {
    /** Could not open or decode the source — worth retrying. */
    DECODE,
    /** Decoded, but there was under ~4 s of audio to work with. */
    TOO_SHORT,
    /** Decoded, and it is digital silence. */
    SILENT,
    /** Cancelled — a track change, or the user stopped the sweep. */
    CANCELLED,
}

/** Either a scan or the reason there isn't one. */
sealed interface ScanResult {
    data class Ok(val scan: TrackScan) : ScanResult
    data class Failed(val reason: ScanFailure, val detail: String = "") : ScanResult
}

/** Clamp to 0..1 — the unit interval nearly everything here lives in. */
internal fun unitF(x: Float): Float = if (x < 0f) 0f else if (x > 1f) 1f else x

internal fun unitD(x: Double): Double = if (x < 0.0) 0.0 else if (x > 1.0) 1.0 else x

/** numpy's linear-interpolation percentile over a *sorted* copy of [values]. */
internal fun percentile(values: DoubleArray, pct: Double): Double {
    if (values.isEmpty()) return 0.0
    val sorted = values.copyOf()
    sorted.sort()
    if (sorted.size == 1) return sorted[0]
    val pos = (pct / 100.0) * (sorted.size - 1)
    val lo = pos.toInt()
    val hi = min(lo + 1, sorted.size - 1)
    return sorted[lo] + (pos - lo) * (sorted[hi] - sorted[lo])
}
