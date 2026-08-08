package com.engabd.sendpin.hue

import com.engabd.sendpin.audio.AnalysisFrame
import java.util.Random
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

// Shell shape and life.
private const val EXPAND = 0.9f          // shell expansion speed, xrank units per second
private const val SHELL_WIDTH = 0.34f    // thickness of the expanding ring
private const val MAX_BURSTS = 10        // concurrent bursts; more just washes out
private const val BURST_LIFE = 2.0f      // cull after this long, fully faded by then
private const val HEIGHT_WEIGHT = 0.7f   // how much lamp height counts against left/right

/**
 * Fireworks' *own* onset sensitivity, deliberately not the rung's
 * `beatThreshold` — which is 99 on Subtle and Extreme, so bursts would never
 * fire there. Bass launches on kicks, mid on snare and guitar onsets.
 */
private const val BASS_THRESH = 0.55f
private const val MID_THRESH = 0.75f

/** Launch a gentle burst after this long without one, so quiet passages sparkle. */
private const val AUTO_LAUNCH_S = 1.6f

/**
 * Palette spread used to colour a burst by its epicentre, so it takes the same
 * colour those lamps carry in the music renderer — coherent rather than random.
 */
private const val COLOUR_SPREAD = 0.7f

/**
 * Per-rung feel: burst gain, resting glow, and fade time constant. Gentler rungs
 * get smaller bursts over a higher resting glow with a longer, softer trail;
 * punchy rungs get bigger, brighter bursts over a darker base and a snappier
 * trail.
 */
private val RUNG_FEEL = mapOf(
    SyncMode.SUBTLE to Triple(0.95f, 0.22f, 0.72f),
    SyncMode.MEDIUM to Triple(1.05f, 0.14f, 0.60f),
    SyncMode.HIGH to Triple(1.15f, 0.10f, 0.52f),
    SyncMode.INTENSE to Triple(1.30f, 0.07f, 0.46f),
    SyncMode.EXTREME to Triple(1.45f, 0.05f, 0.40f),
)
private val FEEL_DEFAULT = Triple(1.10f, 0.12f, 0.55f)

/** One live firework: an epicentre, a colour, a strength and an age. */
private class Burst(
    val ex: Float,       // epicentre xrank, 0..1
    val ez: Float,       // epicentre height, 0..1
    val color: Rgb,      // normalised, max channel 1
    val strength: Float,
    var age: Float = 0f,
)

/**
 * Fireworks: spatial bursts ignite from the music and expand outward.
 *
 * On a kick or a snare/guitar onset a burst launches from a point in the room —
 * bass low and left, mid higher and right — and expands as a bright shell of the
 * palette colour for that region before fading, like a shell blooming and
 * trailing off. Between bursts a soft palette glow breathes with the music so
 * the room is never black.
 *
 * Fireworks carries its own onset sensitivity, independent of the intensity
 * ladder's `beatThreshold` — which the calm rungs set deliberately out of reach —
 * so it stays lively on Subtle and slams on Extreme. The chosen rung tunes the
 * *feel* through [RUNG_FEEL] rather than whether anything happens at all.
 *
 * Unlike the music renderer this owns its own per-burst state and decay, so it
 * is deliberately not run through the engine's brightness smoothing: the snap
 * and expand is the whole point.
 *
 * Ported from syncoV2 `effects/fireworks.py`. Until now `SyncEffect.FIREWORKS`
 * was a selectable enum value with no implementation behind it — `effect` was
 * written to the engine and never read.
 */
class FireworksEffect(seed: Long? = null) {

    private val bursts = ArrayList<Burst>(MAX_BURSTS)
    private var sinceLaunch = 0f
    private val rng = if (seed != null) Random(seed) else Random()

    fun reset() {
        bursts.clear()
        sinceLaunch = 0f
    }

    /** Final per-channel RGB, already master-brightness scaled. */
    fun render(engine: SyncoEngine, frame: AnalysisFrame, dt: Float, p: ModeParams): Map<Int, Rgb> {
        val lamps = engine.lampLayout
        if (lamps.isEmpty()) return emptyMap()
        val (burstGain, glowFeel, tau) = RUNG_FEEL[engine.mode] ?: FEEL_DEFAULT

        for (b in bursts) b.age += dt
        bursts.removeAll { it.age >= BURST_LIFE }

        // The rung's event gates still apply, so narrowband tonal onsets stay
        // muted and burst size follows absolute loudness — a quiet pluck is
        // small, a drop slams.
        sinceLaunch += dt
        val (ampScale, widthGate) = engine.gatesFor(frame)
        var launched = false
        val bassGated = frame.bassStrength * widthGate
        if (frame.bassBeat && bassGated >= BASS_THRESH) {
            launch(engine, bass = true, strength = bassGated * ampScale)
            launched = true
        }
        val midGated = frame.midStrength * widthGate
        if (frame.midBeat && midGated >= MID_THRESH) {
            launch(engine, bass = false, strength = midGated * ampScale)
            launched = true
        }
        if (launched) {
            sinceLaunch = 0f
        } else if (sinceLaunch >= AUTO_LAUNCH_S && engine.energyEnvValue > 0.02f) {
            // Keep quiet passages sparkling, but never burst into a genuinely
            // silent stream — a finished or muted track just fades to the glow.
            sinceLaunch = 0f
            launch(engine, bass = rng.nextBoolean(), strength = 0.8f)
        }

        // Compose: a soft palette afterglow breathing with energy, plus each
        // lamp's strongest expanding shell. The shell keeps its own burst's
        // colour so bursts stay crisp instead of muddying together.
        val mb = engine.brightness
        val glowLevel = glowFeel * (0.4f + 0.6f * engine.energyEnvValue)
        val out = HashMap<Int, Rgb>(lamps.size)
        for ((cid, lamp) in lamps) {
            val x = lamp.first
            val z = lamp.second
            val gc = engine.palette.sample(x * COLOUR_SPREAD + engine.colourPhase)
            val gm = max(gc.first, max(gc.second, gc.third)).takeIf { it > 1e-6f } ?: 1f
            var r = gc.first / gm * glowLevel
            var g = gc.second / gm * glowLevel
            var bl = gc.third / gm * glowLevel

            var best = 0f
            var bcol: Rgb? = null
            for (burst in bursts) {
                val d = hypot(x - burst.ex, HEIGHT_WEIGHT * (z - burst.ez))
                val radius = EXPAND * burst.age
                val shell = (d - radius) / SHELL_WIDTH
                val contrib = burst.strength * exp(-shell * shell) * exp(-burst.age / tau)
                if (contrib > best) { best = contrib; bcol = burst.color }
            }
            if (bcol != null && best > 0f) {
                val bb = min(1f, best * burstGain)
                r = max(r, bcol.first * bb)
                g = max(g, bcol.second * bb)
                bl = max(bl, bcol.third * bb)
            }
            out[cid] = Triple(min(1f, r) * mb, min(1f, g) * mb, min(1f, bl) * mb)
        }
        return out
    }

    /**
     * Ignite at a real lamp in this band's region — bass on the low and left
     * lamps, mid on the higher and right ones — so the burst always lands on a
     * light, bright at t=0, and then expands to its neighbours.
     */
    private fun launch(engine: SyncoEngine, bass: Boolean, strength: Float) {
        if (bursts.size >= MAX_BURSTS) bursts.removeAt(0)  // drop the oldest
        val lamps = engine.lampLayout
        var cands = lamps.entries.filter { if (bass) it.value.first <= 0.5f else it.value.first >= 0.5f }
        if (cands.isEmpty()) cands = lamps.entries.toList()
        val pick = cands[rng.nextInt(cands.size)]
        val ex = pick.value.first
        val ez = pick.value.second
        val col = engine.palette.sample(ex * COLOUR_SPREAD + engine.colourPhase)
        val m = max(col.first, max(col.second, col.third))
        val norm = if (m <= 1e-6f) Triple(1f, 1f, 1f)
        else Triple(col.first / m, col.second / m, col.third / m)
        bursts.add(Burst(ex, ez, norm, strength.coerceIn(0.35f, 1.4f)))
    }
}
