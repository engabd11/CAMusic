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
 * Everything this phone can genuinely play, and nothing narrowed: every codec the
 * engine decodes, at every rate the device can open. Which of them is played is the
 * listener's choice on the server, not ours: Music Assistant's "Preferred audio
 * format" offers each advertised format by name, and under Automatic its "Sample
 * rate selection" (Smart, Bit-perfect, 48 kHz, 96 kHz, Highest supported by player)
 * picks among the rates of the codec listed first. A list that leaves a rate out
 * takes that option away from the listener.
 */
object FormatNegotiator {

    /**
     * What [SendspinNativeEngine] renders by default: 16-bit PCM through an
     * `ENCODING_PCM_16BIT` AudioTrack. Advertising a depth we then truncate only
     * makes the server spend bandwidth on bits thrown away on arrival, so this
     * stays the floor *and* the default ceiling.
     */
    const val DEFAULT_BIT_DEPTH = 16

    /**
     * The ceiling advertised when the user turns high-resolution output on.
     *
     * This describes the **local/ExoPlayer** path, where it is real: media3's
     * float render path (`setEnableAudioFloatOutput`) can carry a decoded
     * 24-bit sample all the way to the sink instead of it being requantised —
     * see [SignalPath].
     *
     * It does **not** describe the Sendspin/Music Assistant path. The native
     * Oboe engine renders int16 end to end (see
     * [SendspinNativeEngine.OUTPUT_BIT_DEPTH]), so advertising this depth
     * there would only spend Music Assistant's bandwidth on bits the engine
     * throws away on arrival. That path's caller —
     * [com.engabd.sendpin.service.Playback.startSendspin], where the format
     * list is built — is where the engine's own capability is known, so the
     * cap is applied there rather than in this object, which has no way to
     * know which engine is asking.
     *
     * Gated on the setting rather than always-on: 24-bit costs real bandwidth,
     * and on a device whose mixer runs at 16-bit the extra bits are resampled
     * away again downstream for nothing.
     */
    const val HIRES_BIT_DEPTH = 24

    /** The depth we advertise given the user's bit-perfect preference. */
    fun bitDepthFor(bitPerfect: Boolean) = if (bitPerfect) HIRES_BIT_DEPTH else DEFAULT_BIT_DEPTH

    /**
     * Every rate the engine follows, 48 kHz first. The first entry of the first codec
     * is the client's default format, which Music Assistant streams under Automatic
     * when it has no session rate to follow; 48 kHz is the phone mixer's own rate.
     * [maxSampleRate] trims the top to what the device can open.
     */
    private val RATES = listOf(48_000, 44_100, 96_000, 88_200, 192_000, 176_400)

    /** Every codec [SendspinNativeEngine] can decode, in the order "auto" offers them. */
    val CODECS = listOf("flac", "pcm", "opus")

    /**
     * [firstCodec] — the codec listed first, or null for "auto" (FLAC, then PCM, then
     * Opus). Every codec is listed either way: the first one only decides what Music
     * Assistant's Automatic format and its Sample rate selection work from.
     * [preferFlac] — under "auto", order lossless FLAC ahead of uncompressed PCM. Both
     * are bit-identical; FLAC costs roughly half the bandwidth and a little decode CPU.
     * [maxSampleRate] — the highest rate the device can open (see
     * [AudioOutputs.maxSupportedSampleRate]). Advertising a rate is a commitment: the
     * server may pick it, so a rate the phone cannot open would play nothing.
     *
     * Opus is 48 kHz only. Every format is 16-bit, because [SendspinNativeEngine]
     * renders int16 end to end ([SendspinNativeEngine.OUTPUT_BIT_DEPTH]): a deeper
     * format would spend bandwidth on bits truncated on arrival.
     */
    fun supportedFormats(
        maxSampleRate: Int,
        firstCodec: String? = null,
        preferFlac: Boolean = true,
    ): List<AudioFormatSpec> {
        val auto = (if (preferFlac) listOf("flac", "pcm") else listOf("pcm", "flac")) + "opus"
        val first = firstCodec?.lowercase()?.takeIf { it in CODECS }
        val codecs = first?.let { listOf(it) + (auto - it) } ?: auto
        val rates = RATES.filter { it <= maxSampleRate }
        return codecs.flatMap { codec ->
            (if (codec == "opus") listOf(48_000) else rates).map { AudioFormatSpec(codec, 2, it, DEFAULT_BIT_DEPTH) }
        }
    }

    /**
     * Could Music Assistant stream a source at [sampleRate]/[bitDepth] without
     * touching it?
     *
     * True only when the rate is one we advertise (so no resampling is forced) and the
     * depth is one the engine renders (so no requantising). Anything else means the
     * server has to convert somewhere between the file and this phone, which is exactly
     * when "play at original quality" should go direct to the source instead.
     */
    fun canStreamUntouched(sampleRate: Int, bitDepth: Int, maxSampleRate: Int): Boolean =
        sampleRate in RATES && sampleRate <= maxSampleRate && bitDepth <= DEFAULT_BIT_DEPTH

    /**
     * The server always streams a format we advertised, so accept the `stream/start`
     * player info directly.
     */
    fun resolve(player: StreamStartPlayerInfo): NegotiatedFormat =
        NegotiatedFormat(player.codec, player.sampleRate, player.bitDepth, player.channels)

    /**
     * The ceiling this phone can actually put out — its mixer's native rate at the
     * depth [bitPerfect] can carry on the **local/ExoPlayer** path (see
     * [HIRES_BIT_DEPTH]'s doc for why that qualifier matters: this is not a claim
     * about [SendspinNativeEngine], which never carries more than
     * [SendspinNativeEngine.OUTPUT_BIT_DEPTH]). This is the honest "playing at"
     * answer when the file is decoded on the device itself (playing a Navidrome
     * stream direct) rather than negotiated with Music Assistant, since nothing
     * upstream gets a say in it.
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
