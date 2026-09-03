package com.engabd.sendpin.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.audio.AnalysisFrame
import com.engabd.sendpin.game.GameNote
import com.engabd.sendpin.game.NoteGenerator
import com.engabd.sendpin.hue.DirectLightSync
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the rhythm tiles game.
 *
 * Feeds live analysis frames into a note generator and scores taps against the
 * nearest note in the matching lane.
 */
class RhythmGameViewModel(app: Application) : AndroidViewModel(app) {
    private val generator = NoteGenerator()
    private val directLightSync = (app as SendpinApp).directLightSync

    private val _notes = MutableStateFlow<List<GameNote>>(emptyList())
    val notes: StateFlow<List<GameNote>> = _notes.asStateFlow()

    private val _score = MutableStateFlow(0)
    val score: StateFlow<Int> = _score.asStateFlow()

    private val _combo = MutableStateFlow(0)
    val combo: StateFlow<Int> = _combo.asStateFlow()

    private val _lastHit = MutableStateFlow<String?>(null)
    val lastHit: StateFlow<String?> = _lastHit.asStateFlow()

    /** Update the falling notes from the latest analysis frame. */
    fun onFrame(frame: AnalysisFrame, positionMs: Long) {
        _notes.value = generator.onFrame(frame, positionMs)
    }

    /** The user tapped a lane. Score it and, if it hits, flash the lights. */
    fun tap(lane: Int, positionMs: Long) {
        val candidates = _notes.value.filter { it.lane == lane }
        val target = candidates.minByOrNull { kotlin.math.abs(it.triggerTimeMs - positionMs) } ?: return
        val delta = kotlin.math.abs(target.triggerTimeMs - positionMs)
        val (points, label) = when {
            delta < 50L -> 100 to "Perfect"
            delta < 120L -> 50 to "Good"
            delta < 200L -> 25 to "OK"
            else -> 0 to "Miss"
        }
        if (points > 0) {
            _score.value += points
            _combo.value += 1
            _lastHit.value = label
            _notes.value = _notes.value.filter { it !== target }
            onHit(target, points)
        } else {
            _combo.value = 0
            _lastHit.value = "Miss"
        }
    }

    private fun onHit(note: GameNote, points: Int) {
        viewModelScope.launch {
            try {
                (directLightSync as? DirectLightSync)?.receiveGameHit(note.lane, points)
            } catch (_: Exception) {
                // Light reaction is best-effort; the game must not crash if the
                // bridge is not connected.
            }
        }
    }
}
