package com.engabd.sendpin.hue

import com.engabd.sendpin.audio.AnalysisFrame
import kotlin.math.exp

/**
 * Layer 3 — Phantom Stage: instrument groups at fixed physical positions in
 * the room for the session. See `docs/creative-light-shows.md`.
 *
 * There is no instrument-separation signal anywhere in the analyser — no
 * "this is a guitar" boolean exists or could exist from a single mixed-down
 * stream. The five groups below are a frequency-band proxy, the same idea
 * [SpatialWaves.HEIGHT_BANDS] already uses: bass energy maps to [BASS],
 * broadband transients to [DRUMS], mid-band transients to [GUITAR], sustained
 * mid-band level to [VOCALS], and sustained top-melbank level to [SYNTHS].
 * "Vocals" in practice means "midrange energy that holds rather than
 * attacks," which includes vocals but is not exclusive to them.
 */
enum class StageInstrument { BASS, DRUMS, GUITAR, VOCALS, SYNTHS }

class PhantomStageLayer : LightShowLayer {
    override val id = "phantom_stage"

    private var cachedPositions: Map<Int, Vec3>? = null
    private var channelInstrument: Map<Int, StageInstrument> = emptyMap()
    private val flash = HashMap<StageInstrument, Float>()

    override fun reset() {
        // Decaying flashes only. The stage layout deliberately survives: it is
        // fixed for the *session*, not the track — the bassist stays in the same
        // corner all night, which is the whole point of a phantom stage.
        flash.clear()
    }

    override fun apply(base: Map<Int, Rgb>, context: LayerContext): Map<Int, Rgb> {
        // A cluster has no meaningful spatial layout to fix positions
        // against — same precedent [RoomTopology.CLUSTER] already carries
        // for room gestures: two lamps on a shelf cannot be "the bassist's
        // corner" any more than they can be "around the room".
        if (context.topology == RoomTopology.CLUSTER || context.positions.isEmpty()) return base

        if (context.positions !== cachedPositions) {
            cachedPositions = context.positions
            channelInstrument = assignPositions(context.positions)
        }
        if (channelInstrument.isEmpty()) return base

        decayFlashes(context.dt)
        triggerFlashes(context.frame)

        // This layer's glow is *additive*, so unlike the engine's own output it
        // has never been through [SyncoEngine.brightness]. Scaling the level by
        // the ceiling is what keeps "the bassist's corner" proportional to the
        // rest of the show instead of drowning it: at a 20% setting an unscaled
        // 0.12 base glow is most of the visible field on its own.
        val ceiling = context.brightness.coerceIn(0f, 1f)

        return base.mapValues { (id, rgb) ->
            val inst = channelInstrument[id] ?: return@mapValues rgb
            val level = (
                PERSISTENT_GLOW +
                    sustainedGlow(inst, context.frame) +
                    (flash[inst] ?: 0f)
                ).coerceIn(0f, 1f) * ceiling
            val (addR, addG, addB) = hsvToRgb(HUE.getValue(inst), GLOW_SATURATION, level)
            val (r, g, b) = rgb
            Triple(
                (r + addR).coerceIn(0f, ceiling),
                (g + addG).coerceIn(0f, ceiling),
                (b + addB).coerceIn(0f, ceiling),
            )
        }
    }

    /**
     * One lamp per instrument, nearest [TARGET_REGION] first, claiming
     * distinct lamps while any remain unclaimed. Stable for the session:
     * called only when [Vec3] positions actually change identity, which
     * happens once per stream, not per frame — the bassist stays in the
     * same corner all night.
     *
     * With fewer than five channels, later instruments in [ASSIGNMENT_ORDER]
     * simply get no lamp of their own — claim order is priority order, and
     * once every lamp is taken there is nothing left to hand out. Falling
     * back to *re-claiming* an already-assigned lamp would let a
     * low-priority instrument silently steal it out from under an earlier
     * one (last write to the same channel id wins), which is worse than a
     * quiet instrument having no dedicated glow this session.
     */
    private fun assignPositions(positions: Map<Int, Vec3>): Map<Int, StageInstrument> {
        val claimed = HashSet<Int>()
        val out = HashMap<Int, StageInstrument>()
        for (inst in ASSIGNMENT_ORDER) {
            val target = TARGET_REGION.getValue(inst)
            val unclaimed = positions.filterKeys { it !in claimed }
            if (unclaimed.isEmpty()) continue
            val nearest = unclaimed.minByOrNull { (_, pos) -> distance(pos, target) } ?: continue
            claimed += nearest.key
            out[nearest.key] = inst
        }
        return out
    }

    private fun triggerFlashes(frame: AnalysisFrame) {
        if (frame.bassBeat) bump(StageInstrument.BASS, frame.bassStrength)
        if (frame.beat) bump(StageInstrument.DRUMS, frame.beatStrength)
        if (frame.midBeat) bump(StageInstrument.GUITAR, frame.midStrength)
    }

    private fun bump(inst: StageInstrument, strength: Float) {
        val level = (strength / FLASH_STRENGTH_NORM).coerceIn(0f, 1f)
        flash[inst] = maxOf(flash[inst] ?: 0f, level)
    }

    private fun decayFlashes(dt: Float) {
        val decay = exp(-dt / FLASH_DECAY_S)
        for (inst in StageInstrument.entries) {
            val level = (flash[inst] ?: 0f) * decay
            flash[inst] = if (level < 0.01f) 0f else level
        }
    }

    /** Held rather than onset-gated: [VOCALS] and [SYNTHS] have no onset boolean of their own. */
    private fun sustainedGlow(inst: StageInstrument, frame: AnalysisFrame): Float = when (inst) {
        StageInstrument.VOCALS -> (frame.bands["mid"] ?: 0f) * VOCALS_GLOW_GAIN
        StageInstrument.SYNTHS -> topMelbankLevel(frame) * SYNTHS_GLOW_GAIN
        else -> 0f
    }

    private fun topMelbankLevel(frame: AnalysisFrame): Float {
        if (frame.melbank.isEmpty()) return 0f
        val (lo, hi) = melbankWindow(1f, frame.melbank.size)
        var sum = 0f
        for (i in lo until hi) sum += frame.melbank[i]
        return sum / (hi - lo)
    }

    private companion object {
        /** Claim order when channels are scarce — roughly perceptual importance. */
        private val ASSIGNMENT_ORDER = listOf(
            StageInstrument.BASS, StageInstrument.VOCALS, StageInstrument.DRUMS,
            StageInstrument.GUITAR, StageInstrument.SYNTHS,
        )

        /** Target normalised room-cube position per instrument. Not measured — a starting layout to retune by ear. */
        private val TARGET_REGION = mapOf(
            StageInstrument.BASS to Vec3(0.10f, 0.5f, 0.10f),
            StageInstrument.DRUMS to Vec3(0.5f, 0.5f, 0.55f),
            StageInstrument.GUITAR to Vec3(0.9f, 0.5f, 0.4f),
            StageInstrument.VOCALS to Vec3(0.5f, 0.3f, 0.5f),
            StageInstrument.SYNTHS to Vec3(0.5f, 0.5f, 0.9f),
        )

        /** One fixed hue per instrument, evenly spread. */
        private val HUE = mapOf(
            StageInstrument.BASS to 0.00f,
            StageInstrument.DRUMS to 0.20f,
            StageInstrument.GUITAR to 0.40f,
            StageInstrument.VOCALS to 0.60f,
            StageInstrument.SYNTHS to 0.80f,
        )

        private const val GLOW_SATURATION = 0.9f
        private const val PERSISTENT_GLOW = 0.12f
        private const val VOCALS_GLOW_GAIN = 0.35f
        private const val SYNTHS_GLOW_GAIN = 0.35f
        private const val FLASH_STRENGTH_NORM = 1.5f
        private const val FLASH_DECAY_S = 0.25f
    }
}
