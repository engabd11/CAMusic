package com.engabd.sendpin.ui.viewmodel

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.audio.AnalysisFrame
import com.engabd.sendpin.game.GameTiming
import com.engabd.sendpin.game.PausableClock
import kotlin.math.abs
import kotlin.math.roundToLong
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
 * The offset is judged-clock-only: the game applies it as `now - offset` inside
 * [RhythmGameViewModel.tap], so the chart, the lights and the bar lines all stay
 * exactly where the music put them. A positive median means the player taps late
 * (touch/display/audio latency) and the judged clock comes back to meet them; a
 * negative one means they tap ahead of the beat.
 */
class RhythmCalibrationViewModel(app: Application) : AndroidViewModel(app) {
    private val directLightSync = (app as SendpinApp).directLightSync
    private val settings = com.engabd.sendpin.data.AppSettings(app)

    private val _state = MutableStateFlow(CalibrationState())
    val state: StateFlow<CalibrationState> = _state.asStateFlow()

    /**
     * The game's clock: the same pausable millisecond clock the game screen judges taps
     * on, so a tap here and a tap there are timed the same way.
     */
    private val clock = PausableClock { SystemClock.elapsedRealtime() }

    fun nowMs(): Long = clock.now()

    fun start() {
        if (running) return
        running = true
        clock.reset()
        taps.clear()
        beatAtMs = null
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
     *
     * Timed from the engine snapshot, exactly as the game charts it: the beat is heard
     * at the moment the frame was analysed, plus the grid's time to the next beat, plus
     * the output lead less the frame's lag. The lead used to be subtracted, which put
     * the pulse a full lead (twice a Bluetooth speaker's delay) away from the audible
     * beat the game then judged against.
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
            val periodMs = (grid.periodS * 1000f).coerceAtLeast(1f)
            val frameMs = if (chart.frameAtMs > 0L) clock.fromSource(chart.frameAtMs) else clock.now()
            val beat = GameTiming.audibleBeatMs(frameMs, grid.timeToNextBeat, leadMs, chart.frameLagMs)
            beatAtMs = beat
            beatPeriodMs = periodMs
            // 0 at the beat, rising to ~1 just before the next.
            val phase = (clock.now() - beat).toFloat().mod(periodMs) / periodMs
            _state.value = _state.value.copy(beatPhase = phase.coerceIn(0f, 1f))
        }
    }

    /**
     * A tap at game time [tapMs] (see [GameTiming.tapGameMs]). Its delta is the signed
     * distance to the nearest audible beat, in ms; positive means the player was late.
     *
     * Taps landing more than 45% of a period from any beat are warm-up, discarded
     * silently: they say "I hadn't found the pulse yet" rather than "the beat is
     * here and I missed it". After twelve usable taps the median is persisted.
     */
    fun tap(tapMs: Long) {
        if (!running || !_state.value.locked || _state.value.done) return
        val beat = beatAtMs ?: return
        if (beatPeriodMs <= 0f) return
        val signed = GameTiming.beatDeltaMs(tapMs, beat, beatPeriodMs)
        if (abs(signed) > beatPeriodMs * 0.45f) return // warm-up; discard

        taps.add(signed.roundToLong())
        if (taps.size >= TAPS_NEEDED) {
            val limit = GameTiming.OFFSET_LIMIT_MS.toLong()
            val applied = GameTiming.median(taps).coerceIn(-limit, limit).toInt()
            _state.value = _state.value.copy(taps = taps.size, done = true, appliedOffsetMs = applied)
            viewModelScope.launch { settings.setGameTimingOffsetMs(applied) }
        } else {
            _state.value = _state.value.copy(taps = taps.size)
        }
    }

    /** When an audible beat falls on the game clock, and the grid's period. */
    private var beatAtMs: Long? = null
    private var beatPeriodMs = 0f

    private val taps = ArrayList<Long>(TAPS_NEEDED + 4)
    private var running = false

    private companion object {
        /** Twelve usable taps: the median of fewer wobbles, more is patience. */
        const val TAPS_NEEDED = 12
    }
}
