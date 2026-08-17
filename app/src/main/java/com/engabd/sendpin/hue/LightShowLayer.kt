package com.engabd.sendpin.hue

import com.engabd.sendpin.audio.AnalysisFrame
import com.engabd.sendpin.audio.StructureState
import com.engabd.sendpin.audio.TrackScan

/**
 * An additive post-processing stage on top of [SyncoEngine]'s output.
 *
 * Four creative light-show features (Music DNA, Emotional Arc, Phantom Stage,
 * Phone Conductor — see `docs/creative-light-shows.md`) all share this shape:
 * take what the engine already rendered, nudge it, hand it on. None of them
 * replace the base show, and none of them need to know `SyncoEngine`'s
 * internals — they read the same [Map] shape [FieldSafety] and
 * [EffectRateLimiter] already process, so a layer composes with the existing
 * pipeline for free.
 *
 * `apply` must be a pure function of `base` and `context` — no I/O, no
 * blocking — because it runs on [DirectLightSync]'s 60 Hz render loop. A
 * layer that needs external state (Phone Conductor's sensors) reads it into
 * a snapshot before calling in, not from inside `apply`.
 */
interface LightShowLayer {
    val id: String
    fun apply(base: Map<Int, Rgb>, context: LayerContext): Map<Int, Rgb>
}

/**
 * Everything a layer might need, gathered once per render frame by
 * [DirectLightSync].
 *
 * Deliberately narrow. `palette`/`brightness` are not here: every layer in
 * this file works by nudging the HSV of whatever [SyncoEngine] already
 * rendered, not by reaching into its internal [Palette]/`ModeParams` — so
 * those fields would be dead weight on every layer that doesn't touch
 * colour selection directly. Device motion is not here either — only
 * [PhoneConductorLayer] needs it, and it has its own lifecycle (sensor
 * registration tied to a setting and to whether a stream is even running),
 * not per-frame render data, so it lives as that layer's own private state.
 */
data class LayerContext(
    /** The live analysis frame this render step is driving from. */
    val frame: AnalysisFrame,
    /** The live structure classification, or null before one exists. */
    val structure: StructureState?,
    /** The adopted pre-scan for the current track, or null before one lands. */
    val scan: TrackScan?,
    /** Normalised room-cube position of every channel. Empty on a bare area. */
    val positions: Map<Int, Vec3>,
    /** What shape the lamps are in — see [RoomTopology]. */
    val topology: RoomTopology,
    /** Seconds into the current track, or -1f when the position is unknown. */
    val trackPositionS: Float,
    /** Elapsed time since the previous frame, for any layer that integrates. */
    val dt: Float,
)

/**
 * Folds every enabled [LightShowLayer] over the engine's output, in order.
 *
 * A separate class rather than a bare `fold` at the call site so ordering
 * and composition are testable independent of any one layer's own logic.
 */
class LayerChain(private val layers: List<LightShowLayer>) {
    fun apply(base: Map<Int, Rgb>, context: LayerContext): Map<Int, Rgb> =
        layers.fold(base) { acc, layer -> layer.apply(acc, context) }

    companion object {
        val EMPTY = LayerChain(emptyList())
    }
}
