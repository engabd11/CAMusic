package com.engabd.sendpin.audio

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class SongPhase { STEADY, BUILDING, DROP, BREAKDOWN }

/**
 * Where the track is in its own arc. Ported from syncoV2 `audio/structure.py`.
 */
data class StructureState(
    val phase: SongPhase = SongPhase.STEADY,
    /** Tension through a build, 0..1. */
    val buildProgress: Float = 0f,
    /** True on the single frame a drop lands. */
    val dropNow: Boolean = false,
    /** A drop is expected very soon. */
    val dropImminent: Boolean = false,
    /**
     * Seconds until a *scheduled* drop, known from an offline analysis of the
     * track. Negative means unknown — the live heuristic only sets the boolean.
     * Pre-drop choreography deepens as this counts down so the pull-down lands
     * exactly against the boundary.
     */
    val dropEtaS: Float = -1f,
    val breakdown: Boolean = false,
    /**
     * The current section's loudness relative to the track's peak, 1.0 when
     * unknown, so choreography can save its full range for the chorus. Filled
     * from an offline scan when one is available.
     */
    val sectionLevel: Float = 1f,
)

/**
 * Musical structure: builds, drops and breakdowns.
 *
 * What separates "reacts to volume" from "feels the music" is responding to the
 * *arc* of a track rather than the instantaneous level. This watches slow
 * envelopes of loudness and spectral brightness and classifies the moment:
 *
 * - **building** — a riser: brightness and energy climbing together, often with
 *   the bass thinning and snare rolls accelerating. [StructureState.buildProgress]
 *   ramps 0→1.
 * - **drop** — the release after a build; a sharp broadband surge as the bass
 *   slams back in. Fires [StructureState.dropNow] once, then holds a refractory.
 * - **breakdown** — energy collapses well below the running average.
 * - **steady** — everything else.
 *
 * Entirely streaming: scalar EMAs and a 2.5 s trend ring, no whole-track
 * knowledge required. [update] takes an optional lookahead slice so a scan-ahead
 * source can later make [StructureState.dropImminent] genuinely predictive;
 * until then a build that has nearly maxed out drives it heuristically.
 *
 * Not thread-safe; owned by whichever thread drives the analyzer.
 */
class StructureTracker(private val framePeriod: Float) {

    private var eFast = 0f
    private var eSlow = 0f
    private var eLong = 0f
    private var cSlow = 0f

    private val win = max(8, (2.5f / framePeriod).roundToInt())
    private val eHist = FloatArray(win)
    private val cHist = FloatArray(win)
    private var histCount = 0
    private var histHead = 0

    private var build = 0f
    private var dropRefractory = 0f
    private var started = false

    fun update(frame: AnalysisFrame, lookahead: List<AnalysisFrame> = emptyList()): StructureState {
        val dt = framePeriod
        val energy = frame.energy
        val centroid = frame.centroid
        val bass = max(frame.bands["sub_bass"] ?: 0f, frame.bands["bass"] ?: 0f)

        if (!started) {
            eFast = energy; eSlow = energy; eLong = energy
            cSlow = centroid
            started = true
        }

        eFast += alpha(0.20f, dt) * (energy - eFast)
        eSlow += alpha(1.5f, dt) * (energy - eSlow)
        eLong += alpha(6.0f, dt) * (energy - eLong)
        cSlow += alpha(1.5f, dt) * (centroid - cSlow)

        // Trend against the oldest entry in the ~2.5 s window.
        val oldestE = if (histCount > 0) eHist[oldestIndex()] else eSlow
        val oldestC = if (histCount > 0) cHist[oldestIndex()] else cSlow
        val eTrend = eSlow - oldestE
        val cTrend = cSlow - oldestC
        pushHist(eSlow, cSlow)

        if (dropRefractory > 0f) dropRefractory = max(0f, dropRefractory - dt)

        // Build tension: brightness and energy trending up together.
        val buildScore = min(1f, max(0f, 2.2f * max(0f, cTrend) + 1.6f * max(0f, eTrend)))
        // Ease up while tension persists, decay when it fades.
        val rate = if (buildScore > build) alpha(0.6f, dt) else alpha(2.0f, dt)
        build += rate * (buildScore - build)

        // Drop: a sharp broadband surge after a build, bass slamming in.
        val surge = eFast - eSlow
        var dropNow = false
        if (dropRefractory <= 0f && build > 0.40f && surge > 0.22f && bass > 0.5f) {
            dropNow = true
            dropRefractory = 1.5f
            build = 0f  // tension released
        }

        val breakdown = eLong > 0.15f && eSlow < 0.45f * eLong

        var dropImminent = build > 0.60f && dropRefractory <= 0f
        if (lookahead.isNotEmpty()) dropImminent = dropImminent || scanLookahead(lookahead)

        val phase = when {
            dropNow -> SongPhase.DROP
            breakdown -> SongPhase.BREAKDOWN
            build > 0.35f -> SongPhase.BUILDING
            else -> SongPhase.STEADY
        }

        return StructureState(
            phase = phase,
            buildProgress = build,
            dropNow = dropNow,
            dropImminent = dropImminent,
            breakdown = breakdown,
        )
    }

    fun reset() {
        started = false
        histCount = 0
        histHead = 0
        java.util.Arrays.fill(eHist, 0f)
        java.util.Arrays.fill(cHist, 0f)
        build = 0f
        dropRefractory = 0f
    }

    private fun oldestIndex(): Int = if (histCount < win) 0 else histHead

    private fun pushHist(e: Float, c: Float) {
        eHist[histHead] = e
        cHist[histHead] = c
        histHead = (histHead + 1) % win
        if (histCount < win) histCount++
    }

    /** True if the upcoming frames contain a clear energy discontinuity. */
    private fun scanLookahead(lookahead: List<AnalysisFrame>): Boolean {
        if (lookahead.size < 3) return false
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (f in lookahead) {
            if (f.energy < lo) lo = f.energy
            if (f.energy > hi) hi = f.energy
        }
        return (hi - lo) > 0.3f && lookahead.last().energy > lookahead.first().energy
    }

    private companion object {
        /** EMA smoothing factor for a given time constant. */
        fun alpha(tauS: Float, dt: Float): Float = 1f - exp(-dt / max(1e-6f, tauS))
    }
}
