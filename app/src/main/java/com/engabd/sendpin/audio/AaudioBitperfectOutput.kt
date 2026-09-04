package com.engabd.sendpin.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import com.engabd.sendpin.data.AppSettings
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Bit-perfect local output through Android's AAudio API.
 *
 * This is the AAudio equivalent of the Oboe-based [OboeAudioSink]/[SendspinNativeOutput]
 * pair, but built for this phone's own decoded library files rather than the Music
 * Assistant stream. It sits in ExoPlayer's renderer factory as the [AudioSink], bypassing
 * media3's [DefaultAudioSink] mixer, resampler, and every in-app audio processor so the
 * PCM the decoder produced reaches a USB DAC unchanged.
 *
 * What this *does* still do:
 *  - accept decoded PCM from ExoPlayer via [handleBuffer]
 *  - forward it to a native AAudio stream opened in the matching format
 *  - keep [AudioLead] updated so the light show can stay on time
 *  - report the signal path state
 *
 * What it deliberately does *not* do:
 *  - run the equaliser, sound modes, or Light Sync tap (those are disabled upstream in
 *    [TapRenderersFactory] when AAudio bit-perfect is active)
 *  - resample, dither, or change bit depth
 *  - Bluetooth output (AAudio exclusive streams target a pinned USB device)
 *
 * Only PCM formats that AAudio can carry directly are supported: 16-bit, 24-bit packed,
 * 32-bit integer, and 32-bit float, at the file's own sample rate. Anything else throws
 * [AudioSink.ConfigurationException] and ExoPlayer falls back to the next sink candidate,
 * which is the normal [DefaultAudioSink] path.
 */
@OptIn(UnstableApi::class)
class AaudioBitperfectOutput(
    private val context: Context,
    /**
     * The placeholder sink that [TapRenderersFactory] built for us. We wrap it only so
     * media3 still has something it recognises if AAudio fails to start; audio never
     * actually reaches it.
     */
    private val fallbackSink: AudioSink,
    private val lead: AudioLead,
) : AudioSink {

    companion object {
        private const val TAG = "AaudioBitperfect"

        /** Approximate ring depth, used only for ExoPlayer's own buffering heuristics. */
        private const val RING_BUFFER_US = 2_000_000L

        /** Native library that opens and feeds the AAudio stream. */
        private const val NATIVE_LIB = "sendspin_aaudio"

        /** Returns true once [System.loadLibrary] succeeds. */
        private val libraryLoaded: Boolean by lazy {
            try {
                System.loadLibrary(NATIVE_LIB)
                true
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "AAudio native library not available: ${e.message}")
                false
            }
        }

        /** PCM encoding this sink can hand straight to AAudio. */
        fun supportsFormat(format: Format): Boolean = when (format.pcmEncoding) {
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_24BIT,
            C.ENCODING_PCM_32BIT,
            C.ENCODING_PCM_FLOAT -> true
            else -> false
        }

        /** Bytes per sample for a supported encoding. */
        private fun bytesPerSample(encoding: Int): Int = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
            else -> 2
        }

        /**
         * media3's encoding for one of the native side's own format codes — see
         * the FORMAT_* constants in sendspin_aaudio.cpp.
         *
         * The translation lives here, where `C.ENCODING_*` can be named, rather
         * than as copies of media3's numeric values in C++ where a renumbering
         * upstream would go unnoticed until something played back as noise.
         */
        private fun formatCodeOf(encoding: Int): Int = when (encoding) {
            C.ENCODING_PCM_16BIT -> 1
            C.ENCODING_PCM_24BIT -> 2
            C.ENCODING_PCM_32BIT -> 3
            C.ENCODING_PCM_FLOAT -> 4
            else -> 0
        }
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var listener: AudioSink.Listener? = null
    private var audioAttributes = AudioAttributes.DEFAULT
    private var playbackParameters = PlaybackParameters.DEFAULT
    private var skipSilenceEnabled = false

    private var configuredFormat: Format? = null
    private var channelCount = 2
    private var sampleRate = 48_000
    private var pcmEncoding = C.ENCODING_PCM_16BIT
    private var bytesPerFrame = 4

    private var streamPtr: Long = 0L
    private var framesWrittenTotal = 0L
    private var endOfStreamRequested = false

    /** The device the open stream was built for; 0 is "wherever Android routes to". */
    private var openedDeviceId = 0

    private var scratch = ByteArray(0)
    private var firstWriteLogged = false

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
        fallbackSink.setListener(listener)
    }

    override fun supportsFormat(format: Format): Boolean =
        Companion.supportsFormat(format)

    override fun getFormatSupport(format: Format): Int =
        if (supportsFormat(format)) AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        else AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun configure(format: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        if (!supportsFormat(format)) {
            throw AudioSink.ConfigurationException(
                "AAudio bit-perfect does not support encoding ${format.pcmEncoding}",
                format,
            )
        }
        if (!libraryLoaded) {
            throw AudioSink.ConfigurationException(
                "AAudio native library is not available on this device",
                format,
            )
        }
        closeStream()
        channelCount = format.channelCount
        sampleRate = format.sampleRate
        pcmEncoding = format.pcmEncoding
        bytesPerFrame = channelCount * bytesPerSample(pcmEncoding)
        framesWrittenTotal = 0L
        endOfStreamRequested = false
        firstWriteLogged = false
        configuredFormat = format
        SignalPath.onDecoderOutput(format)

        val preferred = preferredDeviceId()
        val opened = nativeOpenStream(sampleRate, channelCount, formatCodeOf(pcmEncoding), preferred)
        if (opened == 0L) {
            throw AudioSink.ConfigurationException(
                "Failed to open AAudio stream ${sampleRate}Hz/${channelCount}ch/enc=${pcmEncoding} " +
                    "on device=$preferred",
                format,
            )
        }
        streamPtr = opened
        openedDeviceId = preferred
        Log.i(TAG, "configured ${sampleRate}Hz/${channelCount}ch/enc=${pcmEncoding} device=$preferred")
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        val remaining = buffer.remaining()
        if (remaining <= 0) return true
        val p = streamPtr
        if (p == 0L) return false

        if (scratch.size < remaining) scratch = ByteArray(remaining)
        val startPosition = buffer.position()
        buffer.get(scratch, 0, remaining)

        lead.mediaTimeUs = presentationTimeUs
        val writtenFrames = nativeWrite(p, scratch, 0, remaining)
        val bytesAccepted = (writtenFrames * bytesPerFrame).coerceIn(0L, remaining.toLong()).toInt()

        if (bytesAccepted < remaining) {
            buffer.position(startPosition + bytesAccepted)
        }
        framesWrittenTotal += writtenFrames
        logFirstWrite(writtenFrames, remaining)
        return bytesAccepted >= remaining
    }

    private fun logFirstWrite(frames: Long, offeredBytes: Int) {
        if (firstWriteLogged || frames <= 0) return
        firstWriteLogged = true
        Log.i(
            TAG,
            "first write: $frames frames accepted of ${offeredBytes / bytesPerFrame} offered " +
                "@ ${sampleRate}Hz/${channelCount}ch/enc=${pcmEncoding}",
        )
    }

    override fun play() {
        nativeResume(streamPtr)
    }

    override fun pause() {
        nativePause(streamPtr)
    }

    override fun flush() {
        nativeFlush(streamPtr)
        framesWrittenTotal = 0L
        endOfStreamRequested = false
        lead.leadUs = AudioLead.UNKNOWN
        lead.mediaTimeUs = AudioLead.UNKNOWN
    }

    override fun reset() {
        closeStream()
        configuredFormat = null
        framesWrittenTotal = 0L
        endOfStreamRequested = false
        lead.leadUs = AudioLead.UNKNOWN
        lead.mediaTimeUs = AudioLead.UNKNOWN
    }

    override fun playToEndOfStream() {
        endOfStreamRequested = true
    }

    override fun isEnded(): Boolean =
        endOfStreamRequested && nativeBufferedFrames(streamPtr) <= 0

    override fun hasPendingData(): Boolean =
        nativeBufferedFrames(streamPtr) > 0

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val p = streamPtr
        if (p == 0L || sampleRate <= 0) return 0L
        val buffered = nativeBufferedFrames(p)
        val played = (framesWrittenTotal - buffered).coerceAtLeast(0)
        return played * 1_000_000L / sampleRate
    }

    override fun getPlaybackParameters(): PlaybackParameters = playbackParameters
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        this.playbackParameters = playbackParameters
    }

    override fun getSkipSilenceEnabled(): Boolean = skipSilenceEnabled
    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
        this.skipSilenceEnabled = skipSilenceEnabled
    }

    override fun getAudioAttributes(): AudioAttributes = audioAttributes
    override fun setAudioAttributes(audioAttributes: AudioAttributes) {
        this.audioAttributes = audioAttributes
        fallbackSink.setAudioAttributes(audioAttributes)
    }

    override fun setAudioSessionId(audioSessionId: Int) {
        // AAudio has no platform audio session; nothing to attach.
    }

    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {
        // No auxiliary effects chain on the direct AAudio path.
    }

    /**
     * Follow the output the player was just pointed at, mid-track.
     *
     * The device used to be read once, at [configure], and every later change was
     * dropped on the floor: swap the DAC while Direct to DAC is running and the
     * stream went on addressing the one that had gone, with nothing short of a
     * restart or a trip through the output ladder to get the music back.
     *
     * The swap keeps the native output and everything queued on it, and only the
     * AAudio stream underneath is replaced — see `nativeSetDevice` — so no audio is
     * lost and the position this sink reports never moves. A device that will not
     * take the stream in this format is a real answer, not a failure: it is what a
     * DAC that cannot do 192 kHz says, and what a device unplugged again between
     * the callback and this line says. So the next-best output is tried, then the
     * one that was already working, then whatever Android routes to.
     *
     * Called on the playback thread, like [configure] and [handleBuffer].
     */
    override fun setPreferredDevice(audioDeviceInfo: AudioDeviceInfo?) {
        val want = audioDeviceInfo?.id ?: 0
        val p = streamPtr
        // Nothing open to move. The next configure() reads the pinned device itself.
        if (p == 0L || want == openedDeviceId) return

        for (id in listOf(want, openedDeviceId, 0).distinct()) {
            if (!nativeSetDevice(p, id)) continue
            if (id == want) Log.i(TAG, "moved the AAudio stream to device=$id")
            else Log.w(TAG, "device=$want would not take the stream; on device=$id instead")
            openedDeviceId = id
            return
        }
        // No output would take it at all, which means the route is gone rather than
        // changed. The AAudio stream is closed native-side either way; let the output
        // go with it, so nothing is queued onto one that can never play it.
        Log.e(TAG, "no output would take the AAudio stream; closing it")
        closeStream()
    }

    override fun getAudioTrackBufferSizeUs(): Long = RING_BUFFER_US

    override fun enableTunnelingV21() { /* No tunneling on AAudio direct path. */ }
    override fun disableTunneling() { /* No tunneling on AAudio direct path. */ }

    override fun setVolume(volume: Float) {
        nativeSetVolume(streamPtr, volume)
    }

    override fun handleDiscontinuity() {
        // No internal timeline state to reset beyond what the renderer already manages.
    }

    private fun preferredDeviceId(): Int {
        return try {
            val pinned = AppSettings(context).bootPreferredAudioDeviceId
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.id.toString() == pinned }?.id ?: 0
        } catch (_: Exception) {
            0
        }
    }

    private fun closeStream() {
        val p = streamPtr
        openedDeviceId = 0
        if (p != 0L) {
            streamPtr = 0L
            nativeClose(p)
        }
    }

    /** Native AAudio bridge. Declarations only; implementation lives in cpp/sendspin_aaudio.cpp. */
    private external fun nativeOpenStream(sampleRate: Int, channels: Int, formatCode: Int, deviceId: Int): Long

    /**
     * Reopen [ptr]'s stream on another output, keeping the PCM already queued on it
     * and the frame counters [getCurrentPositionUs] is read from. False if that
     * output would not give up a stream in this format, in which case the native
     * output is left with no stream at all and is only good for closing.
     */
    private external fun nativeSetDevice(ptr: Long, deviceId: Int): Boolean
    private external fun nativeClose(ptr: Long)
    private external fun nativeWrite(ptr: Long, pcm: ByteArray, offset: Int, length: Int): Long
    private external fun nativeFlush(ptr: Long)
    private external fun nativePause(ptr: Long)
    private external fun nativeResume(ptr: Long)
    private external fun nativeBufferedFrames(ptr: Long): Long
    private external fun nativeSetVolume(ptr: Long, volume: Float)
}
