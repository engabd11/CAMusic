package com.engabd.sendpin.ui.viewmodel

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import com.engabd.sendpin.game.GameRecords
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.audio.AnalysisFrame
import com.engabd.sendpin.game.GameNote
import com.engabd.sendpin.game.GameChartSource
import com.engabd.sendpin.game.GameTiming
import com.engabd.sendpin.game.Judgement
import com.engabd.sendpin.game.NoteGenerator
import com.engabd.sendpin.game.PausableClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One scored tap, or one note that fell past the line. */
data class JudgementEvent(
    /** Monotonically increasing, so the UI can tell a repeat from a re-emission. */
    val id: Long,
    val judgement: Judgement,
    val lane: Int,
    val comboAfter: Int,
    val atMs: Long,
)

/** Everything the heads-up display shows. One object, so it recomposes once. */
data class RhythmHud(
    val score: Int = 0,
    val combo: Int = 0,
    val bestCombo: Int = 0,
    val multiplier: Int = 1,
    val perfect: Int = 0,
    val great: Int = 0,
    val good: Int = 0,
    val missed: Int = 0,
    /** Fraction of notes reached that were struck at all, 0..1. Null before the first note. */
    val accuracy: Float? = null,
    /** Whether the tempo grid has locked, which is what the chart is written against. */
    val locked: Boolean = false,
    val bpm: Float = 0f,
    /**
     * The count-in: 4 down to 1 while it runs, 0 once notes are flowing, and -1
     * while the beat is still being found ("listening").
     */
    val countIn: Int = -1,
)

/**
 * One track's best rhythm-game run, persisted as a JSON map keyed by the scan
 * store's track key (`TrackScanRepository.keyFor`), so records follow the track
 * across backends. [bestScore] is what a "new record" is judged on.
 */
@kotlinx.serialization.Serializable
data class GameRecord(
    val bestScore: Int = 0,
    val bestCombo: Int = 0,
    val bestAccuracy: Float = 0f,
    val plays: Int = 0,
)

/** Pure merge of a finished run into a track's record — unit-testable, no DataStore. */
internal fun mergeRecord(old: GameRecord?, score: Int, combo: Int, accuracy: Float?): GameRecord =
    GameRecord(
        bestScore = maxOf(old?.bestScore ?: 0, score),
        bestCombo = maxOf(old?.bestCombo ?: 0, combo),
        bestAccuracy = maxOf(old?.bestAccuracy ?: 0f, accuracy ?: 0f),
        plays = (old?.plays ?: 0) + 1,
    )

/**
 * Drives the rhythm game.
 *
 * ## The one rule this model exists to enforce
 *
 * In this mode the room does not react to the music at all. The music show is switched
 * off in [com.engabd.sendpin.hue.DirectLightSync.setGameMode], the analysis keeps
 * running only because the chart is written from its beat grid, and the lamps show a
 * dim album-colour glow until the player does something. A struck note flashes its
 * lane's lamps, a missed one leaves a faint ember, and that is every light there is.
 * That is what makes the game a game: a room that moved on its own would look the same
 * whether the player was there or not.
 *
 * So there are exactly three calls into the light engine, all from a judgement the
 * player earned or lost ([flashRoom], and the miss and combo-break calls in [tick]),
 * and no path from an analysis frame to a light anywhere in this file.
 *
 * ## Clocks
 *
 * The game clock is [SystemClock.elapsedRealtime] less every pause ([PausableClock]):
 * monotonic, unaffected by the wall clock, and the same clock for the frame collector,
 * the tap handler and the light render loop (which is handed real time through
 * [PausableClock.toSource]). Pausing the song freezes it, so the notes hold their place
 * relative to the line and the run resumes rather than restarting from a board already
 * past it.
 *
 * Nothing is stamped on the main thread's arrival time: the chart is written relative to
 * when the analyser saw each frame, with the lead corrected for the frame's lag, and a
 * tap is timed from the touch event's own stamp. See [GameTiming].
 */
class RhythmGameViewModel(app: Application) : AndroidViewModel(app) {
    private val generator = NoteGenerator()
    private val directLightSync = (app as SendpinApp).directLightSync

    private val _notes = MutableStateFlow<List<GameNote>>(emptyList())
    val notes: StateFlow<List<GameNote>> = _notes.asStateFlow()

    /**
     * False while the active backend cannot measure an output latency (its
     * AudioLead is null — capture and cast feeds), meaning the chart runs in
     * analysis time and the whole game is shifted by the real latency. The
     * screen shows a calibrate hint instead of leaving the player to wonder.
     */
    private val _leadKnown = MutableStateFlow(true)
    val leadKnown: StateFlow<Boolean> = _leadKnown.asStateFlow()

    private val _hud = MutableStateFlow(RhythmHud())
    val hud: StateFlow<RhythmHud> = _hud.asStateFlow()

    private val _judgement = MutableStateFlow<JudgementEvent?>(null)
    val judgement: StateFlow<JudgementEvent?> = _judgement.asStateFlow()

    /**
     * The finished run, published on [finish] for the results sheet. Null while
     * playing. Carries the record it merged into, so the screen can show a
     * new-record badge without reading the store itself.
     */
    data class RunResult(
        val hud: RhythmHud,
        val record: GameRecord,
        val newRecord: Boolean,
        val trackKey: String?,
        /** Signed tap errors in 50 ms buckets from early (left) to late (right). */
        val histogram: IntArray = IntArray(0),
        /** Median signed tap error as judged, in ms; positive is late. Null with too few taps. */
        val medianErrorMs: Long? = null,
        /** The offset in use for this run. */
        val offsetMs: Int = 0,
        /** The offset that would centre the player's taps, or null when the run was too short to say. */
        val suggestedOffsetMs: Int? = null,
    )

    private val _result = MutableStateFlow<RunResult?>(null)
    val result: StateFlow<RunResult?> = _result.asStateFlow()

    /** The current track's persisted record, or null; read once at start. */
    private var currentRecord: GameRecord? = null
    private var currentTrackKey: String? = null

    /** How far notes fall before the line, so the UI can place them. */
    val lookAheadMs: Long get() = generator.lookAheadMs

    /**
     * The tap-timing offset, in ms, and the difficulty scale — both read from
     * settings and applied at once, so a change lands on the very next frame
     * rather than the next session. Positive offset = the player's taps arrive
     * late, so the judged clock comes back by that much.
     */
    private var timingOffsetMs = 0
    private var difficultyScale = 1f
    private var hapticsOn = true

    private val eventId = java.util.concurrent.atomic.AtomicLong()
    private var publishedRevision = -1
    private var notesReached = 0
    private var notesStruck = 0
    private var running = false

    /** True while the player is on the calibration screen: the run is held, not ended. */
    private var calibrating = false

    /** Signed tap errors as judged (tap less note, ms), for the results card. */
    private val tapErrors = ArrayList<Long>()

    private var lastTrackCheckMs = 0L

    /** The chart snapshot last folded in, so one analysis frame is never charted twice. */
    private var lastChart: GameChartSource? = null

    /**
     * The game's clock. Everything here — frames, notes, taps — is on it.
     *
     * Pausable, and that is the whole of how the game pauses with the music. The
     * board is advanced from a frame loop that keeps running whatever the player is
     * doing, so with a plain `elapsedRealtime()` a pause spent the next 1.6 seconds
     * of lookahead retiring every pending note as a miss. Freezing the clock keeps
     * the notes where they are instead of merely stopping the scoring — see
     * [PausableClock].
     */
    private val clock = PausableClock { SystemClock.elapsedRealtime() }

    fun nowMs(): Long = clock.now()

    private val _paused = MutableStateFlow(false)

    /** Whether the run is held because nothing is playing. For the screen's overlay. */
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    /**
     * Follow the audio: hold the run while nothing is playing, let it go when
     * something is.
     *
     * Pushed in by the screen from [com.engabd.sendpin.SendpinApp.audioIsPlaying]
     * rather than collected here, for the same reason the timing settings are —
     * a ViewModel that reached for the application to answer a question the screen
     * already has the answer to is a ViewModel that cannot be tested.
     */
    fun setPlaying(playing: Boolean) {
        if (!running) return
        clock.setPlaying(playing)
        if (_paused.value != !playing) _paused.value = !playing
    }

    /**
     * Enter game mode: darken the room and take the lights off the music.
     *
     * Idempotent, because the screen calls it from a `DisposableEffect` and a
     * configuration change runs that twice.
     */
    fun start() {
        if (running) {
            if (calibrating) resumeFromCalibration()
            return
        }
        running = true
        calibrating = false
        tapErrors.clear()
        lastTrackCheckMs = 0L
        lastChart = null
        clock.reset()
        _paused.value = false
        generator.reset()
        publishedRevision = generator.revision
        _notes.value = emptyList()
        notesReached = 0
        notesStruck = 0
        _judgement.value = null
        _result.value = null
        _hud.value = RhythmHud()
        currentTrackKey = runCatching { directLightSync.currentGameTrackKey() }.getOrNull()
        loadRecordsIfNeeded()
        currentRecord = records[currentTrackKey]
        runCatching { directLightSync.setGameMode(true) }
    }

    /**
     * Hold the run while the player visits calibration.
     *
     * Leaving the screen for calibration used to end the run, because the screen's
     * dispose called [stop]. The clock is frozen and game mode stays up instead; the
     * board is cleared on return (the music kept playing meanwhile, so the old notes no
     * longer describe it) but the score, combo and accuracy are kept.
     */
    fun holdForCalibration() {
        if (!running) return
        calibrating = true
        clock.pause()
        if (!_paused.value) _paused.value = true
    }

    private fun resumeFromCalibration() {
        calibrating = false
        generator.reset()
        lastChart = null
        publishedRevision = -1
        publish()
        // The clock is let go again by the screen's next `setPlaying`.
    }

    /**
     * The screen left composition. Ends the run unless it is only stepping aside for
     * calibration, which [holdForCalibration] has already said.
     */
    fun onScreenLeft() {
        if (calibrating) return
        stop()
    }

    /**
     * End the run and publish its result for the results sheet.
     *
     * Separate from [stop]: the screen calls this when the player backs out or
     * the track ends, and it merges the run into the track's persisted record
     * before the room comes back up. A run with nothing scored publishes nothing.
     */
    fun finish() {
        if (!running) return
        val hud = _hud.value
        if (hud.score > 0) {
            val key = currentTrackKey
            val previousBest = currentRecord?.bestScore ?: 0
            val merged = mergeRecord(currentRecord, hud.score, hud.bestCombo, hud.accuracy)
            if (key != null) {
                records = records + (key to merged)
                persistRecords()
            }
            val enough = tapErrors.size >= MIN_TAPS_FOR_SUGGESTION
            _result.value = RunResult(
                hud = hud,
                record = merged,
                newRecord = key != null && hud.score > previousBest,
                trackKey = key,
                histogram = GameTiming.histogram(tapErrors),
                medianErrorMs = if (enough) GameTiming.median(tapErrors) else null,
                offsetMs = timingOffsetMs,
                suggestedOffsetMs = if (enough) GameTiming.suggestedOffsetMs(timingOffsetMs, tapErrors) else null,
            )
        }
        stop()
    }

    /**
     * The per-track records, held here for the length of a run and owned by
     * [GameRecords] — which the Light Sync tile also reads, to show a best score
     * without starting a game to find one out.
     */
    private var records: Map<String, GameRecord> = emptyMap()
    private var recordsLoaded = false

    private fun loadRecordsIfNeeded() {
        if (recordsLoaded) return
        recordsLoaded = true
        records = GameRecords.load(getApplication())
    }

    private fun persistRecords() = GameRecords.save(getApplication(), records)

    /**
     * Fold in one analysis frame.
     *
     * [leadMs] is how long until the newest audio the backend has handed over is
     * audible; the chart is written in audible time, not analysis time (see
     * [NoteGenerator]). It is corrected here for how far behind that audio the
     * frame ran, and the chart is placed relative to when the analyser saw the frame
     * rather than when this thread got round to it. The frame, grid and lag come from
     * one engine snapshot (`gameChartSource`), not from whatever the conflated frame
     * flow happened to deliver alongside it.
     *
     * [leadKnown] is false when the backend could not measure an output latency at
     * all (capture/cast feeds report a null AudioLead), in which case the chart
     * silently runs in analysis time. Surfaced so the screen can tell the player
     * to calibrate rather than let the game feel systematically off with no
     * explanation.
     */
    fun onFrame(frame: AnalysisFrame, leadMs: Long, leadKnown: Boolean = leadMs != 0L) {
        if (!running) return
        // Drained even when held, so a long pause cannot leave a backlog of stale beats.
        val onsets = directLightSync.drainGameOnsets()
        // A held run charts nothing. The tap normally goes quiet on its own when the
        // music stops, but the scan-driven and capture feeds can keep delivering, and
        // a note charted against a frozen clock lands on top of the hit line.
        if (clock.paused) return
        if (leadKnown != _leadKnown.value) _leadKnown.value = leadKnown

        val realNow = SystemClock.elapsedRealtime()
        // A chart that stopped updating (a feed that bypasses the analysis callback)
        // is no chart: the game then runs on its own loop from the flow's frames.
        val chart = directLightSync.gameChartSource()
            ?.takeIf { it.frameAtMs > 0L && realNow - it.frameAtMs <= CHART_STALE_MS }

        for (o in onsets) {
            val audible = clock.fromSource(o.atMs) + GameTiming.effectiveLeadMs(leadMs, o.lagMs)
            generator.onOnset(o.beat, o.bass, o.mid, o.midStrength, audible)
        }

        if (chart != null && chart === lastChart) {
            publish()
            return
        }
        lastChart = chart
        // Beat flags come from the onset buffer; stripping them here stops a frame
        // that is in both from steering the phase loop twice.
        val source = (chart?.frame ?: frame).copy(beat = false, bassBeat = false, midBeat = false)
        val frameMs = clock.fromSource(chart?.frameAtMs ?: realNow)
        val lead = GameTiming.effectiveLeadMs(leadMs, chart?.frameLagMs ?: 0f)
        generator.onFrame(source, frameMs, lead, chart)
        publish()
    }

    /**
     * [offset] shifts the judged clock; [scale] widens or narrows the judgement
     * windows; [haptics] gates the vibration channel. All three are settings the
     * screen (or a calibration flow) may change mid-session, so they are plain
     * fields applied from the next frame on rather than constructor state.
     */
    fun applyTiming(offsetMs: Int, scale: Float, haptics: Boolean) {
        timingOffsetMs = offsetMs.coerceIn(-300, 300)
        difficultyScale = scale.coerceIn(0.4f, 2f)
        hapticsOn = haptics
    }

    /** The offset currently applied, for the calibration flow's read-back. */
    val appliedTimingOffsetMs: Int get() = timingOffsetMs

    /** The difficulty scale currently applied. */
    val appliedDifficultyScale: Float get() = difficultyScale

    /** Leave game mode and hand the room back to the music. */
    fun stop() {
        if (!running) return
        running = false
        runCatching { directLightSync.setGameMode(false) }
    }

    override fun onCleared() {
        // The screen's DisposableEffect normally does this. This is the backstop for
        // the process-death-adjacent paths that skip it — leaving the room stuck at
        // its game floor with no way back short of restarting the stream would be the
        // worst possible failure for this feature.
        stop()
        super.onCleared()
    }

    /**
     * Advance the game to [now]: retire notes nobody hit, and republish.
     *
     * Driven from the screen's frame loop rather than a timer of its own, so the
     * game only advances while someone is looking at it.
     */
    fun tick(now: Long) {
        // The frame loop is the window's, not the music's — it runs on regardless.
        // Reaping here while the song is paused is what turned a pause into a screen
        // full of misses.
        if (!running || clock.paused) return
        // A new track means the run is over: show its results rather than carrying a
        // score from one song into the next. Checked about once a second.
        if (now - lastTrackCheckMs >= TRACK_CHECK_MS) {
            lastTrackCheckMs = now
            val key = runCatching { directLightSync.currentGameTrackKey() }.getOrNull()
            if (key != null && currentTrackKey != null && key != currentTrackKey && _hud.value.score > 0) {
                finish()
                return
            }
        }
        // Reaped on the judged clock and the difficulty's own window: the same pair
        // `take` searches with, so a note is retired exactly when no tap could still
        // score it. (It was reaped at the unscaled Normal window, so Expert notes
        // lingered past their last scoring moment and Easy ones were cut short.)
        val missed = generator.reap(GameTiming.judgedMs(now, timingOffsetMs), difficultyScale)
        if (missed.isEmpty()) {
            publish()
            return
        }
        notesReached += missed.size
        var hud = _hud.value
        val hadCombo = hud.combo > 0
        // Embers on the lanes that were missed, and the colour fading back if a
        // combo just broke. Nothing flashes: a miss is quiet.
        val realNow = clock.toSource(now)
        runCatching {
            for (lane in missed.map { it.lane }.distinct()) directLightSync.receiveGameMiss(lane, realNow)
            if (hadCombo) directLightSync.receiveGameComboBreak(realNow)
        }
        hud = hud.copy(combo = 0, multiplier = 1, missed = hud.missed + missed.size)
        _hud.value = hud.withAccuracy()
        // One event for the run, not one per note: four notes expiring on the same
        // frame is one mistake, and four "Miss" banners stacked on top of each
        // other reads as a bug. The run is attributed to the lane of the note
        // closest to the line — the one the player was most likely watching —
        // rather than an arbitrary first element.
        val nearest = missed.minByOrNull { it.triggerTimeMs }
        emit(Judgement.MISS, nearest?.lane ?: -1, 0, now)
        publish()
    }

    /**
     * The player tapped [lane] at [now].
     *
     * A tap with no note near it is deliberately free: it scores nothing, flashes
     * nothing, and does not break the combo. Punishing it would make the honest
     * response to an unclear chart — tapping to find out — the losing move, and the
     * chart here is generated live rather than authored.
     *
     * A tap that *does* find a note always scores, because the window
     * [NoteGenerator.take] searches is the widest scoring window. A note the player
     * was too late for is not taken here at all — it is reaped by [tick] as a miss.
     *
     * The tap is judged against `now - timingOffsetMs`: the calibration offset is how
     * late the player's taps run (their measured touch and output latency), and
     * bringing the *judged* clock back by it centres every window on how the player
     * actually experiences the beat. The chart itself is untouched, because a shifted
     * chart would misplace notes relative to the music.
     */
    fun tap(lane: Int, now: Long): Judgement? {
        if (!running || clock.paused) return null
        val judgedNow = GameTiming.judgedMs(now, timingOffsetMs)
        val note = generator.take(lane, judgedNow, difficultyScale) ?: return null
        val signed = judgedNow - note.triggerTimeMs
        val delta = kotlin.math.abs(signed)
        val judgement = Judgement.forDelta(delta, difficultyScale)
        tapErrors.add(signed)
        notesReached++
        notesStruck++

        var hud = _hud.value
        val combo = hud.combo + 1
        val multiplier = multiplierFor(combo)
        val steppedUp = multiplier > hud.multiplier
        hud = hud.copy(
            score = hud.score + judgement.points * multiplier,
            combo = combo,
            bestCombo = maxOf(hud.bestCombo, combo),
            multiplier = multiplier,
            perfect = hud.perfect + if (judgement == Judgement.PERFECT) 1 else 0,
            great = hud.great + if (judgement == Judgement.GREAT) 1 else 0,
            good = hud.good + if (judgement == Judgement.GOOD) 1 else 0,
        )
        _hud.value = hud.withAccuracy()
        emit(judgement, lane, combo, now)
        if (hapticsOn) haptics(judgement, steppedUp)
        flashRoom(note, judgement, combo, now, earlyByMs = note.triggerTimeMs - now)
        publish()
        return judgement
    }

    /**
     * The haptic channel: a tap's confirmation reaches the hand before the eye.
     * Sharp for a Perfect, soft for the rest, a double pulse when the multiplier
     * steps up — the one reward that works with no bridge and no screen attention.
     */
    private fun haptics(judgement: Judgement, steppedUp: Boolean) {
        val vibrator = getApplication<Application>().getSystemService(android.content.Context.VIBRATOR_SERVICE)
            as? android.os.Vibrator ?: return
        if (!vibrator.hasVibrator()) return
        runCatching {
            when {
                steppedUp -> vibrator.vibrate(
                    android.os.VibrationEffect.createWaveform(longArrayOf(0, 24, 60, 24), -1),
                )
                judgement == Judgement.PERFECT -> vibrator.vibrate(
                    android.os.VibrationEffect.createWaveform(longArrayOf(0, 28), -1),
                )
                else -> vibrator.vibrate(android.os.VibrationEffect.createOneShot(12, 96))
            }
        }
    }

    /**
     * The light a hit earns: its lane's lamps flash.
     *
     * [judgement] sets the peak (a Perfect is full, a Good less than half) and [combo]
     * deepens the colour and lengthens the glow. A tap that lands before its note is
     * audible ([earlyByMs] above zero) is held back so the lamp, which takes about
     * [GameTiming.LIGHT_PIPELINE_MS] to show a command, lights as the beat is heard
     * rather than ahead of it. A tap on or after the beat goes out at once, because
     * it can no longer be early.
     *
     * Stamped in real time ([PausableClock.toSource]) because the light render loop is
     * on another thread and knows nothing of the game clock. Best-effort by design: the
     * game is playable with no bridge paired at all, and a light that cannot be reached
     * must not cost the player a note.
     */
    private fun flashRoom(note: GameNote, judgement: Judgement, combo: Int, tapGameMs: Long, earlyByMs: Long) {
        runCatching {
            val startMs = clock.toSource(tapGameMs) + GameTiming.flashDelayMs(earlyByMs)
            directLightSync.receiveGameHit(note.lane, judgement, combo, startMs)
        }
    }

    private fun publish() {
        // Only when something actually changed. [tick] runs on every display frame,
        // and a StateFlow handed a fresh copy of an unchanged list sixty times a
        // second recomposes the whole screen sixty times a second.
        //
        // A copy rather than the generator's own list, because it mutates that one
        // in place and a StateFlow handed the same instance twice publishes neither
        // change.
        if (publishedRevision != generator.revision) {
            publishedRevision = generator.revision
            _notes.value = ArrayList(generator.active())
        }
        val hud = _hud.value
        // Quantised: the raw estimate wanders by fractions of a BPM every frame, and
        // the HUD only ever shows it as a whole number.
        val bpm = generator.bpm
        val countIn = generator.countInBeatsLeft(nowMs())
        if (hud.locked != generator.locked || hud.bpm.toInt() != bpm.toInt() || hud.countIn != countIn) {
            _hud.value = hud.copy(locked = generator.locked, bpm = bpm, countIn = countIn)
        }
    }

    private fun emit(judgement: Judgement, lane: Int, comboAfter: Int, atMs: Long) {
        _judgement.value = JudgementEvent(eventId.incrementAndGet(), judgement, lane, comboAfter, atMs)
    }

    private fun RhythmHud.withAccuracy(): RhythmHud =
        if (notesReached == 0) this else copy(accuracy = notesStruck.toFloat() / notesReached)
}

/** Guitar-hero laddering: every ten unbroken notes is another multiple, up to four. */
private fun multiplierFor(combo: Int): Int = when {
    combo >= 30 -> 4
    combo >= 20 -> 3
    combo >= 10 -> 2
    else -> 1
}

/** Fewest scored taps before the results card dares suggest a timing offset. */
private const val MIN_TAPS_FOR_SUGGESTION = 8

/** How often, in game ms, the current track is compared with the one the run started on. */
private const val TRACK_CHECK_MS = 1000L

/** How old a chart snapshot may be, in ms, before the game stops trusting it. */
private const val CHART_STALE_MS = 400L
