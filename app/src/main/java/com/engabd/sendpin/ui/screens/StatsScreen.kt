package com.engabd.sendpin.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.engabd.sendpin.ui.design.Bloom
import com.engabd.sendpin.ui.design.CircleIconButton
import com.engabd.sendpin.ui.design.LocalAccent
import com.engabd.sendpin.ui.design.navBarInset
import com.engabd.sendpin.ui.design.TitleGap
import com.engabd.sendpin.ui.screens.settings.Note
import com.engabd.sendpin.ui.screens.settings.SettingsCard
import com.engabd.sendpin.ui.screens.settings.StatusPanel
import com.engabd.sendpin.ui.screens.settings.StatusRow
import com.engabd.sendpin.ui.theme.*
import com.engabd.sendpin.ui.viewmodel.StatsViewModel
import com.engabd.sendpin.ui.viewmodel.formatListeningTime
import com.engabd.sendpin.ui.viewmodel.label

/**
 * "Your Listening", last 7 days — a fixed window rather than a picker for v1.
 * Plain Compose rows and bars, no charting library: the data is a handful of
 * grouped counts, not something that earns one.
 */
@Composable
fun StatsScreen(onBack: () -> Unit = {}, viewModel: StatsViewModel = viewModel()) {
    val accent = LocalAccent.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize().background(Ink)) {
        Bloom(accent, 420.dp, (-60).dp, (-56).dp, 0.4f)

        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
                    Text("Your listening", color = TextPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                    Text("Last 7 days", color = TextMuted, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                }
            }

            if (state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Loading…", color = TextMuted, fontFamily = AppFont)
                }
                return@Column
            }

            val nothingYet = state.totalListeningMs == 0L && state.topArtists.isEmpty()
            if (nothingYet) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Nothing logged yet. Play something for a while and check back.",
                        color = TextMuted, fontFamily = AppFont, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
                return@Column
            }

            LazyColumn(
                Modifier.fillMaxSize(),
                // [navBarInset] like every other standalone screen. Without it the
                // last card sat under the nav bar — and, in the overlay layout, under
                // the mini player, which is 128.dp of it.
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp,
                    top = 8.dp, bottom = navBarInset() + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    SettingsCard(title = "This week") {
                        StatusPanel {
                            StatusRow("Total listening", formatListeningTime(state.totalListeningMs))
                            state.mostPlayed?.let { StatusRow("Most played", "${it.title} (${it.plays}×)") }
                        }
                    }
                }

                if (state.topArtists.isNotEmpty()) {
                    item {
                        SettingsCard(title = "Top artists") {
                            RankedBars(
                                items = state.topArtists.map { it.artist to it.plays },
                                accent = accent,
                            )
                        }
                    }
                }

                if (state.providers.isNotEmpty()) {
                    item {
                        SettingsCard(title = "By server") {
                            RankedBars(
                                items = state.providers.map { it.provider to it.plays },
                                accent = accent,
                            )
                        }
                    }
                }

                if (state.formats.isNotEmpty()) {
                    item {
                        SettingsCard(title = "By format") {
                            RankedBars(
                                items = state.formats.map { it.label() to it.plays },
                                accent = accent,
                            )
                        }
                    }
                }

                if (state.dominantKeys.isNotEmpty()) {
                    item {
                        SettingsCard(title = "Dominant keys") {
                            RankedBars(items = state.dominantKeys, accent = accent)
                        }
                    }
                }

                if (state.bpmHistogram.isNotEmpty()) {
                    item {
                        SettingsCard(title = "BPM sweet spot") {
                            RankedBars(items = state.bpmHistogram, accent = accent)
                        }
                    }
                }

                item { Note("Counts a track once it's roughly half played, or after four minutes. That is the same rule the scrobbler uses, so a skip doesn't count.") }
                if (state.dominantKeys.isEmpty() && state.bpmHistogram.isEmpty()) {
                    item {
                        Note(
                            "Key and tempo stats need Listening DNA turned on in Appearance " +
                                "settings, and tracks that have been scanned.",
                        )
                    }
                }
            }
        }
    }
}

/** A simple horizontal-bar ranking — the whole "chart" this screen needs. */
@Composable
private fun RankedBars(items: List<Pair<String, Int>>, accent: androidx.compose.ui.graphics.Color) {
    val max = (items.maxOfOrNull { it.second } ?: 1).coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.take(8).forEach { (label, count) ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        label, color = TextSecondary, fontFamily = AppFont, fontSize = 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    Text("$count", color = TextMuted, fontFamily = MonoFont, fontSize = 12.sp)
                }
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Glass)) {
                    Box(
                        Modifier
                            .fillMaxWidth(count.toFloat() / max)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(3.dp))
                            .background(accent),
                    )
                }
            }
        }
    }
}
