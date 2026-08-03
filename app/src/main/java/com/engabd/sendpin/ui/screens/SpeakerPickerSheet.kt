package com.engabd.sendpin.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.engabd.sendpin.ui.design.HSlider
import com.engabd.sendpin.ui.design.LocalAccent
import com.engabd.sendpin.ui.design.a
import com.engabd.sendpin.ui.design.HideBottomChrome
import com.engabd.sendpin.ui.design.dismissOnDragDown
import com.engabd.sendpin.ui.design.systemNavInset
import com.engabd.sendpin.ui.theme.*
import com.engabd.sendpin.ui.viewmodel.SpeakersViewModel

/**
 * "Play on" — the quick output picker behind the speaker chip at the top of Now
 * Playing.
 *
 * The chip used to navigate to the Speakers tab, which collapsed the player and
 * switched screens to do something the user wanted done *without* leaving what they
 * were looking at (and on the Navidrome backend the Speakers route is disabled, so it
 * did nothing at all). This does the two things that need to be one tap away —
 * switch output, change that speaker's volume — and nothing else. Grouping and sync
 * offsets stay on the Speakers tab; a second way in from here was just another layer
 * to read past.
 *
 * Not a `ModalBottomSheet`, for the same reason `NowPlayingSheet` isn't: it is drawn
 * inside the screen's own Box so the album wash reads through the top edge, which a
 * Material scrim would flatten.
 */
@Composable
fun BoxScope.SpeakerPickerSheet(
    onClose: () -> Unit,
    viewModel: SpeakersViewModel = viewModel(),
) {
    HideBottomChrome()
    val accent = LocalAccent.current
    val joined by viewModel.joined.collectAsState()
    val available by viewModel.available.collectAsState()
    val connected by viewModel.connected.collectAsState()

    // Joined players first (they're what's actually playing), then everything else.
    // Both lists already carry `isTarget`, so the tick needs no extra bookkeeping.
    val rows = remember(joined, available) { joined + available }

    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .dismissOnDragDown(onClose)
            // Swallow taps so a miss near a row doesn't fall through to the scrim.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { }
            .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
            .background(Ink2)
            .border(1.dp, Hairline, RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)),
    ) {
        // The system inset only: the sheet is drawn over the tab bar and the mini
        // player, and `HideBottomChrome` takes them off screen while it is up.
        Column(Modifier.fillMaxWidth().padding(bottom = systemNavInset() + 12.dp)) {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(100)).background(Hairline))
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 14.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Play on", color = TextPrimary, fontFamily = AppFont,
                    fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(Glass).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.Close, "Close", tint = TextSecondary, modifier = Modifier.size(16.dp)) }
            }

            if (rows.isEmpty()) {
                Text(
                    if (connected) "No players available." else "Not connected to Music Assistant.",
                    color = TextMuted, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 20.dp),
                )
            } else {
                // Capped so a big setup scrolls inside the sheet rather than pushing
                // the whole thing past the top of the screen.
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 340.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(rows, key = { it.id }) { row ->
                        SpeakerPickRow(
                            row = row,
                            onSelect = { viewModel.selectPlayer(row.id) },
                            onVolume = { viewModel.setPlayerVolume(row.id, it) },
                        )
                    }
                }
            }

        }
    }
}

@Composable
private fun SpeakerPickRow(
    row: SpeakersViewModel.SpeakerUi,
    onSelect: () -> Unit,
    onVolume: (Float) -> Unit,
) {
    val accent = LocalAccent.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (row.isTarget) accent.a(0.10f) else Ink3)
            .border(
                1.dp,
                if (row.isTarget) accent.a(0.35f) else Hairline,
                RoundedCornerShape(14.dp),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            // Only the name row switches output; the slider below has to stay
            // draggable without also re-targeting playback.
            Modifier.fillMaxWidth().clickable(onClick = onSelect),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                if (row.isSelf) Icons.Default.Smartphone else Icons.Default.Speaker, null,
                tint = if (row.isTarget) accent else TextMuted, modifier = Modifier.size(18.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    row.name, color = TextPrimary, fontFamily = AppFont,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (row.meta.isNotBlank()) {
                    Text(
                        row.meta, color = TextFaint, fontFamily = AppFont,
                        fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (row.isTarget) {
                Icon(Icons.Default.Check, "Playing here", tint = accent, modifier = Modifier.size(18.dp))
            }
        }
        HSlider(row.volume, onChange = onVolume, onCommit = onVolume)
    }
}
