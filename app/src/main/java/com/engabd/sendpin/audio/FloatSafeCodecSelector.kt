package com.engabd.sendpin.audio

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * Puts the platform's own audio decoders (`c2.android.*`, `OMX.google.*`) ahead of
 * the vendor's, for the output modes that ask the decoder for 32-bit float.
 *
 * Samsung's `c2.sec.flac.decoder` accepts the float request and reports float in its
 * output format, but for a 16-bit FLAC it still hands over 16-bit integers. Read as
 * float, that is full-scale noise — which is what High resolution, Pure and Direct to
 * DAC played for every 16-bit/44.1 kHz album on a Galaxy S23, while 24-bit files,
 * which it does convert, played clean. Measured on the device: the same block read as
 * float was `-4.8E32, NaN…` and read as 16-bit was smooth music.
 *
 * Google's decoders honour the float request. They are software decoders, which for
 * audio costs next to nothing. Standard output never asks for float and keeps the
 * device's own order.
 */
@OptIn(UnstableApi::class)
object FloatSafeCodecSelector : MediaCodecSelector {

    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean,
    ): List<MediaCodecInfo> {
        val infos = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
        if (!mimeType.startsWith("audio/")) return infos
        // sortedBy is stable, so each group keeps the order the device gave it.
        return infos.sortedBy { if (isPlatformDecoder(it.name)) 0 else 1 }
    }

    internal fun isPlatformDecoder(name: String): Boolean =
        name.startsWith("c2.android.") || name.startsWith("OMX.google.")
}
