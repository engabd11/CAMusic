package com.engabd.sendpin.ui.viewmodel

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.audio.AnalysisFrame
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What the calibration screen renders. One object, so it recomposes once.
 */
data class CalibrationState(
    /** True once the engine's grid has a lock to pulse against. */
    val locked: Boolean = false,
    /** Position within the current beat, 0..1 — drives the pulse. */
    val beatPhase: Float = 0f,
    /** Usable taps collected so far. */
    val taps: Int = 0,
    /** True when twelve taps are in and the offset has been applied. */
    val done: Boolean = false,
    /** The offset that was applied, shown at completion. */
    val appliedOffsetMs: Int = 0,
)

/**
 * Measures the player's tap timing and writes it to settings.
 *
 * The player taps when they *hear* the beat; the view model compares each tap
 * against the beat grid the game will actually judge them on — the engine's
 * [com.engabd.sendpin.audio.BeatGrid], published through
 * [com.engabd.sendpin.hue.DirectLightSync.gameChartSource] — and the median of
 * twelve deltas becomes the timing offset.
 *
 * The offset is judged-clock-only: the game applies it as `now + offset` inside
 * [RhythmGameViewModel.tap], so the chart, the lights and the bar lines all stay
 * exactly where the music put them. A positive median means the player taps late
 * (touch/display/audio latency) and the judged clock waits for them; a negative
 * one means they tap ahead of the beat.
 */
class RhythmCalibrationViewModel(app: Application) : AndroidViewModel(app) {
    private val directLightSync = (app as SendpinApp).directLightSync
    private val settings = com.engabd.sendpin.data.AppSettings(app)

    private val _state = MutableStateFlow(CalibrationState())
    val state: StateFlow<CalibrationState> = _state.asStateFlow()

    /** The game's clock. Same one the game screen judges taps on. */
    fun nowMs(): Long = SystemClock.elapsedRealtime()

    fun start() {
        if (running) return
        running = true
        taps.clear()
        _state.value = CalibrationState()
    }

    fun stop() {
        if (!running) return
        running = false
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }

    /**
     * Fold one analysis frame in: publish lock state, and remember where the
     * audible beat is so a tap between frames can still be placed.
     */
    fun onFrame(frame: AnalysisFrame, leadMs: Long, leadKnown: Boolean) {
        if (!running) return
        val chart = directLightSync.gameChartSource() ?: return
        val grid = chart.grid ?: return
        if (grid.periodS <= 0f) return
        if (!_state.value.locked && grid.locked) {
            _state.value = _state.value.copy(locked = true)
        }
        if (_state.value.locked) {
            // Where the audible beat is right now. The grid's timeToNextBeat is on
            // the tAudio clock of the frame it was computed from; folding the
            // audible lead in lands the pulse on what the player hears, which is
            // the only thing this screen measures against.
            val periodMs = (grid.periodS * 1000f).coerceAtLeast(1f)
            val toNext = (grid.timeToNextBeat * 1000f - leadMs).mod(periodMs)
            lastPeriodMs = periodMs
            lastPhase = 1f - toNext / periodMs // 0 at the beat, rising to ~1 just before the next
            lastPhaseAtMs = nowMs()
            _state.value = _state.value.copy(beatPhase = lastPhase.coerceIn(0f, 1f))
        }
    }

    /**
     * A tap at [nowMs]. Its delta is the remembered beat phase advanced by real
     * time to the tap's instant — signed distance to the nearest beat, in ms.
     *
     * Taps landing more than 45% of a period from any beat are warm-up, discarded
     * silently: they say "I hadn't found the pulse yet" rather than "the beat is
     * here and I missed it". After twelve usable taps the median is persisted.
     */
    fun tap(nowMs: Long) {
        if (!running || !_state.value.locked || _state.value.done) return
        val phaseAt = lastPhaseAtMs ?: return
        if (lastPeriodMs <= 0f) return
        val advanced = lastPhase + (nowMs - phaseAt) / lastPeriodMs
        val frac = advanced.mod(1f)
        val signed = if (frac <= 0.5f) frac * lastPeriodMs else (frac - 1f) * lastPeriodMs
        if (abs(signed) > lastPeriodMs * 0.45f) return // warm-up; discard

        taps.add(signed.toLong())
        if (taps.size >= TAPS_NEEDED) {
            val applied = medianOf(taps).coerceIn(-300, 300).toInt()
            _state.value = _state.value.copy(taps = taps.size, done = true, appliedOffsetMs = applied)
            viewModelScope.launch { settings.setGameTimingOffsetMs(applied) }
        } else {
            _state.value = _state.value.copy(taps = taps.size)
        }
    }

    /** The phase (0..1) of the most recent grid frame, and the wall time it was read. */
    private var lastPhase = 0f
    private var lastPeriodMs = 0f
    private var lastPhaseAtMs: Long? = null

    private val taps = ArrayList<Long>(TAPS_NEEDED + 4)
    private var running = false

    /** Median of the collected deltas, in ms — even counts average the middle pair. */
    private fun medianOf(values: List<Long>): Long {
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
    }

    private companion object {
        /** Twelve usable taps: the median of fewer wobbles, more is patience. */
        const val TAPS_NEEDED = 12
    }
}
