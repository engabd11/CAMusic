package com.engabd.sendpin.capture

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Process
import android.os.SystemClock
import androidx.media3.common.C
import com.engabd.sendpin.audio.AudioAnalysisTap
import com.engabd.sendpin.audio.AudioLead
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads other apps' PCM off a [MediaProjection] and feeds the analysis tap.
 *
 * One dedicated thread doing blocking reads, one reused direct buffer, no allocation
 * in the loop — the same discipline [AudioAnalysisTap] documents for itself, and for
 * the same reason: this runs at audio rate and a garbage collection here is an
 * audible gap in the light show.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CaptureLoop(
    private val context: Context,
    private val projection: MediaProjection,
    private val tap: AudioAnalysisTap,
    private val lead: AudioLead,
    private val onVerdict: (CaptureSilenceWatchdog.Verdict) -> Unit,
) {

    @Volatile private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null

    /**
     * `RECORD_AUDIO` is checked by the caller before this is constructed; the
     * suppression is for lint, which cannot see across that boundary.
     */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            // Non-obvious and load-bearing: without it this app's own playback is
            // captured and analysed a *second* time, on top of the tap already in its
            // render chain — two writers into a single-producer ring, and a light show
            // reacting to itself.
            .excludeUid(Process.myUid())
            .build()

        val minBytes = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, ENCODING)
        if (minBytes <= 0) return false
        val bufferBytes = maxOf(minBytes * 2, CHUNK_BYTES * 4)

        val built = runCatching {
            AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(ENCODING)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                        .build(),
                )
                .setBufferSizeInBytes(bufferBytes)
                .setAudioPlaybackCaptureConfig(config)
                .build()
        }.getOrNull() ?: return false

        if (built.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { built.release() }
            return false
        }

        record = built
        tap.configure(androidx.media3.common.audio.AudioProcessor.AudioFormat(RATE, 2, ENCODING_MEDIA3))
        tap.flush(androidx.media3.common.audio.AudioProcessor.StreamMetadata.DEFAULT)
        tap.setActive(true)
        lead.leadUs = AudioLead.UNKNOWN

        running = true
        built.startRecording()
        thread = Thread({ loop(built) }, "light-sync-capture").also {
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
        return true
    }

    fun stop() {
        running = false
        thread?.runCatching { join(JOIN_TIMEOUT_MS) }
        thread = null
        record?.runCatching { stop() }
        record?.runCatching { release() }
        record = null
        tap.setActive(false)
    }

    private fun loop(rec: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = ByteBuffer.allocateDirect(CHUNK_BYTES).order(ByteOrder.nativeOrder())
        val startedAt = SystemClock.elapsedRealtime()
        var framesRead = 0L
        var nonZero = 0L
        var lastVerdict: CaptureSilenceWatchdog.Verdict? = null

        while (running) {
            buf.clear()
            val read = runCatching { rec.read(buf, CHUNK_BYTES, AudioRecord.READ_BLOCKING) }.getOrDefault(-1)
            if (read <= 0) {
                if (read < 0) break
                continue
            }
            buf.position(0)
            buf.limit(read)

            // Counted before the tap consumes it, on the same absolute reads the tap
            // uses so the position is untouched. See [CaptureSilenceWatchdog] for why
            // this is exact zeros rather than a level.
            for (i in 0 until read step 2) {
                if (buf.getShort(i).toInt() != 0) { nonZero++; break }
            }

            // A session clock, not a media clock — there is no track behind capture.
            lead.mediaTimeUs = framesRead * 1_000_000L / RATE
            tap.analyseExternal(buf, RATE, 2, ENCODING_MEDIA3, lead.mediaTimeUs)
            framesRead += read / BYTES_PER_FRAME

            val verdict = CaptureSilenceWatchdog.verdict(
                elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                nonZeroSamples = nonZero,
                somethingIsPlaying = PlaybackCapture.somethingIsPlaying(context),
            )
            if (verdict != lastVerdict) {
                lastVerdict = verdict
                onVerdict(verdict)
            }
        }
    }

    private companion object {
        const val RATE = 48_000
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val ENCODING_MEDIA3 = C.ENCODING_PCM_16BIT
        const val BYTES_PER_FRAME = 4  // stereo, 16-bit

        /** ~20 ms, which is the hop the analysis pipeline is built around. */
        const val CHUNK_BYTES = 1920 * BYTES_PER_FRAME

        const val JOIN_TIMEOUT_MS = 500L
    }
}
