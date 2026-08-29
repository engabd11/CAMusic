package com.engabd.sendpin.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.engabd.sendpin.local.db.ArtistPlayCount
import com.engabd.sendpin.local.db.DayTotals
import com.engabd.sendpin.local.db.FormatPlayCount
import com.engabd.sendpin.local.db.HourCell
import com.engabd.sendpin.local.db.KeyPlayCount
import com.engabd.sendpin.local.db.LocalMediaDatabase
import com.engabd.sendpin.local.db.ProviderPlayCount
import com.engabd.sendpin.local.db.TempoEnergyPoint
import com.engabd.sendpin.local.db.TrackPlayCount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import kotlin.math.ln
import kotlin.math.roundToInt

/** How far back the page is looking. */
enum class StatsWindow(val label: String, val days: Int) {
    WEEK("7 days", 7),
    MONTH("30 days", 30),
    QUARTER("90 days", 90),
    ALL("All time", 0);

    /** Epoch millis to count from. Zero for [ALL], which counts everything. */
    fun since(now: Long): Long = if (days == 0) 0L else now - days * 24L * 60 * 60 * 1000
}

class StatsViewModel(app: Application) : AndroidViewModel(app) {

    data class State(
        val loading: Boolean = true,
        val window: StatsWindow = StatsWindow.WEEK,
        val topArtists: List<ArtistPlayCount> = emptyList(),
        val totalListeningMs: Long = 0,
        val plays: Int = 0,
        val mostPlayed: TrackPlayCount? = null,
        val formats: List<FormatPlayCount> = emptyList(),
        val sources: List<ProviderPlayCount> = emptyList(),
        /** Same sources, weighted by time rather than play count. */
        val timeBySource: List<ProviderPlayCount> = emptyList(),
        val dominantKeys: List<Pair<String, Int>> = emptyList(),
        val bpmHistogram: List<Pair<String, Int>> = emptyList(),
        /** 7 x 24 grid of play counts. Row 0 is Sunday, matching SQLite's `%w`. */
        val clock: List<HourCell> = emptyList(),
        val daily: List<DayTotals> = emptyList(),
        val tempoEnergy: List<TempoEnergyPoint> = emptyList(),
        val distinctArtists: Int = 0,
        val newArtists: Int = 0,
        /** Shannon entropy over artist shares, in bits. See [artistEntropy]. */
        val diversityBits: Float = 0f,
        /** The most bits this listening *could* have had, for context. */
        val maxDiversityBits: Float = 0f,
        val longestStreakDays: Int = 0,
        val currentStreakDays: Int = 0,
        /** Median share of a track actually heard, 0..1. Null when nothing recorded it. */
        val medianCompletion: Float? = null,
        /** Share of listening time that was lossless, 0..1. Null when no codec was recorded. */
        val losslessShare: Float? = null,
        /** Oldest row, so "all time" can say how far back it goes. */
        val earliest: Long? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val dao by lazy { LocalMediaDatabase.get(getApplication()).playHistoryDao() }

    init { refresh() }

    fun setWindow(w: StatsWindow) {
        if (w == _state.value.window) return
        _state.value = _state.value.copy(window = w)
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val window = _state.value.window
            _state.value = _state.value.copy(loading = true)
            val since = window.since(System.currentTimeMillis())

            val artists = dao.topArtists(since, limit = 12)
            val daily = dao.dailyTotals(since)
            val completions = dao.completions(since)
            val formats = dao.formatBreakdown(since)
            val distinct = dao.distinctArtists(since)
            val plays = dao.playCount(since)

            _state.value = State(
                loading = false,
                window = window,
                topArtists = artists,
                totalListeningMs = dao.totalListeningMs(since),
                plays = plays,
                mostPlayed = dao.mostPlayedTrack(since),
                formats = formats,
                sources = dao.sourceBreakdown(since),
                timeBySource = dao.timeBySource(since),
                dominantKeys = dao.keyDistribution(since).map { it.label() to it.plays },
                bpmHistogram = bpmHistogram(dao.bpmSamples(since)),
                clock = dao.listeningClock(since),
                daily = daily,
                tempoEnergy = dao.tempoEnergy(since),
                distinctArtists = distinct,
                newArtists = if (since == 0L) distinct else dao.newArtists(since),
                diversityBits = artistEntropy(artists),
                maxDiversityBits = if (distinct > 1) ln(distinct.toFloat()) / LN2 else 0f,
                longestStreakDays = longestStreak(daily.map { it.day }),
                currentStreakDays = currentStreak(daily.map { it.day }),
                medianCompletion = medianCompletion(completions),
                losslessShare = losslessShare(formats),
                earliest = dao.earliest(),
            )
        }
    }
}

private const val LN2 = 0.6931472f

/** "3h 24m", or "12m" under an hour, or "0m" — never a bare "0". */
fun formatListeningTime(ms: Long): String {
    val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(ms)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

/** "FLAC 96/24", "MP3", "Opus" — the label the format-breakdown pie uses. */
fun FormatPlayCount.label(): String {
    val name = codec?.uppercase()?.takeIf { it.isNotBlank() } ?: return "Unknown"
    if (sampleRate <= 0 || bitDepth <= 0) return name
    val khz = sampleRate / 1000f
    val khzLabel = if (khz == khz.toInt().toFloat()) khz.toInt().toString() else "%.1f".format(khz)
    return "$name $khzLabel/$bitDepth"
}

private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

/** "D minor", "C major" — the label Listening DNA's dominant-keys bar uses. */
fun KeyPlayCount.label(): String {
    val note = NOTE_NAMES.getOrNull(keyTonic) ?: return "Unknown"
    return "$note ${if (keyMode == "MAJOR") "major" else "minor"}"
}

/** 10 BPM bins, sorted by how many plays landed in each, for RankedBars. */
private fun bpmHistogram(samples: List<Float>): List<Pair<String, Int>> =
    samples.groupingBy { (it / 10f).toInt() * 10 }.eachCount()
        .entries.sortedByDescending { it.value }
        .map { (bin, count) -> "$bin-${bin + 9}" to count }

/**
 * Shannon entropy over artist shares, in bits.
 *
 * One number that actually says something: how *spread out* the listening was, rather
 * than how much of it there was. Ten plays of one artist is 0 bits; ten plays of ten
 * artists is log2(10) ≈ 3.3. The units are meaningful — one bit is a doubling of
 * effective variety — which is why this is worth showing rather than a percentage.
 *
 * Computed over the top-N artists the page already has rather than the whole table,
 * so it is a lower bound: a long tail can only add to it. Worth knowing when reading
 * the number, and worth stating rather than pretending otherwise.
 */
internal fun artistEntropy(artists: List<ArtistPlayCount>): Float {
    val total = artists.sumOf { it.plays }
    if (total <= 0) return 0f
    var h = 0.0
    for (a in artists) {
        val p = a.plays.toDouble() / total
        if (p > 0.0) h -= p * ln(p)
    }
    return (h / LN2).toFloat()
}

/**
 * The longest run of consecutive days with at least one play.
 *
 * Days come from SQL as `yyyy-MM-dd` in the local zone, so consecutive-ness is a
 * question about the listener's calendar rather than about elapsed hours — which is
 * what makes "I listened every day" mean what it sounds like across a clock change.
 */
internal fun longestStreak(days: List<String>): Int = streaks(days).maxOrNull() ?: 0

/** The run that is still going, or 0 if there was no play today or yesterday. */
internal fun currentStreak(days: List<String>): Int {
    if (days.isEmpty()) return 0
    val sorted = days.distinct().sorted()
    val last = sorted.last()
    val today = java.time.LocalDate.now()
    val lastDate = runCatching { java.time.LocalDate.parse(last) }.getOrNull() ?: return 0
    // Yesterday still counts: a streak should not read as broken first thing in the
    // morning, before the day has had a chance to have any music in it.
    if (java.time.temporal.ChronoUnit.DAYS.between(lastDate, today) > 1) return 0
    return streaks(days).lastOrNull() ?: 0
}

private fun streaks(days: List<String>): List<Int> {
    val dates = days.distinct().mapNotNull { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }.sorted()
    if (dates.isEmpty()) return emptyList()
    val out = ArrayList<Int>()
    var run = 1
    for (i in 1 until dates.size) {
        if (java.time.temporal.ChronoUnit.DAYS.between(dates[i - 1], dates[i]) == 1L) run++
        else { out.add(run); run = 1 }
    }
    out.add(run)
    return out
}

/**
 * The median share of a track that was actually heard.
 *
 * Median rather than mean, because the distribution is not symmetric: most plays run to
 * the end and a few are skipped early, and a mean would let a handful of ten-second
 * samples drag the whole figure down. Capped at 1 — a play can be logged fractionally
 * past the end when the position poll lands late, and 103% is not a fact about anything.
 */
internal fun medianCompletion(rows: List<com.engabd.sendpin.local.db.Completion>): Float? {
    if (rows.isEmpty()) return null
    val shares = rows.map { (it.playedMs.toFloat() / it.durationMs).coerceIn(0f, 1f) }.sorted()
    val mid = shares.size / 2
    return if (shares.size % 2 == 1) shares[mid] else (shares[mid - 1] + shares[mid]) / 2f
}

/**
 * Share of plays that were lossless, by codec name.
 *
 * By play count, not by time — the format breakdown is what is on hand, and a listener
 * asking "how much of this was lossless" is asking about their library rather than
 * about minutes. Null when nothing recorded a codec at all, which is different from
 * zero and should not be shown as 0%.
 */
internal fun losslessShare(formats: List<FormatPlayCount>): Float? {
    val known = formats.filter { !it.codec.isNullOrBlank() }
    val total = known.sumOf { it.plays }
    if (total == 0) return null
    val lossless = known.filter { it.codec!!.lowercase() in LOSSLESS }.sumOf { it.plays }
    return lossless.toFloat() / total
}

private val LOSSLESS = setOf("flac", "alac", "wav", "aiff", "pcm", "ape", "wavpack", "dsd")

/** Day-of-week labels for the listening clock. Index matches SQLite's `%w`, 0 = Sunday. */
val DOW_LABELS = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

/** "68%" from a 0..1 share. */
fun formatShare(v: Float): String = "${(v * 100).roundToInt()}%"
