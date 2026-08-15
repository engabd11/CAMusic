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
            .createMediaSource(mediaItem)
    }
}
