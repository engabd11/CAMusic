package com.engabd.sendpin.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Base64
import android.util.Log
import com.engabd.sendpin.protocol.ClockSync
import com.engabd.sendpin.protocol.StreamStartPlayerInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.max

/**
 * Decodes the Sendspin binary audio stream and plays it through an [AudioTrack]
 * (16-bit PCM). FLAC and Opus are decoded with Android's [MediaCodec]; PCM is a
 * passthrough. Pure Kotlin — no NDK — so it builds and runs without the native
 * pipeline (the native AAudio-I24 path is kept in `cpp/` for the future
 * bit-perfect 24-bit phase). Modeled on the massdroid engine (MIT).
 *
 * Each binary WebSocket frame is
 * `[type:uint8 = 4][server_ts_us: int64 big-endian][payload…]`.
 *
 * The [clock] is held for the later drift-correcting multi-room sync; a solo
 * player is paced naturally by the AudioTrack at the stream's sample rate.
 */
class SendspinAudioEngine(@Suppress("unused") private val clock: ClockSync) {

    private companion object {
        const val TAG = "SendspinAudio"
        const val HEADER_SIZE = 9
        const val TYPE_PLAYER_AUDIO = 4
        const val OPUS_MAX_INPUT = 64 * 1024
        const val FLAC_MAX_INPUT = 256 * 1024
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val QUEUE_CAPACITY = 2048
    }

    private class Frame(val serverTsUs: Long, val payload: ByteArray)

    private val queue = LinkedBlockingQueue<Frame>(QUEUE_CAPACITY)
    @Volatile private var running = false
    private var worker: Thread? = null

    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var isPcm = false
    @Volatile private var volume = 1.0f

    @Synchronized
    fun start(format: StreamStartPlayerInfo) {
        stop()
        val channels = format.channels.coerceIn(1, 2)
        isPcm = format.codec.equals("pcm", ignoreCase = true)
        codec = when (format.codec.lowercase()) {
            "opus" -> createOpusDecoder(format.sampleRate, channels, format.codecHeader)
            "flac" -> createFlacDecoder(format.sampleRate, channels, format.bitDepth, format.codecHeader)
            else -> null   // pcm or unknown -> passthrough / silence
        }
        track = createTrack(format.sampleRate, channels).also { it.setVolume(volume); it.play() }
        running = true
        worker = thread(name = "sendspin-audio", isDaemon = true) { runLoop() }
        Log.d(TAG, "start ${format.codec} ${format.sampleRate}/${format.bitDepth} ch=$channels")
    }

    fun submit(frame: ByteArray) {
        if (!running) return
        if (frame.size <= HEADER_SIZE || (frame[0].toInt() and 0xFF) != TYPE_PLAYER_AUDIO) return
        val f = Frame(parseTimestampUs(frame), frame.copyOfRange(HEADER_SIZE, frame.size))
        if (!queue.offer(f)) { queue.poll(); queue.offer(f) }   // drop oldest under pressure
    }

    /** stream/clear — a seek or track jump: drop buffered audio and reset. */
    fun flush() {
        queue.clear()
        try { codec?.flush(); codec?.start() } catch (_: Exception) {}
        try { track?.pause(); track?.flush(); track?.play() } catch (_: Exception) {}
    }

    @Synchronized
    fun stop() {
        running = false
        worker?.interrupt(); worker = null
        queue.clear()
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        try { track?.pause(); track?.flush(); track?.release() } catch (_: Exception) {}
        track = null
    }

    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        track?.setVolume(volume)
    }

    // --- decode loop ------------------------------------------------------

    private fun runLoop() {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val frame = try {
                queue.poll(200, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                break
            } ?: continue
            val t = track ?: continue

            if (isPcm) {
                t.write(frame.payload, 0, frame.payload.size)   // s16le passthrough
                continue
            }

            val c = codec ?: continue
            try {
                val inIdx = c.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (inIdx >= 0) {
                    c.getInputBuffer(inIdx)?.let { buf ->
                        buf.clear(); buf.put(frame.payload)
                        c.queueInputBuffer(inIdx, 0, frame.payload.size, frame.serverTsUs, 0)
                    }
                }
                var outIdx = c.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                while (outIdx >= 0) {
                    if (info.size > 0) {
                        c.getOutputBuffer(outIdx)?.let { out ->
                            // Hand the codec's own buffer straight to the track: no
                            // intermediate ByteArray, so nothing to allocate, copy or
                            // pool on the decode hot path (~112 KB/s at 900 kbps FLAC).
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            t.write(out, info.size, AudioTrack.WRITE_BLOCKING)
                        }
                    }
                    c.releaseOutputBuffer(outIdx, false)
                    outIdx = c.dequeueOutputBuffer(info, 0)
                }
            } catch (e: IllegalStateException) {
                Log.w(TAG, "codec error: ${e.message}")
            }
        }
    }

    // --- codec / track creation (derived from the massdroid engine) -------

    private fun createTrack(sampleRate: Int, channels: Int): AudioTrack {
        val channelMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = max(minBuf, sampleRate * channels * 2)   // ~1 s of headroom
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun createFlacDecoder(sampleRate: Int, channels: Int, bitDepth: Int, codecHeader: String?): MediaCodec {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_FLAC, sampleRate, channels)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FLAC_MAX_INPUT)
        format.setInteger("bit-depth", bitDepth)
        codecHeader?.let {
            try { format.setByteBuffer("csd-0", ByteBuffer.wrap(Base64.decode(it, Base64.DEFAULT))) } catch (_: Exception) {}
        }
        return MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC).apply {
            configure(format, null, null, 0); start()
        }
    }

    private fun createOpusDecoder(sampleRate: Int, channels: Int, codecHeader: String?): MediaCodec {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, channels)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, OPUS_MAX_INPUT)
        val csd0 = codecHeader?.let {
            try { Base64.decode(it, Base64.DEFAULT) } catch (_: Exception) { null }
        } ?: opusHead(channels, sampleRate)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
        val preSkipNs = 3840L * 1_000_000_000L / sampleRate.toLong()
        format.setByteBuffer("csd-1", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).apply { putLong(preSkipNs); rewind() })
        format.setByteBuffer("csd-2", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).apply { putLong(80_000_000L); rewind() })
        return MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).apply {
            configure(format, null, null, 0); start()
        }
    }

    private fun opusHead(channels: Int, sampleRate: Int): ByteArray =
        ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("OpusHead".toByteArray())
            put(1)
            put(channels.toByte())
            putShort(3840.toShort())
            putInt(sampleRate)
            putShort(0)
            put(0)
        }.array()

    private fun parseTimestampUs(data: ByteArray): Long {
        var ts = 0L
        for (i in 1..8) ts = (ts shl 8) or (data[i].toLong() and 0xffL)
        return ts
    }
}
