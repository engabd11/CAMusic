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

    /**
     * Nudge [base] and return the result.
     *
     * A layer either fills [out] and returns it, or returns [base] unchanged
     * when it has nothing to say this frame. It must not write to [base]: the
     * chain hands the same buffer back and forth, so writing to the input is
     * writing to the previous layer's output while it is still being read.
     *
     * [out] exists so the chain can supply a buffer it already owns. Every layer
     * used to end in `base.mapValues { … }`, which allocates a fresh
     * `LinkedHashMap` per layer per frame — with four layers on and ten channels
     * that is 240 maps a second on the one thread in this app with a hard
     * deadline, before counting what the bodies allocate inside them. It has a
     * default so that a test, which cares about one layer and not about
     * allocation, can still call this with two arguments.
     */
    fun apply(
        base: Map<Int, Rgb>,
        context: LayerContext,
        out: MutableMap<Int, Rgb> = HashMap(),
    ): Map<Int, Rgb>

    /**
     * Drop everything carried between frames — smoothed levels, decaying
     * flashes, accumulated phases, per-track caches.
     *
     * Called for the same reasons [DirectLightSync.onAnalysisReset] drops the
     * tempo and structure trackers' history: a seek or a track change makes it
     * wrong rather than merely stale, and the previous song's build has no
     * business warming the new one. Also called when a layer is switched on, so
     * it starts from where the room actually is rather than from wherever it was
     * when the user last switched it off.
     *
     * A no-op by default, so a stateless layer stays a one-method contract.
     */
    fun reset() {}
}

/**
 * Everything a layer might need, gathered once per render frame by
 * [DirectLightSync].
 *
 * Deliberately narrow. `palette` is not here: every layer works by nudging the
 * HSV of whatever [SyncoEngine] already rendered, not by reaching into its
 * internal [Palette]/`ModeParams`, so it would be dead weight on all four.
 * Device motion is not here either — only [PhoneConductorLayer] needs it, and
 * it has its own lifecycle (sensor registration tied to a setting and to
 * whether a stream is even running), not per-frame render data, so it lives as
 * that layer's own private state.
 *
 * [brightness] *is* here, and has to be: the engine applies it as the very last
 * step of its own render, so a layer that adds or multiplies afterwards is
 * working above a ceiling the user set. See its own note below.
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
    /**
     * The user's brightness ceiling, 0..1 — the same value [SyncoEngine.brightness]
     * already scaled `base` by.
     *
     * A layer must not produce a channel above it. The engine applies the
     * setting as the last thing it does, so anything the chain adds on top
     * (Phantom Stage's glow) or multiplies up (Music DNA's arc, the Conductor's
     * tilt) lands *outside* it, and [FieldSafety] does not catch that — it
     * limits how often the field may flash, not how bright it may sit. At a 20%
     * setting an uncapped additive glow is most of the visible field.
     *
     * Defaults to 1f — no ceiling — so a test fixture only states it when
     * that is what it is testing. The one production caller always passes the
     * live setting.
     */
    val brightness: Float = 1f,
)

/**
 * Folds every enabled [LightShowLayer] over the engine's output, in order.
 *
 * A separate class rather than a bare `fold` at the call site so ordering
 * and composition are testable independent of any one layer's own logic.
 *
 * It owns **two** output maps and alternates between them, so a four-layer chain
 * running at 60 Hz allocates nothing per frame rather than 240 maps a second.
 * That is safe because nothing downstream keeps a reference past the frame:
 * [FieldSafety.process] and [EffectRateLimiter.process] each build their own
 * `HashMap(colors.size)` from what they are given, so by the time the next frame
 * overwrites a buffer, everything that was going to read it has copied out of
 * it. A layer that has nothing to say returns `base` and the chain simply does
 * not swap, which is why the buffers are cleared on write rather than reused
 * in place.
 *
 * **Not thread-safe**, and the render loop is the only caller.
 */
class LayerChain(private val layers: List<LightShowLayer>) {

    private val bufferA = HashMap<Int, Rgb>()
    private val bufferB = HashMap<Int, Rgb>()
    private var useA = true

    fun apply(base: Map<Int, Rgb>, context: LayerContext): Map<Int, Rgb> {
        var current = base
        for (layer in layers) {
            val out = if (useA) bufferA else bufferB
            out.clear()
            val result = layer.apply(current, context, out)
            // Only take the buffer out of rotation if the layer actually used
            // it. A pass-through hands `base` straight back, and swapping then
            // would leave the next layer writing into the map the one after it
            // is about to read.
            if (result === out) useA = !useA
            current = result
        }
        return current
    }

    /** [LightShowLayer.reset] every layer in the chain. */
    fun reset() = layers.forEach { it.reset() }

    companion object {
        val EMPTY = LayerChain(emptyList())
    }
}
