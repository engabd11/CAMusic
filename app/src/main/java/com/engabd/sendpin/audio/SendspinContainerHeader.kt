package com.engabd.sendpin.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * Synthesizes the container framing ExoPlayer's extractors sniff and parse, from
 * the raw elementary-stream bytes Sendspin puts on the wire.
 *
 * MediaCodec and ExoPlayer's extractors want different things from the *same* wire
 * data. MediaCodec's FLAC/Opus decoders take `codec_header` directly as CSD — see
 * [SendspinAudioEngine.createFlacDecoder]/[SendspinAudioEngine.createOpusDecoder] —
 * because MediaCodec is handed pre-parsed codec parameters out of band. ExoPlayer's
 * extractors instead *sniff* a real container from the byte stream itself, so this
 * class exists purely to wrap the same bytes in the minimal container each
 * extractor needs. No audio data is transformed — only framed.
 */
object SendspinContainerHeader {

    /** The one metadata block a FLAC stream is required to have. */
    private const val FLAC_STREAMINFO_SIZE = 34

    /** `"fLaC"` magic (4 bytes) + a metadata-block header (4 bytes), preceding STREAMINFO. */
    private const val FLAC_HEADER_PREFIX_SIZE = 8

    /**
     * `"fLaC"` + a single STREAMINFO metadata block, matching what `FlacExtractor`
     * sniffs for (the 4-byte magic) and needs to parse the stream (STREAMINFO -
     * sample rate, channels, bit depth). Sendspin FLAC frames follow directly after
     * this and need no further framing of their own - a FLAC elementary stream is
     * just STREAMINFO followed by frames, which is exactly what MediaCodec's csd-0
     * convention already assumes.
     *
     * @param codecHeaderB64 the base64 `stream/start.codec_header`. [extractStreamInfo]
     *   tolerates two shapes on the wire - see its own doc for why both are real.
     *   Returns null (a stream-start error, not something to paper over) when
     *   neither shape matches: a fabricated STREAMINFO would decode to a
     *   plausible-looking stream at the wrong sample rate/channel count, which is
     *   worse than failing loudly.
     */
    fun flacStreamHeader(codecHeaderB64: String?): ByteArray? {
        val streamInfo = decodeBase64(codecHeaderB64)?.let { extractStreamInfo(it) } ?: return null
        return ByteBuffer.allocate(4 + 4 + FLAC_STREAMINFO_SIZE).apply {
            put(MAGIC_FLAC)
            // Metadata block header: 1 bit last-block=1, 7 bits type=0 (STREAMINFO),
            // 24 bits length=34, big-endian - i.e. 0x80 0x00 0x00 0x22.
            put(0x80.toByte()); put(0x00); put(0x00); put(FLAC_STREAMINFO_SIZE.toByte())
            put(streamInfo)
        }.array()
    }

    /**
     * Pulls the bare [FLAC_STREAMINFO_SIZE]-byte STREAMINFO payload out of a decoded
     * `codec_header`, which arrives in either of two shapes depending on the server:
     * bare STREAMINFO (34 bytes - the spec's csd-0 convention, and what this code
     * originally assumed exclusively), or a complete FLAC header - `"fLaC"` magic +
     * metadata-block header + STREAMINFO (42 bytes). The 42-byte shape is not
     * hypothetical: confirmed on-device against a real Music Assistant server, whose
     * `codec_header` decoded to exactly 42 bytes starting with the FLAC magic and
     * made every MA stream fail to open with "missing or malformed codec_header
     * (decodedSize=42, want 34)" before this handled it. Anything else is genuinely
     * malformed, not a third shape to guess at.
     */
    fun extractStreamInfo(decoded: ByteArray): ByteArray? = when {
        decoded.size == FLAC_STREAMINFO_SIZE -> decoded
        decoded.size == FLAC_HEADER_PREFIX_SIZE + FLAC_STREAMINFO_SIZE &&
            decoded.copyOfRange(0, 4).contentEquals(MAGIC_FLAC) ->
            decoded.copyOfRange(FLAC_HEADER_PREFIX_SIZE, FLAC_HEADER_PREFIX_SIZE + FLAC_STREAMINFO_SIZE)
        else -> null
    }

    /**
     * The MIME decoder, not the plain one: it tolerates embedded line breaks,
     * matching android.util.Base64.DEFAULT's leniency on the encoders elsewhere in
     * this codebase (see [SendspinAudioEngine.createFlacDecoder]) without depending
     * on the Android framework - this class stays plain-JVM testable because of it.
     *
     * Also pads to a multiple of 4 first: unlike `android.util.Base64.DEFAULT`,
     * `java.util.Base64`'s decoders reject unpadded input, and nothing guarantees
     * Music Assistant / sendspin-js sends padded base64. Tried unpadded too, in
     * case a string that's already a multiple of 4 gets spuriously over-padded.
     */
    fun decodeBase64(b64: String?): ByteArray? {
        if (b64.isNullOrEmpty()) return null
        val padded = b64 + "=".repeat((4 - b64.length % 4) % 4)
        return runCatching { Base64.getMimeDecoder().decode(padded) }.getOrNull()
            ?: runCatching { Base64.getMimeDecoder().decode(b64) }.getOrNull()
    }

    /**
     * A canonical 44-byte WAV/RIFF header for the raw PCM Sendspin sends when the
     * negotiated codec is `"pcm"` - passthrough, no decoder involved, so the wire
     * bytes *are* the sample data (see [SendspinAudioEngine.start]'s `isPcm` path).
     *
     * The stream is live and unbounded, so the declared sizes can't be the real
     * ones - [Int.MAX_VALUE] is the standard placeholder streaming encoders use for
     * "keep reading until the source ends" rather than trusting the declared size.
     */
    fun wavStreamHeader(sampleRate: Int, channels: Int, bitDepth: Int): ByteArray {
        val blockAlign = channels * (bitDepth / 8)
        val byteRate = sampleRate * blockAlign
        val dataSize = Int.MAX_VALUE - 36 // headroom so riffSize below can't overflow
        val riffSize = dataSize + 36
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(MAGIC_RIFF); putInt(riffSize); put(MAGIC_WAVE)
            put(MAGIC_FMT); putInt(16) // PCM fmt chunk is always 16 bytes
            putShort(1) // audioFormat = 1 -> integer PCM
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(bitDepth.toShort())
            put(MAGIC_DATA); putInt(dataSize)
        }.array()
    }

    private val MAGIC_FLAC = "fLaC".toByteArray(Charsets.US_ASCII)
    private val MAGIC_RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
    private val MAGIC_WAVE = "WAVE".toByteArray(Charsets.US_ASCII)
    private val MAGIC_FMT = "fmt ".toByteArray(Charsets.US_ASCII)
    private val MAGIC_DATA = "data".toByteArray(Charsets.US_ASCII)
}
