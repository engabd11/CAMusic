package com.engabd.sendpin.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
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
 * Decodes the Sendspin binary audio stream and plays it through an [AudioTrack].
 * Supports 16-bit and 24-bit PCM output: when [bitPerfect24Bit] is true the
 * AudioTrack is built with `ENCODING_PCM_24BIT_PACKED` (API 31+, which is our
 * minSdk), so 24-bit FLAC streams keep their full depth instead of being
 * truncated to 16-bit. FLAC and Opus are decoded with Android's [MediaCodec];
 * PCM is a passthrough. Pure Kotlin — no NDK — so it builds and runs without
 * the native pipeline (the native AAudio-I24 path is kept in `cpp/` for the
 * future bit-perfect phase that bypasses the Android mixer). Modeled on the
 * massdroid engine (MIT).
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

    /**
     * When true, the AudioTrack is built with `ENCODING_PCM_24BIT_PACKED` so
     * 24-bit streams are rendered at full depth. Set from
     * [com.engabd.sendpin.data.AppSettings.bitPerfect24Bit] before [start].
     * Our minSdk is 31, so the 24-bit encoding is always available.
     */
    @Volatile var bitPerfect24Bit: Boolean = false

    /**
     * The preferred output device for audio routing (USB DAC support).
     * When non-null, the AudioTrack is routed to this [AudioDeviceInfo] via
     * `setPreferredDevice`. Set from
     * [com.engabd.sendpin.data.AppSettings.preferredAudioDeviceId] before
     * [start]; resolved to an [AudioDeviceInfo] by the caller via
     * [AudioManager.getDevices].
     */
    @Volatile var preferredOutputDevice: AudioDeviceInfo? = null

    companion object {
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

        /**
         * How long the decode loop naps between re-asking the clock while the head
         * gate holds. Short enough that the head is released promptly once the filter
         * converges — that delay is dead air at the start of the track.
         */
        const val HOLD_POLL_MS = 25L

        /**
         * Resolve a stored device ID string (from
         * [com.engabd.sendpin.data.AppSettings.preferredAudioDeviceId]) to a live
         * [AudioDeviceInfo] by scanning the system's output devices. Returns null
         * when the device is no longer connected (unplugged USB DAC, etc.).
         */
        fun resolveAudioDevice(audioManager: AudioManager, deviceId: String): AudioDeviceInfo? {
            if (deviceId.isBlank()) return null
            return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.id.toString() == deviceId }
        }
    }

    /**
     * What to do with the frame at the head of a stream.
     *
     * Split out as a pure function because it is the whole of the fix for tracks that
     * began a couple of seconds in, and the engine around it is welded to [AudioTrack]
     * and [MediaCodec] — there is no other way to test the decision.
     */
    internal object HeadGate {
        /**
         * How long the head of a stream may be held back waiting for the clock filter
         * to converge before we give up and play it unscheduled.
         *
         * `client/time` runs at a 300 ms cadence until 50 samples are in, and
         * `isReadyForPlaybackStart()` wants eight low-error ones — about 2.4 s from a
         * cold connect, and nothing at all on a connection that has been up a while,
         * which is the normal case since the connection service keeps the socket open
         * whether or not music is playing. Three seconds clears the cold-start case
         * with margin; past that the offset is not coming, and indefinite silence is
         * worse than a stream that is merely out of step.
         */
        const val MAX_STALL_MS = 3_000L

        enum class Decision {
            /** Schedule normally: the clock is trustworthy. */
            SCHEDULE,

            /**
             * Leave the queue alone and ask again — the clock isn't ready yet.
             *
             * Emphatically *not* "drop this frame". Holding means the audio waits; it
             * does not mean it is thrown away. See the call site.
             */
            HOLD,

            /** Give up waiting and play it now, unscheduled. */
            PLAY_NOW,
        }

        fun decide(clockReady: Boolean, stalledMs: Long, maxStallMs: Long = MAX_STALL_MS): Decision = when {
            clockReady -> Decision.SCHEDULE
            stalledMs >= maxStallMs -> Decision.PLAY_NOW
            else -> Decision.HOLD
        }
    }

    private class Frame(val serverTsUs: Long, val payload: ByteArray)

    private val queue = LinkedBlockingQueue<Frame>(QUEUE_CAPACITY)
    @Volatile private var running = false
    private var worker: Thread? = null

    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var isPcm = false
    /** Whether the current AudioTrack was built for 24-bit packed PCM. */
    private var use24Bit = false
    /** What the AudioTrack was built for, to check the decoder against. */
    private var trackSampleRate = 0
    private var trackChannels = 0
    /**
     * The next frame is the head of a stream and should be held until its scheduled
     * moment. Cleared once that frame is released; set again by [start] and [flush],
     * which are the two points where playback restarts from a known position.
     */
    @Volatile private var awaitStart = false
    /** When the current head-of-stream wait began, for the give-up deadline. */
    @Volatile private var headArmedAtMs = 0L
    @Volatile private var volume = 1.0f

    /** Arm the head-of-stream gate and restart its deadline. */
    private fun armHead() {
        awaitStart = true
        headArmedAtMs = android.os.SystemClock.elapsedRealtime()
    }

    @Synchronized
    fun start(format: StreamStartPlayerInfo) {
        stop()
        val channels = format.channels.coerceIn(1, 2)
        isPcm = format.codec.equals("pcm", ignoreCase = true)
        // 24-bit output is only worth requesting when the source is actually
        // 24-bit — a 16-bit file sent through a 24-bit track is just padded
        // zeroes, and MediaCodec decodes it to 16-bit anyway. The bit-perfect
        // setting gates the feature; the stream's bit depth gates it per-track.
        use24Bit = bitPerfect24Bit && format.bitDepth >= 24
        codec = when (format.codec.lowercase()) {
            "opus" -> createOpusDecoder(format.sampleRate, channels, format.codecHeader)
            "flac" -> createFlacDecoder(format.sampleRate, channels, format.bitDepth, format.codecHeader)
            else -> null   // pcm or unknown -> passthrough / silence
        }
        trackSampleRate = format.sampleRate
        trackChannels = channels
        track = createTrack(format.sampleRate, channels, use24Bit).also { it.setVolume(volume); it.play() }
        armHead()
        running = true
        worker = thread(name = "sendspin-audio", isDaemon = true) { runLoop() }
        Log.d(TAG, "start ${format.codec} ${format.sampleRate}/${format.bitDepth} ch=$channels 24bit=$use24Bit")
    }

    fun submit(frame: ByteArray) {
        if (!running) return
        if (frame.size <= HEADER_SIZE || (frame[0].toInt() and 0xFF) != TYPE_PLAYER_AUDIO) return
        val f = Frame(parseTimestampUs(frame), frame.copyOfRange(HEADER_SIZE, frame.size))
        if (queue.offer(f)) return
        // Full. Which end to sacrifice depends on what the queue is holding: normally
        // the oldest frame is the most stale and goes, but while the head gate is
        // armed the oldest frame is the *start of the track* the gate exists to
        // protect — dropping it there would reintroduce the bug from the other side.
        if (awaitStart) return
        queue.poll()
        queue.offer(f)
    }

    /** stream/clear — a seek or track jump: drop buffered audio and reset. */
    fun flush() {
        queue.clear()
        // A seek or track jump restarts from a known point, so the next frame is a
        // head-of-stream again and gets scheduled like one.
        armHead()
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

    /**
     * Route audio output to a specific [AudioDeviceInfo] (USB DAC, Bluetooth
     * headset, etc.). Takes effect on the next [start] call — the AudioTrack is
     * rebuilt per stream. Call with null to revert to the system default route.
     */
    fun setPreferredDevice(device: AudioDeviceInfo?) {
        preferredOutputDevice = device
    }

    // --- decode loop ------------------------------------------------------

    private fun runLoop() {
        val info = MediaCodec.BufferInfo()
        while (running) {
            // ── Head-of-stream gate ────────────────────────────────────────────
            //
            // Only the *first* frame of a stream is scheduled; after that the
            // AudioTrack paces playback itself at the stream's sample rate.
            //
            // Scheduling every frame was wrong and audible: the clock filter needs a
            // few round-trips to converge, so early frames played immediately (unsynced)
            // and then, a second or two in, the loop suddenly started blocking on frames
            // whose scheduled time had already been consumed by the unscheduled head
            // start. That transition is a stall the listener hears as a skip. Deciding
            // once, at the head of the stream, cannot do that: either the whole stream
            // is scheduled or none of it is.
            //
            // The decision is made *before* touching the queue, and that ordering is
            // the whole fix for "the track starts a few seconds in" and "no audio for
            // the first few seconds". This used to poll a frame first and then
            // `continue` on HOLD, which threw that frame away — so every frame that
            // arrived while the clock was converging was silently destroyed. The
            // server streams several seconds ahead, so by the time the offset landed
            // the head of the queue was already seconds into the song; and if the
            // clock never converged at all, the audio was simply gone.
            //
            // Holding without polling lets the frames pile up instead. Whenever the
            // gate opens, the head of the queue is still the head of the *track*.
            var scheduleHead = false
            if (awaitStart) {
                val stalledMs = android.os.SystemClock.elapsedRealtime() - headArmedAtMs
                // `isSynced()` is true after a *single* round-trip, long before the
                // filter has converged, so scheduling on it meant scheduling against
                // an offset still seconds wide: the head was either held in silence
                // while the server ran on, or dropped as "late". Playback start has
                // its own, stricter readiness test — this is what it was for.
                when (HeadGate.decide(clockReady = clock.isReadyForPlaybackStart(), stalledMs = stalledMs)) {
                    HeadGate.Decision.HOLD -> {
                        // Don't touch the queue. Let it fill.
                        try { Thread.sleep(HOLD_POLL_MS) } catch (_: InterruptedException) { break }
                        continue
                    }
                    HeadGate.Decision.PLAY_NOW ->
                        Log.w(TAG, "head: clock never converged in ${stalledMs}ms — playing unscheduled")
                    HeadGate.Decision.SCHEDULE -> scheduleHead = true
                }
            }

            val frame = try {
                queue.poll(200, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                break
            } ?: continue                      // nothing yet — the gate re-decides next pass
            val t = track ?: continue

            if (awaitStart) {
                // A frame is in hand, so the gate has actually done its job and can
                // close. Disarming any earlier would have let a pass that polled
                // nothing leave the rest of the stream unscheduled.
                awaitStart = false
                if (scheduleHead && !awaitFrameTime(frame.serverTsUs)) {
                    // Its moment has passed — which for the head of a stream means we
                    // spent the wait converging, not that this audio is stale. The
                    // spec's "drop late chunks" is about staying in sync mid-stream;
                    // applying it here deletes the start of the song, which is the bug
                    // being fixed. Play it, and accept being a beat behind the group.
                    Log.d(TAG, "head late but earliest available — playing unscheduled")
                } else if (scheduleHead) {
                    Log.i(TAG, "head released: err=${clock.errorUs()}us")
                }
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
                // The decoder's own answer about what it is producing. The AudioTrack
                // was built from the server's `stream/start` claim, so a mismatch here
                // means the badge is lying and playback is pitched wrong. Log only —
                // rebuilding the track mid-stream is a bigger change than this earns.
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    runCatching {
                        val f = c.outputFormat
                        val rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (rate != trackSampleRate || ch != trackChannels) {
                            Log.w(TAG, "decoder output $rate/${ch}ch != track $trackSampleRate/${trackChannels}ch")
                        }
                    }
                    outIdx = c.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                }
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
     * Waiting is bounded: a timestamp implausibly far ahead means the server's epoch
     * isn't what we think, and sleeping on it would stall playback outright — better
     * to play immediately and let the next frames settle.
     *
     * Whether the clock is trustworthy enough to schedule against at all is the
     * caller's decision, via [HeadGate] — this only converts and waits.
     */
    private fun awaitFrameTime(serverTsUs: Long): Boolean {
        if (serverTsUs <= 0L) return true                         // nothing to schedule against
        val localUs = clock.serverTimeToLocal(serverTsUs) - staticDelayMs * 1_000L
        var waitUs = localUs - clock.nowUs()
        if (waitUs < -LATE_TOLERANCE_US) {                        // too late to be useful
            Log.d(TAG, "head late by ${-waitUs / 1000}ms — dropped")
            return false
        }
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

    /**
     * Build the AudioTrack. When [bit24] is true, uses
     * `ENCODING_PCM_24BIT_PACKED` (API 31+, which is our minSdk) so 24-bit
     * sources are rendered at full depth. When [preferredOutputDevice] is
     * set, routes output to that device (USB DAC) via `setPreferredDevice`.
     */
    private fun createTrack(sampleRate: Int, channels: Int, bit24: Boolean): AudioTrack {
        val channelMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val encoding = if (bit24) AudioFormat.ENCODING_PCM_24BIT_PACKED else AudioFormat.ENCODING_PCM_16BIT
        // 24-bit packed is 3 bytes per sample; 16-bit is 2. The buffer is sized
        // for ~1 s of headroom either way.
        val bytesPerSample = if (bit24) 3 else 2
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
        val bufSize = max(minBuf, sampleRate * channels * bytesPerSample)
        val builder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
        val t = builder.build()
        // Route to a preferred output device (USB DAC) if one is set. Done after
        // build because `setPreferredDevice` is a runtime call, not a builder one.
        preferredOutputDevice?.let { dev -> t.setPreferredDevice(dev) }
        return t
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
