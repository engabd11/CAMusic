package com.engabd.sendpin.hue.ambience

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log

private const val TAG = "AmbienceAudio"

/**
 * The ambience synth's output.
 *
 * A plain [AudioTrack], deliberately — not Oboe and not ExoPlayer.
 *
 * **Not Oboe.** `SendspinNativeOutput` exists because a multi-room stream has to hit a
 * server-derived timeline to the millisecond, and that earns a real-time HAL thread and
 * a JNI ring. An ambience bed has no such deadline: it is generated locally and there is
 * nobody to stay in step with. Opening a second Oboe stream would buy nothing and risk
 * contending with the Sendspin engine's.
 *
 * **Not ExoPlayer.** ExoPlayer decodes, and there is nothing here to decode. (The
 * user-clip override is a different matter, and that one *is* an ExoPlayer — see
 * `AmbienceClipPlayer`.)
 */
class AudioTrackSink(context: Context) : AudioSink {

    override val sampleRate: Int = run {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
            ?.takeIf { it in 22_050..192_000 } ?: 48_000
    }

    private val bufferBytes: Int = run {
        val min = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT,
        ).coerceAtLeast(4096)
        // Deliberately deep — about 200 ms. Two jobs at once: the underrun margin
        // against a GC pause on a JVM writer thread, and the lookahead that guarantees
        // events reach the timeline before the light tick reads it. See AmbienceSession.
        maxOf(min, (sampleRate * BYTES_PER_FRAME * TARGET_BUFFER_S).toInt())
    }

    private val track = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                // MOVIE rather than MUSIC: this is ambience, and on devices that voice
                // their output by content type it should not be handed a music EQ.
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build(),
        )
        .setAudioFormat(
            AudioFormat.Builder()
                // Float, not 16-bit. The beds sit around -40 dBFS in a quiet room, which
                // is exactly where 16-bit quantisation noise stops being theoretical.
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build(),
        )
        .setBufferSizeInBytes(bufferBytes)
        .setTransferMode(AudioTrack.MODE_STREAM)
        // NOT low-latency: that mode shrinks the buffer, which is the opposite of what
        // a continuous bed wants.
        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)
        .build()

    @Volatile private var released = false
    @Volatile private var started = false

    /** `playbackHeadPosition` is a 32-bit frame counter that wraps; this accumulates it. */
    private var wrapBase = 0L
    private var lastRaw = 0

    override fun write(block: FloatArray, frames: Int): Int {
        if (released) return -1
        if (!started) {
            try { track.play(); started = true } catch (e: IllegalStateException) {
                Log.w(TAG, "play() failed: ${e.message}")
                return -1
            }
        }
        // WRITE_BLOCKING *is* the pacing. The loop needs no timer: it returns when the
        // buffer has room, which is exactly one buffer behind the speaker.
        val n = track.write(block, 0, frames * CHANNELS, AudioTrack.WRITE_BLOCKING)
        if (n < 0) {
            Log.w(TAG, "write failed: $n")
            return -1
        }
        return n / CHANNELS
    }

    override fun playedFrames(): Long {
        if (released) return wrapBase
        val raw = track.playbackHeadPosition
        if (raw < lastRaw) wrapBase += 1L shl 32     // the counter wrapped
        lastRaw = raw
        return wrapBase + (raw.toLong() and 0xFFFF_FFFFL)
    }

    fun setVolume(v: Float) {
        if (!released) runCatching { track.setVolume(v.coerceIn(0f, 1f)) }
    }

    override fun pause() { if (!released) runCatching { track.pause() } }

    override fun resume() {
        if (!released && started) runCatching { track.play() }
    }

    override fun release() {
        if (released) return
        released = true
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.release() }
    }

    private companion object {
        const val CHANNELS = 2
        const val BYTES_PER_FRAME = 4 * CHANNELS      // float stereo
        const val TARGET_BUFFER_S = 0.2f
    }
}

/**
 * Audio focus for an ambience show.
 *
 * ## The in-process hazard
 *
 * `LocalPlayer` documents it: requesting `AUDIOFOCUS_GAIN` makes the platform evict the
 * previous holder, and in this app that holder is routinely the Sendspin path *in the
 * same process*, which reads the eviction as "another app took over" and releases its
 * engine. Effects is a third claimant with exactly the same problem.
 *
 * So the order matters, and it is not "just take focus": announce the handover through
 * `PlaybackOwner` first, stop the other backends **explicitly** rather than relying on
 * eviction to do it, and only then ask for focus.
 *
 * @param onLoss permanent loss — stop the show. Never auto-resume; that is the rule the
 *   call-pause path already follows.
 * @param onTransientLoss pause. Because the show clock *is* the playhead, pausing the
 *   track freezes the lights too, with nothing extra to write.
 * @param onDuck a nav prompt should not end an ambience show: duck the sound, keep the
 *   room.
 */
class AudioFocusGate(
    context: Context,
    private val onLoss: () -> Unit,
    private val onTransientLoss: () -> Unit,
    private val onGain: () -> Unit,
    private val onDuck: (Boolean) -> Unit,
) : FocusGate {

    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> onLoss()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> onTransientLoss()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> onDuck(true)
            AudioManager.AUDIOFOCUS_GAIN -> { onDuck(false); onGain() }
        }
    }

    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build(),
        )
        // Ducking is handled above rather than by the platform, so the lights can be
        // kept while the sound drops.
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener(listener)
        .build()

    override fun request(): Boolean =
        am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    override fun abandon() { runCatching { am.abandonAudioFocusRequest(request) } }
}
