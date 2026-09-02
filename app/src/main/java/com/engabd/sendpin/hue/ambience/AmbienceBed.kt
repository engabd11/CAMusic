package com.engabd.sendpin.hue.ambience

import com.engabd.sendpin.audio.AnalysisFrame
import com.engabd.sendpin.audio.AudioLead
import kotlin.math.abs

/**
 * The recording an ambience show is reacting to, and the clock it is reacting on.
 *
 * ## Why the sound stopped being generated
 *
 * The synthesised path exists because a recording cannot be told when to thunder. That
 * is true, and it is the wrong problem: nothing needs to tell a recording anything, so
 * long as the *lights* are told what the recording is doing. A real storm recorded by a
 * real microphone is better than any synthesis this app will ever contain, and it
 * already carries every relationship the effect was trying to reconstruct — how far the
 * strike was, where it was, how the rain gathered before it.
 *
 * So the arrow is reversed. The recording plays, [com.engabd.sendpin.audio.AudioAnalysisTap]
 * analyses the very samples the speaker is playing, and the analysis becomes the show's
 * event source. The room no longer runs a storm of its own alongside a recording of a
 * different one; it reacts to the storm that is audible.
 *
 * ## The one clock
 *
 * Everything here is stamped in **media time** — position within the file — because that
 * is the only clock both media can agree on. The tap knows the exact media position of
 * every frame it analyses; the sink knows the media position being heard. An event is
 * stamped where its sound is in the file, the light tick renders at the position being
 * heard, and the two line up by construction rather than by calibration, which is the
 * same argument the scripted path always made, with the recording promoted to cause.
 */

/**
 * What the recording is doing at one instant, in the shape a light script wants.
 *
 * A flattening of [AnalysisFrame] rather than the frame itself, and deliberately: the
 * frame is fourteen fields wide, several of them arrays the analyzer rewrites in place,
 * and none of it can be read safely from the render thread. This is nine floats that
 * are copied once, on the analysis thread, and never touched again.
 */
class BedSample {
    /** Media position this describes, in seconds. */
    @JvmField var tS: Double = 0.0

    /** Broadband level, 0..1. */
    @JvmField var energy: Float = 0f

    /** Band levels, 0..1, as the analyzer's AGC sees them. */
    @JvmField var sub: Float = 0f
    @JvmField var bass: Float = 0f
    @JvmField var lowMid: Float = 0f
    @JvmField var mid: Float = 0f
    @JvmField var high: Float = 0f

    /**
     * Where the sound is in the stereo field, -1 hard left to +1 hard right.
     *
     * Energy-weighted across the melbank, so it follows whatever is loudest rather than
     * averaging a clap on the left against rain on the right into the middle.
     */
    @JvmField var pan: Float = 0f

    /** Spectral centroid, 0..1. High is bright and close; low is dull and far. */
    @JvmField var centroid: Float = 0f

    /**
     * The three numbers a script actually draws with, each 0..1 and each normalised
     * against how this recording has been sounding over the last several seconds.
     *
     * The raw bands above cannot be used for this. The analyzer's per-band AGC has a
     * ~70 second half-life, so the same rain reads near 1.0 before a track's first
     * thunderclap and near 0.15 for a minute after it — a room drawn straight from
     * `high` would dim for a minute every time it thundered. These are measured against
     * a floor and a ceiling that move over seconds instead, which is the horizon a
     * listener actually perceives: not "loud compared to the last minute" but "louder
     * than it was just now".
     */
    /** Everything, together. How much is going on. */
    @JvmField var level: Float = 0f

    /** The bottom end: thunder rolling, a shell's thump, a fire's roar. */
    @JvmField var rumble: Float = 0f

    /** The top end: rain, crackle, spray, sparks. */
    @JvmField var rain: Float = 0f

    fun copyFrom(o: BedSample) {
        tS = o.tS; energy = o.energy
        sub = o.sub; bass = o.bass; lowMid = o.lowMid; mid = o.mid; high = o.high
        pan = o.pan; centroid = o.centroid
        level = o.level; rumble = o.rumble; rain = o.rain
    }

    /** The low end, raw. [rumble] is what a script should normally draw with. */
    val low: Float get() = maxOf(sub, bass)
}

/**
 * The last few seconds of [BedSample]s, written by the analysis thread and read by the
 * 60 Hz light tick.
 *
 * The same lock-free shape as [AmbienceTimeline] and for the same reasons: one writer,
 * one reader, a fixed ring, and a volatile head that publishes each slot after it is
 * filled. Nothing here allocates after construction.
 *
 * Sized to hold about ten seconds at the analyzer's ~50 Hz hop, which is more than any
 * script looks back over and enough that a render thread descheduled for a moment still
 * finds the sample it wants.
 */
class AmbienceBedTrack(capacity: Int = 512) {

    init {
        require(capacity > 0 && capacity and (capacity - 1) == 0) {
            "capacity must be a power of two, was $capacity"
        }
    }

    private val mask = (capacity - 1).toLong()
    private val slots = Array(capacity) { BedSample() }

    @Volatile private var head = 0L

    /** True once anything at all has been analysed. */
    val ready: Boolean get() = head > 0L

    /** Single writer only. Copies out of [s]; the caller keeps ownership. */
    fun append(s: BedSample) {
        slots[(head and mask).toInt()].copyFrom(s)
        head++          // volatile write: publishes the slot above
    }

    /**
     * Read the sample nearest [tS] into [out], returning false if there is nothing
     * within [TOLERANCE_S] of it.
     *
     * False is a real answer and callers must honour it: before the first frame is
     * analysed, across a loop point, and for a second or so after a seek, there is
     * genuinely nothing to react to, and a script that treated that as silence would
     * blink the room dark every time the bed looped.
     *
     * Walks back from the newest, which is where the answer almost always is — the
     * render tick asks about now, and now is the most recent thing written.
     */
    fun sampleAt(tS: Double, out: BedSample): Boolean {
        val h = head          // volatile read: acquires every slot written before it
        var i = h - 1
        val floor = maxOf(0L, h - slots.size)
        var best: BedSample? = null
        var bestGap = Double.MAX_VALUE
        while (i >= floor) {
            val s = slots[(i and mask).toInt()]
            val gap = abs(s.tS - tS)
            if (gap < bestGap) { bestGap = gap; best = s }
            // Written in media order, so once the gap starts growing again the nearest
            // has been passed and there is no reason to walk the rest of the ring.
            else if (s.tS < tS) break
            i--
        }
        if (best == null || bestGap > TOLERANCE_S) return false
        out.copyFrom(best)
        return true
    }

    fun clear() {
        head = 0L
    }

    private companion object {
        /**
         * How stale a sample may be and still describe "now".
         *
         * Three analysis hops. Beyond that the bed has genuinely stopped — paused,
         * seeking, or between loops — and saying so is better than holding the last
         * value, which would freeze the room mid-flash.
         */
        const val TOLERANCE_S = 0.06
    }
}

/**
 * The analysed recording behind a show: where it is, and what it is doing.
 *
 * An interface so the whole reactive path can be driven off-device. A fake that returns
 * a scripted position and replays canned frames turns "does the room flash on the
 * thunderclap" into an ordinary unit test, with no player, no codec and no speaker.
 */
interface AmbienceAudioAnalysis {

    /**
     * Media position being **heard** right now, in seconds, or `NaN` before playback has
     * produced one.
     *
     * Heard, not queued. The tap sees audio a sink buffer before the ear does, and a
     * light rendered against the tap's position would run that far early — the same
     * correction [com.engabd.sendpin.hue.FrameDelayQueue] applies to the music show,
     * expressed as a clock because a script draws an event over time rather than
     * emitting one frame per analysis hop.
     */
    fun heardMediaS(): Double

    /**
     * Install the per-frame consumer, or clear it with null.
     *
     * Invoked on the analysis thread with the frame and the exact media position of the
     * audio it describes.
     */
    fun onFrame(consumer: ((AnalysisFrame, Double) -> Unit)?)
}

/**
 * [AmbienceAudioAnalysis.heardMediaS] over an [AudioLead], smoothed into a clock.
 *
 * The lead probe publishes two numbers on every buffer the sink receives: the media time
 * of that buffer, and how far ahead of the ear it is. Their difference is the position
 * being heard, exactly, in the sink's own clock — no estimation and nothing to
 * calibrate. What it is not is *continuous*: it steps once per buffer, some tens of
 * milliseconds apart, and a 60 Hz light tick reading it raw would quantise every event
 * onset to that step.
 *
 * So the pair seeds a free-running clock that advances on wall time between buffers and
 * is corrected as each new pair arrives. Small corrections are slewed in rather than
 * applied, for the reason `FrameDelayQueue` slews its delay: stepping the clock would
 * jump the whole room a frame or two, which is visible where a slow drift is not.
 */
class AmbienceMediaClock(private val lead: AudioLead) {

    private var baseS = Double.NaN
    private var baseNanos = 0L

    fun reset() {
        baseS = Double.NaN
        baseNanos = 0L
    }

    fun nowS(): Double {
        val now = System.nanoTime()
        val free = if (baseS.isNaN()) Double.NaN else baseS + (now - baseNanos) / 1e9

        val m = lead.mediaTimeUs
        val l = lead.leadUs
        if (m != AudioLead.UNKNOWN && l != AudioLead.UNKNOWN) {
            val heard = (m - l) / 1e6
            if (free.isNaN()) {
                baseS = heard
                baseNanos = now
            } else {
                val err = heard - free
                when {
                    // A loop point, or a seek. Jumped to rather than slewed: slewing
                    // across a loop would walk the clock through several seconds of the
                    // file that are not playing. The session notices the same jump for
                    // itself, in the media positions of the frames it is handed, and
                    // drops the events it had scheduled against the old position.
                    err < -RESYNC_S || err > RESYNC_S -> {
                        baseS = heard
                        baseNanos = now
                    }
                    else -> {
                        baseS = free + err * CORRECTION
                        baseNanos = now
                    }
                }
            }
        } else if (!free.isNaN()) {
            // The sink has no position for a moment — mid-flush, or between items. Free
            // -wheel rather than collapse; the room carrying on is worth far more than
            // a few milliseconds of drift.
            baseS = free
            baseNanos = now
        }

        val t = if (baseS.isNaN()) Double.NaN else baseS
        return if (t.isNaN()) t else t + LIGHT_PIPELINE_S
    }

    private companion object {
        /**
         * How far ahead of the ear a light frame is rendered.
         *
         * Hue costs about this much between a datagram leaving the phone and the bulb
         * having visibly changed — a Zigbee hop plus the lamp's own ramp. Rendering the
         * frame for where the audio *will* be by then is what puts the flash on the
         * clap rather than a tenth of a second behind it. The same constant, for the
         * same reason, as `FrameDelayQueue.LIGHT_PIPELINE_MS`.
         */
        const val LIGHT_PIPELINE_S = 0.10

        /** Beyond this the position did not drift, it moved. */
        const val RESYNC_S = 0.35

        /** Fraction of the residual taken per read. ~60 Hz, so this settles in ~0.2 s. */
        const val CORRECTION = 0.08
    }
}
