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
     * What [SendspinExoEngine] renders by default: 16-bit PCM through an
     * `ENCODING_PCM_16BIT` AudioTrack. Advertising a depth we then truncate only
     * makes the server spend bandwidth on bits thrown away on arrival, so this
     * stays the floor *and* the default ceiling.
     */
    const val DEFAULT_BIT_DEPTH = 16

    /**
     * The ceiling when the user turns bit-perfect on. The engine asks the FLAC
     * decoder for float output and builds the AudioTrack from the depth the
     * decoder actually reports, so 24-bit sources keep their resolution instead
     * of being requantised — see [SendspinExoEngine.bitPerfect].
     *
     * Gated on the setting rather than always-on: 24-bit costs real bandwidth,
     * and on a device whose mixer runs at 16-bit the extra bits are resampled
     * away again downstream for nothing.
     */
    const val HIRES_BIT_DEPTH = 24

    /** The depth we advertise given the user's bit-perfect preference. */
    fun bitDepthFor(bitPerfect: Boolean) = if (bitPerfect) HIRES_BIT_DEPTH else DEFAULT_BIT_DEPTH

    /** CD and DVD rate families — covers essentially every library file. */
    private val RATES_STANDARD = listOf(48_000, 44_100)

    /**
     * Adds the hi-res members of each family — 2× and 4× of 48k and 44.1k.
     *
     * 176.4/192 are filtered out by [maxSampleRate] unless the caller has actually
     * probed the device for them: advertising a rate the phone can't open means the
     * server picks it and nothing plays, and on a phone whose mixer runs at 48 kHz
     * a 192 kHz stream is ~4.6 Mbit/s that gets resampled straight back down.
     */
    private val RATES_HIRES = listOf(48_000, 44_100, 96_000, 88_200, 192_000, 176_400)

    /**
     * The default ceiling on advertised rates.
     *
     * 96 kHz keeps the historical list exactly as it was; anything higher has to be
     * asked for explicitly, with a device probe behind it.
     */
    const val DEFAULT_MAX_RATE = 96_000

    /** Every codec [SendspinExoEngine] can decode, in the order "auto" offers them. */
    val CODECS = listOf("flac", "pcm", "opus")

    /**
     * [preferHiRes] — also offer 88.2/96 kHz, so hi-res masters stream at their native
     * rate instead of being downsampled to 48 kHz by the server.
     * [preferFlac] — order lossless FLAC ahead of uncompressed PCM. Both are
     * bit-identical; FLAC costs roughly half the bandwidth and a little decode CPU.
     * Only consulted when [codec] is null.
     * [codec] — advertise this codec *alone*. The server may only stream a format the
     * client listed, so narrowing the list is the one way to make a choice stick
     * rather than express a preference the server is free to ignore. This is how the
     * official Music Assistant app implements its codec setting.
     *
     * [bitPerfect] — advertise 24-bit rather than 16. Only the lossless codecs get
     * it: Opus is lossy and decodes to 16-bit whatever depth we claim, so asking
     * for 24 there would be a number with nothing behind it.
     *
     * 48 kHz stays first for compatibility: it is Music Assistant's own default and the
     * ordering that grouped/multi-room sync was validated against. The added rates give
     * the server an exact match to pick when the source is not 48 kHz.
     */
    fun supportedFormats(
        preferHiRes: Boolean = true,
        preferFlac: Boolean = true,
        codec: String? = null,
        bitPerfect: Boolean = false,
        maxSampleRate: Int = DEFAULT_MAX_RATE,
    ): List<AudioFormatSpec> {
        val rates = (if (preferHiRes) RATES_HIRES else RATES_STANDARD).filter { it <= maxSampleRate }
        val depth = bitDepthFor(bitPerfect)
        val only = codec?.lowercase()?.takeIf { it in CODECS }
        if (only != null) {
            val d = if (only == "opus") DEFAULT_BIT_DEPTH else depth
            return ratesFor(only, rates).map { AudioFormatSpec(only, 2, it, d) }
        }

        val losslessOrder = if (preferFlac) listOf("flac", "pcm") else listOf("pcm", "flac")
        return buildList {
            for (c in losslessOrder) for (rate in rates) {
                add(AudioFormatSpec(c, 2, rate, depth))
            }
            // Lossy, and last: only reached if the server can serve none of the above.
            // Opus is 48 kHz only, and listing it first breaks grouped sync.
            add(AudioFormatSpec("opus", 2, 48_000, DEFAULT_BIT_DEPTH))
        }
    }

    /** Opus is 48 kHz only; the lossless codecs take whatever the rate list offers. */
    private fun ratesFor(codec: String, rates: List<Int>): List<Int> =
        if (codec == "opus") listOf(48_000) else rates

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
        bitPerfect: Boolean = false,
        maxSampleRate: Int = DEFAULT_MAX_RATE,
    ): Boolean {
        val rates = (if (preferHiRes) RATES_HIRES else RATES_STANDARD).filter { it <= maxSampleRate }
        return sampleRate in rates && bitDepth <= bitDepthFor(bitPerfect)
    }

    /**
     * The server always streams a format we advertised, so accept the `stream/start`
     * player info directly.
     */
    fun resolve(player: StreamStartPlayerInfo): NegotiatedFormat =
        NegotiatedFormat(player.codec, player.sampleRate, player.bitDepth, player.channels)

    /**
     * The ceiling this phone can actually put out — its mixer's native rate at the
     * depth [SendspinExoEngine] renders. This is the honest "playing at" answer
     * when the file is decoded on the device itself (playing a Navidrome stream
     * direct) rather than negotiated with Music Assistant, since nothing upstream
     * gets a say in it.
     */
    fun deviceOutputQuality(bitPerfect: Boolean = false): StreamQuality {
        val rate = try {
            android.media.AudioTrack.getNativeOutputSampleRate(android.media.AudioManager.STREAM_MUSIC)
        } catch (_: Throwable) {
            48_000
        }
        return StreamQuality(codec = "PCM", sampleRateHz = rate, bitDepth = bitDepthFor(bitPerfect))
    }
}
