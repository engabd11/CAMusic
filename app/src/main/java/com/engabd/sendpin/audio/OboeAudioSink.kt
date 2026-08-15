package com.engabd.sendpin.audio

import android.media.AudioDeviceInfo
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Bridges ExoPlayer's decoded PCM to [SendspinNativeOutput] (the Oboe engine) -
 * the "OboeRenderer" the plan describes, implemented as ExoPlayer's actual seam
 * for this (`AudioSink`, what `MediaCodecAudioRenderer` writes decoded output
 * to) rather than a full custom `Renderer`. There is no reference to port here:
 * Massdroid doesn't route through ExoPlayer's `AudioSink`/`AudioProcessor` SPI
 * at all - it calls the native engine directly from its own MediaCodec decode
 * loop. This is new integration code, unverified beyond compiling.
 *
 * Presentation time: [handleBuffer] gets ExoPlayer's own `presentationTimeUs`,
 * which is *media-relative* (elapsed samples since the stream started), not the
 * original Sendspin `server_ts_us` - that association is long gone by the time
 * bytes reach here. See [SendspinDataSource.presentationLocalUs] for how the
 * absolute local target is recovered from it.
 *
 * [tap] is fed a copy of every buffer, independent of the copy that actually
 * reaches Oboe: a bug in the tap's `AudioProcessor` contract (which this drives
 * directly, outside its usual home inside `DefaultAudioSink`'s processor chain)
 * can then never corrupt what gets played, only what gets analysed. Not
 * currently wired to [com.engabd.sendpin.hue.DirectLightSync] - see
 * [SendspinExoEngine]'s docstring.
 *
 * Only PCM 16-bit is supported (matching [SendspinOutputEngine]/Oboe, which is
 * hardcoded I16) - [configure] throws for anything else, which for MA means
 * bit-perfect/24-bit playback is not available through the Oboe path.
 */
@OptIn(UnstableApi::class)
class OboeAudioSink(
    private val nativeOutput: SendspinNativeOutput,
    private val tap: AudioAnalysisTap,
    /** Whether the *next* [configure] should open with timeline drift correction - i.e. whether the current stream is grouped (SYNC) vs solo (DIRECT). */
    private val driftCorrection: () -> Boolean,
    /** The [SendspinDataSource] currently feeding ExoPlayer, for [SendspinDataSource.presentationLocalUs]. */
    private val currentDataSource: () -> SendspinDataSource?,
) : AudioSink {

    companion object {
        // SendspinOutputEngine's ring is RING_SECONDS (4s) deep - see its header.
        // There's no real hardware AudioTrack buffer here to report a size for,
        // so this stands in for it (used by ExoPlayer only for buffering
        // heuristics, not for anything sync-critical).
        private const val RING_BUFFER_US = 4_000_000L
    }

    private var listener: AudioSink.Listener? = null
    private var audioAttributes = AudioAttributes.DEFAULT
    private var playbackParameters = PlaybackParameters.DEFAULT
    private var skipSilenceEnabled = false
    private var configuredFormat: Format? = null
    private var channelCount = 2
    private var bytesPerFrame = 4
    private var sampleRate = 48_000

    private var framesWrittenTotal = 0L
    private var endOfStreamRequested = false

    private var scratch = ByteArray(0)
    private var tapScratch = ByteArray(0)

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
    }

    override fun supportsFormat(format: Format): Boolean = format.pcmEncoding == C.ENCODING_PCM_16BIT

    override fun getFormatSupport(format: Format): Int =
        if (supportsFormat(format)) AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY else AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        // Approximate: frames handed to the native ring, minus what's still
        // buffered there. Good enough for ExoPlayer's own bookkeeping - this
        // app's actual Now Playing position comes from server/state + ClockSync
        // (see Playback.anchorProgress), not from the player's reported position.
        if (sampleRate <= 0) return 0L
        val played = (framesWrittenTotal - nativeOutput.bufferedFrames()).coerceAtLeast(0)
        return played * 1_000_000L / sampleRate
    }

    override fun configure(format: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        if (format.pcmEncoding != C.ENCODING_PCM_16BIT) {
            throw AudioSink.ConfigurationException(
                "OboeAudioSink only supports 16-bit PCM (got encoding=${format.pcmEncoding})", format,
            )
        }
        try {
            tap.configure(AudioProcessor.AudioFormat(format))
        } catch (e: AudioProcessor.UnhandledAudioFormatException) {
            throw AudioSink.ConfigurationException(e, format)
        }
        channelCount = format.channelCount
        sampleRate = format.sampleRate
        bytesPerFrame = channelCount * 2
        framesWrittenTotal = 0L
        endOfStreamRequested = false
        configuredFormat = format
        if (!nativeOutput.start(sampleRate, channelCount, driftCorrection())) {
            throw AudioSink.ConfigurationException("SendspinNativeOutput.start failed", format)
        }
    }

    override fun play() {
        nativeOutput.resumeStream()
    }

    override fun handleDiscontinuity() {
        // No timeline-position bookkeeping here depends on continuity (unlike
        // DefaultAudioSink's trim/gapless handling), so there's nothing to do.
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val remaining = buffer.remaining()
        if (remaining <= 0) return true
        if (scratch.size < remaining) scratch = ByteArray(remaining)
        buffer.get(scratch, 0, remaining)

        feedTap(scratch, remaining)

        val dataSource = currentDataSource()
        if (dataSource != null) {
            val presentationLocalUs = dataSource.presentationLocalUs(presentationTimeUs)
            nativeOutput.write(scratch, 0, remaining, presentationLocalUs)
        }
        framesWrittenTotal += remaining / bytesPerFrame
        return true
    }

    /**
     * Best-effort analysis feed - see the class doc on why a failure here must
     * never propagate to the actual audio path.
     */
    private fun feedTap(pcm: ByteArray, length: Int) {
        try {
            if (tapScratch.size < length) tapScratch = ByteArray(length)
            System.arraycopy(pcm, 0, tapScratch, 0, length)
            tap.queueInput(ByteBuffer.wrap(tapScratch, 0, length).order(ByteOrder.nativeOrder()))
            // Pass-through processor: drain and discard: this sink's own scratch
            // copy (not the tap's output) is what's already been forwarded to
            // Oboe above, so there's nothing left to do with these bytes.
            while (tap.getOutput().hasRemaining()) { /* discard */ }
        } catch (_: Exception) {
            // Analysis is not allowed to take playback down with it.
        }
    }

    override fun playToEndOfStream() {
        endOfStreamRequested = true
    }

    override fun isEnded(): Boolean = endOfStreamRequested && nativeOutput.bufferedFrames() <= 0

    override fun hasPendingData(): Boolean = nativeOutput.bufferedFrames() > 0

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        // Not applied: SendspinOutputEngine has no speed control beyond its own
        // automatic drift-correction resampler, which is unrelated to a
        // user-requested playback speed (not a realistic MA use case).
        this.playbackParameters = playbackParameters
    }

    override fun getPlaybackParameters(): PlaybackParameters = playbackParameters

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
        this.skipSilenceEnabled = skipSilenceEnabled
    }

    override fun getSkipSilenceEnabled(): Boolean = skipSilenceEnabled

    override fun setAudioAttributes(audioAttributes: AudioAttributes) {
        this.audioAttributes = audioAttributes
    }

    override fun getAudioAttributes(): AudioAttributes = audioAttributes

    override fun setAudioSessionId(audioSessionId: Int) {
        // No-op: there's no platform AudioTrack/session behind this sink for an
        // effects chain (equalizer, etc.) to attach to.
    }

    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {
        // No-op, for the same reason as setAudioSessionId.
    }

    override fun setPreferredDevice(audioDeviceInfo: AudioDeviceInfo?) {
        // Not supported: SendspinOutputEngine opens its Oboe stream without a
        // preferred-device pin. USB DAC routing for the Oboe path is a gap
        // relative to the default AudioTrack path (SendspinAudioEngine,
        // SendspinExoEngine without Oboe), both of which honour it.
    }

    override fun getAudioTrackBufferSizeUs(): Long = RING_BUFFER_US

    override fun enableTunnelingV21() {
        // No-op: tunneling has no meaning for a software ring + Oboe callback.
    }

    override fun disableTunneling() {
        // No-op, matches enableTunnelingV21.
    }

    override fun setVolume(volume: Float) {
        nativeOutput.setVolume(volume)
    }

    override fun pause() {
        nativeOutput.pauseStream()
    }

    override fun flush() {
        nativeOutput.flush()
        framesWrittenTotal = 0L
        endOfStreamRequested = false
    }

    override fun reset() {
        nativeOutput.stop()
        configuredFormat = null
        framesWrittenTotal = 0L
        endOfStreamRequested = false
    }
}
