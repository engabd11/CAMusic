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
 * [setFeeding] is true. When no AirPlay device is connected,
 * [queueInput] is a pure pass-through — no copy, no native call, no
 * overhead.
 *
 * ## Format
 *
 * The AirPlay sender expects 16-bit interleaved stereo PCM. The native
 * side resamples to 44100 Hz, so any input sample rate is fine — but the
 * *encoding* must be 16-bit. When ExoPlayer's output is
 * `ENCODING_PCM_FLOAT` (which it is when the hi-res / bit-perfect path is
 * active), the float samples are converted to 16-bit before being sent to
 * the native ring. When it's already `ENCODING_PCM_16BIT`, the bytes are
 * passed directly.
 *
 * ## Threading
 *
 * [queueInput] is called on ExoPlayer's audio thread. The native ring
 * buffer is lock-free SPSC, so this is safe. No blocking, no allocation
 * on the hot path — the scratch buffer is reused across calls.
 */
@OptIn(UnstableApi::class)
class AirPlayOutputProcessor(
    private val output: AirPlayOutput,
) : BaseAudioProcessor() {

    /** Whether the AirPlay output is connected and we should feed it. */
    @Volatile
    private var feed: Boolean = false

    /**
     * Reusable scratch buffer for the PCM copy. Grown as needed, never
     * shrunk — the buffer size is stable after the first few callbacks
     * because ExoPlayer's buffer size is constant for a given format.
     *
     * Not synchronised — [queueInput] is called from one thread (ExoPlayer's
     * audio thread), and nothing else touches this buffer.
     */
    private var scratch: ByteArray = ByteArray(0)

    /** The encoding of the PCM we're receiving, from [onConfigure]. */
    private var pcmEncoding: Int = C.ENCODING_PCM_16BIT

    /** Set when the AirPlay session launches, cleared when it closes. */
    fun setFeeding(on: Boolean) {
        feed = on
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT -> {
                pcmEncoding = inputAudioFormat.encoding
                inputAudioFormat  // pass-through: same format out
            }
            else -> AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        // Feed AirPlay only when connected — no copy, no native call otherwise.
        if (feed) {
            when (pcmEncoding) {
                C.ENCODING_PCM_16BIT -> {
                    // Direct copy: bytes are already 16-bit PCM.
                    if (scratch.size < remaining) scratch = ByteArray(remaining)
                    val pos = inputBuffer.position()
                    inputBuffer.get(scratch, 0, remaining)
                    inputBuffer.position(pos) // reset for the pass-through put
                    output.writePcm(scratch, 0, remaining)
                }
                C.ENCODING_PCM_FLOAT -> {
                    // Float → 16-bit conversion. Each float sample (4 bytes)
                    // becomes one 16-bit sample (2 bytes), so the output is
                    // half the size.
                    val numSamples = remaining / 4  // float = 4 bytes
                    val outBytes = numSamples * 2   // int16 = 2 bytes
                    if (scratch.size < outBytes) scratch = ByteArray(outBytes)

                    val pos = inputBuffer.position()
                    val floatBuf = inputBuffer.asFloatBuffer()
                    for (i in 0 until numSamples) {
                        // Clamp [-1.0, 1.0] → [-32768, 32767]
                        val f = floatBuf.get().coerceIn(-1f, 1f)
                        val sample = (f * 32767f).toInt()
                        scratch[i * 2] = (sample and 0xFF).toByte()
                        scratch[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
                    }
                    inputBuffer.position(pos) // reset for the pass-through put
                    output.writePcm(scratch, 0, outBytes)
                }
            }
        }

        // Pass-through: the same bytes reach the Android mixer.
        replaceOutputBuffer(remaining).put(inputBuffer).flip()
    }

    override fun onReset() {
        feed = false
        scratch = ByteArray(0)
    }
}