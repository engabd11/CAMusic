package com.engabd.sendpin.ui.screens

import androidx.compose.ui.graphics.rememberGraphicsLayer
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.engabd.sendpin.ui.screens.settings.OledButton
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.engabd.sendpin.local.db.DayTotals
import com.engabd.sendpin.local.db.HourCell
import com.engabd.sendpin.local.db.TempoEnergyPoint
import com.engabd.sendpin.ui.design.AmbientRain
import com.engabd.sendpin.ui.design.Bloom
import com.engabd.sendpin.ui.design.CircleIconButton
import com.engabd.sendpin.ui.design.LocalAccent
import com.engabd.sendpin.ui.design.Pill
import com.engabd.sendpin.ui.design.TitleGap
import com.engabd.sendpin.ui.design.a
import com.engabd.sendpin.ui.design.navBarInset
import com.engabd.sendpin.ui.screens.settings.Note
import com.engabd.sendpin.ui.screens.settings.SettingsCard
import com.engabd.sendpin.ui.screens.settings.StatusPanel
import com.engabd.sendpin.ui.screens.settings.StatusRow
import com.engabd.sendpin.ui.theme.*
import com.engabd.sendpin.ui.viewmodel.DOW_LABELS
import com.engabd.sendpin.ui.viewmodel.StatsViewModel
import com.engabd.sendpin.ui.viewmodel.StatsWindow
import com.engabd.sendpin.ui.viewmodel.formatListeningTime
import com.engabd.sendpin.ui.viewmodel.formatShare
import com.engabd.sendpin.ui.viewmodel.label

/**
 * "Your listening" — what the history actually supports being asked.
 *
 * Every chart here is a Compose `Canvas` or a stack of boxes; no charting library. The
 * shapes are a heatmap, a scatter and some bars, all of which are a handful of lines
 * each, and a dependency for that would be more code than the code.
 *
 * The window is a picker rather than a constant, and that is not cosmetic: a listening
 * habit is something noticed over months. The streak, the variety figure and the
 * new-artist count all say something different at ninety days than at seven.
 */
@Composable
fun StatsScreen(onBack: () -> Unit = {}, viewModel: StatsViewModel = viewModel()) {
    val accent = LocalAccent.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize().background(Ink)) {
        Bloom(accent, 420.dp, (-60).dp, (-56).dp, 0.4f)
        AmbientRain(Modifier.fillMaxSize())

        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
                    Text("Your listening", color = TextPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                    Text(
                        windowSubtitle(state),
                        color = TextMuted, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(start = 18.dp, end = 18.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatsWindow.entries.forEach { w ->
                    Pill(w.label, w == state.window) { viewModel.setWindow(w) }
                }
            }

            if (state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Loading…", color = TextMuted, fontFamily = AppFont)
                }
                return@Column
            }

            if (state.totalListeningMs == 0L && state.topArtists.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Nothing logged in this window. Play something for a while and check back.",
                        color = TextMuted, fontFamily = AppFont,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
                return@Column
            }

            LazyColumn(
                Modifier.fillMaxSize(),
                // [navBarInset] like every other standalone screen. Without it the last
                // card sat under the nav bar — and, in the overlay layout, under the
                // mini player, which is 128.dp of it.
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = 8.dp, bottom = navBarInset() + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // The recap, first: it is the one thing on this screen someone would
                // want to show another person, and burying a share action under nine
                // cards of charts is how it never gets found.
                if (state.plays > 0) {
                    item(key = "recap", contentType = "card") { RecapCard(state) }
                }

                item(key = "overview", contentType = "card") {
                    SettingsCard(title = "Overview") {
                        StatusPanel {
                            StatusRow("Total listening", formatListeningTime(state.totalListeningMs))
                            StatusRow("Plays", "${state.plays}")
                            StatusRow("Artists", "${state.distinctArtists}")
                            if (state.newArtists > 0) StatusRow("New to you", "${state.newArtists}")
                            state.mostPlayed?.let { StatusRow("Most played", "${it.title} (${it.plays}x)") }
                            state.medianCompletion?.let {
                                StatusRow("Typical track finished", formatShare(it))
                            }
                            state.losslessShare?.let { StatusRow("Lossless", formatShare(it)) }
                            if (state.longestStreakDays > 0) {
                                StatusRow("Longest streak", plural(state.longestStreakDays, "day"))
                            }
                            if (state.currentStreakDays > 0) {
                                StatusRow("Current streak", plural(state.currentStreakDays, "day"))
                            }
                        }
                    }
                }

                if (state.clock.isNotEmpty()) {
                    item(key = "clock", contentType = "card") {
                        SettingsCard(
                            title = "Listening clock",
                            lead = "When the music actually happens.",
                        ) { ListeningClock(state.clock, accent) }
                    }
                }

                if (state.distinctArtists > 1) {
                    item(key = "variety", contentType = "card") {
                        SettingsCard(
                            title = "Variety",
                            lead = "How spread out the listening was, not how much of it there was.",
                            info = VARIETY_INFO,
                        ) { EntropyMeter(state.diversityBits, state.maxDiversityBits, accent) }
                    }
                }

                if (state.tempoEnergy.size >= 4) {
                    item(key = "tempo", contentType = "card") {
                        SettingsCard(
                            title = "Tempo and energy",
                            lead = "One dot per scanned track. Warm is major, cool is minor.",
                        ) { TempoEnergyScatter(state.tempoEnergy, accent) }
                    }
                }

                if (state.daily.size >= 2) {
                    item(key = "daily", contentType = "card") {
                        SettingsCard(title = "Day by day") { DailyBars(state.daily, accent) }
                    }
                }

                if (state.topArtists.isNotEmpty()) {
                    item(key = "artists", contentType = "card") {
                        SettingsCard(title = "Top artists") {
                            RankedBars(state.topArtists.map { it.artist to it.plays }, accent)
                        }
                    }
                }

                if (state.sources.isNotEmpty()) {
                    item(key = "sources", contentType = "card") {
                        SettingsCard(
                            title = "Where it came from",
                            lead = "The provider the bytes were streamed from, not just the backend.",
                        ) { RankedBars(state.sources.map { it.provider to it.plays }, accent) }
                    }
                }

                if (state.timeBySource.size > 1) {
                    item(key = "time_by_source", contentType = "card") {
                        SettingsCard(
                            title = "Time by source",
                            lead = "The same thing by minutes - a long album is not one play.",
                        ) {
                            RankedBars(
                                state.timeBySource.map {
                                    it.provider to (it.plays / 60_000).coerceAtLeast(0)
                                },
                                accent, unit = "m",
                            )
                        }
                    }
                }

                if (state.formats.isNotEmpty()) {
                    item(key = "formats", contentType = "card") {
                        SettingsCard(title = "By format") {
                            RankedBars(state.formats.map { it.label() to it.plays }, accent)
                        }
                    }
                }

                if (state.dominantKeys.isNotEmpty()) {
                    item(key = "keys", contentType = "card") {
                        SettingsCard(title = "Dominant keys") {
                            RankedBars(state.dominantKeys, accent)
                        }
                    }
                }

                if (state.bpmHistogram.isNotEmpty()) {
                    item(key = "bpm", contentType = "card") {
                        SettingsCard(title = "BPM sweet spot") {
                            RankedBars(state.bpmHistogram, accent)
                        }
                    }
                }

                item(key = "note_counting", contentType = "note") {
                    Note(
                        "Counts a track once it is roughly half played, or after four minutes. " +
                            "That is the same rule the scrobbler uses, so a skip does not count.",
                    )
                }
                if (state.dominantKeys.isEmpty() && state.bpmHistogram.isEmpty()) {
                    item(key = "note_dna", contentType = "note") {
                        Note(
                            "Key, tempo and energy need Listening DNA turned on in Appearance " +
                                "settings, and tracks that have been scanned.",
                        )
                    }
                }
            }
        }
    }
}

private const val VARIETY_INFO =
    "Shannon entropy over how often each artist came up, in bits. Everything by one " +
        "artist is 0 bits; an even spread across eight is 3. One bit is a doubling of " +
        "effective variety, which is why it reads in bits rather than as a percentage." +
        "\n\nMeasured over the top artists listed below, so it is a floor - a long tail " +
        "of one-off plays can only push it higher."

private fun plural(n: Int, noun: String) = "$n $noun" + if (n == 1) "" else "s"

@Composable
private fun windowSubtitle(state: StatsViewModel.State): String {
    val earliest = state.earliest
    return if (state.window == StatsWindow.ALL && earliest != null) {
        "Since " + java.time.Instant.ofEpochMilli(earliest)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    } else {
        state.window.label
    }
}

/**
 * Hour-of-day against day-of-week, as a heatmap.
 *
 * `timestamp` has been written to every row since the table existed and never once been
 * read. It is the most personal thing in here — it says whether music is a commute, an
 * evening, or something that happens at 2am — and it cost one `strftime` to surface.
 *
 * Seven rows of twenty-four, sized by weight rather than fixed dp so it fits a phone and
 * a tablet without a second layout.
 */
@Composable
private fun ListeningClock(cells: List<HourCell>, accent: Color) {
    val grid = remember(cells) {
        val m = Array(7) { IntArray(24) }
        cells.forEach { c ->
            if (c.dow in 0..6 && c.hour in 0..23) m[c.dow][c.hour] = c.plays
        }
        m
    }
    val peak = remember(grid) { grid.maxOf { row -> row.maxOrNull() ?: 0 }.coerceAtLeast(1) }

    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for (d in 0..6) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    DOW_LABELS[d], color = TextFaint, fontFamily = MonoFont, fontSize = 9.sp,
                    modifier = Modifier.width(26.dp),
                )
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    for (h in 0..23) {
                        val v = grid[d][h].toFloat() / peak
                        Box(
                            Modifier.weight(1f).height(13.dp).clip(RoundedCornerShape(2.dp))
                                // Never fully transparent: an empty hour should read as
                                // "nothing here", not as a gap in the chart.
                                .background(if (v <= 0f) Glass else accent.a(0.18f + 0.82f * v)),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Row(Modifier.fillMaxWidth().padding(start = 30.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("00", "06", "12", "18", "23").forEach {
                Text(it, color = TextFaint, fontFamily = MonoFont, fontSize = 9.sp)
            }
        }
    }
}

/** Variety in bits, against the most this listening could have had. */
@Composable
private fun EntropyMeter(bits: Float, maxBits: Float, accent: Color) {
    val frac = if (maxBits > 0f) (bits / maxBits).coerceIn(0f, 1f) else 0f
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "%.2f bits".format(bits),
                color = TextPrimary, fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 15.sp,
            )
            Text(
                "of %.2f possible".format(maxBits),
                color = TextMuted, fontFamily = MonoFont, fontSize = 11.sp,
            )
        }
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Glass)) {
            Box(
                Modifier.fillMaxWidth(frac).fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp)).background(accent),
            )
        }
        Text(
            when {
                frac >= 0.9f -> "Very even - almost no artist dominates."
                frac >= 0.7f -> "Broad, with a few favourites."
                frac >= 0.4f -> "A handful of artists carry most of it."
                else -> "Concentrated on a small number of artists."
            },
            color = TextFaint, fontSize = 11.sp,
        )
    }
}

/**
 * Every scanned track as a dot: tempo across, energy up.
 *
 * The one chart here that shows a *shape* rather than a ranking — whether the listening
 * clusters somewhere, splits in two, or spreads out. Colour carries mode, so a wall of
 * cool dots at low energy reads differently from a warm one.
 */
@Composable
private fun TempoEnergyScatter(points: List<TempoEnergyPoint>, accent: Color) {
    val bounds = remember(points) {
        val lo = (points.minOf { it.bpm } - 5f).coerceAtLeast(0f)
        val hi = (points.maxOf { it.bpm } + 5f).coerceAtLeast(lo + 1f)
        lo to hi
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            val (lo, hi) = bounds
            // Faint horizontal rules at quarter energy, so "high" and "low" have
            // somewhere to be read against rather than being a cloud in a box.
            for (i in 1..3) {
                val y = size.height * i / 4f
                drawLine(
                    color = Color.White.copy(alpha = 0.05f),
                    start = androidx.compose.ui.geometry.Offset(0f, y),
                    end = androidx.compose.ui.geometry.Offset(size.width, y),
                    strokeWidth = 1f,
                )
            }
            points.forEach { p ->
                val x = ((p.bpm - lo) / (hi - lo)).coerceIn(0f, 1f) * size.width
                val y = size.height - p.energy.coerceIn(0f, 1f) * size.height
                val warm = p.keyMode == "MAJOR"
                drawCircle(
                    color = if (warm) accent.copy(alpha = 0.75f) else Color(0xFF5AC8FA).copy(alpha = 0.65f),
                    radius = 4.5f,
                    center = androidx.compose.ui.geometry.Offset(x, y),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${bounds.first.toInt()} BPM", color = TextFaint, fontFamily = MonoFont, fontSize = 9.sp)
            Text("energy up the side", color = TextFaint, fontSize = 9.sp)
            Text("${bounds.second.toInt()} BPM", color = TextFaint, fontFamily = MonoFont, fontSize = 9.sp)
        }
    }
}

/** Minutes per day, oldest first. The shape of a habit rather than a total. */
@Composable
private fun DailyBars(daily: List<DayTotals>, accent: Color) {
    val peak = remember(daily) { (daily.maxOfOrNull { it.listenedMs } ?: 1L).coerceAtLeast(1L) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth().height(70.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            // Trailing window: a ninety-day view has too many days to draw one bar each
            // on a phone, and the recent ones are the ones being asked about.
            daily.takeLast(45).forEach { d ->
                val frac = (d.listenedMs.toFloat() / peak).coerceIn(0.02f, 1f)
                Box(
                    Modifier.weight(1f).fillMaxHeight(frac)
                        .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                        .background(accent.a(0.35f + 0.65f * frac)),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(daily.takeLast(45).first().day, color = TextFaint, fontFamily = MonoFont, fontSize = 9.sp)
            Text(
                "peak ${formatListeningTime(peak)}",
                color = TextFaint, fontFamily = MonoFont, fontSize = 9.sp,
            )
            Text(daily.last().day, color = TextFaint, fontFamily = MonoFont, fontSize = 9.sp)
        }
    }
}

/** A simple horizontal-bar ranking — still the right shape for a top-N list. */
@Composable
private fun RankedBars(items: List<Pair<String, Int>>, accent: Color, unit: String = "") {
    val max = (items.maxOfOrNull { it.second } ?: 1).coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.take(8).forEach { (label, count) ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        label, color = TextSecondary, fontFamily = AppFont, fontSize = 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    Text("$count$unit", color = TextMuted, fontFamily = MonoFont, fontSize = 12.sp)
                }
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Glass)) {
                    Box(
                        Modifier.fillMaxWidth(count.toFloat() / max).fillMaxHeight()
                            .clip(RoundedCornerShape(3.dp)).background(accent),
                    )
                }
            }
        }
    }
}

/**
 * The shareable recap, and the button that shares it.
 *
 * The poster is composed for real rather than drawn twice into an offscreen
 * canvas: what is captured is the layer this composition already recorded, so the
 * picture that leaves is exactly the one on screen. That also means it can only be
 * shared once it has been drawn, which is why the button reports rather than
 * silently doing nothing when the layer is still empty.
 */
@Composable
private fun RecapCard(state: com.engabd.sendpin.ui.viewmodel.StatsViewModel.State) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val accent = com.engabd.sendpin.ui.design.LocalAccent.current
    val scope = rememberCoroutineScope()
    val layer = rememberGraphicsLayer()
    var status by remember { mutableStateOf<String?>(null) }

    SettingsCard(
        title = "Your recap",
        lead = "A picture of what you have been listening to, worked out on this phone.",
        info = "Everything on it comes from this device: the play history it keeps, and " +
            "the key and tempo its own offline scanner measured. Nothing was logged by a " +
            "service and nothing is uploaded by sharing it - the share sheet hands one " +
            "read-only picture to whichever app you pick, and that is the whole of " +
            "it.\n\nThe key and tempo lines need Listening DNA switched on under " +
            "Playback & Behavior; without it the rest still works.\n\nTip: change the " +
            "window at the top of this screen and the recap follows it.",
    ) {
        RecapPoster(state = state, layer = layer, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        OledButton("Share this", accent = accent, outline = true) {
            scope.launch {
                val bitmap = layer.toShareableBitmap()
                status = if (bitmap == null) "Give it a moment to draw, then try again"
                else shareRecap(context, bitmap)
            }
        }
        status?.let { Spacer(Modifier.height(6.dp)); Note(it, warn = true) }
    }
}
