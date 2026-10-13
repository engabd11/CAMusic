package com.engabd.sendpin.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.engabd.sendpin.local.db.AlbumPlayCount
import com.engabd.sendpin.local.db.ArtistPlayCount
import com.engabd.sendpin.local.db.DayTotals
import com.engabd.sendpin.local.db.FormatPlayCount
import com.engabd.sendpin.local.db.HourCell
import com.engabd.sendpin.local.db.KeyPlayCount
import com.engabd.sendpin.local.db.LocalMediaDatabase
import com.engabd.sendpin.local.db.MonthTotals
import com.engabd.sendpin.local.db.ProviderPlayCount
import com.engabd.sendpin.local.db.TempoEnergyPoint
import com.engabd.sendpin.local.db.TrackPlayCount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
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

/**
 * A calendar year in the local zone, as the window `[1 January, next 1 January)`.
 * The same local zone the queries' `strftime(..., 'localtime')` uses, so a play
 * at 11pm on New Year's Eve counts for the year its listener was in.
 */
internal fun yearBounds(year: Int, zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> =
    LocalDate.of(year, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli() to
        LocalDate.of(year + 1, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()

/** All twelve months, January first, with a zero row for any month that had no plays. */
internal fun fillMonths(rows: List<MonthTotals>): List<MonthTotals> {
    val byMonth = rows.associateBy { it.month }
    return (1..12).map { byMonth[it] ?: MonthTotals(it, 0, 0L) }
}

/** Short month labels for the year in review, index 0 = January. */
val MONTH_LABELS = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

class StatsViewModel(app: Application) : AndroidViewModel(app) {

    /** What only a calendar year is asked: its shape by month, and its favourites. */
    data class YearReview(
        val year: Int,
        /** Twelve rows, January first; a month with no plays is a zero row. */
        val months: List<MonthTotals>,
        val topTracks: List<TrackPlayCount>,
        val topAlbums: List<AlbumPlayCount>,
        /** The month with the most listening time, 1 = January. Null with no time logged. */
        val busiestMonth: Int?,
        /** The single day with the most listening time. */
        val biggestDay: DayTotals?,
        /** False while the year is still running: the figures are "so far". */
        val finished: Boolean,
    )

    data class State(
        val loading: Boolean = true,
        val window: StatsWindow = StatsWindow.WEEK,
        /** A calendar year being reviewed instead of [window]. */
        val year: Int? = null,
        /** Every year with a play in it, newest first, for the year pills. */
        val years: List<Int> = emptyList(),
        val review: YearReview? = null,
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
        /** The history could not be read; the screen offers a retry. */
        val error: String? = null,
    ) {
        /** What the page is showing: "7 days", "All time" or "2025". */
        val periodLabel: String get() = year?.toString() ?: window.label
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val dao by lazy { LocalMediaDatabase.get(getApplication()).playHistoryDao() }

    init { refresh() }

    fun setWindow(w: StatsWindow) {
        if (w == _state.value.window && _state.value.year == null) return
        _state.value = _state.value.copy(window = w, year = null)
        refresh()
    }

    /** Review one calendar year instead of a rolling window. */
    fun setYear(year: Int) {
        if (year == _state.value.year) return
        _state.value = _state.value.copy(year = year)
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            // A query that throws (a database being migrated, a disk full) used to end
            // this coroutine with the screen stuck on "Loading…" for good - or take the
            // app down with it. It is said instead, with a retry.
            try {
                load()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("Stats", "couldn't read listening history", e)
                _state.value = _state.value.copy(loading = false, error = "Couldn't read your listening history.")
            }
        }
    }

    private suspend fun load() {
        run {
            val window = _state.value.window
            val year = _state.value.year
            _state.value = _state.value.copy(loading = true, error = null)
            val now = System.currentTimeMillis()
            // A rolling window runs to now and beyond (a play logged while this loads
            // still counts); a year is bounded at both ends.
            val (since, until) = if (year != null) yearBounds(year) else window.since(now) to Long.MAX_VALUE

            val artists = dao.topArtists(since, until, limit = 12)
            val daily = dao.dailyTotals(since, until)
            val completions = dao.completions(since, until)
            val formats = dao.formatBreakdown(since, until)
            val distinct = dao.distinctArtists(since, until)
            val plays = dao.playCount(since, until)

            val review = if (year == null) null else {
                val months = fillMonths(dao.monthlyTotals(since, until))
                YearReview(
                    year = year,
                    months = months,
                    topTracks = dao.topTracks(since, until, limit = 5),
                    topAlbums = dao.topAlbums(since, until, limit = 5),
                    busiestMonth = months.filter { it.listenedMs > 0 }.maxByOrNull { it.listenedMs }?.month,
                    biggestDay = daily.filter { it.listenedMs > 0 }.maxByOrNull { it.listenedMs },
                    finished = until <= now,
                )
            }

            _state.value = State(
                loading = false,
                window = window,
                year = year,
                years = dao.yearsWithPlays(),
                review = review,
                topArtists = artists,
                totalListeningMs = dao.totalListeningMs(since, until),
                plays = plays,
                mostPlayed = dao.mostPlayedTrack(since, until),
                formats = formats,
                sources = dao.sourceBreakdown(since, until),
                timeBySource = dao.timeBySource(since, until),
                dominantKeys = dao.keyDistribution(since, until).map { it.label() to it.plays },
                bpmHistogram = bpmHistogram(dao.bpmSamples(since, until)),
                clock = dao.listeningClock(since, until),
                daily = daily,
                tempoEnergy = dao.tempoEnergy(since, until),
                distinctArtists = distinct,
                newArtists = if (since == 0L) distinct else dao.newArtists(since, until),
                diversityBits = artistEntropy(artists),
                maxDiversityBits = if (distinct > 1) ln(distinct.toFloat()) / LN2 else 0f,
                longestStreakDays = longestStreak(daily.map { it.day }),
                // A finished year has no streak still going, whatever its last days were.
                currentStreakDays = if (until > now) currentStreak(daily.map { it.day }) else 0,
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
