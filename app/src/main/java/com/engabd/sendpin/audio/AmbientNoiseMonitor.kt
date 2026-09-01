package com.engabd.sendpin.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Monitors ambient room noise so an auto-mix can pick the next track's energy
 * to match the mood of the room — a quiet room at night gets calm tracks, a
 * party gets lively ones, and the DJ is the room itself.
 *
 * ## Why this exists
 *
 * Radio mode already keeps music going past the queue end. What it picks is
 * similar to what was just on, which is right for a listening session but
 * ignores *where* the listener is. A dinner party and a pre-bedtime wind-down
 * sound nothing alike, and the only signal that tells them apart is the room
 * itself — the ambient noise floor rises and falls with activity.
 *
 * ## What it measures
 *
 * One channel of 8 kHz mono 16-bit PCM from the microphone, read in short
 * buffers. Each buffer's RMS is computed and normalised to 0..1, where 0 is
 * digital silence and 1 is a loud room (clamped at [NOISE_REF_RMS], the RMS a
 * typical party reaches at arm's length from a phone). The normalised value
 * is published as [noiseLevel].
 *
 * ## What it does not do
 *
 * **Audio is never stored.** Buffers are read, squared, summed, and
 * discarded — no ring, no file, no copy. The microphone is the only audio
 * source, and it is opened only when [enabled] is true and playback is active.
 * The monitor has no access to the played audio; it hears what the room hears.
 *
 * ## Threading
 *
 * The capture loop runs on a background [Dispatchers.IO] coroutine. The
 * published [StateFlow] is thread-safe by definition. [setEnabled] and
 * [setPlaybackActive] are called from arbitrary threads and are guarded by
 * `@Volatile` state and a synchronized restart decision.
 *
 * ## Permission
 *
 * Requires [Manifest.permission.RECORD_AUDIO]. The monitor is a no-op when
 * the permission is not granted: it reports 0 and never opens the mic. The
 * RECORD_AUDIO permission is already declared for the MediaProjection
 * capture path; this feature reuses it.
 */
class AmbientNoiseMonitor(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    /**
     * Current ambient noise level, normalised 0..1.
     *
     * `0` when the monitor is not running (disabled, no playback, or no mic
     * permission) — silence and "not listening" are the same from the
     * consumer's perspective, since the auto-mix should fall back to its base
     * target when the room is unknown.
     */
    private val _noiseLevel = MutableStateFlow(0f)
    val noiseLevel: StateFlow<Float> = _noiseLevel.asStateFlow()

    /**
     * Whether the user has enabled mood-driven auto-mix.
     *
     * The monitor runs only when this is true *and* playback is active — a
     * phone in a pocket is not a room, and the mic should not be held open
     * when nothing is playing. See [setPlaybackActive].
     */
    @Volatile
    private var enabled = false

    /**
     * Whether playback is currently active (playing, not paused).
     *
     * The auto-mix only needs a noise reading when it is about to pick the
     * next track, which only happens during playback. Between tracks is
     * silence, and the mic would just capture the room tone of nobody
     * listening — not useful, and wasteful.
     */
    @Volatile
    private var playbackActive = false

    private var captureJob: Job? = null

    /**
     * Enable or disable the monitor.
     *
     * Restarting the capture loop is cheap: opening an AudioRecord at 8 kHz
     * mono costs a few milliseconds. The check for playback-active is inside
     * [restart] so enabling alone does not open the mic.
     */
    fun setEnabled(on: Boolean) {
        if (on == enabled) return
        enabled = on
        restart()
    }

    /**
     * Tell the monitor whether playback is active.
     *
     * Called from the player's state collector. When playback stops the
     * mic is released immediately — the room's noise floor is irrelevant
     * between tracks, and holding the mic open drains battery.
     */
    fun setPlaybackActive(active: Boolean) {
        if (active == playbackActive) return
        playbackActive = active
        restart()
    }

    @Synchronized
    private fun restart() {
        val shouldRun = enabled && playbackActive
        if (shouldRun && captureJob == null) {
            captureJob = scope.launch(Dispatchers.IO) { captureLoop() }
        } else if (!shouldRun && captureJob != null) {
            captureJob?.cancel()
            captureJob = null
            _noiseLevel.value = 0f
        }
    }

    /**
     * The capture loop. Runs on a background coroutine.
     *
     * Opens an [AudioRecord] at 8 kHz mono 16-bit — the lowest rate that
     * still captures the room-noise band (mostly 100 Hz–4 kHz). Reads short
     * buffers, computes RMS, and publishes the normalised level. Audio is
     * never persisted: the buffer is stack-local and overwritten each
     * iteration.
     */
    @SuppressLint("MissingPermission")
    private suspend fun captureLoop() {
        if (!hasRecordPermission()) return

        val sampleRate = SAMPLE_RATE
        val bufferSize = min(
            AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(MIN_BUFFER_SAMPLES),
            MAX_BUFFER_SAMPLES,
        )

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        } catch (e: Exception) {
            // Some devices throw on construction for unsupported configurations.
            // The monitor simply stays silent — no point crashing the app over
            // an optional feature.
            return
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return
        }

        val buf = ShortArray(READ_SAMPLES)
        try {
            recorder.startRecording()
            while (scope.isActive && enabled && playbackActive) {
                val read = recorder.read(buf, 0, buf.size)
                if (read > 0) {
                    _noiseLevel.value = computeNoiseLevel(buf, read)
                }
            }
        } finally {
            try { recorder.stop() } catch (_: IllegalStateException) {}
            recorder.release()
        }
    }

    private fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    companion object {
        /** 8 kHz: the lowest standard rate, enough for room noise. */
        private const val SAMPLE_RATE = 8000

        /** How many samples to read per buffer (~250 ms at 8 kHz). */
        private const val READ_SAMPLES = 2048

        /** Floor on the AudioRecord buffer size. */
        private const val MIN_BUFFER_SAMPLES = 1024

        /** Ceiling to avoid over-allocating. */
        private const val MAX_BUFFER_SAMPLES = 16384

        /**
         * The RMS a loud room produces at arm's length, used to normalise 0..1.
         *
         * A quiet office is ~0.001–0.005, a living room conversation ~0.01–0.03,
         * and a lively party ~0.05–0.1 on the 0..1 sample scale. Clamping at
         * 0.05 means a party reaches 1.0 and silence sits near 0, with the
         * normal listening range spread across the useful middle.
         */
        private const val NOISE_REF_RMS = 0.05f

        /**
         * Compute the normalised noise level from a short buffer.
         *
         * Pure after the buffer is in hand — factored out so it can be tested
         * without an AudioRecord. The RMS is computed on the raw 16-bit
         * samples normalised to [-1, 1], then divided by [NOISE_REF_RMS]
         * and clamped to [0, 1].
         */
        fun computeNoiseLevel(samples: ShortArray, count: Int): Float {
            if (count <= 0) return 0f
            var sumSq = 0.0
            for (i in 0 until count) {
                val s = samples[i] / 32768f
                sumSq += (s * s).toDouble()
            }
            val rms = sqrt(sumSq / count).toFloat()
            return (rms / NOISE_REF_RMS).coerceIn(0f, 1f)
        }

        /**
         * Map an ambient noise level to an energy target for track selection.
         *
         * A pure function: given the normalised noise level (0 = silent, 1 =
         * loud) and a base target the auto-mix would pick in the absence of
         * this signal, return the energy target the next track should aim
         * for.
         *
         * The mapping is a linear blend between a floor ([ENERGY_FLOOR],
         * ~0.2 — calm, for silence) and the base target raised by
         * [ENERGY_BOOST] for a loud room. The base target is the midpoint: a
         * mid-level room gets [baseTarget] itself, silence pulls below it,
         * and a loud room pushes above it.
         *
         * The range is clamped to [0, 1] so the result can be used directly
         * as a similarity weight.
         *
         * @param noiseLevel normalised ambient noise, 0..1
         * @param baseTarget the energy target the auto-mix picks without
         *   this signal, 0..1
         */
        fun noiseLevelToEnergyTarget(noiseLevel: Float, baseTarget: Float): Float {
            val n = noiseLevel.coerceIn(0f, 1f)
            val base = baseTarget.coerceIn(0f, 1f)
            // Piecewise: the lower half interpolates from ENERGY_FLOOR (at
            // silence) to the base target (at mid-level), and the upper half
            // interpolates from the base target to base + ENERGY_BOOST (at
            // loud). The join at n=0.5 is continuous: both halves give
            // exactly baseTarget there, so a mid-level room is unmoved.
            return if (n <= 0.5f) {
                // Silence → floor; mid → base
                ENERGY_FLOOR + (base - ENERGY_FLOOR) * (n / 0.5f)
            } else {
                // Mid → base; loud → base + boost
                base + ENERGY_BOOST * ((n - 0.5f) / 0.5f)
            }.coerceIn(0f, 1f)
        }

        /** Minimum energy target: calm tracks for a silent room. */
        private const val ENERGY_FLOOR = 0.2f

        /** How far above the base a loud room can push the target. */
        private const val ENERGY_BOOST = 0.3f
    }
}