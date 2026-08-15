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

    /**
     * `"fLaC"` + a single STREAMINFO metadata block, matching what `FlacExtractor`
     * sniffs for (the 4-byte magic) and needs to parse the stream (STREAMINFO -
     * sample rate, channels, bit depth). Sendspin FLAC frames follow directly after
     * this and need no further framing of their own - a FLAC elementary stream is
     * just STREAMINFO followed by frames, which is exactly what MediaCodec's csd-0
     * convention already assumes.
     *
     * @param codecHeaderB64 the base64 `stream/start.codec_header` - required to be
     *   exactly [FLAC_STREAMINFO_SIZE] bytes once decoded, the same payload
     *   MediaCodec's FLAC decoder takes as csd-0. Returns null (a stream-start
     *   error, not something to paper over) when it's missing or the wrong size:
     *   a fabricated STREAMINFO would decode to a plausible-looking stream at the
     *   wrong sample rate/channel count, which is worse than failing loudly.
     */
    fun flacStreamHeader(codecHeaderB64: String?): ByteArray? {
        // The MIME decoder, not the plain one: it tolerates embedded line breaks,
        // matching android.util.Base64.DEFAULT's leniency on the encoders elsewhere
        // in this codebase (see SendspinAudioEngine.createFlacDecoder) without
        // depending on the Android framework - this class stays plain-JVM
        // testable because of it.
        val streamInfo = codecHeaderB64
            ?.let { runCatching { Base64.getMimeDecoder().decode(it) }.getOrNull() }
            ?.takeIf { it.size == FLAC_STREAMINFO_SIZE }
            ?: return null
        return ByteBuffer.allocate(4 + 4 + FLAC_STREAMINFO_SIZE).apply {
            put(MAGIC_FLAC)
            // Metadata block header: 1 bit last-block=1, 7 bits type=0 (STREAMINFO),
            // 24 bits length=34, big-endian - i.e. 0x80 0x00 0x00 0x22.
            put(0x80.toByte()); put(0x00); put(0x00); put(FLAC_STREAMINFO_SIZE.toByte())
            put(streamInfo)
        }.array()
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
