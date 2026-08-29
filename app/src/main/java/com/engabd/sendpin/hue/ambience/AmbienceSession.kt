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
 */
class AmbienceSession(
    val script: AmbienceScript,
    private val room: RoomModel,
    private val sink: AudioSink?,
    private val focus: FocusGate?,
    params: AmbienceParams,
    /** Called once if the audio path dies, so the UI can say so rather than going quiet. */
    private val onAudioFailed: (String) -> Unit = {},
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

    /** Each reader owns its scratch array, so the two never touch the same memory. */
    private val lightScratch = arrayOfNulls<AmbienceEvent>(96)
    private val genScratch = arrayOfNulls<AmbienceEvent>(96)

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
        running = true
        if (sink != null) genJob = scope.launch(genDispatcher) { generatorLoop() }
        return true
    }

    fun retune(p: AmbienceParams) {
        params = p
        script.retune(p)
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
        if (sink == null) {
            if (t > generatedToS) {
                script.schedule(generatedToS, t + SILENT_LOOKAHEAD_S) { timeline.append(it) }
                generatedToS = t + SILENT_LOOKAHEAD_S
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
            script.schedule(generatedToS, endS) { timeline.append(it) }
            generatedToS = endS

            java.util.Arrays.fill(block, 0f)
            val n = timeline.windowAt(startS, genScratch)
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
    }
}
