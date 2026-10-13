package com.engabd.sendpin.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engabd.sendpin.ui.design.a
import com.engabd.sendpin.ui.screens.settings.SettingsCard
import com.engabd.sendpin.ui.screens.settings.StatusPanel
import com.engabd.sendpin.ui.screens.settings.StatusRow
import com.engabd.sendpin.ui.theme.AppFont
import com.engabd.sendpin.ui.theme.Glass
import com.engabd.sendpin.ui.theme.MonoFont
import com.engabd.sendpin.ui.theme.TextFaint
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextPrimary
import com.engabd.sendpin.ui.theme.TextSecondary
import com.engabd.sendpin.ui.viewmodel.MONTH_LABELS
import com.engabd.sendpin.ui.viewmodel.StatsViewModel
import com.engabd.sendpin.ui.viewmodel.formatListeningTime

/**
 * The year in review: one calendar year's shape by month, its favourite songs
 * and albums, and the month and day it peaked. Shown above the rest of the
 * stats when a year pill is picked; every other card below it then covers the
 * same year, because the window they read is the year's.
 */
@Composable
internal fun YearReviewCard(review: StatsViewModel.YearReview, accent: Color) {
    SettingsCard(
        title = if (review.finished) "${review.year} in review" else "${review.year} so far",
        lead = "The year by month, and what it kept coming back to.",
    ) {
        MonthBars(review, accent)

        if (review.busiestMonth != null || review.biggestDay != null) {
            StatusPanel {
                review.busiestMonth?.let { m ->
                    val month = review.months[m - 1]
                    StatusRow("Busiest month", "${MONTH_LABELS[m - 1]} · ${formatListeningTime(month.listenedMs)}")
                }
                review.biggestDay?.let { d ->
                    StatusRow("Biggest day", "${prettyDay(d.day)} · ${formatListeningTime(d.listenedMs)}")
                }
            }
        }

        if (review.topTracks.isNotEmpty()) {
            Ranked("Top songs", review.topTracks.map { Triple(it.title, it.artist, it.plays) }, accent)
        }
        if (review.topAlbums.isNotEmpty()) {
            Ranked("Top albums", review.topAlbums.map { Triple(it.album, it.artist, it.plays) }, accent)
        }
    }
}

/** Twelve bars, January to December, by listening time. */
@Composable
private fun MonthBars(review: StatsViewModel.YearReview, accent: Color) {
    val peak = (review.months.maxOfOrNull { it.listenedMs } ?: 0L).coerceAtLeast(1L)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth().height(80.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            review.months.forEach { m ->
                val frac = m.listenedMs.toFloat() / peak
                Box(
                    Modifier.weight(1f).fillMaxHeight(frac.coerceIn(0.03f, 1f))
                        .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                        // An empty month stays visible as a stub, so a gap reads as
                        // "nothing that month" rather than as a missing bar.
                        .background(if (m.listenedMs <= 0L) Glass else accent.a(0.35f + 0.65f * frac))
                        .semantics {
                            contentDescription =
                                "${MONTH_LABELS[m.month - 1]}: ${formatListeningTime(m.listenedMs)}, ${m.plays} plays"
                        },
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            MONTH_LABELS.forEach {
                Text(
                    it.take(1), color = TextFaint, fontFamily = MonoFont, fontSize = 9.sp,
                    modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

/** A numbered list: title, who it is by, and how often it was played. */
@Composable
private fun Ranked(heading: String, rows: List<Triple<String, String, Int>>, accent: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(heading, color = TextMuted, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        rows.forEachIndexed { i, (title, by, plays) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${i + 1}", color = accent, fontFamily = MonoFont, fontWeight = FontWeight.Bold,
                    fontSize = 13.sp, modifier = Modifier.width(22.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        title, color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.Bold,
                        fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (by.isNotBlank()) {
                        Text(
                            by, color = TextSecondary, fontFamily = AppFont, fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text("${plays}x", color = TextMuted, fontFamily = MonoFont, fontSize = 12.sp)
            }
        }
    }
}

/** "2026-03-14" as "14 Mar". Falls back to the raw string for anything unparseable. */
private fun prettyDay(day: String): String = runCatching {
    val d = java.time.LocalDate.parse(day)
    "${d.dayOfMonth} ${MONTH_LABELS[d.monthValue - 1]}"
}.getOrDefault(day)
