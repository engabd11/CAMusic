package com.engabd.sendpin.usb

import android.content.Context
import android.hardware.usb.UsbManager
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.AudioDeviceInfo
import android.os.Handler
import android.os.Looper
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

    /** Set when this track is above the DAC's rates and is being divided down; see [pick]. */
    private var decimator: UsbDecimator? = null
    private var convertedFromHz: Int? = null
    private var decimation = 1
    private var floatIn = FloatArray(0)
    private var floatOut = FloatArray(0)
    private var floatBytes: java.nio.ByteBuffer = java.nio.ByteBuffer.allocate(0)
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
            // On the main looper, not the playback thread's: that one quits when the
            // player is released, and a stop queued on it then never ran - the track
            // leaked with its native buffer.
            Handler(Looper.getMainLooper())
                .postDelayed({ runCatching { track.stop() }; track.release() }, 300)
        }.onFailure { Log.w(TAG, "could not announce playback to Android", it) }
    }

    private var handler: Handler? = null
    private val handBack = Runnable {
        session?.let {
            Log.i(TAG, "paused ${IDLE_HANDBACK_MS / 1000}s: handing the DAC back to Android")
            UsbVolume.detach(it)
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
        Log.i(TAG, "the DAC is gone: carrying on through Android")
        return failOver("the DAC was disconnected")
    }

    /** Hand the rest of the track to [fallback], configured as the DAC was. */
    private fun failOver(why: String): Boolean {
        val format = lastFormat ?: return false
        pendingSwitch = null
        toFallback(why)
        runCatching { fallback.configure(format, lastBufferSize, lastOutputChannels) }
            .onFailure { Log.w(TAG, "fallback could not take over", it) }
        if (playing) fallback.play()
        return true
    }

    /**
     * A format change the DAC has to make, waiting for the previous track's queued audio
     * to play out — see [configure]. Applied by [handleBuffer] once the queue is empty.
     */
    private data class PendingSwitch(
        val format: Format,
        val bufferSize: Int,
        val outputChannels: IntArray?,
        /** A same-rate bit-depth change from [handleDiscontinuity], rather than a configure. */
        val depthOnly: StreamingAlt? = null,
    )

    private var pendingSwitch: PendingSwitch? = null

    /** Apply [pendingSwitch] once nothing of the previous format is left to play. */
    private fun applyPendingSwitch(s: UsbAudioSession): Boolean {
        val p = pendingSwitch ?: return true
        if (playing && !s.suspended && s.pendingFrames() > 0) return false
        pendingSwitch = null
        if (p.depthOnly != null) switchDepth(s, p.depthOnly) else configureNow(p.format, p.bufferSize, p.outputChannels)
        return true
    }

    override fun configure(format: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        lastFormat = format
        lastBufferSize = specifiedBufferSize
        lastOutputChannels = outputChannels
        SignalPath.onDecoderOutput(format)
        // A new format on the same DAC while the previous track is still queued: let it
        // play out first, rather than cut its last second. This used to wait right here,
        // up to 1.5 s, holding the player's own thread; now the switch waits in
        // [pendingSwitch] and handleBuffer() declines the new track's audio until the
        // queue is empty, which is how media3's own sink drains a format change.
        val s = session
        if (usbActive && s != null && !s.suspended && playing && s.pendingFrames() > 0 &&
            isRawPcm(format) && needsSwitch(s, format)
        ) {
            pendingSwitch = PendingSwitch(format, specifiedBufferSize, outputChannels)
            return
        }
        pendingSwitch = null
        configureNow(format, specifiedBufferSize, outputChannels)
    }

    /** Whether [format] would put the DAC in a different format or rate from the one it is in. */
    private fun needsSwitch(s: UsbAudioSession, format: Format): Boolean {
        val first = candidates(format).firstOrNull() ?: return true
        return !(s.format == first.alt && s.confirmedRate == first.dacRate)
    }

    private fun configureNow(format: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        val all = if (isRawPcm(format)) candidates(format) else emptyList()
        val s = session
        if (all.isEmpty() || s == null) {
            toFallback("the DAC does not offer ${format.sampleRate} Hz x ${format.channelCount} ch")
            fallback.configure(format, specifiedBufferSize, outputChannels)
            // Mid-queue, media3 already told this sink to play; the fallback never heard
            // it. Unstarted, it took no audio and the player stalled at the track change.
            if (playing) fallback.play()
            return
        }
        // The file's own rate first, then the rates CAMusic can divide it down to. A DAC
        // that refuses one - its clock does not list the rate, or reports back another -
        // gets the next, rather than the whole track going to Android.
        var target: Target? = null
        for (t in all) {
            val same = usbActive && !s.suspended && s.format == t.alt && s.confirmedRate == t.dacRate
            if (same || (s.configure(t.alt, t.dacRate) && s.start())) {
                target = t
                if (!same) {
                    s.setPaused(!playing)
                    Log.i(TAG, "USB bit-perfect: ${t.alt.bitResolution}-bit ${s.confirmedRate} Hz, ${t.alt.channels} ch (decoder ${format.pcmEncoding})")
                }
                break
            }
            Log.i(TAG, "${UacReport.khz(t.dacRate)} kHz refused: ${s.error}")
        }
        if (target == null) {
            toFallback(s.error ?: "the DAC could not be configured")
            fallback.configure(format, specifiedBufferSize, outputChannels)
            if (playing) fallback.play()
            return
        }
        val (alt, wantRate, factor) = target
        fallback.reset()
        usbActive = true
        decimator = if (factor > 1) UsbDecimator(factor, format.channelCount) else null
        decimation = factor
        convertedFromHz = if (factor > 1) format.sampleRate else null
        if (factor > 1) Log.i(TAG, "converting ${format.sampleRate} Hz to $wantRate Hz for the DAC")
        UsbVolume.attach(s, allowDigital = com.engabd.sendpin.data.AppSettings(context).bootUsbDigitalVolume)
        publishPath(s, alt)
        inEncoding = format.pcmEncoding
        channels = format.channelCount
        inBytesPerFrame = UsbPcm.bytesPerSample(inEncoding) * channels
        rate = s.confirmedRate ?: wantRate
        slotBytes = alt.subslotBytes
        bits = alt.bitResolution
        resetCounters()
    }

    /** Where a track goes: the DAC format, the DAC's rate, and the ratio it was divided by (1 = none). */
    private data class Target(val alt: StreamingAlt, val dacRate: Int, val factor: Int)

    /**
     * The DAC format for [format]: its own rate, and the file's own bit depth where the
     * DAC has it (see [UsbFormatChoice]). The file's depth comes from its own header, via
     * the signal path; the decoder's output is float and no longer says.
     *
     * A rate above everything the DAC takes (192 kHz on a 96 kHz DAC) is divided by 2 or
     * 4 in CAMusic ([UsbDecimator]) rather than handed to Android, whose own path on many
     * phones converts everything to 48 kHz. Not bit-perfect, and the signal path says so,
     * but it keeps the DAC on this driver at the best rate it has. Float input only,
     * which is what the float path decodes to.
     */
    private fun candidates(format: Format): List<Target> {
        val s = session ?: openSession() ?: return emptyList()
        val rate = format.sampleRate
        val sourceBits = SignalPath.state.value.source.bitDepth
        // With each UAC2 alternate's rates filled in from its clock once read: a DAC
        // whose clock has said it stops at 96 kHz is not offered 192 kHz again.
        val outputs = s.outputs()
        val out = mutableListOf<Target>()
        UsbFormatChoice.choose(outputs, rate, format.channelCount, sourceBits)?.let { out += Target(it, rate, 1) }
        if (format.pcmEncoding == C.ENCODING_PCM_FLOAT) {
            for (factor in listOf(2, 4)) {
                if (rate % factor != 0) continue
                val alt = UsbFormatChoice.choose(outputs, rate / factor, format.channelCount, null) ?: continue
                out += Target(alt, rate / factor, factor)
            }
        }
        return out
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

    /** Tell the signal path what the DAC was given and confirmed. */
    private fun publishPath(s: UsbAudioSession, alt: StreamingAlt) {
        val vol = UsbVolume.state.value
        SignalPath.onUsbPath(
            SignalPath.UsbPath(
                dacName = s.dacName,
                confirmedRateHz = s.confirmedRate ?: 0,
                dacBits = alt.bitResolution,
                slotBits = alt.subslotBytes * 8,
                samplesUntouched = vol?.digital != true && convertedFromHz == null,
                convertedFromHz = convertedFromHz,
                asyncClock = alt.endpoint?.sync == "asynchronous" || alt.feedback != null,
                volume = when {
                    vol == null -> "none on the DAC (set it on the headphones or amp)"
                    vol.digital -> "digital (not bit-perfect)"
                    else -> "DAC hardware volume"
                },
            ),
        )
    }

    private fun toFallback(why: String) {
        SignalPath.onUsbPath(null)
        if (usbActive || session != null) Log.i(TAG, "playing through Android: $why")
        usbActive = false
        closeSession()
    }

    private fun closeSession() {
        session?.let(UsbVolume::detach)
        handler?.removeCallbacks(handBack)
        session?.close()
        session = null
    }

    private fun resetCounters() {
        startMediaTimeUs = C.TIME_UNSET
        playedBase = session?.playedFrames() ?: 0L
        endOfStream = false
        carryLen = 0
        decimator?.reset()
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        failOverIfLost()
        if (usbActive) session?.let { if (!applyPendingSwitch(it)) return false }
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

        val frames = minOf(buffer.remaining() / inBytesPerFrame, room * decimation)
        if (frames > 0) writeFrames(s, buffer, frames)
        if (buffer.remaining() in 1 until inBytesPerFrame) {
            while (buffer.hasRemaining()) carry[carryLen++] = buffer.get()
        }
        return !buffer.hasRemaining()
    }

    private fun writeFrames(s: UsbAudioSession, src: ByteBuffer, frames: Int) {
        val d = decimator
        if (d != null) return writeDecimated(s, d, src, frames)
        val need = frames * channels * slotBytes
        if (scratch.size < need) scratch = ByteArray(need)
        // volume is ExoPlayer's (fades, ducking); digitalGain is the opt-in digital volume
        // for a DAC with none of its own. Both 1 is the file's own samples.
        val n = UsbPcm.convert(src, inEncoding, frames * channels, scratch, 0, slotBytes, bits, volume * UsbVolume.digitalGain)
        var off = 0
        // Room was checked, so this takes everything; the loop only guards a race with a flush.
        repeat(3) { if (off < n) off += s.write(scratch, off, n - off) }
    }

    /** Float input divided down by [d], then converted to the DAC's format. */
    private fun writeDecimated(s: UsbAudioSession, d: UsbDecimator, src: ByteBuffer, frames: Int) {
        val n = frames * channels
        if (floatIn.size < n) floatIn = FloatArray(n)
        val fb = src.order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        fb.get(floatIn, 0, n)
        src.position(src.position() + n * 4)
        val outCap = (frames / 2 + 1) * channels
        if (floatOut.size < outCap) floatOut = FloatArray(outCap)
        val outFrames = d.process(floatIn, frames, floatOut)
        if (outFrames <= 0) return
        val bytes = outFrames * channels * 4
        if (floatBytes.capacity() < bytes) floatBytes = java.nio.ByteBuffer.allocate(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        floatBytes.clear()
        floatBytes.asFloatBuffer().put(floatOut, 0, outFrames * channels)
        floatBytes.limit(bytes)
        val need = outFrames * channels * slotBytes
        if (scratch.size < need) scratch = ByteArray(need)
        val m = UsbPcm.convert(floatBytes, UsbPcm.PCM_FLOAT, outFrames * channels, scratch, 0, slotBytes, bits, volume * UsbVolume.digitalGain)
        var off = 0
        repeat(3) { if (off < m) off += s.write(scratch, off, m - off) }
    }

    override fun play() {
        val wasPlaying = playing
        playing = true
        handler?.removeCallbacks(handBack)
        failOverIfLost()
        if (!usbActive) return fallback.play()
        val s = session ?: return
        if (s.suspended) {
            if (s.resumeFromAndroid()) {
                UsbVolume.attach(s, allowDigital = com.engabd.sendpin.data.AppSettings(context).bootUsbDigitalVolume)
            } else {
                // Not taken back - another app holds it now, or it refused the rate. This
                // used to log and carry on: the session stayed suspended, so nothing
                // counted the stream as dead, the queue filled and then refused every
                // buffer, and the player sat on "playing" with a frozen bar and no sound.
                // The rest of the track goes through Android instead, and says so.
                Log.w(TAG, "could not take the DAC back: ${s.error}")
                failOver("the DAC could not be taken back (${s.error ?: "refused"})")
                if (!wasPlaying) announcePlaying()
                return
            }
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
        // A seek: nothing of the old format is left to wait for.
        session?.let { applyPendingSwitch(it) }
    }

    override fun reset() {
        pendingSwitch = null
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
    /**
     * A track boundary in the output (media3 calls this from onProcessedStreamChange).
     *
     * Two tracks at the same rate but different bit depths decode to the same float
     * format, so media3 never calls configure() between them - the second would go out
     * in the first one's format, and a 24-bit track after a 16-bit one would be cut to
     * 16 bits. So the format is chosen again here, from the new track's own depth; if it
     * differs, the previous track plays out and the DAC switches at the boundary.
     */
    override fun handleDiscontinuity() {
        if (!usbActive) return fallback.handleDiscontinuity()
        val s = session ?: return
        // A new rate reaches configure() anyway; only a same-rate depth change needs this.
        val nextRate = SignalPath.state.value.source.sampleRateHz
        if (nextRate != null && nextRate != rate) return
        // A converted track's rate differs from the DAC's by design; its end is a rate
        // change, which configure() handles.
        if (decimator != null) return
        val want = UsbFormatChoice.choose(s.outputs(), rate, channels, SignalPath.state.value.source.bitDepth) ?: return
        if (want == s.format) return
        Log.i(TAG, "track boundary: ${s.format?.bitResolution}-bit -> ${want.bitResolution}-bit at $rate Hz")
        val format = lastFormat ?: return
        // The previous track's last second plays out first, without holding this thread.
        pendingSwitch = PendingSwitch(format, lastBufferSize, lastOutputChannels, depthOnly = want)
        applyPendingSwitch(s)
    }

    private fun switchDepth(s: UsbAudioSession, want: StreamingAlt) {
        if (!s.configure(want, rate) || !s.start()) {
            failOver(s.error ?: "the DAC could not switch format")
            return
        }
        s.setPaused(!playing)
        slotBytes = want.subslotBytes
        bits = want.bitResolution
        UsbVolume.attach(s, allowDigital = com.engabd.sendpin.data.AppSettings(context).bootUsbDigitalVolume)
        publishPath(s, want)
        resetCounters()
    }

    companion object {
        private const val TAG = "UsbBitperfect"
        /** Paused this long, the DAC goes back to Android. User decision, 2026-09-29. */
        const val IDLE_HANDBACK_MS = 30_000L
    }
}
