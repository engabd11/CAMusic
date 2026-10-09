package com.engabd.sendpin.usb

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * The volume of the DAC CAMusic's USB driver is playing through (milestone 5 of
 * docs/plan/usb-bitperfect-driver.md).
 *
 * While USB bit-perfect holds the DAC, Android is not using it, so Android's media
 * volume changes nothing that can be heard. The level lives here instead, in one of two
 * ways:
 *
 * - **Hardware**: the DAC's own volume control (a USB Audio feature unit). The samples
 *   still leave untouched; the DAC turns them down. Bit-perfect, and the default.
 * - **Digital**: for a DAC with no volume control, and only if the listener switched it
 *   on — the samples are scaled, and the signal path says it is no longer bit-perfect.
 *
 * [state] is null when neither applies, and every volume control in the app falls back
 * to Android's own media volume.
 */
object UsbVolume {

    data class Info(
        /** 0..1, linear in decibels between the DAC's minimum and maximum. */
        val level: Float,
        /** How many steps the range has, for volume keys. */
        val steps: Int,
        /** True for [Mode.DIGITAL]: the samples are scaled. */
        val digital: Boolean,
    )

    private enum class Mode { HARDWARE, DIGITAL }

    private val _state = MutableStateFlow<Info?>(null)
    val state: StateFlow<Info?> = _state.asStateFlow()

    @Volatile private var session: UsbAudioSession? = null
    @Volatile private var mode = Mode.HARDWARE
    @Volatile private var range: Triple<Int, Int, Int>? = null

    /** The digital gain the output applies; 1 unless digital volume is in use. */
    @Volatile
    var digitalGain = 1f
        private set

    /** The DAC [s] is now playing; [allowDigital] is the listener's opt-in. */
    internal fun attach(s: UsbAudioSession, allowDigital: Boolean) {
        session = s
        val r = runCatching { s.hardwareVolumeRange() }.getOrNull()
        range = r
        if (r != null) {
            mode = Mode.HARDWARE
            digitalGain = 1f
            val current = runCatching { s.hardwareVolume() }.getOrNull() ?: r.second
            _state.value = Info(levelOf(current, r), stepsOf(r), digital = false)
        } else if (allowDigital) {
            mode = Mode.DIGITAL
            _state.value = Info(digitalLevel, DIGITAL_STEPS, digital = true)
            digitalGain = gainOf(digitalLevel)
        } else {
            _state.value = null
            digitalGain = 1f
        }
    }

    internal fun detach(s: UsbAudioSession) {
        if (session !== s) return
        session = null
        range = null
        _state.value = null
        digitalGain = 1f
    }

    private var digitalLevel = 1f

    /** The last level above zero, which unmuting returns to. */
    @Volatile private var lastAudible = 0.5f

    /**
     * The DAC's volume is a USB control transfer: up to a second of blocking I/O, and the
     * callers are the media session and the volume keys, on the main thread. So writes
     * go to their own thread, and only the newest one waiting is sent — a held volume
     * key produces a burst, and the DAC only needs to land on the last.
     */
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "UsbVolume").apply { isDaemon = true }
    }
    private val pendingWrite = java.util.concurrent.atomic.AtomicReference<Pair<UsbAudioSession, Pair<Int, Boolean>>?>(null)

    private fun writeLater(s: UsbAudioSession, value: Int, mute: Boolean) {
        if (pendingWrite.getAndSet(s to (value to mute)) != null) return
        writer.execute {
            val (target, v) = pendingWrite.getAndSet(null) ?: return@execute
            runCatching { target.setHardwareVolume(v.first, mute = v.second) }
        }
    }

    /** Set the level, 0..1. Zero mutes. */
    fun set(level: Float) {
        val l = level.coerceIn(0f, 1f)
        if (l > 0f) lastAudible = l
        when (mode) {
            Mode.HARDWARE -> {
                val s = session ?: return
                val r = range ?: return
                writeLater(s, valueOf(l, r), mute = l <= 0f)
                _state.value = Info(l, stepsOf(r), digital = false)
            }
            Mode.DIGITAL -> {
                if (session == null) return
                digitalLevel = l
                digitalGain = gainOf(l)
                _state.value = Info(l, DIGITAL_STEPS, digital = true)
            }
        }
    }

    /**
     * Unmute to where the level was before. Unmuting used to jump to half volume
     * whatever it had been, which on a DAC driving sensitive IEMs is a surprise.
     */
    fun unmute() = set(lastAudible)

    /** One volume-key step up or down. */
    fun step(up: Boolean) {
        val info = _state.value ?: return
        val stepSize = 1f / info.steps.coerceAtLeast(1)
        set(info.level + if (up) stepSize else -stepSize)
    }

    // ── mapping, pure ───────────────────────────────────────────────────────

    /** 1/256 dB value for [level] on range (min, max, res), snapped to the DAC's step. */
    internal fun valueOf(level: Float, r: Triple<Int, Int, Int>): Int {
        val (min, max, res) = r
        val raw = min + level * (max - min)
        val snapped = min + ((raw - min) / res).roundToInt() * res
        return snapped.coerceIn(min, max)
    }

    internal fun levelOf(value: Int, r: Triple<Int, Int, Int>): Float {
        val (min, max, _) = r
        return ((value - min).toFloat() / (max - min)).coerceIn(0f, 1f)
    }

    internal fun stepsOf(r: Triple<Int, Int, Int>): Int = ((r.second - r.first) / r.third).coerceIn(1, 100)

    /** Digital: 0..1 as decibels over [DIGITAL_RANGE_DB], zero silent. */
    internal fun gainOf(level: Float): Float =
        if (level <= 0f) 0f else Math.pow(10.0, (level - 1f) * DIGITAL_RANGE_DB / 20.0).toFloat()

    private const val DIGITAL_STEPS = 30
    private const val DIGITAL_RANGE_DB = 60.0
}
