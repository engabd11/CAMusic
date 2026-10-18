package com.engabd.sendpin.hue.ambience

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Process
import android.util.Log
import com.engabd.sendpin.audio.ANALYSIS_HOP
import com.engabd.sendpin.audio.ANALYSIS_SAMPLE_RATE
import com.engabd.sendpin.audio.AudioAnalyzer
import com.engabd.sendpin.audio.BoxResampleClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "AmbienceBedTimeline"

/**
 * Finds, builds and caches the [AmbienceBedTimeline] of a bundled bed.
 *
 * The first time a bed is used it is decoded and analysed (a few seconds for a few
 * minutes of audio, at background priority) and the result is written to the app's files
 * directory, keyed by the asset path and its size so replacing a bed in an app update
 * rebuilds it. Every later start reads a few tens of kilobytes.
 *
 * A show never waits on this for long: [load] gives up after a timeout and the show
 * starts with live analysis, the way a user's own clip always does. The build carries on
 * in the background, so the *next* start has the timeline.
 */
object AmbienceBedTimelineStore {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val memory = ConcurrentHashMap<String, AmbienceBedTimeline>()
    private val inFlight = ConcurrentHashMap<String, Deferred<AmbienceBedTimeline?>>()

    /** The timeline if it is already built (memory or disk), without waiting for a build. */
    fun cached(context: Context, assetPath: String): AmbienceBedTimeline? {
        val key = keyFor(context, assetPath) ?: return null
        memory[key]?.let { return it }
        return readDisk(context, key)?.also { memory[key] = it }
    }

    /**
     * The timeline for [assetPath], building it if needed, or null if that takes longer
     * than [timeoutMs] or fails. A null is not an error: the show falls back to live
     * analysis.
     */
    suspend fun load(context: Context, assetPath: String, timeoutMs: Long = 6_000L): AmbienceBedTimeline? {
        cached(context, assetPath)?.let { return it }
        val key = keyFor(context, assetPath) ?: return null
        val app = context.applicationContext
        val job = inFlight.computeIfAbsent(key) {
            scope.async {
                try {
                    build(app, assetPath)?.also {
                        memory[key] = it
                        writeDisk(app, key, it)
                    }
                } finally {
                    inFlight.remove(key)
                }
            }
        }
        return withTimeoutOrNull(timeoutMs) { job.await() }
    }

    private fun keyFor(context: Context, assetPath: String): String? {
        val size = runCatching {
            context.assets.openFd(assetPath).use { it.length }
        }.getOrElse {
            runCatching { context.assets.open(assetPath).use { s -> s.available().toLong() } }.getOrNull()
        } ?: return null
        return assetPath.replace(Regex("[^A-Za-z0-9]"), "_") + "_$size"
    }

    private fun file(context: Context, key: String): File =
        File(File(context.filesDir, "ambience_timelines").also { it.mkdirs() }, "$key.v${AmbienceBedTimeline.VERSION}.bin")

    private fun readDisk(context: Context, key: String): AmbienceBedTimeline? = runCatching {
        val f = file(context, key)
        if (!f.exists()) return null
        DataInputStream(BufferedInputStream(f.inputStream())).use { AmbienceBedTimeline.read(it) }
    }.getOrNull()

    private fun writeDisk(context: Context, key: String, t: AmbienceBedTimeline) {
        runCatching {
            val dest = file(context, key)
            val tmp = File(dest.parentFile, dest.name + ".tmp")
            DataOutputStream(BufferedOutputStream(tmp.outputStream())).use { t.write(it) }
            if (!tmp.renameTo(dest)) tmp.delete()
        }.onFailure { Log.w(TAG, "couldn't cache timeline: ${it.message}") }
    }

    // ---- Decoding ----------------------------------------------------------

    private fun build(context: Context, assetPath: String): AmbienceBedTimeline? {
        val previous = Process.getThreadPriority(Process.myTid())
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        return try {
            val started = System.currentTimeMillis()
            val fd: AssetFileDescriptor? = runCatching { context.assets.openFd(assetPath) }.getOrNull()
            val extractor = MediaExtractor()
            var temp: File? = null
            try {
                if (fd != null) {
                    extractor.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                } else {
                    // A compressed asset has no file descriptor of its own.
                    temp = File.createTempFile("bed", ".bin", context.cacheDir)
                    context.assets.open(assetPath).use { i -> temp.outputStream().use { o -> i.copyTo(o) } }
                    extractor.setDataSource(temp.absolutePath)
                }
                decode(extractor)?.also {
                    Log.i(
                        TAG,
                        "$assetPath: ${it.onsetCount} onsets over ${"%.1f".format(it.durationS)} s " +
                            "in ${System.currentTimeMillis() - started} ms",
                    )
                }
            } finally {
                runCatching { extractor.release() }
                runCatching { fd?.close() }
                temp?.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "couldn't analyse $assetPath: ${e.message}")
            null
        } finally {
            runCatching { Process.setThreadPriority(Process.myTid(), previous) }
        }
    }

    private fun decode(extractor: MediaExtractor): AmbienceBedTimeline? {
        val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
            extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: return null
        extractor.selectTrack(trackIndex)
        val inputFormat = extractor.getTrackFormat(trackIndex)
        val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return null
        val containerS = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
            inputFormat.getLong(MediaFormat.KEY_DURATION) / 1_000_000.0
        } else 0.0

        val hopS = ANALYSIS_HOP.toDouble() / ANALYSIS_SAMPLE_RATE
        val analyzer = AudioAnalyzer()
        val builder = AmbienceBedTimeline.Builder(hopS)
        var hop = 0
        val feeder = StereoHopFeeder { l, r ->
            val frame = analyzer.pushStereo(l, r)
            // The audio a frame describes ends at the end of its hop.
            builder.consume(frame, (hop + 1) * hopS)
            hop++
        }

        val codec = MediaCodec.createDecoderByType(mime)
        try {
            codec.configure(inputFormat, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var format = codec.outputFormat
            val deadline = System.currentTimeMillis() + DECODE_BUDGET_MS
            while (!outputDone) {
                if (System.currentTimeMillis() > deadline) return null
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(DEQUEUE_US)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx)
                        val size = if (buf == null) -1 else extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(idx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val idx = codec.dequeueOutputBuffer(info, DEQUEUE_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> format = codec.outputFormat
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (idx >= 0) {
                        if (info.size > 0) {
                            codec.getOutputBuffer(idx)?.let { out ->
                                out.position(info.offset)
                                out.limit(info.offset + info.size)
                                feeder.feed(out, format)
                            }
                        }
                        codec.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
        if (hop == 0) return null
        val analysedS = hop * hopS
        // The player loops at the container's length, so that is the loop; the analysed
        // length only stands in when the container does not say.
        return builder.build(if (containerS > 0.0) containerS else analysedS)
    }

    private const val DEQUEUE_US = 10_000L

    /** A bed that takes longer than this to decode is not worth waiting for. */
    private const val DECODE_BUDGET_MS = 90_000L

    /**
     * Downmix-free stereo resampling to the analysis rate, in whole hops.
     *
     * Left and right are kept apart because pan is what tells the room which side of it a
     * clap came from. Box-averaged like the live tap, so frames agree.
     */
    private class StereoHopFeeder(private val onHop: (FloatArray, FloatArray) -> Unit) {
        private var rate = 0
        private var clock = BoxResampleClock(1f)
        private var accL = 0f
        private var accR = 0f
        private var accN = 0
        private var lastL = 0f
        private var lastR = 0f
        private val hopL = FloatArray(ANALYSIS_HOP)
        private val hopR = FloatArray(ANALYSIS_HOP)
        private var filled = 0

        fun feed(buffer: ByteBuffer, format: MediaFormat) {
            val sr = format.getInteger(MediaFormat.KEY_SAMPLE_RATE, 0)
            val ch = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 0)
            if (sr <= 0 || ch <= 0) return
            if (sr != rate) {
                rate = sr
                clock = BoxResampleClock(sr.toFloat() / ANALYSIS_SAMPLE_RATE)
            }
            val enc = format.getInteger(MediaFormat.KEY_PCM_ENCODING, ENCODING_PCM_16BIT)
            val bytes = when (enc) {
                ENCODING_PCM_FLOAT, ENCODING_PCM_32BIT -> 4
                ENCODING_PCM_24BIT_PACKED -> 3
                else -> 2
            }
            buffer.order(ByteOrder.nativeOrder())
            val frameBytes = bytes * ch
            val frames = buffer.remaining() / frameBytes
            var off = buffer.position()
            fun sample(at: Int): Float = when (enc) {
                ENCODING_PCM_FLOAT -> buffer.getFloat(at)
                ENCODING_PCM_32BIT -> buffer.getInt(at) / 2147483648f
                ENCODING_PCM_24BIT_PACKED ->
                    ((buffer.get(at).toInt() and 0xFF) or ((buffer.get(at + 1).toInt() and 0xFF) shl 8) or
                        (buffer.get(at + 2).toInt() shl 16)) / 8388608f
                else -> buffer.getShort(at) / 32768f
            }
            for (f in 0 until frames) {
                val l = sample(off)
                val r = if (ch > 1) sample(off + bytes) else l
                off += frameBytes
                accL += l; accR += r; accN++
                var due = clock.advance()
                while (due-- > 0) {
                    if (accN > 0) {
                        lastL = accL / accN; lastR = accR / accN
                        accL = 0f; accR = 0f; accN = 0
                    }
                    hopL[filled] = lastL; hopR[filled] = lastR
                    filled++
                    if (filled == ANALYSIS_HOP) {
                        onHop(hopL, hopR)
                        filled = 0
                    }
                }
            }
        }

        private companion object {
            // AudioFormat.ENCODING_*, spelled out as TrackScanner does.
            const val ENCODING_PCM_16BIT = 2
            const val ENCODING_PCM_FLOAT = 4
            const val ENCODING_PCM_24BIT_PACKED = 21
            const val ENCODING_PCM_32BIT = 22
        }
    }
}
