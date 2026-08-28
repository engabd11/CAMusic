package com.engabd.sendpin.audio

import android.os.SystemClock
import android.util.Log

/**
 * Kotlin facade over the native (Oboe) Sendspin audio output.
 *
 * Ported from Massdroid's `SendspinNativeOutput.kt` (github.com/sfortis/massdroid_native,
 * MIT) with no functional changes beyond the package - see `sendspin_output_jni.cpp`
 * for the matching JNI symbol names.
 *
 * The output stage runs on a real-time HAL thread inside `SendspinOutputEngine`
 * (C++), so it is immune to JVM/ART GC pauses that stall a JVM-thread AudioTrack
 * writer and punch micro-drops in grouped sync. A deep, server-time-anchored ring
 * sits between the (GC-pausable) Kotlin decode - [OboeRenderer], fed by ExoPlayer -
 * and the native callback; per-chunk timeline re-anchoring happens inside the
 * callback (skip/insert), so playback keeps group sync without a shallow buffer.
 * See `sendspin_output_engine.h` for the full rationale.
 *
 * Threading: [write] is called only from the decode/playback thread (single
 * producer). [start]/[stop]/[flush]/[release] come from the control path.
 *
 * PCM contract: [write] takes interleaved little-endian int16 frames. `offset`
 * must be 2-byte aligned (the native side reinterprets the buffer as int16);
 * callers feeding a raw frame with an odd header offset must copy into an
 * aligned buffer first.
 */
class SendspinNativeOutput {

    companion object {
        private const val TAG = "SendspinNative"

        @Volatile
        private var libraryLoaded = false

        private fun ensureLibrary(): Boolean {
            if (libraryLoaded) return true
            return try {
                System.loadLibrary("sendspin_output")
                libraryLoaded = true
                true
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load sendspin_output native library: ${e.message}")
                false
            }
        }
    }

    @Volatile private var ptr: Long = 0L

    val isStarted: Boolean get() = ptr != 0L

    /**
     * Opens and starts the native Oboe output stream. [driftCorrection] enables
     * timeline alignment (grouped/SYNC); pass false for solo/DIRECT (pure FIFO).
     * Returns false on failure.
     */
    @Synchronized
    fun start(sampleRate: Int, channels: Int, driftCorrection: Boolean): Boolean {
        if (!ensureLibrary()) return false
        if (ptr != 0L) stop()
        val created = nativeCreate()
        if (created == 0L) {
            Log.e(TAG, "nativeCreate failed")
            return false
        }
        if (!nativeStart(created, sampleRate, channels, driftCorrection)) {
            nativeDestroy(created)
            Log.e(TAG, "nativeStart failed (${sampleRate}Hz ch=$channels)")
            return false
        }
        ptr = created
        // The native engine is recreated on every (re)open, so re-apply the
        // cached output gain + compressor level + dither (native defaults are
        // volume 1.0, compressor 0 = off, dither off, so a default needs no call).
        // Without the volume re-apply, a reopen mid-duck would jump back to full.
        if (volume != 1f) nativeSetVolume(created, volume)
        if (compressorLevel != 0) nativeSetCompressorLevel(created, compressorLevel)
        if (ditherEnabled) nativeSetDither(created, true)
        return true
    }

    /** Stops and closes the stream, freeing native resources. */
    @Synchronized
    fun stop() {
        val p = ptr
        if (p == 0L) return
        ptr = 0L
        nativeStop(p)
        nativeDestroy(p)
    }

    /** Drops all buffered audio + timeline markers (seek / track change). */
    fun flush() {
        val p = ptr
        if (p != 0L) nativeFlush(p)
    }

    /**
     * Feeds decoded interleaved int16 PCM. Returns frames accepted.
     *
     * [presentationLocalUs] is the intended **`CLOCK_BOOTTIME`** instant of the first
     * frame — this app's own domain, as every caller already produces. It used to be
     * documented as `CLOCK_MONOTONIC`, which is what the native engine wants but not
     * what anyone was passing; [bootToMonotonicUs] now converts at this boundary rather
     * than asking each caller to remember, which is how the mismatch survived.
     */
    fun write(pcm: ByteArray, offset: Int, length: Int, presentationLocalUs: Long): Int {
        val p = ptr
        if (p == 0L || length <= 0) return 0
        return nativeWrite(p, pcm, offset, length, bootToMonotonicUs(presentationLocalUs))
    }

    /**
     * `CLOCK_BOOTTIME` (this app's time domain) → `CLOCK_MONOTONIC` (the native
     * engine's). **The two are not interchangeable and the gap is unbounded.**
     *
     * Everything above this line runs on `CLOCK_BOOTTIME`: [com.engabd.sendpin.protocol.MonotonicClock]
     * is `SystemClock.elapsedRealtimeNanos`, deliberately, because the Sendspin clock
     * filter must not see a step when the phone suspends — its own header says so, and
     * `client/time`, `server/time` and every scheduling decision share that domain.
     *
     * Everything below runs on `CLOCK_MONOTONIC`, equally deliberately: the engine
     * compares against `AAudioStream_getTimestamp(CLOCK_MONOTONIC)`, which is the only
     * domain the DAC's own timestamps are available in.
     *
     * Nothing converted between them. `BOOTTIME` counts suspended time and `MONOTONIC`
     * does not, so a `BOOTTIME` value handed to the native side reads as an instant
     * that far in the future — minutes on a phone that has been asleep once, hours on
     * one that has been up for days. The engine then never considers the audio due,
     * never renders it, never drains the ring, and every later write is refused. On
     * device: `first write: 2374 of 2374 accepted` followed immediately by
     * `refused 50 consecutive buffers (buffered=7206, written=7206)` — buffered equal
     * to written, meaning not one frame was ever consumed.
     *
     * This only bites with drift correction on — i.e. grouped playback, where
     * the SYNC timing policy is in use. Solo playback sets it false and the engine
     * ignores the timestamp entirely as a pure FIFO, which is why the Oboe path could
     * look merely unproven rather than broken.
     *
     * Read fresh on every call rather than cached: the offset between the two clocks
     * *is* accumulated suspend time, so it changes by exactly the amount of every
     * screen-off. A cached offset would work until the first suspend and then
     * reintroduce this bug in a form that only appears after the phone has slept.
     */
    private fun bootToMonotonicUs(bootUs: Long): Long {
        val bootNowUs = SystemClock.elapsedRealtimeNanos() / 1000
        val monotonicNowUs = System.nanoTime() / 1000
        return bootUs - (bootNowUs - monotonicNowUs)
    }

    /** DAC presentation lag in microseconds (0 until the first valid timestamp). */
    fun outputLatencyUs(): Long {
        val p = ptr
        return if (p == 0L) 0L else nativeOutputLatencyUs(p)
    }

    /** Frames buffered (decoded, not yet played) in the native ring. */
    fun bufferedFrames(): Long {
        val p = ptr
        return if (p == 0L) 0L else nativeBufferedFrames(p)
    }

    /**
     * AAudio device id the stream is routed to (0 if not open). Match against
     * AudioManager.getDevices() to recover device type / product name.
     */
    fun deviceId(): Int {
        val p = ptr
        return if (p == 0L) 0 else nativeDeviceId(p)
    }

    /** Smoothed timeline drift in microseconds (intended minus DAC presentation). */
    fun driftEmaUs(): Long {
        val p = ptr
        return if (p == 0L) 0L else nativeDriftEmaUs(p)
    }

    /** Cumulative ring-underrun frames (audible dropouts); 0 = clean. */
    fun underrunFrames(): Long {
        val p = ptr
        return if (p == 0L) 0L else nativeUnderrunFrames(p)
    }

    /** Last applied resampler rate (1.0 = locked passthrough; off-1.0 = correcting). */
    fun resampleRate(): Double {
        val p = ptr
        return if (p == 0L) 1.0 else nativeResampleRateMicros(p) / 1_000_000.0
    }

    // Cached so a non-default gain (e.g. a transient focus duck) survives the
    // native recreate on every (re)open (see start()); native default is 1.0.
    @Volatile private var volume = 1f

    fun setVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        this.volume = v
        val p = ptr
        if (p != 0L) nativeSetVolume(p, v)
    }

    // Cached so it survives the native recreate on every (re)open (see start()).
    @Volatile private var compressorLevel = 0

    /**
     * Dynamic-range compressor level: 0 = off, 1 = soft, 2 = medium, 3 = hard.
     * Amplitude-only output effect (applied in the native callback with the
     * volume gain); no effect on timing/latency/sync.
     */
    fun setCompressorLevel(level: Int) {
        val bounded = level.coerceIn(0, 3)
        compressorLevel = bounded
        val p = ptr
        if (p != 0L) nativeSetCompressorLevel(p, bounded)
    }

    // Cached so it survives the native recreate on every (re)open (see start()).
    @Volatile private var ditherEnabled = false

    /**
     * High-end output quantization: noise-shaped TPDF dither at the float->int16
     * step. Amplitude-only (sample values), no effect on timing/latency/sync.
     */
    fun setDither(enabled: Boolean) {
        ditherEnabled = enabled
        val p = ptr
        if (p != 0L) nativeSetDither(p, enabled)
    }

    /**
     * Freeze (true) or resume (false) the consumer without dropping the ring.
     * Frozen = fade to silence then hold the read position, preserving the
     * buffered audio across a transient interruption. Unfreeze fades back in
     * from the held sample. Used for solo/DIRECT focus interruptions instead of
     * a flush (the server feeds realtime after a flush and never rebuilds the
     * deep buffer).
     */
    fun setFrozen(frozen: Boolean) {
        val p = ptr
        if (p != 0L) nativeSetFrozen(p, frozen)
    }

    /**
     * Idle power management: stop/restart the Oboe callback without closing the
     * stream or freeing the ring. pauseStream halts the real-time HAL thread
     * (no CPU, no audio hardware held) when playback is idle; resumeStream
     * restarts it quickly for the next stream.
     */
    fun pauseStream() {
        val p = ptr
        if (p != 0L) nativePauseStream(p)
    }

    fun resumeStream() {
        val p = ptr
        if (p != 0L) nativeResumeStream(p)
    }

    /** True if Oboe disconnected the stream (route preempted, e.g. phone call). */
    fun isDisconnected(): Boolean {
        val p = ptr
        return p != 0L && nativeIsDisconnected(p)
    }

    @Synchronized
    fun release() {
        stop()
    }

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(ptr: Long)
    private external fun nativeStart(ptr: Long, sampleRate: Int, channels: Int, driftCorrection: Boolean): Boolean
    private external fun nativeStop(ptr: Long)
    private external fun nativeFlush(ptr: Long)
    private external fun nativeWrite(ptr: Long, pcm: ByteArray, offset: Int, length: Int, presentationLocalUs: Long): Int
    private external fun nativeOutputLatencyUs(ptr: Long): Long
    private external fun nativeBufferedFrames(ptr: Long): Long
    private external fun nativeSetVolume(ptr: Long, volume: Float)
    private external fun nativeSetCompressorLevel(ptr: Long, level: Int)
    private external fun nativeSetDither(ptr: Long, enabled: Boolean)
    private external fun nativeSetFrozen(ptr: Long, frozen: Boolean)
    private external fun nativePauseStream(ptr: Long)
    private external fun nativeResumeStream(ptr: Long)
    private external fun nativeIsDisconnected(ptr: Long): Boolean
    private external fun nativeDeviceId(ptr: Long): Int
    private external fun nativeDriftEmaUs(ptr: Long): Long
    private external fun nativeUnderrunFrames(ptr: Long): Long
    private external fun nativeResampleRateMicros(ptr: Long): Long
}
