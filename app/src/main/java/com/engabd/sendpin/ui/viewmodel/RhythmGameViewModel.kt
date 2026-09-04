package com.engabd.sendpin.ui.viewmodel

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.audio.AnalysisFrame
import com.engabd.sendpin.game.GameNote
import com.engabd.sendpin.game.GameBand
import com.engabd.sendpin.game.Judgement
import com.engabd.sendpin.game.NoteGenerator
import com.engabd.sendpin.game.bandOf
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
)

/**
 * Drives the rhythm game.
 *
 * ## The one rule this model exists to enforce
 *
 * In this mode the room does not react to the music on its own. The light show still
 * *runs* in full — the beat grid, the track scan, the per-instrument reactions — but
 * [com.engabd.sendpin.hue.DirectLightSync.setGameMode] holds its output down to a dim
 * floor, and the only thing that ever lets it through is a note struck inside its
 * window. That is what makes the game a game: a room that reacted on every beat
 * regardless would look identical whether the player was there or not, and every hit
 * would be invisible inside a show that was already moving.
 *
 * The reward is the show itself, not a substitute for it. Hitting the kick plays what
 * the room does on a kick, because what is gated is the engine's own frame rather
 * than a flash invented here.
 *
 * So there is exactly one call into the light engine — from a [Judgement] that scored
 * points — and no path from an analysis frame to a light anywhere in this file.
 *
 * ## Clock
 *
 * [SystemClock.elapsedRealtime]: monotonic, unaffected by the wall clock, and — the
 * point — the same clock for the frame collector and the tap handler. The previous
 * version scheduled notes against the *player's* position when it had one and the
 * wall clock when it did not, which meant a seek, a track change or simply a backend
 * without a position moved every pending note by an arbitrary amount.
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

    /** How far notes fall before the line, so the UI can place them. */
    val lookAheadMs: Long get() = generator.lookAheadMs

    private var eventId = 0L
    private var publishedRevision = -1
    private var notesReached = 0
    private var notesStruck = 0
    private var running = false

    /** The game's clock. Everything here — frames, notes, taps — is on it. */
    fun nowMs(): Long = SystemClock.elapsedRealtime()

    /**
     * Enter game mode: darken the room and take the lights off the music.
     *
     * Idempotent, because the screen calls it from a `DisposableEffect` and a
     * configuration change runs that twice.
     */
    fun start() {
        if (running) return
        running = true
        generator.reset()
        publishedRevision = generator.revision
        _notes.value = emptyList()
        notesReached = 0
        notesStruck = 0
        _judgement.value = null
        _hud.value = RhythmHud()
        runCatching { directLightSync.setGameMode(true) }
    }

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
     * Fold in one analysis frame.
     *
     * [leadMs] is how long until this frame's audio is audible — see [NoteGenerator]
     * for why the chart is written in audible time rather than analysis time.
     *
     * [leadKnown] is false when the backend could not measure an output latency at
     * all (capture/cast feeds report a null AudioLead), in which case the chart
     * silently runs in analysis time. Surfaced so the screen can tell the player
     * to calibrate rather than let the game feel systematically off with no
     * explanation.
     */
    fun onFrame(frame: AnalysisFrame, leadMs: Long, leadKnown: Boolean = leadMs != 0L) {
        if (!running) return
        if (leadKnown != _leadKnown.value) _leadKnown.value = leadKnown
        generator.onFrame(frame, nowMs(), leadMs, directLightSync.gameChartSource())
        publish()
    }

    /**
     * Advance the game to [now]: retire notes nobody hit, and republish.
     *
     * Driven from the screen's frame loop rather than a timer of its own, so the
     * game only advances while someone is looking at it.
     */
    fun tick(now: Long) {
        if (!running) return
        val missed = generator.reap(now)
        if (missed.isEmpty()) {
            publish()
            return
        }
        notesReached += missed.size
        var hud = _hud.value
        hud = hud.copy(combo = 0, multiplier = 1, missed = hud.missed + missed.size)
        _hud.value = hud.withAccuracy()
        // One event for the run, not one per note: four notes expiring on the same
        // frame is one mistake, and four "Miss" banners stacked on top of each other
        // reads as a bug.
        emit(Judgement.MISS, missed.first().lane, 0, now)
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
     */
    fun tap(lane: Int, now: Long): Judgement? {
        if (!running) return null
        val note = generator.take(lane, now) ?: return null
        val delta = kotlin.math.abs(note.triggerTimeMs - now)
        val judgement = Judgement.forDelta(delta)
        notesReached++
        notesStruck++

        var hud = _hud.value
        val combo = hud.combo + 1
        val multiplier = multiplierFor(combo)
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
        flashRoom(note, judgement, combo)
        publish()
        return judgement
    }

    /**
     * The one place a light is ever asked to do anything in this mode.
     *
     * This does not *paint* anything — it opens the gate that lets the real light
     * show through for a moment. Which lamps move, and how, is the engine's answer
     * to what the music is doing at that instant: a kick reads as a kick, a hi-hat
     * as a hi-hat, with the track scan behind it. Hitting the note buys the
     * reaction; it does not replace it. See DirectLightSync.setGameMode.
     *
     * How *well* it was hit sets how far the gate opens, so a Perfect plays the show
     * at full and a scrappy Good plays it at rather less. The note's own intensity
     * moves that a little as well: a note the music barely made was never going to
     * pay out much.
     *
     * The note's band decides *where* in the room the answer lands — bass on the
     * floor, hats at the ceiling, the melody lane as a colour sweep — so the room
     * answers in the shape of the music rather than as one undifferentiated flash.
     *
     * Best-effort by design: the game is playable with no bridge paired at all, and
     * a light that cannot be reached must not cost the player a note.
     */
    private fun flashRoom(note: GameNote, judgement: Judgement, combo: Int) {
        runCatching {
            directLightSync.receiveGameHit(
                strength = (judgement.points / 100f) * (0.7f + note.intensity * 0.3f),
                combo = combo,
                band = bandOf(note.kind),
            )
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
        if (hud.locked != generator.locked || hud.bpm.toInt() != bpm.toInt()) {
            _hud.value = hud.copy(locked = generator.locked, bpm = bpm)
        }
    }

    private fun emit(judgement: Judgement, lane: Int, comboAfter: Int, atMs: Long) {
        _judgement.value = JudgementEvent(++eventId, judgement, lane, comboAfter, atMs)
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
