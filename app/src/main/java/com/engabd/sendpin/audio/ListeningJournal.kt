package com.engabd.sendpin.audio

import kotlin.math.roundToInt

/**
 * One completed play, the minimal shape the journal needs.
 *
 * Deliberately not [com.engabd.sendpin.local.db.PlayHistoryEntity]: that row
 * carries codec, bit depth, provider, stream provider and a dozen other fields
 * the journal does not read, and coupling the two would mean every column added
 * to the stats screen is suddenly part of the journal's API. This is the subset
 * the narrative actually uses, and the call site maps the entity to it.
 */
data class PlayRecord(
    /** Epoch millis when this play was logged. */
    val timestamp: Long,
    val trackTitle: String,
    val artist: String,
    val genre: String,
    val trackId: String,
    val durationMs: Long,
)

/**
 * A natural-language daily listening summary, generated from play history and
 * whatever offline scans are available.
 *
 * The Stats screen already has tables — play counts, genre bars, a tempo
 * histogram. What it does not have is a sentence that says what the day was
 * *like*: "You listened for two hours, mostly jazz in the evening, centred on
 * E minor at about 95 BPM." That is what this produces.
 *
 * Pure and stateless: pass in the plays and the scans, get back a string. No
 * DataStore, no database, no Android dependency — so it is unit-testable without
 * a device, and a future caller can run it on a week or a month by pre-filtering
 * the plays.
 *
 * Everything is defensive: a play with no scan contributes nothing to the key
 * or tempo averages but still counts toward play counts and listening time. A
 * day with no plays at all produces a one-line "you didn't listen to anything
 * today" rather than a crash.
 */
object ListeningJournal {

    /** Twelve pitch classes, indexed by [MusicalKey.tonic] (0 = C, 11 = B). */
    private val NOTE_NAMES = arrayOf(
        "C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B",
    )

    /**
     * Produce the daily narrative.
     *
     * @param plays the completed plays for the day, in any order — they are
     *   sorted by timestamp internally.
     * @param scans offline scans keyed by [PlayRecord.trackId]; a play whose
     *   trackId is not in the map simply has no key or tempo to contribute.
     */
    fun generate(plays: List<PlayRecord>, scans: Map<String, TrackScan>): String {
        if (plays.isEmpty()) return "You didn't listen to anything today."

        val sorted = plays.sortedBy { it.timestamp }
        val firstHour = hourOfDay(sorted.first().timestamp)
        val lastHour = hourOfDay(sorted.last().timestamp)

        val totalMs = plays.sumOf { it.durationMs }
        val totalMinutes = totalMs / 60_000

        val mostPlayedTrack = plays
            .groupingBy { it.trackTitle }
            .eachCount()
            .maxByOrNull { it.value }
        val mostPlayedArtist = plays
            .groupingBy { it.artist }
            .eachCount()
            .maxByOrNull { it.value }

        val genreBreakdown = plays
            .groupingBy { it.genre.takeIf { g -> g.isNotBlank() } ?: "Unknown" }
            .eachCount()
            .toList()
            .sortedByDescending { it.second }
            .take(3)

        // Key and tempo come only from plays whose trackId has a scan.
        val scannedPlays = sorted.mapNotNull { p -> scans[p.trackId]?.let { p to it } }

        val keyOfDay = scannedPlays
            .mapNotNull { (_, scan) -> scan.key }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key

        val avgBpm = scannedPlays
            .map { (_, scan) -> scan.bpm }
            .filter { it > 0f }
            .takeIf { it.isNotEmpty() }
            ?.average()

        return buildString {
            // ── time of day ──────────────────────────────────────────────
            append("You listened ")
            append(timeOfDaySpan(firstHour, lastHour))
            append(", ")

            // ── total time ──────────────────────────────────────────────
            append(formatDuration(totalMinutes))
            append(" in total. ")

            // ── most played track ───────────────────────────────────────
            if (mostPlayedTrack != null && mostPlayedTrack.value > 1) {
                append("\"${mostPlayedTrack.key}\" was the day's repeat, played ")
                append("${mostPlayedTrack.value} times. ")
            } else {
                append("No track repeated — it was a browsing day. ")
            }

            // ── most played artist ──────────────────────────────────────
            if (mostPlayedArtist != null && mostPlayedArtist.value > 1) {
                append("${mostPlayedArtist.key} was the artist you came back to, ")
                append("with ${mostPlayedArtist.value} plays. ")
            }

            // ── genre breakdown ─────────────────────────────────────────
            if (genreBreakdown.isNotEmpty()) {
                append("Your listening leaned ")
                append(genreBreakdown.joinToString(", ") { (genre, count) ->
                    "$genre ($count)"
                })
                append(". ")
            }

            // ── key of the day ───────────────────────────────────────────
            if (keyOfDay != null) {
                append("The day's centre of gravity was ")
                append(keyName(keyOfDay))
                append(". ")
            }

            // ── tempo average ───────────────────────────────────────────
            if (avgBpm != null) {
                append("Tempo averaged around ")
                append(avgBpm.roundToInt())
                append(" BPM. ")
            }
        }.trim()
    }

    /** Hour of day, 0..23, from an epoch-millis timestamp. */
    private fun hourOfDay(timestamp: Long): Int =
        ((timestamp / 3_600_000) % 24).toInt()

    /**
     * "in the morning", "across the afternoon and into the evening", etc.
     *
     * Uses first and last play to bracket the day; a single play at 8 AM is
     * "in the morning", not "from morning to morning".
     */
    private fun timeOfDaySpan(firstHour: Int, lastHour: Int): String {
        val first = bucket(firstHour)
        val last = bucket(lastHour)
        if (first == last) return "in the $first"
        if (adjacent(first, last)) return "from $first into the $last"
        return "from $first through the $last"
    }

    /** Morning 5-11, afternoon 12-16, evening 17-21, night 22-4. */
    private fun bucket(hour: Int): String = when (hour) {
        in 5..11 -> "morning"
        in 12..16 -> "afternoon"
        in 17..21 -> "evening"
        else -> "night"
    }

    /** Whether two time-of-day buckets are next to each other in the day. */
    private fun adjacent(a: String, b: String): Boolean {
        val order = listOf("morning", "afternoon", "evening", "night")
        val ia = order.indexOf(a)
        val ib = order.indexOf(b)
        return ia >= 0 && ib >= 0 && kotlin.math.abs(ia - ib) == 1
    }

    /** "2 hours and 15 minutes", "45 minutes", "less than a minute". */
    private fun formatDuration(totalMinutes: Long): String {
        if (totalMinutes < 1) return "less than a minute"
        val hours = totalMinutes / 60
        val mins = totalMinutes % 60
        return when {
            hours == 0L -> "$mins minute${plural(mins)}"
            mins == 0L -> "$hours hour${plural(hours)}"
            else -> "$hours hour${plural(hours)} and $mins minute${plural(mins)}"
        }
    }

    private fun plural(n: Long): String = if (n == 1L) "" else "s"

    /** "E minor", "A major", in standard pitch-class notation. */
    private fun keyName(key: MusicalKey): String {
        val note = NOTE_NAMES.getOrNull(key.tonic) ?: "unknown"
        val mode = if (key.mode == MusicalMode.MAJOR) "major" else "minor"
        return "$note $mode"
    }
}