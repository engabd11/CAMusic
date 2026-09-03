package com.engabd.sendpin.game

import com.engabd.sendpin.audio.AnalysisFrame

enum class NoteKind { KICK, SNARE, HAT, MELODY }

data class GameNote(
    val lane: Int,
    val triggerTimeMs: Long,
    val kind: NoteKind,
    val intensity: Float,
)

/**
 * Generates rhythm-game notes from the live analysis stream.
 *
 * Notes are produced with a lookahead so they can fall from the top of the screen
 * and reach the hit line on the beat. Duplicate suppression keeps the same drum
 * hit from spawning two overlapping notes.
 */
class NoteGenerator(private val lanes: Int = 4) {
    private val pending = ArrayDeque<GameNote>()

    fun onFrame(frame: AnalysisFrame, positionMs: Long, lookAheadMs: Long = 2000L): List<GameNote> {
        val now = positionMs
        if (frame.bassBeat) addNote(0, now + lookAheadMs, NoteKind.KICK, frame.bassStrength)
        if (frame.beat && !frame.bassBeat) addNote(1, now + lookAheadMs, NoteKind.SNARE, frame.beatStrength)
        if (frame.midBeat) addNote(2, now + lookAheadMs, NoteKind.HAT, frame.midStrength)
        val topBin = frame.melbank.withIndex().maxByOrNull { it.value }?.index ?: -1
        if (topBin >= 8 && frame.energy > 0.15f) {
            addNote(3, now + lookAheadMs, NoteKind.MELODY, frame.energy)
        }

        // Expire notes that have long since passed the hit window.
        while (pending.isNotEmpty() && pending.first().triggerTimeMs < now - 500L) pending.removeFirst()
        return pending.filter { it.triggerTimeMs in now..(now + lookAheadMs) }
    }

    private fun addNote(lane: Int, timeMs: Long, kind: NoteKind, intensity: Float) {
        if (lane !in 0 until lanes) return
        // Deduplicate notes in the same lane within ~120 ms.
        if (pending.any { it.lane == lane && kotlin.math.abs(it.triggerTimeMs - timeMs) < 120L }) return
        pending.addLast(GameNote(lane, timeMs, kind, intensity.coerceIn(0f, 1f)))
    }
}
