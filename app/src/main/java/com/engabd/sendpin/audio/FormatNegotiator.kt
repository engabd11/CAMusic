package com.engabd.sendpin.audio

import com.engabd.sendpin.protocol.AudioFormatSpec
import com.engabd.sendpin.protocol.StreamStartPlayerInfo

data class NegotiatedFormat(
    val codec: String,    // "flac", "opus", or "pcm"
    val sampleRate: Int,
    val bitDepth: Int,
    val channels: Int,
)

/**
 * Builds the format list we advertise to the server in `player@v1_support.supported_formats`.
 *
 * The server may only stream a format that appears in this list, so anything missing
 * here is something Music Assistant is *forced* to convert. That is the whole reason
 * 44.1 kHz is in the list: a Navidrome library is mostly CD rips, and a client that
 * only offers 48 kHz makes the server resample every one of them.
 */
object FormatNegotiator {

    /**
     * The Kotlin engine renders 16-bit PCM — see [SendspinAudioEngine], whose
     * AudioTrack is built as `ENCODING_PCM_16BIT`. The native AAudio 24-bit path in
     * `cpp/` is not wired up yet, and advertising a depth we cannot render only makes
     * the server spend bandwidth on bits we truncate on arrival. Raise this to 24 in
     * the same change that lands real 24-bit output.
     */
    const val MAX_BIT_DEPTH = 16

    /** CD and DVD rate families — covers essentially every library file. */
    private val RATES_STANDARD = listOf(48_000, 44_100)

    /** Adds the hi-res member of each family (2× 48k and 2× 44.1k). */
    private val RATES_HIRES = listOf(48_000, 44_100, 96_000, 88_200)

    /**
     * [preferHiRes] — also offer 88.2/96 kHz, so hi-res masters stream at their native
     * rate instead of being downsampled to 48 kHz by the server.
     * [preferFlac] — order lossless FLAC ahead of uncompressed PCM. Both are
     * bit-identical; FLAC costs roughly half the bandwidth and a little decode CPU.
     *
     * 48 kHz stays first for compatibility: it is Music Assistant's own default and the
     * ordering that grouped/multi-room sync was validated against. The added rates give
     * the server an exact match to pick when the source is not 48 kHz.
     */
    fun supportedFormats(
        preferHiRes: Boolean = true,
        preferFlac: Boolean = true,
    ): List<AudioFormatSpec> {
        val rates = if (preferHiRes) RATES_HIRES else RATES_STANDARD
        val losslessOrder = if (preferFlac) listOf("flac", "pcm") else listOf("pcm", "flac")
        return buildList {
            for (codec in losslessOrder) for (rate in rates) {
                add(AudioFormatSpec(codec, 2, rate, MAX_BIT_DEPTH))
            }
            // Lossy, and last: only reached if the server can serve none of the above.
            // Opus is 48 kHz only, and listing it first breaks grouped sync.
            add(AudioFormatSpec("opus", 2, 48_000, MAX_BIT_DEPTH))
        }
    }

    /** Defaults, for callers with no user settings to hand. */
    val supportedFormats: List<AudioFormatSpec> get() = supportedFormats()

    /**
     * Could Music Assistant stream a source at [sampleRate]/[bitDepth] without
     * touching it?
     *
     * True only when the rate is one we advertise (so no resampling) and the depth
     * is one we can render (so no requantising). Anything else means the server
     * has to convert somewhere between the file and this phone — which is exactly
     * when "play at original quality" should go direct to the source instead.
     */
    fun canStreamUntouched(
        sampleRate: Int,
        bitDepth: Int,
        preferHiRes: Boolean = true,
    ): Boolean {
        val rates = if (preferHiRes) RATES_HIRES else RATES_STANDARD
        return sampleRate in rates && bitDepth <= MAX_BIT_DEPTH
    }

    /**
     * The server always streams a format we advertised, so accept the `stream/start`
     * player info directly.
     */
    fun resolve(player: StreamStartPlayerInfo): NegotiatedFormat =
        NegotiatedFormat(player.codec, player.sampleRate, player.bitDepth, player.channels)

    /**
     * The ceiling this phone can actually put out — its mixer's native rate at the
     * depth [SendspinAudioEngine] renders. This is the honest "playing at" answer
     * when the file is decoded on the device itself (playing a Navidrome stream
     * direct) rather than negotiated with Music Assistant, since nothing upstream
     * gets a say in it.
     */
    fun deviceOutputQuality(): StreamQuality {
        val rate = try {
            android.media.AudioTrack.getNativeOutputSampleRate(android.media.AudioManager.STREAM_MUSIC)
        } catch (_: Throwable) {
            48_000
        }
        return StreamQuality(codec = "PCM", sampleRateHz = rate, bitDepth = MAX_BIT_DEPTH)
    }
}
