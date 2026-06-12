package com.cyborgautomation.sendspin.audio

import com.cyborgautomation.sendspin.protocol.AudioFrame
import com.cyborgautomation.sendspin.protocol.ClockSync
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import java.util.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class PlaybackScheduler(
    private val clockSync: ClockSync,
    private val audioOutput: AudioOutput,
    private val flacDecoder: NativeFlacDecoder?,
    private val negotiatedFormat: NegotiatedFormat,
) {
    private val queue = PriorityQueue<AudioFrame>(compareBy { it.timestamp })
    private var cursorUs: Long = 0
    private var started = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    // PCM scratch buffer for FLAC decode output
    private val pcmScratchBuffer = ByteArray(196608)  // 64k samples × 3 bytes

    // Drift correction parameters
    private val engageUs: Long = 3000    // 3ms — start correcting
    private val deadbandUs: Long = 1500  // 1.5ms — stop correcting
    private val reanchorUs: Long = 500_000  // 500ms — full resync

    fun start() {
        if (!audioOutput.start()) return
        started = true
        job = scope.launch { run() }
    }

    fun stop() {
        started = false
        job?.cancel()
        audioOutput.close()
    }

    fun enqueue(frame: AudioFrame) {
        queue.add(frame)
    }

    fun subscribeTo(frames: Flow<AudioFrame>) {
        scope.launch {
            frames.collect { frame ->
                enqueue(frame)
            }
        }
    }

    private suspend fun run() {
        while (started && coroutineContext.isActive) {
            val frame = synchronized(queue) { queue.poll() }
            if (frame == null) {
                delay(5)
                continue
            }

            val nowLocal = System.nanoTime() / 1000
            val nowServer = clockSync.localTimeToServer(nowLocal)

            // Initialize cursor on first frame
            if (cursorUs == 0L) {
                cursorUs = frame.timestamp
            }

            // Calculate drift
            val drift = frame.timestamp - cursorUs

            // Drift correction
            if (abs(drift) > reanchorUs) {
                // Too far — full resync
                cursorUs = frame.timestamp
            } else if (abs(drift) > engageUs) {
                // Ahead: drop frames
                if (drift < 0) {
                    val correctionFrames = (abs(drift) / 2000).toInt()
                    synchronized(queue) {
                        repeat(min(correctionFrames, queue.size)) {
                            queue.poll()
                        }
                    }
                }
                // Behind: no delay → catch up naturally
            }

            // Wait until playback time
            val targetLocal = clockSync.serverTimeToLocal(frame.timestamp)
            val waitUs = targetLocal - (System.nanoTime() / 1000)
            if (waitUs > 0) {
                delay(waitUs / 1000)  // Convert microseconds to milliseconds
            }

            // Resolve PCM bytes
            when (frame) {
                is AudioFrame.Pcm -> {
                    // Zero conversion — raw packed i24 LE bytes
                    audioOutput.write(frame.rawPcmBytes)
                }
                is AudioFrame.Flac -> {
                    // Decode FLAC → packed i24 PCM
                    val samples = flacDecoder?.decode(frame.compressedData, pcmScratchBuffer) ?: 0
                    if (samples > 0) {
                        val pcmBytes = samples * 3
                        audioOutput.write(pcmScratchBuffer.copyOf(pcmBytes))
                    }
                }
            }

            // Advance cursor by frame duration
            val frameSizeSamples = when (frame) {
                is AudioFrame.Pcm -> frame.rawPcmBytes.size / (negotiatedFormat.channels * 3)
                is AudioFrame.Flac -> 1024  // FLAC frame is ~1024 samples (typical)
            }
            cursorUs += frameSizeSamples * 1_000_000L / negotiatedFormat.sampleRate
        }
    }
}
