package com.engabd.sendpin.audio

import org.junit.Assert.assertTrue
import org.junit.Test

class ListeningJournalTest {

    // ── empty ─────────────────────────────────────────────────────────────

    @Test
    fun `no plays produces a one-line nothing-played message`() {
        val result = ListeningJournal.generate(emptyList(), emptyMap())

        assertTrue(result.contains("didn't listen"))
    }

    // ── single track ──────────────────────────────────────────────────────

    @Test
    fun `a single play mentions the track and its time of day`() {
        val play = play(
            timestamp = morning(10, 0),
            title = "Blue in Green",
            artist = "Miles Davis",
            genre = "Jazz",
            trackId = "t1",
            durationMs = 5 * 60_000,
        )
        val scans = mapOf("t1" to scan(bpm = 65f, key = MusicalKey(2, MusicalMode.MINOR, 0.9f)))

        val result = ListeningJournal.generate(listOf(play), scans)

        assertTrue("Should mention morning: $result", result.contains("morning"))
        assertTrue("Should mention 5 minutes: $result", result.contains("5 minutes"))
        assertTrue("Should not claim a repeat play count: $result", !result.contains("2 times") && !result.contains("3 times"))
        assertTrue("Should mention jazz: $result", result.contains("Jazz"))
        assertTrue("Should mention the key: $result", result.contains("D minor"))
        assertTrue("Should mention the tempo: $result", result.contains("65 BPM"))
    }

    // ── diverse day ───────────────────────────────────────────────────────

    @Test
    fun `a diverse day names the most played track and artist, and spans time`() {
        val plays = listOf(
            play(morning(8, 30), "Morning Coffee", "Lo-Fi Artist", "Lo-Fi", "a", 3 * 60_000),
            play(morning(9, 0), "Morning Coffee", "Lo-Fi Artist", "Lo-Fi", "a", 3 * 60_000),
            play(afternoon(14, 0), "Afternoon Drive", "The Commute", "Rock", "b", 4 * 60_000),
            play(evening(19, 0), "Evening Jazz", "Miles Davis", "Jazz", "c", 5 * 60_000),
            play(evening(20, 0), "Evening Jazz", "Miles Davis", "Jazz", "c", 5 * 60_000),
        )
        val scans = mapOf(
            "a" to scan(bpm = 80f, key = MusicalKey(0, MusicalMode.MAJOR, 0.9f)),
            "b" to scan(bpm = 140f, key = MusicalKey(7, MusicalMode.MAJOR, 0.8f)),
            "c" to scan(bpm = 100f, key = MusicalKey(0, MusicalMode.MAJOR, 0.7f)),
        )

        val result = ListeningJournal.generate(plays, scans)

        // Most played track (2x each for "Morning Coffee" and "Evening Jazz" — first wins by maxByOrNull)
        assertTrue("Should mention a repeat: $result", result.contains("repeat"))
        // Most played artist: "Lo-Fi Artist" and "Miles Davis" both at 2 — first wins
        assertTrue("Should mention an artist with plays: $result",
            result.contains("2 plays"))
        // Time span
        assertTrue("Should mention the span: $result",
            result.contains("morning") && result.contains("evening"))
        // Total: 3+3+4+5+5 = 20 minutes
        assertTrue("Should mention 20 minutes: $result", result.contains("20 minutes"))
        // Key of the day: C major appears 4 times (a x2, c x2), G major once — C major wins
        assertTrue("Should mention C major: $result", result.contains("C major"))
        // Tempo average: (80+80+140+100+100)/5 = 100
        assertTrue("Should mention ~100 BPM: $result", result.contains("100 BPM"))
    }

    // ── late-night only ───────────────────────────────────────────────────

    @Test
    fun `late-night-only plays are described as night`() {
        val plays = listOf(
            play(night(23, 30), "Insomnia", "Night Owl", "Ambient", "n1", 8 * 60_000),
            play(night(1, 0), "Still Up", "Night Owl", "Ambient", "n2", 6 * 60_000),
        )
        val scans = mapOf(
            "n1" to scan(bpm = 70f, key = MusicalKey(5, MusicalMode.MINOR, 0.85f)),
            "n2" to scan(bpm = 75f, key = MusicalKey(5, MusicalMode.MINOR, 0.9f)),
        )

        val result = ListeningJournal.generate(plays, scans)

        assertTrue("Should mention night: $result", result.contains("night"))
        assertTrue("Should mention the artist repeat: $result",
            result.contains("Night Owl") && result.contains("2 plays"))
        // Total: 14 minutes
        assertTrue("Should mention 14 minutes: $result", result.contains("14 minutes"))
        // Key of the day: F minor (tonic=5, minor) both plays
        assertTrue("Should mention F minor: $result", result.contains("F minor"))
        // Tempo: (70+75)/2 = 72.5 -> rounded to 73
        assertTrue("Should mention ~73 BPM: $result", result.contains("73 BPM"))
    }

    // ── plays without scans ───────────────────────────────────────────────

    @Test
    fun `plays without scans still count toward time and genre but not key or tempo`() {
        val plays = listOf(
            play(morning(9, 0), "Unknown Track", "Mystery Artist", "Electronic", "no-scan", 4 * 60_000),
        )

        val result = ListeningJournal.generate(plays, emptyMap())

        assertTrue("Should mention the time: $result", result.contains("morning"))
        assertTrue("Should mention 4 minutes: $result", result.contains("4 minutes"))
        assertTrue("Should mention electronic: $result", result.contains("Electronic"))
        // No key or tempo should appear
        assertTrue("Should not mention BPM: $result", !result.contains("BPM"))
        assertTrue("Should not mention major/minor: $result",
            !result.contains("major") && !result.contains("minor"))
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun play(
        timestamp: Long,
        title: String,
        artist: String,
        genre: String,
        trackId: String,
        durationMs: Long,
    ) = PlayRecord(timestamp, title, artist, genre, trackId, durationMs)

    /** Epoch millis for a given hour/minute on 2025-09-01 (arbitrary fixed date). */
    private fun at(hour: Int, minute: Int): Long {
        // 2025-09-01 00:00:00 UTC = 1756684800000L
        return 1_756_684_800_000L + (hour * 60 + minute) * 60_000L
    }

    private fun morning(hour: Int, minute: Int) = at(hour, minute)
    private fun afternoon(hour: Int, minute: Int) = at(hour, minute)
    private fun evening(hour: Int, minute: Int) = at(hour, minute)
    private fun night(hour: Int, minute: Int) = at(hour, minute)

    private fun scan(bpm: Float, key: MusicalKey): TrackScan = TrackScan(
        durationS = 300f,
        bpm = bpm,
        confidence = 0.9f,
        beats = FloatArray(0),
        accents = FloatArray(0),
        downbeat = 0,
        sections = emptyList(),
        intensity = null,
        key = key,
    )
}