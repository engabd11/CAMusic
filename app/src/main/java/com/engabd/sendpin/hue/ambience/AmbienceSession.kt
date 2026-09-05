package com.engabd.sendpin.hue.ambience

import android.util.Log
import com.engabd.sendpin.hue.FieldSafety
import com.engabd.sendpin.hue.Rgb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

private const val TAG = "Ambience"

/**
 * Somewhere to write interleaved stereo float, and a playhead to read back.
 *
 * An interface so the whole of [AmbienceSession] can be driven off-device: a fake sink
 * whose playhead advances by exactly the frames written turns "does the sound line up
 * with the lights" into an ordinary unit test.
 */
interface AudioSink {
    val sampleRate: Int

    /** Blocking. Returns frames written, or negative on failure. */
    fun write(block: FloatArray, frames: Int): Int

    /**
     * Frames the speaker has actually **played**, not frames written.
     *
     * The distinction is the whole reason the show clock works. The write cursor runs a
     * buffer ahead of the sound; the playhead is what the listener is hearing right now,
     * and it is the only thing a light frame can honestly be aligned to.
     */
    fun playedFrames(): Long

    fun pause()
    fun resume()
    fun release()
}

/** Audio focus, behind an interface for the same reason as [AudioSink]. */
interface FocusGate {
    /** False if focus was refused, in which case the session must not start. */
    fun request(): Boolean
    fun abandon()
}

/**
 * One running ambience show.
 *
 * Owns the clock, the timeline, the script and its own [FieldSafety]. Does **not** own
 * the Hue session or the render loop — `DirectLightSync` calls [renderLights] from the
 * loop it already runs, so all the pacing, the rate limiting, the encoder and the DTLS
 * reconnect machinery come for free.
 *
 * ## Why the generator runs ahead
 *
 * [generatorLoop] schedules a block of events, renders that block's audio, and writes
 * it. The write blocks until there is room, which paces the whole thing with no timer:
 * the loop naturally sits exactly one buffer — about 200 ms — ahead of the playhead.
 *
 * That lead is doing two jobs at once. It is the underrun margin against a GC pause on a
 * JVM thread, and it is the guarantee that every event the light tick could ask about
 * was published to the timeline before the tick could reach it.
 *
 * The buffer's own lead is not, however, the whole of the scheduling horizon. An event
 * that makes sound *before* its own `startS` — a firework whistling on the way up — has
 * to exist before the block carrying that sound is rendered, which is earlier than the
 * block its `startS` falls in. [AmbienceScript.lookaheadS] is how a script says so, and
 * [generatorLoop] schedules out to it.
 *
 * ## None of which happens when a recording is playing
 *
 * All of the above is the *synthesised* path, which is now the fallback rather than the
 * norm. With a recording — a bundled bed, or the listener's own clip — there is no
 * generator, no synth and no scheduler. [onAnalysisFrame] runs instead, on the analysis
 * thread, turning what the speaker is actually playing into this effect's events; the
 * clock becomes the recording's own position in the file; and the room reacts to a real
 * storm rather than performing one alongside it. See [AmbienceAudioAnalysis].
 */
class AmbienceSession(
    val script: AmbienceScript,
    private val room: RoomModel,
    private val sink: AudioSink?,
    private val focus: FocusGate?,
    params: AmbienceParams,
    /** Called once if the audio path dies, so the UI can say so rather than going quiet. */
    private val onAudioFailed: (String) -> Unit = {},
    /**
     * The recording this show reacts to, when one is playing.
     *
     * Mutually exclusive with [sink]: a show either generates its sound, in which case
     * the synth's playhead is the clock and the script invents the events, or it plays a
     * recording, in which case the recording's own position is the clock and the
     * recording is the event source. Never both — there is only one room, and two
     * storms in it was the whole problem.
     */
    private val analysis: AmbienceAudioAnalysis? = null,
) {

    /**
     * The flash budget, session-owned rather than shared with the music show.
     *
     * Two reasons it is a separate instance. The budget is stateful, and one show's
     * spend is not the other's; and `selectLimiter` swaps in a *relaxed* limiter on the
     * Intense and Extreme rungs, which ambience must not silently inherit from whatever
     * the music show happened to be on.
     *
     * `calmGated = false` on purpose. The strict default engages on hard-swinging
     * content and then refuses to release until the content calms — but in a
     * thunderstorm the strikes *are* the content, so a storm would compress itself into
     * a much softer storm than the tile promised. Ungated, engagement keys purely off
     * the flash budget: the room is still protected from a sustained flicker, but a
     * lightning strike is allowed to be a lightning strike. This is the same escape the
     * Extreme rung already gets, and here the listener has asked for lightning by name.
     */
    val safety = FieldSafety(calmGated = false)

    private val timeline = AmbienceTimeline()

    /**
     * What the recording is doing, for the scripts whose beds follow it.
     *
     * Written by the analysis thread and read by the light tick, on the same
     * single-writer/volatile-head terms as [timeline].
     */
    private val bed = AmbienceBedTrack()

    /** Onset detection over the recording. Analysis thread only. */
    private val bedAnalyser = AmbienceBedAnalyser(hopS = ANALYSIS_HOP / ANALYSIS_SR.toDouble())

    /** Media position of the last analysed frame, for spotting a loop or a seek. */
    private var lastAnalysedS = Double.NaN

    /**
     * True when the recording is the event source, so the analysis thread is the
     * timeline's writer and [renderLights] must not schedule.
     *
     * [AmbienceTimeline] is single-writer by construction, and which thread that writer
     * is depends on this. Reacting, the analysis thread appends and the render loop only
     * reads; scripted, the render loop appends and the analysis thread only fills the
     * bed. Both are one writer — but only because [schedule] is suppressed in the first
     * case and [AmbienceScript.react] is a no-op in the second, which is a load-bearing
     * pairing rather than a coincidence.
     */
    private val reactive: Boolean = analysis != null && script.eventsComeFromAudio

    /**
     * Set by the analysis thread when the recording jumps, and acted on by the render
     * thread, for scripts the render thread schedules.
     *
     * The clears themselves have to happen on whichever thread owns the timeline, or the
     * single-writer property the whole lock-free design rests on is lost at exactly the
     * moment — a loop point — when the room is most likely to be showing something.
     */
    @Volatile private var rescheduleFromS = Double.NaN

    /**
     * Each reader owns its scratch array, so the two never touch the same memory.
     *
     * Sized for the worst honest case rather than the typical one. An event now lives
     * in the timeline for as long as *either* medium can still read it, and for a storm
     * that is dominated by the audio: a distant strike is still rolling twenty seconds
     * after its flash, so a wild storm can have a dozen strikes overlapping where the
     * light alone would have had one. Overflow is silent by design — see
     * [AmbienceTimeline.windowAt] — and it would drop the oldest, which is exactly the
     * strike whose thunder is arriving now.
     */
    private val lightScratch = arrayOfNulls<AmbienceEvent>(160)
    private val genScratch = arrayOfNulls<AmbienceEvent>(160)

    private val frame = HashMap<Int, Rgb>(room.count * 2)

    @Volatile private var params: AmbienceParams = params
    @Volatile private var running = false
    @Volatile private var paused = false

    /** Show time of the first sample written, so the clock starts at zero. */
    private var generatedToS = 0.0
    private var writtenFrames = 0L

    private var genJob: Job? = null
    private val genExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ambience-gen").apply { isDaemon = true }
    }
    private val genDispatcher = genExecutor.asCoroutineDispatcher()

    /** Fallback clock for a silent show. */
    private val startedNanos = System.nanoTime()

    /**
     * Where the show is, in seconds — the *playhead*, when there is one.
     *
     * With sound, this is what the speaker is emitting, so a light frame rendered at
     * this instant lines up at the ear rather than at the write cursor. Without sound
     * (the user turned it off, or chose their own clip) it falls back to wall time.
     *
     * A stalled playhead — an underrun with the stream still nominally playing — would
     * freeze the room, which is a much worse failure than a few milliseconds of drift.
     * So a playhead that has not moved for a while is abandoned in favour of wall time
     * until it recovers.
     */
    fun nowS(): Double {
        // A recording's own position, when there is one. Not the wall clock and not a
        // synth playhead: the show's events are stamped where their sound sits in the
        // file, so the only clock that can line them up is the file's.
        analysis?.let { a ->
            if (paused) return pausedAtS
            val media = a.heardMediaS()
            if (!media.isNaN()) return media
            // Before the first buffer, and for a moment across a track change. The
            // room carrying on matters more than a few milliseconds of drift.
            return (System.nanoTime() - startedNanos) / 1e9
        }
        val s = sink ?: return (System.nanoTime() - startedNanos) / 1e9
        if (paused) return pausedAtS
        val played = s.playedFrames()
        val wall = (System.nanoTime() - startedNanos) / 1e9
        if (played != lastPlayed) { lastPlayed = played; lastPlayedWall = wall }
        else if (wall - lastPlayedWall > STALL_S) {
            // Do not log every frame; the room carrying on is the important part.
            return wall - stallWallOffset
        }
        val head = played.toDouble() / s.sampleRate
        stallWallOffset = wall - head
        return head
    }

    private var lastPlayed = -1L
    private var lastPlayedWall = 0.0
    private var stallWallOffset = 0.0
    private var pausedAtS = 0.0

    fun start(scope: CoroutineScope): Boolean {
        if (running) return true
        if (focus?.request() == false) {
            Log.w(TAG, "audio focus refused; not starting ${script.effect.wire}")
            return false
        }
        script.bind(room, params)
        script.bindBed(if (analysis != null) bed else null)
        running = true
        analysis?.onFrame(::onAnalysisFrame)
        if (sink != null) genJob = scope.launch(genDispatcher) { generatorLoop() }
        return true
    }

    /**
     * One analysis hop of the recording, on the analysis thread.
     *
     * The whole of the reactive path: describe the moment, notice whether anything
     * happened in it, and let the script say what that means for this effect. Both
     * objects handed to the script are reused, which is why [AmbienceBedTrack.append]
     * copies rather than stores.
     *
     * @param atS media position of the audio this frame describes — where it sits in
     *   the file, not when it was analysed. Events are stamped with it, and the light
     *   tick renders against the position being heard, so the two meet without either
     *   side knowing about the other.
     */
    private fun onAnalysisFrame(frame: com.engabd.sendpin.audio.AnalysisFrame, atS: Double) {
        if (!running) return
        // The bed looped, or the listener seeked. Everything scheduled describes a part
        // of the file that is no longer playing, and a strike still flashing from before
        // the loop would be a flash with nothing under it.
        val jumped = !lastAnalysedS.isNaN() &&
            (atS < lastAnalysedS - DISCONTINUITY_S || atS > lastAnalysedS + DISCONTINUITY_S)
        if (jumped) {
            bed.clear()
            bedAnalyser.reset()
            // Only touch the timeline on the thread that writes it. See [reactive].
            if (reactive) timeline.clear() else rescheduleFromS = atS
            // And the script's own refractory bookkeeping, which the clears above
            // cannot reach. Without this a looping reactive show went dark for a
            // whole loop after the first pass - see AmbienceScript.onBedReset.
            script.onBedReset()
        }
        lastAnalysedS = atS

        val (sample, onset) = bedAnalyser.consume(frame, atS)
        bed.append(sample)
        script.react(sample, onset) { timeline.append(it) }
    }

    fun retune(p: AmbienceParams) {
        params = p
        script.retune(p)
    }

    /**
     * The user's master brightness ceiling moved.
     *
     * Separate from [retune] because the script does not need telling: the
     * ceiling is applied to the rendered frame in [renderLights], not inside the
     * script, and handing a script a fresh params object would restart tuning it
     * has already done.
     */
    fun setBrightness(v: Float) {
        params = params.copy(brightness = v.coerceIn(0f, 1f))
    }

    fun pause() {
        if (paused) return
        pausedAtS = nowS()
        paused = true
        sink?.pause()
    }

    fun resume() {
        if (!paused) return
        paused = false
        sink?.resume()
    }

    fun stop() {
        running = false
        analysis?.onFrame(null)
        genJob?.cancel(); genJob = null
        sink?.release()
        focus?.abandon()
        timeline.clear()
        genExecutor.shutdownNow()
    }

    /**
     * The light frame for right now.
     *
     * Called from `DirectLightSync`'s render loop. Returns null before the show has
     * anything to say, so the caller can fall through rather than blanking the room.
     */
    fun renderLights(): Map<Int, Rgb>? {
        if (!running) return null
        val t = nowS()
        // A silent show still needs its events, and with no sink there is no generator
        // coroutine to schedule them. Cheap: the scripts' schedule() is a handful of
        // arithmetic per event, and this only runs when there is no audio thread.
        //
        // Suppressed outright when the recording is the event source: running the
        // scheduler as well would put an invented storm in the room alongside the
        // audible one. Scripts that are texture with decoration on top keep scheduling
        // either way — see [AmbienceScript.eventsComeFromAudio].
        if (sink == null && !reactive) {
            // The recording looped or was seeked while this script was scheduling
            // against its position. Everything already scheduled describes a stretch of
            // the file that is not playing any more, and `generatedToS` is now far ahead
            // of the clock — left alone, the effect would schedule nothing ever again.
            val resume = rescheduleFromS
            if (!resume.isNaN()) {
                rescheduleFromS = Double.NaN
                timeline.clear()
                generatedToS = resume
            }
            // At least as far as the script's own lead, or an effect whose events are
            // visible before they start — a firework's climbing ember — would have
            // nothing to show until the moment it burst.
            val horizon = t + maxOf(SILENT_LOOKAHEAD_S, script.lookaheadS.toDouble())
            if (horizon > generatedToS) {
                script.schedule(generatedToS, horizon) { timeline.append(it) }
                generatedToS = horizon
            }
        }
        val n = timeline.windowAt(t, lightScratch)
        frame.clear()
        script.renderLights(t, lightScratch, n, frame)
        if (frame.isEmpty()) return null
        val ceiling = params.brightness.coerceIn(0f, 1f)
        if (ceiling < 1f) {
            for ((k, v) in frame) frame[k] = Rgb(v.first * ceiling, v.second * ceiling, v.third * ceiling)
        }
        return frame
    }

    private fun generatorLoop() {
        val s = sink ?: return
        val sr = s.sampleRate
        val block = FloatArray(BLOCK_FRAMES * 2)
        val scope = CoroutineScope(genDispatcher)
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
        } catch (_: Throwable) {
            // A thread priority we were not allowed to set is not a reason to be silent.
        }
        while (running && scope.isActive) {
            val startS = writtenFrames.toDouble() / sr
            val endS = startS + BLOCK_FRAMES.toDouble() / sr
            // Schedule *before* rendering, always. This ordering is what guarantees the
            // light tick can never ask about an event that has not been published.
            //
            // Out to the script's own horizon, not merely to the end of this block.
            // Scheduling to `endS` publishes an event exactly when the block containing
            // its `startS` comes up, which is in time for anything that begins when the
            // event does — and far too late for anything that begins *before* it. A
            // shell whistles for most of a second on the way up, so by the time
            // scheduling reached it the blocks carrying its climb had already been
            // rendered and written: the launch was scheduled, rendered by nobody, and
            // silent. See [AmbienceScript.lookaheadS].
            val horizon = endS + script.lookaheadS
            if (horizon > generatedToS) {
                script.schedule(generatedToS, horizon) { timeline.append(it) }
                generatedToS = horizon
            }

            java.util.Arrays.fill(block, 0f)
            // Over the block, not at its start. An event beginning mid-block is not
            // alive at the block's first sample, and asking `windowAt(startS)` therefore
            // withheld it for a whole buffer — clipping the attack off every transient
            // that did not happen to start on a boundary.
            val n = timeline.windowOver(startS, endS, genScratch)
            script.renderAudio(block, BLOCK_FRAMES, startS, sr, genScratch, n)
            for (i in block.indices) block[i] = softClip(block[i])

            val wrote = s.write(block, BLOCK_FRAMES)
            if (wrote < 0) {
                running = false
                onAudioFailed("audio output failed")
                return
            }
            writtenFrames += wrote
        }
    }

    private companion object {
        /** ~21 ms at 48 kHz. Small enough to be responsive, big enough to be cheap. */
        const val BLOCK_FRAMES = 1024

        /** How far ahead a silent show schedules, since nothing else paces it. */
        const val SILENT_LOOKAHEAD_S = 0.5

        /** A playhead frozen this long is an underrun; fall forward on wall time. */
        const val STALL_S = 0.1

        /**
         * A jump in the recording's position bigger than this is a loop or a seek.
         *
         * Generous, because analysis hops do not arrive perfectly evenly and the
         * penalty for calling an ordinary gap a discontinuity — dropping the events in
         * flight — is a visible stutter, while the penalty for missing a real one is
         * one stale flash.
         */
        const val DISCONTINUITY_S = 0.5

        /** The tap's analysis hop and rate, matching `AudioAnalyzer`. */
        const val ANALYSIS_HOP = 441
        const val ANALYSIS_SR = 22_050
    }
}
