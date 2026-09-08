package com.engabd.sendpin.spotify

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.nio.ByteBuffer
import java.nio.ByteOrder
import xyz.gianlu.librespot.player.mixing.output.OutputAudioFormat
import xyz.gianlu.librespot.player.mixing.output.SinkOutput

/**
 * librespot's audio output, written straight into this phone's [AudioTrack] — and
 * into Light Sync's analysis ring while it is at it.
 *
 * librespot decodes in-process (route B: the Spotify client *is* the player), so
 * this is where its PCM leaves the library and enters the app: every buffer the
 * player hands over is played and, in the same breath, fed to a dedicated
 * [SpotifyEngine.tap] through `analyseExternal` — the same entry the MediaProjection
 * capture path uses, and under the same single-producer contract: the player's
 * audio thread is this tap's only writer, ever.
 *
 * **Byte order:** librespot's desktop sinks are Java `SourceDataLine`s, which take
 * big-endian PCM; Android's [AudioTrack] wants little-endian. The output format
 * says which one arrived, and [write] swaps pairs when it is big-endian. Getting
 * this wrong is not a subtle artifact — it is full-scale noise on every track —
 * which is why the swap is decided from the format object rather than assumed.
 */
class SpotifySink(private val tapHolder: SpotifyEngine.TapHolder) : SinkOutput {

    private var track: AudioTrack? = null

    /** Samples handed to the output so far — the analysis tap's session clock. */
    private var samplesWritten: Long = 0

    private var sampleRate: Int = 44100
    private var channels: Int = 2

    /** True when the incoming PCM is big-endian and needs a pair swap. */
    private var swapBytes: Boolean = false

    override fun start(format: OutputAudioFormat): Boolean {
        sampleRate = format.sampleRate.toInt()
        channels = format.channels
        swapBytes = format.isBigEndian
        samplesWritten = 0

        val channelMask = if (channels == 1) {
            AudioFormat.CHANNEL_OUT_MONO
        } else {
            AudioFormat.CHANNEL_OUT_STEREO
        }
        val audioFormat = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(sampleRate * channels * 2 / 4)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(audioFormat)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuf * 2)
            .build()
        track?.play()
        return true
    }

    override fun write(buffer: ByteArray, offset: Int, len: Int) {
        val t = track ?: return
        if (swapBytes) {
            var i = offset
            val end = offset + len - 1
            while (i < end) {
                val b = buffer[i]
                buffer[i] = buffer[i + 1]
                buffer[i + 1] = b
                i += 2
            }
        }
        t.write(buffer, offset, len)
        // A view, not a copy: `wrap(...).slice()` aliases the player's own array.
        // That is safe because `analyseExternal` -> `feedRing` reads every frame
        // into the analysis ring synchronously, on this thread, before returning —
        // nothing downstream keeps a reference to these bytes. It must stay that
        // way: an analysis path that ever defers its read would be reading a
        // buffer librespot has already refilled. Taken after the in-place swap
        // above, so the tap sees the same byte order the output did.
        val analysis = ByteBuffer.wrap(buffer, offset, len)
            .order(ByteOrder.LITTLE_ENDIAN)
            .slice()
        tapHolder.tap.analyseExternal(
            analysis,
            sampleRate,
            channels,
            androidx.media3.common.C.ENCODING_PCM_16BIT,
            samplesWritten * 1_000_000L / sampleRate,
        )
        samplesWritten += len / channels / 2
    }

    override fun setVolume(volume: Float): Boolean = false

    override fun drain() {
        track?.let { it.pause(); it.flush(); it.play() }
    }

    override fun flush() {
        samplesWritten = 0
        track?.flush()
    }

    override fun stop() {
        track?.pause()
    }

    override fun release() {
        track?.release()
        track = null
    }

    override fun close() {
        release()
    }
}
