package com.engabd.sendpin.audio

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer

/**
 * How far ahead of the speaker the analysis tap is running, in microseconds, or
 * [UNKNOWN] before playback has produced a usable position.
 *
 * A stable handle onto a measurement whose source is not: ExoPlayer builds a new
 * [AudioSink] — and therefore a new [AudioLeadProbe] — whenever it reconfigures,
 * while Light Sync wants one thing to read for the life of the process. Owned
 * alongside the tap by [LocalPlayer] and shared with
 * [com.engabd.sendpin.hue.DirectLightSync].
 */
class AudioLead {

    /** Written by the playback thread, read by the Light Sync render loop. */
    @Volatile
    var leadUs: Long = UNKNOWN
        internal set

    /** Lead in milliseconds, or null when it isn't known yet. */
    val leadMs: Float? get() = leadUs.takeIf { it != UNKNOWN }?.let { it / 1000f }

    /**
     * Position **within the current track** of the buffer entering the sink, or
     * [UNKNOWN].
     *
     * Where in the song the audio the tap is about to analyse comes from, exact
     * and free — the renderer has to know it to queue the buffer at all. Without
     * it a track scan has nothing to line itself up against: the analyzer's own
     * `tAudio` counts from wherever the last flush happened, which is not the
     * same as where the song is.
     *
     * Set before the buffer reaches the processor chain, so the tap can pair it
     * with the samples it is being handed. Written by the playback thread.
     */
    @Volatile
    var mediaTimeUs: Long = UNKNOWN
        internal set

    companion object {
        const val UNKNOWN = Long.MIN_VALUE
    }
}

/**
 * Measures how far ahead of the speaker the analysis tap is running.
 *
 * The tap sees audio at the moment it enters the sink; the listener hears it
 * only once the AudioTrack's buffer has drained down to it. That gap — the
 * *lead* — is what lets Light Sync be on time rather than late: Hue's own
 * pipeline (a Zigbee hop plus the bulb's ramp) costs about 100 ms, so as long as
 * the lead exceeds that, a rendered frame can be held back and still reach the
 * bulb at the instant the sample reaches the ear.
 *
 * syncoV2 could only estimate this. It taps a separate ffmpeg decode of the
 * Music Assistant stream, which has no fixed relationship to where the speakers
 * are, so it carries a Kalman-filtered playback clock and a calibrator that
 * learns the residual error over a few beats — and still exposes a manual offset
 * for whatever is left. On-device both numbers are available, in the same clock,
 * on the same thread, on every buffer:
 *
 * - `presentationTimeUs` — the media time of the audio being queued *now*
 * - `getCurrentPositionUs` — the media time being *heard* now
 *
 * Their difference is the lead, exactly, with no estimation and nothing for the
 * user to tune. It stays correct across seeks and track changes for free,
 * because both come from the sink's own clock rather than anything reconstructed
 * alongside it.
 *
 * Wrapping the sink is the only way to see both: audio processors are handed the
 * raw [ByteBuffer] with no timestamp, and the position is not reachable from
 * inside the processor chain at all.
 */
@OptIn(UnstableApi::class)
class AudioLeadProbe(
    sink: AudioSink,
    private val out: AudioLead,
) : ForwardingAudioSink(sink) {

    /**
     * The offset baked into every `presentationTimeUs`, per the [AudioSink]
     * contract — the renderer adds it to the media timestamp before handing the
     * buffer over, so that renderer time runs continuously across a gapless
     * queue instead of restarting at each track.
     *
     * Which means `presentationTimeUs` alone is *not* the position in the song:
     * for the second track of a queue it is the first track's duration plus the
     * position, and a beat grid lined up against it would be out by minutes.
     * The renderer tells the sink the offset for exactly this reason, and
     * subtracting it gives the media time back.
     */
    private var streamOffsetUs = 0L

    /**
     * What the sink was configured with, after the processor chain.
     *
     * The last format the app can observe before Android takes over, and the one
     * the Output card compares against the decoder's to answer "am I losing bits
     * here". Reported rather than acted on - this override changes nothing.
     */
    override fun configure(
        inputFormat: androidx.media3.common.Format,
        specifiedBufferSize: Int,
        outputChannels: IntArray?,
    ) {
        SignalPath.onSinkFormat(inputFormat)
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        streamOffsetUs = outputStreamOffsetUs
        super.setOutputStreamOffsetUs(outputStreamOffsetUs)
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        // Published before delegating, because the processor chain — the audio
        // tap included — runs inside the delegate. By the time the tap is handed
        // these samples this already says which part of the track they are.
        out.mediaTimeUs = presentationTimeUs - streamOffsetUs
        // Sampled before delegating: `presentationTimeUs` describes the buffer
        // about to be queued, so it has to be paired with the position as it
        // stands now, not after this buffer has joined the backlog.
        val playing = super.getCurrentPositionUs(false)
        if (playing != AudioSink.CURRENT_POSITION_NOT_SET) {
            val lead = presentationTimeUs - playing
            // A seek in flight, or a position still describing the previous item
            // during a transition, can momentarily produce nonsense. Anything
            // outside a plausible sink-buffer range is discarded rather than
            // smoothed, so one bad pair cannot drag the delay off.
            out.leadUs = if (lead in 0..MAX_PLAUSIBLE_LEAD_US) lead else AudioLead.UNKNOWN
        }
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    override fun flush() {
        out.leadUs = AudioLead.UNKNOWN
        out.mediaTimeUs = AudioLead.UNKNOWN
        super.flush()
    }

    override fun reset() {
        out.leadUs = AudioLead.UNKNOWN
        out.mediaTimeUs = AudioLead.UNKNOWN
        super.reset()
    }

    private companion object {
        /**
         * Audio sink buffers run to a few hundred milliseconds; media3 sizes a
         * PCM track at roughly 250 ms and nothing here asks for more. Two seconds
         * is far past anything legitimate, so a larger value means the pair was
         * taken across a discontinuity.
         */
        const val MAX_PLAUSIBLE_LEAD_US = 2_000_000L
    }
}
