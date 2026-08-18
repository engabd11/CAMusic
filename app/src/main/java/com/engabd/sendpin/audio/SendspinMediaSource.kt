package com.engabd.sendpin.audio

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.engabd.sendpin.protocol.StreamStartPlayerInfo

/**
 * Builds the [MediaSource] ExoPlayer loads a Sendspin stream from: a
 * [ProgressiveMediaSource] reading through the [SendspinDataSource] the caller
 * hands in, with the negotiated codec's MIME type set explicitly.
 *
 * ExoPlayer's default extractor selection sniffs a file extension or magic
 * bytes; [SendspinDataSource.streamUri] is a synthetic `sendspin://stream/N` with
 * neither, and no bytes exist to sniff until the first [SendspinDataSource.read]
 * call. Setting the MIME type from the negotiated [StreamStartPlayerInfo.codec]
 * makes extractor selection deterministic instead of dependent on read timing.
 */
@OptIn(UnstableApi::class)
object SendspinMediaSource {

    fun create(dataSource: SendspinDataSource, format: StreamStartPlayerInfo): MediaSource {
        val mimeType = when (format.codec.lowercase()) {
            "flac" -> MimeTypes.AUDIO_FLAC
            "opus" -> MimeTypes.AUDIO_OGG
            else -> MimeTypes.AUDIO_WAV
        }
        val mediaItem = MediaItem.Builder()
            .setUri(dataSource.streamUri)
            .setMimeType(mimeType)
            .build()
        return ProgressiveMediaSource.Factory(SendspinDataSource.Factory { dataSource })
            // ProgressiveMediaSource only asks the LoadControl "should I still be
            // loading?" once every [ContinueLoadingCheckIntervalBytes] read - its
            // default, 1 MB, is sized for downloading a file, where racing ahead of
            // playback is the point. For our lossless-audio bitrates that's some
            // 6-8s of compressed data the loading thread can (and, once the clock
            // filter drops out of schedule and SendspinSyncDataSource.awaitNextFrame
            // stops pacing reads, does) pull through in one uninterrupted burst
            // before DefaultLoadControl's maxBufferMs ever gets a say - measured
            // on-device as a pause or seek that stayed audible for exactly that
            // long. A small interval makes the check frequent enough for
            // maxBufferMs to actually bound how far the loader can get ahead.
            .setContinueLoadingCheckIntervalBytes(CONTINUE_LOADING_CHECK_INTERVAL_BYTES)
            .createMediaSource(mediaItem)
    }

    private const val CONTINUE_LOADING_CHECK_INTERVAL_BYTES = 32 * 1024
}
