package com.engabd.sendpin.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/**
 * Decodes a track and hands the result to [finishScan].
 *
 * `MediaCodec` in synchronous mode, driven off a background-priority coroutine.
 * A local file decodes many times faster than it plays, so a scan started as a
 * song begins is finished seconds into it; a streamed one is bounded by the
 * download, which is why [TrackScanRepository] fetches remote tracks to a
 * temporary file first rather than letting `MediaExtractor` do its own
 * networking.
 *
 * The thread priority is not a nicety. The whole point of the scan is a better
 * show, and a scan that steals cycles from the render loop or the audio thread
 * costs more than it is worth — a stutter in the room, or worse, an underrun in
 * the music. Background priority means the scheduler settles that argument in
 * favour of playback every time.
 */
object TrackScanner {

    private const val TAG = "TrackScanner"

    /**
     * Analysis cap. Beyond twelve minutes a track is a DJ set or a live album
     * side, where a global tempo estimate has little to say anyway, and the scan
     * would cost more memory than the result is worth.
     */
    const val MAX_TRACK_S = 720f

    /** Wall-clock ceiling on one decode, so a stalled source cannot pin a thread. */
    private const val DECODE_TIMEOUT_MS = 120_000L

    private const val DEQUEUE_TIMEOUT_US = 10_000L

    /**
     * Analyse the audio at [path].
     *
     * Cancellable at every buffer: a track change should stop the scan for the
     * track that is no longer playing immediately, not once it happens to finish.
     */
    suspend fun scan(path: String): ScanResult = withContext(Dispatchers.Default) {
        val previousPriority = Process.getThreadPriority(Process.myTid())
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        try {
            decode(path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Scan of $path failed: ${e.message}")
            ScanResult.Failed(ScanFailure.DECODE, e.message ?: e::class.java.simpleName)
        } finally {
            runCatching { Process.setThreadPriority(Process.myTid(), previousPriority) }
        }
    }

    private suspend fun decode(path: String): ScanResult {
        if (!File(path).exists()) return ScanResult.Failed(ScanFailure.DECODE, "file is gone")

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(path)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return ScanResult.Failed(ScanFailure.DECODE, "no audio track")

            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: return ScanResult.Failed(ScanFailure.DECODE, "no mime type")

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            val ex = OfflineExtractor()
            val pump = Pump(ex)
            val info = MediaCodec.BufferInfo()
            val deadline = System.currentTimeMillis() + DECODE_TIMEOUT_MS
            var sawInputEos = false
            var sawOutputEos = false
            var outputFormat = codec.outputFormat

            while (!sawOutputEos) {
                coroutineContext.ensureActive()
                if (System.currentTimeMillis() > deadline) {
                    return ScanResult.Failed(ScanFailure.DECODE, "decode timed out")
                }

                if (!sawInputEos) {
                    val index = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)
                        val size = if (buffer == null) -1 else extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            sawInputEos = true
                        } else {
                            val ptUs = extractor.sampleTime
                            codec.queueInputBuffer(index, 0, size, ptUs, 0)
                            extractor.advance()
                            // The cap is on decoded audio, and the presentation
                            // time is the cheapest honest measure of it.
                            if (ptUs > MAX_TRACK_S * 1_000_000L) sawInputEos = true
                        }
                    }
                }

                when (val index = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = codec.outputFormat
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (index >= 0) {
                        if (info.size > 0) {
                            codec.getOutputBuffer(index)?.let { out ->
                                out.position(info.offset)
                                out.limit(info.offset + info.size)
                                pump.feed(out, outputFormat)
                            }
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            sawOutputEos = true
                        }
                    }
                }
            }
            // The container's own duration, which the decode cannot know: a capped
            // decode stops early and its frame count then describes the analysis, not
            // the track. See [TrackScan.analysedS].
            val fullDurationS = runCatching {
                if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                    inputFormat.getLong(MediaFormat.KEY_DURATION) / 1_000_000f
                } else 0f
            }.getOrDefault(0f)
            return finishScan(ex, fullDurationS)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * Downmix, resample and forward decoded PCM into the [OfflineExtractor].
     *
     * The downmix and the box-filter resample are the same two steps
     * [AudioAnalysisTap] performs on the live path, sharing the phase clock that
     * makes them agree. That is what "the frames are interchangeable" means in
     * practice: a scanned frame and a live frame of the same music are built
     * from the same samples, so every threshold tuned against one holds for the
     * other.
     */
    private class Pump(private val ex: OfflineExtractor) {
        private var resampler = BoxResampleClock(1f)
        private var rate = 0
        private var channels = 0
        private var pcmEncoding = AudioFormatEncoding.PCM_16

        private var accSum = 0f
        private var accCount = 0
        private var last = 0f

        private val out = FloatArray(OUT_CHUNK)
        private var outCount = 0

        fun feed(buffer: ByteBuffer, format: MediaFormat) {
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE, 0)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 0)
            if (sampleRate <= 0 || channelCount <= 0) return
            if (sampleRate != rate) {
                rate = sampleRate
                resampler = BoxResampleClock(sampleRate.toFloat() / ANALYSIS_SAMPLE_RATE)
            }
            channels = channelCount
            pcmEncoding = AudioFormatEncoding.of(format)

            buffer.order(ByteOrder.nativeOrder())
            val bytesPerSample = pcmEncoding.bytes
            val frameBytes = bytesPerSample * channels
            if (frameBytes <= 0) return
            val frames = buffer.remaining() / frameBytes
            var offset = buffer.position()

            for (f in 0 until frames) {
                // (L+R)/2 — the mid downmix, which is exactly what the live tap
                // analyses. The side channels only ever fed stereo pan, and pan
                // comes from the tap live, so there is nothing to keep here.
                var sum = 0f
                for (c in 0 until channels) {
                    sum += pcmEncoding.read(buffer, offset + c * bytesPerSample)
                }
                val mono = sum / channels
                offset += frameBytes

                accSum += mono
                accCount++
                var due = resampler.advance()
                while (due-- > 0) {
                    if (accCount > 0) {
                        last = accSum / accCount
                        accSum = 0f
                        accCount = 0
                    }
                    out[outCount++] = last
                    if (outCount == out.size) {
                        ex.push(out, outCount)
                        outCount = 0
                    }
                }
            }
            if (outCount > 0) {
                ex.push(out, outCount)
                outCount = 0
            }
        }

        private companion object {
            /** Comfortably more than one decoder buffer's worth of output. */
            const val OUT_CHUNK = 4096
        }
    }

    /**
     * How the decoder hands over its samples.
     *
     * `MediaCodec` normally answers in 16-bit PCM, but a decoder is free to
     * report [MediaFormat.KEY_PCM_ENCODING] and give floats or 24/32-bit — and
     * the high-resolution sources this app exists to play are exactly the ones
     * that do. Reading those as shorts produces noise, and noise analyses as a
     * track with no beat in it at all.
     */
    private enum class AudioFormatEncoding(val bytes: Int) {
        PCM_16(2), PCM_24(3), PCM_FLOAT(4), PCM_32(4);

        fun read(buffer: ByteBuffer, at: Int): Float = when (this) {
            PCM_16 -> buffer.getShort(at) / 32768f
            // Packed little-endian three-byte samples, sign extended.
            PCM_24 -> {
                val v = (buffer.get(at).toInt() and 0xFF) or
                    ((buffer.get(at + 1).toInt() and 0xFF) shl 8) or
                    (buffer.get(at + 2).toInt() shl 16)
                v / 8388608f
            }
            PCM_FLOAT -> buffer.getFloat(at)
            PCM_32 -> buffer.getInt(at) / 2147483648f
        }

        companion object {
            // AudioFormat.ENCODING_* — spelled out rather than imported so the
            // mapping reads as the table it is.
            private const val ENCODING_PCM_16BIT = 2
            private const val ENCODING_PCM_FLOAT = 4
            private const val ENCODING_PCM_24BIT_PACKED = 21
            private const val ENCODING_PCM_32BIT = 22

            fun of(format: MediaFormat): AudioFormatEncoding =
                when (format.getInteger(MediaFormat.KEY_PCM_ENCODING, ENCODING_PCM_16BIT)) {
                    ENCODING_PCM_FLOAT -> PCM_FLOAT
                    ENCODING_PCM_24BIT_PACKED -> PCM_24
                    ENCODING_PCM_32BIT -> PCM_32
                    else -> PCM_16
                }
        }
    }
}
