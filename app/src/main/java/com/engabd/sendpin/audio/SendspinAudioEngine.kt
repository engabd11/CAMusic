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
 * Frames are **scheduled**, not played on arrival: each carries the server-clock
 * instant it should be heard, and [clock] maps that onto the local clock. See
 * [awaitFrameTime] — without it the server's read-ahead buffer (several seconds) is
 * poured straight into the track, so a track starts part-way in and can never stay in
 * step with another speaker.
 */
class SendspinAudioEngine(private val clock: ClockSync) {

    /**
     * The server-side `sendspin_static_delay` this player is configured with, in ms.
     * Subtracted from every frame's scheduled time, per the spec. Zero until the
     * server tells us otherwise.
     */
    @Volatile var staticDelayMs: Int = 0

    private companion object {
        const val TAG = "SendspinAudio"
        const val HEADER_SIZE = 9
        const val TYPE_PLAYER_AUDIO = 4
        const val OPUS_MAX_INPUT = 64 * 1024
        const val FLAC_MAX_INPUT = 256 * 1024
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val QUEUE_CAPACITY = 2048

        /**
         * How late a frame may be and still be worth playing. Below this the audio is
         * already behind and playing it only pushes everything after it further out;
         * the spec says to drop it.
         */
        const val LATE_TOLERANCE_US = 120_000L

        /**
         * A scheduled time further ahead than this is not believed. The clock filter
         * needs a few round-trips to converge, and sleeping on a bad offset would
         * stall playback completely — so an implausible lead plays now instead.
         */
        const val MAX_LEAD_US = 15_000_000L
    }

    private class Frame(val serverTsUs: Long, val payload: ByteArray)

    private val queue = LinkedBlockingQueue<Frame>(QUEUE_CAPACITY)
    @Volatile private var running = false
    private var worker: Thread? = null

    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var isPcm = false
    /**
     * The next frame is the head of a stream and should be held until its scheduled
     * moment. Cleared once that frame is released; set again by [start] and [flush],
     * which are the two points where playback restarts from a known position.
     */
    @Volatile private var awaitStart = false
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
        awaitStart = true
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
        // A seek or track jump restarts from a known point, so the next frame is a
        // head-of-stream again and gets scheduled like one.
        awaitStart = true
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

            // Only the *first* frame of a stream is scheduled; after that the
            // AudioTrack paces playback itself at the stream's sample rate.
            //
            // Scheduling every frame was wrong and audible: the clock filter needs a
            // few round-trips to converge, so early frames played immediately (unsynced)
            // and then, a second or two in, `isSynced()` flipped and the loop suddenly
            // started blocking on frames whose scheduled time had already been consumed
            // by the unscheduled head start. That transition is a stall the listener
            // hears as a skip. Deciding once, at the head of the stream, cannot do that:
            // either the whole stream is scheduled or none of it is.
            if (awaitStart) {
                awaitStart = false
                if (!awaitFrameTime(frame.serverTsUs)) continue
            }

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

    /**
     * Block until [serverTsUs] — the instant the server wants this chunk heard —
     * arrives on the local clock. Returns false if the frame is too late to play.
     *
     * Called for the head of a stream only — see the call site. The spec is explicit: "Binary audio
     * messages contain timestamps in the server's time domain indicating when the
     * audio should be played… Clients must translate this server timestamp to their
     * local clock using the offset computed from clock synchronization", and "Audio
     * chunks may arrive with timestamps in the past… clients should drop these late
     * chunks to maintain sync."
     *
     * The loop used to write every frame the moment it arrived. Since the server
     * streams as far ahead as `buffer_capacity` allows — several seconds — the whole
     * prefetched buffer was played out immediately, so a track began some seconds into
     * itself and the phone could never stay in step with another speaker. [ClockSync]
     * was built for exactly this and had no reader.
     *
     * Waiting is bounded: a timestamp implausibly far ahead means the clock filter
     * hasn't converged (or the server's epoch isn't what we think), and sleeping on it
     * would stall playback outright — better to play immediately and let the next
     * frames settle.
     */
    private fun awaitFrameTime(serverTsUs: Long): Boolean {
        if (serverTsUs <= 0L || !clock.isSynced()) return true    // nothing to schedule against
        val localUs = clock.serverTimeToLocal(serverTsUs) - staticDelayMs * 1_000L
        var waitUs = localUs - clock.nowUs()
        if (waitUs < -LATE_TOLERANCE_US) return false             // too late to be useful
        if (waitUs > MAX_LEAD_US) return true                     // implausible: don't stall on it
        while (running && waitUs > 0) {
            // Sleep in slices so stop()/flush() are still responsive mid-wait.
            val slice = waitUs.coerceAtMost(20_000L)
            try {
                Thread.sleep(slice / 1_000L, ((slice % 1_000L) * 1_000L).toInt())
            } catch (_: InterruptedException) {
                return false
            }
            waitUs = localUs - clock.nowUs()
        }
        return running
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
