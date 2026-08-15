package com.engabd.sendpin.audio

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.engabd.sendpin.protocol.StreamStartPlayerInfo
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Wraps one Sendspin stream (`stream/start` .. `stream/end`) as an ExoPlayer
 * [DataSource], so ExoPlayer's decode/gapless/ReplayGain/audio-focus/session
 * machinery - and the [AudioAnalysisTap] already sitting in its render chain via
 * [TapRenderersFactory] - all apply to MA playback the same way they already do
 * for the local player (see [LocalPlayer]). This is what makes direct Hue Bridge
 * Light Sync work for MA: the tap sees whatever flows through ExoPlayer's sink,
 * with no separate wiring needed.
 *
 * `open()` synthesizes the container header the negotiated codec needs
 * ([SendspinContainerHeader] for FLAC/PCM, [OggOpusPacketizer] for Opus - see
 * their docs for why this framing exists at all) and `read()` drains it before
 * any audio bytes. Audio frames arrive via [submit], called by whatever bridges
 * [com.engabd.sendpin.protocol.SendspinClient.PlaybackSink] to the currently-open
 * instance - not wired yet; that's a later step in the plan (`docs/exoplayer-upgrade-plan.md`
 * Step 6). Until then this class is a complete, independently testable unit with
 * no caller.
 *
 * The two subclasses are Massdroid's solo/grouped split: [SendspinSyncDataSource]
 * blocks [read] until each frame's server-clock schedule says it's due - every
 * frame, not just the head, because unlike [SendspinAudioEngine] there is no
 * downstream `AudioTrack` left to pace playback once the bytes leave here (see the
 * plan's design decision #2, "clock sync via DataSource pre-buffering").
 * [SendspinDirectDataSource] never blocks. Picking one is the caller's job.
 */
@OptIn(UnstableApi::class)
abstract class SendspinDataSource(
    protected val format: StreamStartPlayerInfo,
) : BaseDataSource(/* isNetwork = */ true) {

    /** One Sendspin binary audio frame, header stripped, with its server timestamp. */
    protected class Frame(val serverTsUs: Long, val payload: ByteArray)

    companion object {
        const val QUEUE_CAPACITY = 2048
        private val nextStreamId = AtomicInteger(0)
    }

    private val streamId = nextStreamId.getAndIncrement()

    /**
     * A synthetic per-stream URI - there's no real network location, but
     * [androidx.media3.common.MediaItem] needs one, and [SendspinMediaSource]
     * reads this to build it *before* [open] runs (which is when [getUri] starts
     * returning non-null, per the [DataSource] contract).
     */
    val streamUri: Uri = Uri.parse("sendspin://stream/$streamId")

    protected val queue = LinkedBlockingQueue<Frame>(QUEUE_CAPACITY)

    @Volatile private var endSignalled = false
    @Volatile private var opened = false

    private var opusMuxer: OggOpusPacketizer? = null

    /** Bytes queued for the next [read] call: either the synthesized header, or one muxed audio page/frame. */
    private var pendingOutput: ByteArray? = null
    private var pendingOffset = 0

    /**
     * The local (CLOCK_BOOTTIME-domain, matching [com.engabd.sendpin.protocol.MonotonicClock])
     * instant media-time zero of this stream is intended to be heard - i.e. the
     * schedule decision for the first frame released by [awaitNextFrame]. Armed
     * once, by whichever subclass releases that frame, via [armPresentationAnchor].
     *
     * This exists to answer a question ExoPlayer's own pipeline can't: by the time
     * decoded PCM reaches [OboeAudioSink.handleBuffer], the extractor/decoder have
     * long since disconnected it from the Sendspin frame(s) it came from, so there
     * is no `serverTimestampUs` left to convert. What survives the trip is
     * `presentationTimeUs` - ExoPlayer's own *media-relative* clock, which advances
     * in exact lockstep with real elapsed samples (constant sample rate, no
     * time-stretching in the extractor). So `anchor + presentationTimeUs` recovers
     * the correct absolute local target for any decoded chunk, without needing to
     * track individual frames through decode.
     */
    @Volatile private var presentationAnchorLocalUs: Long? = null

    /** Arms [presentationAnchorLocalUs] once, from the first call. Later calls are no-ops. */
    protected fun armPresentationAnchor(localUs: Long) {
        if (presentationAnchorLocalUs == null) presentationAnchorLocalUs = localUs
    }

    /**
     * The absolute local instant a decoded chunk at [mediaTimeUs] (ExoPlayer's own
     * `presentationTimeUs`, media-relative) is intended to be heard. Falls back to
     * "now" if nothing has armed the anchor yet, which should not happen in
     * practice - audio only reaches the decoder after [awaitNextFrame] has
     * released at least one frame, and every implementation arms the anchor at
     * that same point.
     */
    fun presentationLocalUs(mediaTimeUs: Long): Long =
        (presentationAnchorLocalUs ?: com.engabd.sendpin.protocol.MonotonicClock.nowUs()) + mediaTimeUs

    /**
     * Block (per subclass policy) until the next frame is due, or return null once
     * the stream has genuinely ended (end-of-stream signalled *and* the queue has
     * drained). Never returns null for "nothing queued yet" - that case is the
     * implementation's job to keep polling through.
     */
    protected abstract fun awaitNextFrame(): ByteArray?

    /** `stream/end` seen and drained: no more frames coming. */
    protected fun isEndOfStreamSignalled() = endSignalled

    /** A binary audio frame off the wire, header already stripped by the caller. */
    fun submit(serverTsUs: Long, payload: ByteArray) {
        if (endSignalled) return
        val frame = Frame(serverTsUs, payload)
        if (queue.offer(frame)) return
        // Full: the oldest frame is the most stale, so it's the one to sacrifice.
        queue.poll()
        queue.offer(frame)
    }

    /** `stream/end` - [read] returns [C.RESULT_END_OF_INPUT] once the queue drains, not before. */
    fun signalEndOfStream() {
        endSignalled = true
    }

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        pendingOutput = when (format.codec.lowercase()) {
            "flac" -> SendspinContainerHeader.flacStreamHeader(format.codecHeader) ?: run {
                val decodedSize = SendspinContainerHeader.decodeBase64(format.codecHeader)?.size
                throw IOException(
                    "Sendspin FLAC stream: missing or malformed codec_header " +
                        "(raw='${format.codecHeader}' decodedSize=$decodedSize, want 34)",
                )
            }
            "opus" -> {
                val muxer = OggOpusPacketizer(serialNumber = streamId)
                opusMuxer = muxer
                val headPacket = SendspinContainerHeader.decodeBase64(format.codecHeader)
                    ?: opusHeadPacket(format.channels, format.sampleRate)
                muxer.headerPages(headPacket)
            }
            else -> SendspinContainerHeader.wavStreamHeader(format.sampleRate, format.channels, format.bitDepth)
        }
        pendingOffset = 0
        opened = true
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val out = pendingOutput
        if (out == null) {
            val frame = awaitNextFrame() ?: return C.RESULT_END_OF_INPUT
            pendingOutput = opusMuxer?.audioPage(frame) ?: frame
            pendingOffset = 0
            return read(buffer, offset, length)
        }
        val n = minOf(out.size - pendingOffset, length)
        System.arraycopy(out, pendingOffset, buffer, offset, n)
        pendingOffset += n
        if (pendingOffset >= out.size) pendingOutput = null
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = if (opened) streamUri else null

    override fun close() {
        // Guard against a close() with no matching open(): BaseDataSource's own
        // transferEnded() has no such guard, and calling it unmatched leaves its
        // internal DataSpec null - the next transferEnded() (a double-close, or
        // ExoPlayer probing/releasing a DataSource instance it never opened,
        // which our Factory can hand back since it always returns the same
        // captured instance) then NPEs inside DefaultBandwidthMeter reading that
        // null DataSpec, surfacing as a silent, unhandled ExoPlaybackException -
        // playback just stops with no audio and no obvious error.
        if (!opened) return
        opened = false
        pendingOutput = null
        queue.clear()
        transferEnded()
    }

    /** Matches [SendspinAudioEngine.opusHead] - a minimal OpusHead when the server sends none. */
    private fun opusHeadPacket(channels: Int, sampleRate: Int): ByteArray =
        ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("OpusHead".toByteArray(Charsets.US_ASCII))
            put(1)
            put(channels.toByte())
            putShort(3840.toShort())
            putInt(sampleRate)
            putShort(0)
            put(0)
        }.array()

    /**
     * Supplies fresh [SendspinDataSource] instances to ExoPlayer's media source.
     * [builder] is the caller's job (Step 6): construct a [SendspinSyncDataSource]
     * or [SendspinDirectDataSource] against whatever format/clock/group state is
     * current when ExoPlayer asks for one.
     */
    class Factory(private val builder: () -> SendspinDataSource) : DataSource.Factory {
        override fun createDataSource(): DataSource = builder()
    }
}
