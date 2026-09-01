package com.engabd.sendpin.audio

/**
 * Decides whether a gap between two tracks deserves an ambience bridge, and
 * what kind.
 *
 * The Set Builder and the auto-queue already know the tempo, the key and the
 * energy of every scanned track. Between two tracks whose keys or tempos do
 * not mix, a DJ does not simply cut: they insert something beatless, a swell,
 * or a dramatic break. This is the logic for that decision, pure and
 * stateless so it can be tested without a player or a bridge.
 *
 * The bridge itself is an ambience event, played through the existing
 * [com.engabd.sendpin.hue.ambience.AmbienceClipPlayer] and rendered on the
 * light side by [com.engabd.sendpin.hue.DirectLightSync]. This object only
 * decides *what* and *how long*; the caller handles the playback.
 */
object TransitionBridge {

    /** What kind of bridge to insert between two tracks, if any. */
    enum class BridgeType {
        /** No bridge needed; the tracks mix. */
        NONE,
        /** A beatless pad, for incompatible keys. */
        PAD,
        /** A slow swell, for a large tempo jump. */
        SWELL,
        /** A dramatic thunderclap, for a sudden energy drop. */
        THUNDERCLAP,
    }

    /**
     * A track the bridge logic can reason about. The same fields as
     * [SetBuilder.Candidate], kept separate so the bridge does not depend on
     * the Set Builder's existence.
     */
    data class Track(
        val bpm: Float = 0f,
        val key: MusicalKey? = null,
        /** Mean section energy, 0..1. */
        val energy: Float = 0.5f,
    ) {
        companion object {
            fun fromScan(scan: TrackScan): Track = Track(
                bpm = scan.bpm,
                key = scan.key,
                energy = scan.sections
                    .takeIf { it.isNotEmpty() }
                    ?.map { it.energy }
                    ?.average()
                    ?.toFloat()
                    ?.coerceIn(0f, 1f)
                    ?: 0.5f,
            )
        }
    }

    /**
     * What bridge, if any, to insert between [prev] and [next].
     *
     * The decision order is the order a DJ would rank the problems:
     *
     * 1. **Energy cliff.** A drop greater than [ENERGY_CLIFF] is the most
     *    jarring transition and gets the most dramatic bridge: a thunderclap
     *    that covers the gap and gives the room a beat to reset in.
     * 2. **Incompatible keys.** Two tonal tracks whose keys do not mix on
     *    the Camelot wheel get a beatless pad: something that carries the
     *    room from one tonal centre to the other without a key clash.
     * 3. **Tempo cliff.** A BPM difference greater than [TEMPO_CLIFF_PCT]
     *    (accounting for half/double time) gets a swell: a slow rise or
     *    fall that bridges the tempo gap without a jarring jump.
     *
     * If none of these trip, the tracks mix and no bridge is needed.
     *
     * [prev] is null at the start of a set, where there is nothing to bridge
     * from.
     */
    fun bridge(prev: Track?, next: Track): BridgeType {
        if (prev == null) return BridgeType.NONE

        // 1. Energy cliff: the most dramatic mismatch gets the most dramatic bridge.
        val energyDrop = prev.energy - next.energy
        if (energyDrop > ENERGY_CLIFF) return BridgeType.THUNDERCLAP

        // 2. Key incompatibility: two tonal tracks that do not mix.
        val a = prev.key
        val b = next.key
        if (a != null && b != null && a.confidence > 0.3f && b.confidence > 0.3f) {
            if (!Camelot.compatible(a, b)) return BridgeType.PAD
        }

        // 3. Tempo cliff: a BPM jump the ear notices, accounting for half/double.
        if (prev.bpm > 0f && next.bpm > 0f && !Camelot.bpmMatch(prev.bpm, next.bpm, TEMPO_CLIFF_PCT)) {
            return BridgeType.SWELL
        }

        return BridgeType.NONE
    }

    /**
     * How long the bridge should last, in seconds. Pure function of the type.
     */
    fun durationS(type: BridgeType): Float = when (type) {
        BridgeType.NONE -> 0f
        BridgeType.PAD -> 4f
        BridgeType.SWELL -> 3f
        BridgeType.THUNDERCLAP -> 2f
    }

    /**
     * The energy cliff below which a thunderclap bridge is used. A third of
     * the range: below that the drop is a jolt, above it the tracks are
     * close enough that a cut is fine.
     */
    private const val ENERGY_CLIFF = 0.35f

    /**
     * The tempo tolerance beyond which a swell bridge is used. Tighter than
     * Camelot.bpmMatch's default 6%, because a *transition* is not a *mix*:
     * a DJ who is cutting rather than blending will accept a 10% tempo
     * difference, but beyond that the jump is audible.
     */
    private const val TEMPO_CLIFF_PCT = 0.10f
}