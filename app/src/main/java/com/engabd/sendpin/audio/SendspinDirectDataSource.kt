package com.engabd.sendpin.audio

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.engabd.sendpin.protocol.MonotonicClock
import com.engabd.sendpin.protocol.StreamStartPlayerInfo
import java.util.concurrent.TimeUnit

/**
 * Solo (single-device) playback: there is no peer to phase-lock to, so absolute
 * server-time scheduling buys nothing and only adds latency (and the far-future-
 * timestamp startup hang [SendspinPlaybackSupport.HeadGate] otherwise guards against).
 * See Massdroid's `SendspinDirectEngine`, which this mirrors: [awaitNextFrame]
 * never blocks on the clock. Frames reach ExoPlayer as soon as they're queued.
 *
 * [SendspinDataSource.presentationLocalUs] is still armed here (from "now", on
 * the first frame) for API consistency, but it's functionally inert for direct
 * playback: [OboeAudioSink] opens the native output with drift correction off
 * for a solo stream, and `SendspinOutputEngine`'s callback (see the native
 * engine's `onAudioReady`) only ever reads the intended-presentation-time
 * comparison when drift correction is on - off, it's a pure FIFO passthrough.
 */
@OptIn(UnstableApi::class)
class SendspinDirectDataSource(
    format: StreamStartPlayerInfo,
) : SendspinDataSource(format) {

    companion object {
        private const val QUEUE_POLL_MS = 200L
    }

    override fun awaitNextFrame(): ByteArray? {
        while (true) {
            val frame = queue.poll(QUEUE_POLL_MS, TimeUnit.MILLISECONDS)
            if (frame != null) {
                armPresentationAnchor(MonotonicClock.nowUs())
                return frame.payload
            }
            if (isEndOfStreamSignalled() && queue.isEmpty()) return null
        }
    }
}
