package com.engabd.sendpin.hue

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The rungs, ascending. The 0..1 ladder axis below is divided across these.
 */
private val LADDER = listOf(
    SyncMode.SUBTLE, SyncMode.MEDIUM, SyncMode.HIGH, SyncMode.INTENSE, SyncMode.EXTREME,
)

/** What Auto uses when the user has not narrowed the selection. */
val DEFAULT_AUTO_LEVELS = listOf(SyncMode.SUBTLE, SyncMode.MEDIUM, SyncMode.HIGH)

// ── The loudness window ───────────────────────────────────────────────────
private const val SIG_LO_REF = 0.25f  // a quiet intro sits at the bottom
private const val SIG_HI_REF = 0.88f  // a full drop sits at the top

// ── Layer A: the absolute character score ─────────────────────────────────
//
// Weights sum to 1. Attack and busyness carry the most because they measure the
// thing most directly — how percussive and how constantly active the music is.
// Tempo is weighted lower on purpose: a half- or double-time BPM lock is a real
// failure mode and must not move a song a whole band on its own. Spectral tilt
// is lower still, being the noisiest of the four.
//
// There is deliberately no loudness term. It sounds like it belongs, but energy
// is normalised per track, so mean loudness measures sustained-versus-transient
// rather than intensity: a constant drone scores near 1 and a busy drum track,
// spiking between near-silent gaps, scores near 0.2. It ranks real music almost
// exactly backwards.
private const val CHAR_W_TEMPO = 0.20f
private const val CHAR_W_BUSY = 0.32f
private const val CHAR_W_ATTACK = 0.34f
private const val CHAR_W_BASS = 0.14f

/** Onset broadbandness spans roughly this range on real material. */
private const val CHAR_ATTACK_LO = 0.04f
private const val CHAR_ATTACK_HI = 0.30f

/** Mean onset flux that reads as constantly busy. */
private const val CHAR_BUSY_FULL = 0.34f

/**
 * Character assumed before anything is known. Mid-ladder and deliberately shy
 * of the top, so an unidentified track opens around High and never on Extreme.
 */
private const val CHAR_NEUTRAL = 0.50f

private const val CHAR_TAU_S = 12.0f
private const val CHAR_WARMUP_S = 20.0f
private const val CHAR_MIN_ENERGY = 0.15f

/**
 * Layer B: character to the ladder band it earns, linearly interpolated.
 * Deliberately non-linear at the top — only a genuinely heavy track's ceiling
 * reaches Extreme's cell, which is what makes Extreme rare rather than "the
 * loudest 3% of literally every song".
 *
 * The floors rise much more slowly than the ceilings: a heavy track earns a high
 * ceiling but must keep somewhere to *fall* to, or a breakdown has nowhere to go.
 */
private val CHAR_BAND_ANCHORS = arrayOf(
    //        character, floor, ceiling      what it sounds like
    floatArrayOf(0.00f, 0.00f, 0.16f),    // ambient / drone
    floatArrayOf(0.25f, 0.03f, 0.22f),    // lofi / very chill
    floatArrayOf(0.40f, 0.14f, 0.58f),    // soft indie / acoustic
    floatArrayOf(0.60f, 0.30f, 0.86f),    // pop / house
    floatArrayOf(0.75f, 0.34f, 0.99f),    // energetic dance
    floatArrayOf(1.00f, 0.40f, 1.00f),    // EDM / heavy
)

/**
 * Layer C: target share of playtime per rung, ascending. The axis is divided in
 * these proportions rather than evenly, which is what encodes the intended feel —
 * High and Intense carry the music, Extreme is a narrow spike at the very top.
 * Renormalised over whatever subset is enabled, so the relative prominence
 * survives a narrower selection.
 */
private val RUNG_SHARE = floatArrayOf(0.07f, 0.16f, 0.38f, 0.36f, 0.03f)

private const val PICK_HYST_FRAC = 0.35f
private const val PICK_DWELL_S = 3.5f
private const val PICK_BIG_JUMP = 2
private const val PICK_BIG_DWELL_S = 1.0f
private const val PICK_ATTACK = 0.10f
private const val PICK_DECAY = 0.03f
private const val PICK_SLOW_ATTACK = 0.017f
private const val PICK_SLOW_DECAY = 0.006f
private const val PICK_BEAT_FULL = 3.0f
private const val PICK_BPM_LO = 85.0f
private const val PICK_BPM_HI = 150.0f
private const val PICK_DYN_REF = 0.26f
private const val PICK_MOOD_MAX = 0.16f
private const val PICK_PEAK_GAMMA = 1.3f

private fun unit(x: Float): Float = if (x < 0f) 0f else if (x > 1f) 1f else x

/**
 * One 0..1 score for how much of a song this is, comparable *across* songs.
 *
 * Every term survives per-track normalisation, which is the whole point: a lofi
 * track has to score low even though its own chorus normalises to 1.0 exactly
 * like an EDM drop does.
 */
fun songCharacter(tempo: Float, busy: Float, attack: Float, bass: Float): Float = unit(
    CHAR_W_TEMPO * unit(tempo) +
        CHAR_W_BUSY * unit(busy) +
        CHAR_W_ATTACK * unit(attack) +
        CHAR_W_BASS * unit(bass)
)

/** The floor..ceiling of the ladder axis a song of this character earns. */
private fun characterBand(character: Float): Pair<Float, Float> {
    val c = unit(character)
    for (i in 0 until CHAR_BAND_ANCHORS.size - 1) {
        val a = CHAR_BAND_ANCHORS[i]
        val b = CHAR_BAND_ANCHORS[i + 1]
        if (c <= b[0]) {
            val w = if (b[0] <= a[0]) 0f else (c - a[0]) / (b[0] - a[0])
            return (a[1] + (b[1] - a[1]) * w) to (a[2] + (b[2] - a[2]) * w)
        }
    }
    val last = CHAR_BAND_ANCHORS.last()
    return last[1] to last[2]
}

/**
 * Cell widths and interior edges of [rungs] on the ladder axis.
 *
 * Each rung keeps its [RUNG_SHARE], renormalised over the selection — a soft
 * remap rather than a clip, so the top of a narrow selection is still reached by
 * the tracks that earn it.
 */
private fun rungCells(rungs: List<SyncMode>): Pair<FloatArray, FloatArray> {
    val shares = rungs.map { RUNG_SHARE[LADDER.indexOf(it)] }
    val total = shares.sum().takeIf { it > 0f } ?: 1f
    val widths = FloatArray(rungs.size) { shares[it] / total }
    val edges = FloatArray(max(0, rungs.size - 1))
    var acc = 0f
    for (i in 0 until rungs.size - 1) {
        acc += widths[i]
        edges[i] = acc
    }
    return edges to widths
}

/**
 * Blend live features into one 0..1 musical-intensity signal.
 *
 * Loudness is the gate: salience can only *amplify* a loud moment, never
 * manufacture intensity out of a quiet one, so a silent intro cannot trip
 * Intense. Tempo and percussiveness add a modest steady-state lift, so a fast
 * busy track sits a rung above a sparse one at equal loudness.
 */
private fun intensitySignal(energy: Float, salience: Float, tempo: Float, perc: Float): Float {
    val loud = unit(energy)
    val moment = loud * (0.55f + 0.45f * unit(salience))
    return unit(0.68f * moment + 0.16f * unit(tempo) + 0.16f * unit(perc))
}

/**
 * Resolve Auto to a concrete rung from what the music actually is.
 *
 * Three layers. The song's absolute **character** decides the band of the ladder
 * it earns; its own **moment** then moves within that band; and the **enabled
 * set** rescales the axis rather than clipping it, so a heavy track reaches the
 * top of the selection, a chill one stays low in it, and no song is forced to
 * use every rung enabled.
 *
 * The point of the character layer is that a chill track's ceiling is Medium
 * however loud its own chorus gets. Without it, per-track normalisation means
 * every song's biggest moment looks identical and Auto reaches Intense on music
 * that does not call for it.
 *
 * On the direct path there is no offline profile, so character is estimated live
 * over a warm-up window, starting from [CHAR_NEUTRAL] so an unidentified track
 * opens around High and cannot jump to Extreme on its first loud bar.
 *
 * This is purely a *selection*. It never changes how a rung renders.
 *
 * Ported from syncoV2 `effects/modes.py::AutoIntensityPicker`. Not thread-safe;
 * driven from the render loop.
 */
class AutoIntensityPicker {

    private var signal = 0.56f   // mid-range, so the room opens mid-band
    private var slow = 0.56f
    private var beatRate = 0f
    private var charAttack = 0.5f * (CHAR_ATTACK_LO + CHAR_ATTACK_HI)
    private var charBusy = 0.5f * CHAR_BUSY_FULL
    private var charBass = 0.5f
    private var charAge = 0f
    private var since = PICK_DWELL_S

    /** The rung currently emitted, or null before the first update. */
    var level: SyncMode? = null
        private set

    fun reset() {
        signal = 0.56f
        slow = 0.56f
        beatRate = 0f
        charAttack = 0.5f * (CHAR_ATTACK_LO + CHAR_ATTACK_HI)
        charBusy = 0.5f * CHAR_BUSY_FULL
        charBass = 0.5f
        charAge = 0f
        level = null
        since = PICK_DWELL_S
    }

    /** Clear the dwell so the next update may switch at once. */
    fun allowImmediateRepick() {
        since = PICK_DWELL_S
    }

    /**
     * Advance one frame and return the rung Auto should be at now.
     *
     * [character] and [signal] come from an offline profile when one exists; on
     * the live path both are estimated here.
     */
    fun update(
        dt: Float,
        energy: Float,
        salience: Float,
        bpm: Float,
        beat: Boolean,
        allowed: List<SyncMode>,
        onsetWidth: Float = 0.5f,
        centroid: Float = 0.5f,
        flux: Float = CHAR_BUSY_FULL * 0.5f,
        signal: Float? = null,
        character: Float? = null,
        lo: Float = SIG_LO_REF,
        hi: Float = SIG_HI_REF,
        dynamics: Float? = null,
        mood: Float = 0f,
    ): SyncMode {
        // Percussiveness: a leaky integrator on beats per second, which decays
        // through quiet bridges and climbs on a busy groove.
        val tau = 1.5f
        beatRate *= max(0f, 1f - dt / tau)
        if (beat) beatRate += 1f / tau
        val perc = unit(beatRate / PICK_BEAT_FULL)
        val tempo = if (bpm > 0f) unit((bpm - PICK_BPM_LO) / (PICK_BPM_HI - PICK_BPM_LO)) else 0.5f

        val raw = intensitySignal(energy, salience, tempo, perc)
        this.signal += (raw - this.signal) * (if (raw > this.signal) PICK_ATTACK else PICK_DECAY)
        slow += (raw - slow) * (if (raw > slow) PICK_SLOW_ATTACK else PICK_SLOW_DECAY)
        since += dt

        // Character is tracked only while music is actually playing, so a silent
        // intro or a gap between tracks cannot drag the estimate down.
        if (character == null && energy >= CHAR_MIN_ENERGY) {
            val ema = min(1f, dt / CHAR_TAU_S)
            charAttack += (onsetWidth - charAttack) * ema
            charBusy += (flux - charBusy) * ema
            charBass += ((1f - centroid) - charBass) * ema
            charAge += dt
        }
        val char = character?.let { unit(it) } ?: liveCharacter(tempo)

        val sig = when {
            signal != null -> unit(signal)
            dynamics != null -> slow
            else -> this.signal
        }
        val target = resolve(sig, allowed, lo, hi, dynamics, mood, char)

        val current = level
        if (current == null) {
            level = target  // first frame adopts without waiting on the dwell
        } else if (target != current) {
            // A big move — a drop or a breakdown — commits fast so the switch
            // lands on the section; a one-rung nudge keeps the long dwell.
            val gap = abs(LADDER.indexOf(target) - LADDER.indexOf(current))
            val dwell = if (gap >= PICK_BIG_JUMP) PICK_BIG_DWELL_S else PICK_DWELL_S
            if (since >= dwell) {
                level = target
                since = 0f
            }
        }
        return level!!
    }

    /** The live character estimate, eased in from neutral over the warm-up. */
    private fun liveCharacter(tempo: Float): Float {
        val raw = songCharacter(
            tempo = tempo,
            busy = charBusy / CHAR_BUSY_FULL,
            attack = (charAttack - CHAR_ATTACK_LO) / (CHAR_ATTACK_HI - CHAR_ATTACK_LO),
            bass = charBass,
        )
        val w = unit(charAge / CHAR_WARMUP_S)
        return CHAR_NEUTRAL + (raw - CHAR_NEUTRAL) * w
    }

    /** Place this moment on the ladder, then on the enabled set, with hysteresis. */
    private fun resolve(
        sig: Float,
        allowed: List<SyncMode>,
        lo: Float,
        hi: Float,
        dynamics: Float?,
        mood: Float,
        character: Float,
    ): SyncMode {
        val rungs = allowed.filter { it in LADDER }.sortedBy { LADDER.indexOf(it) }
            .ifEmpty { DEFAULT_AUTO_LEVELS }
        val n = rungs.size
        val span = max(1e-3f, hi - lo)
        var p = unit((sig - lo) / span)
        if (dynamics != null) {
            // How much of its band this song has earned: 0 flat, 1 dynamic.
            val w = unit(dynamics / PICK_DYN_REF)
            p = 0.5f + w * (p - 0.5f)
        }
        p = unit(p + mood.coerceIn(-PICK_MOOD_MAX, PICK_MOOD_MAX))
        val (floor, ceiling) = characterBand(character)
        val pos = floor + (ceiling - floor) * p.pow(PICK_PEAK_GAMMA)

        val (edges, widths) = rungCells(rungs)
        var b = rungs.indexOf(level).takeIf { it >= 0 } ?: run {
            var i = 0
            while (i < n - 1 && pos >= edges[i]) i++
            i
        }
        // Dead-band scaled to the cell, so switches stay stable without making a
        // narrow cell unreachable.
        while (b < n - 1 && pos > edges[b] + PICK_HYST_FRAC * min(widths[b], widths[b + 1])) b++
        while (b > 0 && pos < edges[b - 1] - PICK_HYST_FRAC * min(widths[b - 1], widths[b])) b--
        return rungs[b]
    }
}
