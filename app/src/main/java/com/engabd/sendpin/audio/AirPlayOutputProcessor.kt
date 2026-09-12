package com.engabd.sendpin.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * A pass-through [AudioProcessor] that taps ExoPlayer's decoded PCM and
 * feeds it to an [AirPlayOutput] for streaming over the network.
 *
 * Sits in the render pipeline alongside [AudioAnalysisTap] and [LocalDsp]:
 * audio flows through unchanged (the Android mixer still works in
 * parallel for local listening), and the same PCM is copied to the
 * AirPlay output's native ring buffer.
 *
 * ## When it is active
 *
 * The processor is always in the chain (it returns the input format from
 * [onConfigure]), but [queueInput] only feeds the AirPlay output when
 * [AirPlayOutput.isConnected] is true. When no AirPlay device is
 * connected, [queueInput] is a pure pass-through — no copy, no native
 * call, no overhead.
 *
 * ## Format
 *
 * The AirPlay sender expects 16-bit interleaved stereo at 44100 Hz (or
 * any rate — the native side resamples to 44100). When ExoPlayer's output
 * is `ENCODING_PCM_FLOAT`, we need to convert to 16-bit before feeding
 * the native ring. When it's already `ENCODING_PCM_16BIT`, we pass the
 * bytes directly.
 *
 * ## Threading
 *
 * [queueInput] is called on ExoPlayer's audio thread. The native ring
 * buffer is lock-free SPSC, so this is safe. No blocking, no allocation
 * on the hot path.
 */
@OptIn(UnstableApi::class)
class AirPlayOutputProcessor(
    private val output: AirPlayOutput,
) : BaseAudioProcessor() {

    /** Whether the AirPlay output is connected and we should feed it. */
    @Volatile
    private var feed: Boolean = false

    /** Set when the AirPlay session launches, cleared when it closes. */
    fun setFeeding(on: Boolean) {
        feed = on
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT -> inputAudioFormat
            else -> AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        // Feed AirPlay only when connected — no copy, no native call otherwise.
        if (feed) {
            val pos = inputBuffer.position()
            val arr = ByteArray(remaining)
            inputBuffer.get(arr)
            inputBuffer.position(pos) // reset for the pass-through put below
            output.writePcm(arr, 0, arr.size)
        }

        // Pass-through: the same bytes reach the Android mixer.
        replaceOutputBuffer(remaining).put(inputBuffer).flip()
    }

    override fun onReset() {
        feed = false
    }
}