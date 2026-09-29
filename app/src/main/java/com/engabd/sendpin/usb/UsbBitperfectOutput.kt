package com.engabd.sendpin.usb

import android.content.Context
import android.hardware.usb.UsbManager
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.AudioDeviceInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import com.engabd.sendpin.audio.AudioLead
import com.engabd.sendpin.audio.SignalPath
import java.nio.ByteBuffer

/**
 * "USB bit-perfect": ExoPlayer's decoded PCM straight to a USB DAC through CAMusic's own
 * driver ([UsbAudioSession]), at the file's own rate, with every sample unchanged
 * ([UsbPcm]). docs/plan/usb-bitperfect-driver.md, milestone 3.
 *
 * Anything the DAC cannot take as it is — no DAC attached, no USB permission, a rate it
 * does not offer (192 kHz on a BTD 700), a compressed format — goes to [fallback], the
 * ordinary sink, for that track: the music plays either way, and the signal path says
 * which way it went.
 *
 * The DAC is taken while playing and handed back to Android after [IDLE_HANDBACK_MS]
 * paused, with the queued audio kept so playback resumes where it was.
 */
@OptIn(UnstableApi::class)
class UsbBitperfectOutput(
    private val context: Context,
    private val fallback: AudioSink,
    private val lead: AudioLead,
) : AudioSink {

    private var session: UsbAudioSession? = null
    /** Whether the current track goes to the USB DAC (true) or to [fallback]. */
    private var usbActive = false

    private var inEncoding = C.ENCODING_PCM_16BIT
    private var inBytesPerFrame = 4
    private var channels = 2
    private var rate = 0
    private var slotBytes = 2
    private var bits = 16

    private var startMediaTimeUs = C.TIME_UNSET
    private var playedBase = 0L
    private var endOfStream = false
    private var playing = false
    private var volume = 1f

    private var scratch = ByteArray(0)
    private val carry = ByteArray(32)
    private var carryLen = 0

    /**
     * Tell Android that CAMusic is the app playing.
     *
     * Android gives the media session - the notification's controls, the headphone and
     * Bluetooth buttons - to the app that most recently started audio through Android's
     * own audio stack. This output never does, so Android believed the last player was
     * whichever other app had played, and handed that app the session: pause from the
     * headphones still reached CAMusic while it played, play never came back.
     *
     * So on each start or resume, a tenth of a second of silence goes through Android
     * and stops. Not a loop: once per press, to the phone's own output (the DAC is
     * CAMusic's), and nothing afterwards. Android then keeps CAMusic as the player until
     * another app actually plays.
     */
    private fun announcePlaying() {
        val frames = 4800 // 100 ms at 48 kHz
        runCatching {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(48_000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(frames * 2)
                .build()
            track.write(ShortArray(frames), 0, frames)
            track.play()
            Handler(Looper.myLooper() ?: Looper.getMainLooper())
                .postDelayed({ runCatching { track.stop() }; track.release() }, 300)
        }.onFailure { Log.w(TAG, "could not announce playback to Android", it) }
    }

    private var handler: Handler? = null
    private val handBack = Runnable {
        session?.let {
            Log.i(TAG, "paused ${IDLE_HANDBACK_MS / 1000}s: handing the DAC back to Android")
            it.suspendForAndroid()
        }
    }

    private fun isRawPcm(format: Format) =
        format.sampleMimeType == MimeTypes.AUDIO_RAW && UsbPcm.bytesPerSample(format.pcmEncoding) > 0

    override fun setListener(listener: AudioSink.Listener) = fallback.setListener(listener)

    override fun supportsFormat(format: Format): Boolean = isRawPcm(format) || fallback.supportsFormat(format)

    override fun getFormatSupport(format: Format): Int =
        if (isRawPcm(format)) AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY else fallback.getFormatSupport(format)

    /** The last configure(), so a DAC lost mid-track can hand the track to [fallback]. */
    private var lastFormat: Format? = null
    private var lastBufferSize = 0
    private var lastOutputChannels: IntArray? = null

    /**
     * The DAC went away mid-track (unplugged): hand the rest of the track to [fallback],
     * configured as the DAC was. The player has already been paused by the unplug; when
     * play is pressed again the music carries on through the phone, not into nothing.
     * Returns false if there was nothing to fail over to.
     */
    private fun failOverIfLost(): Boolean {
        val s = session ?: return false
        if (!usbActive || !s.streamDied) return false
        val format = lastFormat ?: return false
        Log.i(TAG, "the DAC is gone: carrying on through Android")
        toFallback("the DAC was disconnected")
        runCatching { fallback.configure(format, lastBufferSize, lastOutputChannels) }
            .onFailure { Log.w(TAG, "fallback could not take over", it) }
        if (playing) fallback.play()
        return true
    }

    override fun configure(format: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        lastFormat = format
        lastBufferSize = specifiedBufferSize
        lastOutputChannels = outputChannels
        SignalPath.onDecoderOutput(format)
        val target = if (isRawPcm(format)) pick(format) else null
        val s = session
        if (target == null || s == null) {
            toFallback("the DAC does not offer ${format.sampleRate} Hz x ${format.channelCount} ch")
            fallback.configure(format, specifiedBufferSize, outputChannels)
            return
        }
        val (alt, wantRate) = target
        val same = usbActive && !s.suspended && s.format == alt && s.confirmedRate == wantRate
        if (!same) {
            drainBeforeSwitch(s)
            if (!s.configure(alt, wantRate) || !s.start()) {
                toFallback(s.error ?: "the DAC could not be configured")
                fallback.configure(format, specifiedBufferSize, outputChannels)
                return
            }
            s.setPaused(!playing)
            Log.i(TAG, "USB bit-perfect: ${alt.bitResolution}-bit ${s.confirmedRate} Hz, ${alt.channels} ch (decoder ${format.pcmEncoding})")
        }
        fallback.reset()
        usbActive = true
        inEncoding = format.pcmEncoding
        channels = format.channelCount
        inBytesPerFrame = UsbPcm.bytesPerSample(inEncoding) * channels
        rate = s.confirmedRate ?: wantRate
        slotBytes = alt.subslotBytes
        bits = alt.bitResolution
        resetCounters()
    }

    /** The DAC format for [format]: its own rate, the deepest bit depth the DAC offers. */
    private fun pick(format: Format): Pair<StreamingAlt, Int>? {
        val s = session ?: openSession() ?: return null
        val rate = format.sampleRate
        val alt = s.info.outputs
            .filter { it.channels == format.channelCount && it.endpoint != null }
            .filter { a ->
                rate in a.sampleRates ||
                    a.sampleRateRange?.contains(rate) == true ||
                    // UAC2 lists no rates in the descriptors; the clock read-back decides.
                    (a.sampleRates.isEmpty() && a.sampleRateRange == null)
            }
            .maxWithOrNull(compareBy<StreamingAlt>({ it.bitResolution }, { it.subslotBytes }))
            ?: return null
        return alt to rate
    }

    private fun openSession(): UsbAudioSession? {
        val usb = context.getSystemService(UsbManager::class.java) ?: return null
        val device = UsbDacProbe.audioDevices(context).firstOrNull() ?: return null
        if (!usb.hasPermission(device)) {
            Log.w(TAG, "no USB permission for ${device.productName}; playing through Android")
            return null
        }
        return UsbAudioSession.open(context, device)?.also { session = it }
    }

    /**
     * A new format on the same DAC: let the previous track's queued audio play out
     * first (up to 1.5 s, and only while playing), rather than cut its last second.
     */
    private fun drainBeforeSwitch(s: UsbAudioSession) {
        if (!usbActive || !playing || s.suspended) return
        val until = SystemClock.uptimeMillis() + 1500
        while (s.pendingFrames() > 0 && SystemClock.uptimeMillis() < until) Thread.sleep(5)
    }

    private fun toFallback(why: String) {
        if (usbActive || session != null) Log.i(TAG, "playing through Android: $why")
        usbActive = false
        closeSession()
    }

    private fun closeSession() {
        handler?.removeCallbacks(handBack)
        session?.close()
        session = null
    }

    private fun resetCounters() {
        startMediaTimeUs = C.TIME_UNSET
        playedBase = session?.playedFrames() ?: 0L
        endOfStream = false
        carryLen = 0
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        failOverIfLost()
        if (!usbActive) return fallback.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        val s = session ?: return false
        if (!buffer.hasRemaining()) return true
        if (startMediaTimeUs == C.TIME_UNSET) startMediaTimeUs = presentationTimeUs
        lead.mediaTimeUs = presentationTimeUs

        val outBytesPerFrame = slotBytes * channels
        val ringBytes = rate.toLong() * outBytesPerFrame
        var room = ((ringBytes - s.queuedBytes()) / outBytesPerFrame).toInt()

        // A frame split across two buffers: finish it first.
        if (carryLen > 0) {
            if (room < 1) return false
            while (carryLen < inBytesPerFrame && buffer.hasRemaining()) carry[carryLen++] = buffer.get()
            if (carryLen < inBytesPerFrame) return true
            writeFrames(s, ByteBuffer.wrap(carry, 0, inBytesPerFrame), 1)
            carryLen = 0
            room--
        }

        val frames = minOf(buffer.remaining() / inBytesPerFrame, room)
        if (frames > 0) writeFrames(s, buffer, frames)
        if (buffer.remaining() in 1 until inBytesPerFrame) {
            while (buffer.hasRemaining()) carry[carryLen++] = buffer.get()
        }
        return !buffer.hasRemaining()
    }

    private fun writeFrames(s: UsbAudioSession, src: ByteBuffer, frames: Int) {
        val need = frames * channels * slotBytes
        if (scratch.size < need) scratch = ByteArray(need)
        val n = UsbPcm.convert(src, inEncoding, frames * channels, scratch, 0, slotBytes, bits, volume)
        var off = 0
        // Room was checked, so this takes everything; the loop only guards a race with a flush.
        repeat(3) { if (off < n) off += s.write(scratch, off, n - off) }
    }

    override fun play() {
        val wasPlaying = playing
        playing = true
        handler?.removeCallbacks(handBack)
        failOverIfLost()
        if (!usbActive) return fallback.play()
        val s = session ?: return
        if (s.suspended && !s.resumeFromAndroid()) {
            Log.w(TAG, "could not take the DAC back: ${s.error}")
        }
        s.setPaused(false)
        if (!wasPlaying) announcePlaying()
    }

    override fun pause() {
        playing = false
        if (!usbActive) return fallback.pause()
        session?.setPaused(true)
        val looper = Looper.myLooper() ?: return
        val h = handler ?: Handler(looper).also { handler = it }
        h.removeCallbacks(handBack)
        h.postDelayed(handBack, IDLE_HANDBACK_MS)
    }

    override fun flush() {
        if (!usbActive) return fallback.flush()
        session?.flush()
        resetCounters()
    }

    override fun reset() {
        fallback.reset()
        usbActive = false
        closeSession()
    }

    override fun release() {
        closeSession()
        fallback.release()
    }

    override fun playToEndOfStream() {
        if (!usbActive) return fallback.playToEndOfStream()
        endOfStream = true
    }

    override fun isEnded(): Boolean =
        if (!usbActive) fallback.isEnded() else endOfStream && (session?.pendingFrames() ?: 0L) <= 0L

    override fun hasPendingData(): Boolean =
        if (!usbActive) fallback.hasPendingData()
        // A dead stream has nothing pending; claiming otherwise would stall the player.
        else if (session?.streamDied == true) false
        else (session?.pendingFrames() ?: 0L) > 0L

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        if (!usbActive) return fallback.getCurrentPositionUs(sourceEnded)
        val s = session ?: return AudioSink.CURRENT_POSITION_NOT_SET
        val start = startMediaTimeUs
        if (start == C.TIME_UNSET || rate <= 0) return AudioSink.CURRENT_POSITION_NOT_SET
        return start + (s.playedFrames() - playedBase) * 1_000_000L / rate
    }

    override fun setVolume(volume: Float) {
        // 1.0 is bit-perfect. Anything else — a fade, ducking for a notification — is
        // digital gain applied to the samples written from now on.
        this.volume = volume
        fallback.setVolume(volume)
    }

    override fun getAudioTrackBufferSizeUs(): Long =
        if (usbActive) 1_000_000L else fallback.audioTrackBufferSizeUs

    override fun getPlaybackParameters(): PlaybackParameters = fallback.playbackParameters
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) = fallback.setPlaybackParameters(playbackParameters)
    override fun getSkipSilenceEnabled(): Boolean = fallback.skipSilenceEnabled
    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) = fallback.setSkipSilenceEnabled(skipSilenceEnabled)
    override fun getAudioAttributes(): AudioAttributes? = fallback.audioAttributes
    override fun setAudioAttributes(audioAttributes: AudioAttributes) = fallback.setAudioAttributes(audioAttributes)
    override fun setAudioSessionId(audioSessionId: Int) = fallback.setAudioSessionId(audioSessionId)
    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) = fallback.setAuxEffectInfo(auxEffectInfo)
    override fun setPreferredDevice(audioDeviceInfo: AudioDeviceInfo?) = fallback.setPreferredDevice(audioDeviceInfo)
    override fun enableTunnelingV21() = fallback.enableTunnelingV21()
    override fun disableTunneling() = fallback.disableTunneling()
    override fun handleDiscontinuity() { if (!usbActive) fallback.handleDiscontinuity() }

    companion object {
        private const val TAG = "UsbBitperfect"
        /** Paused this long, the DAC goes back to Android. User decision, 2026-09-29. */
        const val IDLE_HANDBACK_MS = 30_000L
    }
}
