package com.engabd.sendpin.audio

import android.media.AudioDeviceInfo
import android.util.Log
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
    /**
     * Called once, on the first stream this sink fails to drain at all.
     *
     * The sink cannot rebuild the player it lives inside, so it reports and the engine
     * decides — see [SendspinExoEngine]'s handling, which drops back to the platform
     * sink for the rest of the session. Nothing depends on it: a caller that passes
     * nothing gets a loud failure and no fallback, which is still better than silence.
     */
    private val onStalled: () -> Unit = {},
) : AudioSink {

    companion object {
        private const val TAG = "OboeAudioSink"

        // SendspinOutputEngine's ring is RING_SECONDS (4s) deep - see its header.
        // There's no real hardware AudioTrack buffer here to report a size for,
        // so this stands in for it (used by ExoPlayer only for buffering
        // heuristics, not for anything sync-critical).
        private const val RING_BUFFER_US = 4_000_000L

        /**
         * What a stalled sink reports as. ExoPlayer wants an int error code alongside
         * the format; there is no platform `AudioTrack` behind this sink to have
         * produced one, and `AudioTrack.ERROR` is the honest general answer: the write
         * did not happen and the sink cannot say more than that.
         */
        private const val WRITE_ERROR_CODE = -1
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

    /** Cleared per [configure], so there is one "first write" line per stream. */
    private var firstWriteLogged = false

    /**
     * Back pressure or a fault — see [SinkStallDetector], where that judgement lives.
     */
    private val stall = SinkStallDetector()

    /** Cleared per [configure]: one timeline line per stream, not one per buffer. */
    private var timelineLogged = false

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
        firstWriteLogged = false
        timelineLogged = false
        stall.reset()
        val drift = driftCorrection()
        // Logged before the call, so a native start that never returns is still
        // attributable — and after it, so a `false` is not left to be inferred from the
        // ConfigurationException alone.
        if (!nativeOutput.start(sampleRate, channelCount, drift)) {
            Log.e(TAG, "native start refused $sampleRate/${channelCount}ch drift=$drift")
            throw AudioSink.ConfigurationException("SendspinNativeOutput.start failed", format)
        }
        Log.i(TAG, "configured $sampleRate/${channelCount}ch/16-bit drift=$drift")
    }

    override fun play() {
        nativeOutput.resumeStream()
    }

    override fun handleDiscontinuity() {
        // No timeline-position bookkeeping here depends on continuity (unlike
        // DefaultAudioSink's trim/gapless handling), so there's nothing to do.
    }

    /**
     * Hand decoded PCM to the native ring, honouring [AudioSink]'s contract about what
     * was actually taken.
     *
     * **Returning `true` unconditionally, as this used to, is not a shortcut — it is a
     * lie with two consequences.** `handleBuffer` returns whether the buffer was fully
     * consumed; `false` tells ExoPlayer to re-present the same one once the sink has
     * drained. Answering `true` regardless means a ring that cannot take the audio drops
     * it silently, *and* [framesWrittenTotal] keeps climbing past what was really
     * accepted — which corrupts [getCurrentPositionUs], since that is written-minus-
     * buffered. A pinned position is exactly what ExoPlayer's "stuck playing with no
     * progress" watchdog measures, so one wrong return value produces both the silence
     * and the timeout that were observed together on-device.
     *
     * `write` returns frames accepted, so the honest answer is available and was simply
     * being discarded. Whether a refusing ring is the *cause* of that session's silence
     * is still unproven — the diagnostics below are what will settle it — but the
     * contract violation is a defect either way.
     */
    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val remaining = buffer.remaining()
        if (remaining <= 0) return true
        if (scratch.size < remaining) scratch = ByteArray(remaining)
        val startPosition = buffer.position()
        buffer.get(scratch, 0, remaining)

        // The timestamp is best-effort; the audio is not. This used to skip the write
        // entirely when there was no current data source — which is reachable, since
        // `SendspinExoEngine.flush()` clears it on `stream/clear` while decoded buffers
        // for the outgoing stream can still be in flight — and skipping it means the
        // buffer is consumed above, reported as handled below, and never heard: silence
        // with nothing logged and no error. Drift correction has a defined answer for a
        // missing anchor already (`presentationLocalUs` falls back to "now"), so fall
        // back to that rather than dropping samples on the floor.
        val source = currentDataSource()
        val presentationLocalUs = source?.presentationLocalUs(presentationTimeUs)
            ?: (com.engabd.sendpin.protocol.MonotonicClock.nowUs() + presentationTimeUs)
        logTimeline(source, presentationTimeUs, presentationLocalUs)

        val framesAccepted = nativeOutput.write(scratch, 0, remaining, presentationLocalUs)
        val bytesAccepted = (framesAccepted * bytesPerFrame).coerceIn(0, remaining)

        // Rewind to exactly what was left over, so the re-presented buffer starts where
        // the ring stopped taking rather than replaying audio it already holds.
        if (bytesAccepted < remaining) buffer.position(startPosition + bytesAccepted)

        // Only the accepted bytes reach the tap: the rest is coming back on the next
        // call, and analysing it twice would double-count it into the light show.
        if (bytesAccepted > 0) feedTap(scratch, bytesAccepted)

        framesWrittenTotal += framesAccepted
        logFirstWrite(framesAccepted, remaining)
        noteRefusal(bytesAccepted, remaining)
        return bytesAccepted >= remaining
    }

    /**
     * The producer's half of the timeline, in one line, once per stream.
     *
     * The engine's own diagnostic prints `intendedHead` and `dac0` apart on the native
     * side; this is the same question one step earlier, before the value crosses JNI. If
     * the anchor is already implausible here, the fault is in this process and no NDK
     * rebuild is needed to see it; if it is sane here and `intendedHead` is not, the
     * fault is in the crossing or in the marker bookkeeping. Between the two lines there
     * is nowhere left for it to hide, which is what three attempts at this bug were
     * missing.
     *
     * `anchor` is what [SendspinDataSource] armed from the frame it released; `media` is
     * ExoPlayer's own stream-relative time; `local` is their sum, which is what the ring
     * gets. `boot` and `mono` are printed because the two differ by suspend time and the
     * conversion between them at the JNI boundary is one of the things under suspicion.
     */
    private fun logTimeline(source: SendspinDataSource?, mediaTimeUs: Long, localUs: Long) {
        if (timelineLogged) return
        timelineLogged = true
        Log.i(
            TAG,
            "timeline (producer): anchor=${source?.presentationAnchorUs} media=$mediaTimeUs " +
                "local=$localUs boot=${com.engabd.sendpin.protocol.MonotonicClock.nowUs()} " +
                "mono=${System.nanoTime() / 1000} source=${source?.javaClass?.simpleName}",
        )
    }

    /**
     * One line per stream, the first time PCM is actually accepted by the native ring.
     *
     * The equivalent line on the raw-`AudioTrack` engine path is what separates
     * "nothing ever decoded" from "audio reached the output and was inaudible
     * anyway" — and this path had no counterpart, so the one device session that
     * hit it had to be diagnosed from `AAudio` and `dumpsys audio` instead. That
     * detour is the reason this exists.
     */
    private fun logFirstWrite(framesAccepted: Int, offered: Int) {
        if (firstWriteLogged || framesAccepted <= 0) return
        firstWriteLogged = true
        Log.i(
            TAG,
            "first write: $framesAccepted frames accepted of ${offered / bytesPerFrame} offered " +
                "@ $sampleRate/${channelCount}ch/16-bit, buffered=${nativeOutput.bufferedFrames()}",
        )
    }

    /**
     * Say so when the ring stops accepting anything, and give up when it never starts.
     *
     * A sink that refuses every buffer is silent by definition, and returning `false`
     * correctly makes ExoPlayer wait rather than discard — but waiting for ever looks
     * identical to a stall from outside, which is precisely the failure this path shipped
     * with: a player reporting itself as playing, emitting nothing, for the whole of a
     * track. The refusal was already *detected* and merely logged; it is now acted on.
     *
     * Two stages, because they answer different questions. The warning carries the native
     * counters — which of `intendedHead` / `dac0` is wrong is the open question of PR #59,
     * and this is the log line that has been producing the evidence for it. The failure
     * is what stops the silence: [AudioSink.WriteException] surfaces through ExoPlayer as
     * a real playback error rather than an indefinite wait, and [onStalled] lets the
     * engine fall back to the platform sink for the rest of the session.
     */
    private fun noteRefusal(bytesAccepted: Int, offered: Int) {
        when (stall.onBuffer(bytesAccepted, offered)) {
            SinkStallDetector.Verdict.OK -> Unit

            SinkStallDetector.Verdict.WARN -> Log.e(
                TAG,
                "native ring has refused ${stall.refusals} consecutive buffers " +
                    "(buffered=${nativeOutput.bufferedFrames()} frames, written=$framesWrittenTotal) - " +
                    "nothing is draining it, so this stream will be silent. " +
                    "driftEma=${nativeOutput.driftEmaUs()}us latency=${nativeOutput.outputLatencyUs()}us " +
                    "underrun=${nativeOutput.underrunFrames()} " +
                    "device=${nativeOutput.deviceId()} disconnected=${nativeOutput.isDisconnected()}",
            )

            SinkStallDetector.Verdict.STALLED -> {
                Log.e(
                    TAG,
                    "native ring has taken nothing for ${stall.refusals} buffers - giving up on " +
                        "this sink rather than playing silence (buffered=${nativeOutput.bufferedFrames()}, " +
                        "written=$framesWrittenTotal, driftEma=${nativeOutput.driftEmaUs()}us)",
                )
                onStalled()
                throw AudioSink.WriteException(
                    WRITE_ERROR_CODE,
                    configuredFormat ?: Format.Builder().build(),
                    // Recoverable: the audio itself is fine and another sink can play it,
                    // which is exactly what [onStalled] arranges for the next stream.
                    /* isRecoverable = */ true,
                )
            }
        }
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
        // relative to the raw-AudioTrack path (SendspinExoEngine without Oboe),
        // which honours it.
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
