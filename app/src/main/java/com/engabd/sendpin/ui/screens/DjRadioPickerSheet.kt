package com.engabd.sendpin.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.engabd.sendpin.audio.DjMood
import com.engabd.sendpin.ma.LibraryViewModel
import com.engabd.sendpin.ui.design.HideBottomChrome
import com.engabd.sendpin.ui.design.LocalAccent
import com.engabd.sendpin.ui.design.a
import com.engabd.sendpin.ui.design.dismissOnDragDown
import com.engabd.sendpin.ui.design.rememberArtRequest
import com.engabd.sendpin.ui.design.systemNavInset
import com.engabd.sendpin.ui.theme.AppFont
import com.engabd.sendpin.ui.theme.Glass
import com.engabd.sendpin.ui.theme.Hairline
import com.engabd.sendpin.ui.theme.Ink
import com.engabd.sendpin.ui.theme.Ink2
import com.engabd.sendpin.ui.theme.Ink3
import com.engabd.sendpin.ui.theme.MonoFont
import com.engabd.sendpin.ui.theme.TextFaint
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextPrimary

/**
 * What a DJ set should start from, asked before it starts.
 *
 * The button used to answer this itself, with whatever a random page handed back
 * first — and then answer it the same way again on the next press, because a set
 * that was already loaded was simply carried on rather than restarted. A set is
 * defined by the track it is seeded from and by the brief it is held to, and both
 * of those are things the listener knows and no amount of analysis does.
 *
 * So: six songs, chosen to be as unlike each other as the library allows (see
 * [com.engabd.sendpin.audio.DjSeeds]), and a row of briefs above them — the time
 * of day and the thing you are about to do, which is the other way people reach
 * for music. Picking a brief refills the six from what answers it, so the two
 * halves are one choice rather than two.
 *
 * Drawn as a sheet inside the caller's root `Box`, the same way
 * [MediaActionsSheet] and the player sheets are, rather than as a
 * `ModalBottomSheet` — so it matches, and so it cannot take window focus and put
 * the activity through a resize.
 */
@Composable
fun BoxScope.DjRadioPickerSheet(
    state: LibraryViewModel.DjPicker,
    onClose: () -> Unit,
    onMood: (DjMood) -> Unit,
    onReroll: () -> Unit,
    /** Start from this song, under the brief currently selected. */
    onPick: (com.engabd.sendpin.ma.MaItem) -> Unit,
    /** Start with no seed at all — the old one-tap behaviour, kept as a choice. */
    onSurprise: () -> Unit,
) {
    HideBottomChrome()
    BackHandler(onBack = onClose)
    val accent = LocalAccent.current

    Box(
        Modifier
            .matchParentSize()
            .background(Ink.copy(alpha = 0.62f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClose() }
    )

    val shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .dismissOnDragDown(onClose)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { }
            .clip(shape)
            .background(Ink2)
            .border(1.dp, Hairline, shape),
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = systemNavInset() + 14.dp)) {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(100)).background(Hairline))
            }

            Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Default.GraphicEq, null, tint = accent, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Start the set from",
                        color = TextPrimary, fontFamily = AppFont,
                        fontWeight = FontWeight.ExtraBold, fontSize = 16.sp,
                    )
                    Text(
                        state.mood.blurb,
                        color = TextMuted, fontFamily = AppFont, fontSize = 11.5.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                // Six more of the same brief. The one control that admits none of
                // these six is the one — which, on a library of any size, is most
                // of the time.
                IconChip(Icons.Default.Refresh, "Six more", accent, onReroll)
            }

            // The briefs. Horizontally scrolled rather than wrapped: they are a
            // single ranked row and wrapping them turns a row into a paragraph.
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DjMood.entries.forEach { mood ->
                    MoodChip(mood, selected = mood == state.mood, accent = accent) { onMood(mood) }
                }
            }

            Box(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 380.dp)) {
                if (state.loading && state.seeds.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = accent, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                    }
                } else if (state.seeds.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "Nothing to start a set from yet.",
                            color = TextMuted, fontFamily = AppFont, fontSize = 13.sp,
                        )
                    }
                } else {
                    Column(
                        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        state.seeds.forEach { seed ->
                            SeedRow(seed, accent, dimmed = state.loading) { onPick(seed.item) }
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            // The old behaviour, kept and named. Someone who does not want to
            // choose should not have to, and hiding that behind a dismiss would
            // make the overlay a toll on the one-tap button it replaced.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(accent.a(0.16f))
                    .border(1.dp, accent.a(0.34f), RoundedCornerShape(14.dp))
                    .clickable { onSurprise() }
                    .padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Default.Casino, null, tint = accent, modifier = Modifier.size(18.dp))
                Text(
                    if (state.mood.open) "Just start something" else "Just start something · ${state.mood.title}",
                    color = TextPrimary, fontFamily = AppFont,
                    fontWeight = FontWeight.Bold, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** One of the six: art, title, artist, and the line that says why it is different. */
@Composable
private fun SeedRow(
    seed: LibraryViewModel.DjSeed,
    accent: Color,
    dimmed: Boolean,
    onClick: () -> Unit,
) {
    val item = seed.item
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .clip(shape)
            .background(Glass)
            .border(1.dp, Hairline, shape)
            .clickable(enabled = !dimmed, onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(46.dp).clip(RoundedCornerShape(9.dp)).background(Ink3),
            contentAlignment = Alignment.Center,
        ) {
            val art = rememberArtRequest(item.image, pixels = 160)
            if (art != null) {
                AsyncImage(
                    model = art, contentDescription = null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(Icons.Default.Album, null, tint = TextFaint, modifier = Modifier.size(18.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                item.name, color = TextPrimary, fontFamily = AppFont,
                fontWeight = FontWeight.Bold, fontSize = 13.5.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            item.subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it, color = TextMuted, fontFamily = AppFont, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            // Genre, tempo, how hard it goes — the three things that make these six
            // six different songs rather than six titles. Mono, because two of the
            // three are numbers and they should line up down the list.
            seed.note?.let {
                Text(
                    it, color = accent.a(0.85f), fontFamily = MonoFont, fontSize = 9.5.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** One brief. Lit when it is the one the six were chosen under. */
@Composable
private fun MoodChip(mood: DjMood, selected: Boolean, accent: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .clip(shape)
            .background(if (selected) accent.a(0.22f) else Glass)
            .border(1.dp, if (selected) accent.a(0.5f) else Hairline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 8.dp),
    ) {
        Text(
            mood.title,
            color = if (selected) TextPrimary else TextMuted,
            fontFamily = AppFont,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            fontSize = 12.sp,
            maxLines = 1,
        )
    }
}

/** A round icon button, for the one action in the sheet's header. */
@Composable
private fun IconChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    accent: Color,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(50))
            .background(accent.a(0.16f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = accent, modifier = Modifier.size(17.dp))
    }
}
