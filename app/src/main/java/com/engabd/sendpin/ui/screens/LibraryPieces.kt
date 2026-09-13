package com.engabd.sendpin.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engabd.sendpin.ui.design.*
import com.engabd.sendpin.ui.theme.*

/**
 * Small shared pieces of the library grid: section labels, the new-playlist
 * affordance, and the create-playlist dialog.
 *
 * Split out of `LibraryScreen.kt` — see the note at the top of that file.
 */

/**
 * A section label between content blocks.
 *
 * `LibraryShelf`, not `Shelf`: splitting this file out revealed that
 * `ArtistDetailScreen` has a byte-identical private `Shelf`, which was invisible
 * while both were file-private. Widening this one to `internal` collided with it.
 * The library's own name is the more accurate of the two anyway, and the duplicate
 * is three lines — noted rather than hoisted into the design package, which would
 * mean editing a screen this refactor otherwise does not touch.
 */
@Composable
internal fun LibraryShelf(text: String, count: Int = 0) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionLabel(text)
        if (count > 0) {
            Text(
                count.toString(),
                color = TextFaint,
                fontFamily = MonoFont,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        // A rule out to the margin, so a shelf reads as a band across the page rather
        // than a label floating above some tiles. It is what tells you the row below
        // scrolls sideways and the next one is a different thing.
        Box(Modifier.weight(1f).height(1.dp).background(HairlineSoft))
    }
}

/** The "New playlist" affordance at the top of the Playlists list. */
@Composable
internal fun NewPlaylistRow(onClick: () -> Unit) {
    val accent = LocalAccent.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Glass)
            .border(1.dp, HairlineSoft, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Default.Add, null, tint = accent, modifier = Modifier.size(20.dp))
        Text(
            "New playlist",
            color = TextPrimary, fontFamily = AppFont,
            style = MaterialTheme.typography.titleLarge
        )
    }
}

/**
 * Name a new playlist. It is created empty — both backends make one that way,
 * and tracks go in afterwards — so the caption says so rather than leaving the
 * user waiting for a picker that isn't coming.
 */
@Composable
internal fun CreatePlaylistDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    val accent = LocalAccent.current
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink2,
        title = {
            Text(
                "New playlist", color = TextPrimary, fontFamily = AppFont,
                fontWeight = FontWeight.ExtraBold, fontSize = 17.sp,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    placeholder = { Text("Playlist name", color = TextFaint, fontFamily = AppFont) },
                    colors = accentTextFieldColors(accent),
                )
                Text(
                    "Created empty - add tracks to it afterwards.",
                    color = TextMuted, fontFamily = AppFont, fontSize = 12.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name) }, enabled = name.isNotBlank()) {
                Text("Create", color = if (name.isBlank()) TextFaint else accent, fontFamily = AppFont, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextMuted, fontFamily = AppFont)
            }
        },
    )
}

/**
 * Overlay for switching between configured libraries.
 *
 * Triggered by tapping the library badge in the header — a quicker way to switch
 * than going to Settings, especially when there are multiple servers configured.
 */
@Composable
internal fun LibrarySwitchOverlay(
    servers: List<com.engabd.sendpin.library.ServerConfig>,
    activeId: String?,
    /**
     * The second line of a row. Defaults to the kind's own label, which is all a
     * remote server has to say — but Downloads can say how much is actually on the
     * phone, and that one line is most of what makes it read as a real library rather
     * than a menu entry.
     */
    subtitleFor: (com.engabd.sendpin.library.ServerConfig) -> String = { it.kind.label },
    onDismiss: () -> Unit,
    onSelect: (com.engabd.sendpin.library.ServerConfig) -> Unit,
) {
    val accent = LocalAccent.current
    
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink2,
        title = {
            Text(
                "Switch Library",
                color = TextPrimary, fontFamily = AppFont,
                fontWeight = FontWeight.ExtraBold, fontSize = 17.sp,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (servers.isEmpty()) {
                    Text(
                        "No libraries configured yet.",
                        color = TextMuted, fontFamily = AppFont, fontSize = 13.sp,
                    )
                } else {
                    servers.forEach { config ->
                        LibrarySwitchRow(
                            config = config,
                            subtitle = subtitleFor(config),
                            isActive = config.id == activeId,
                            accent = accent,
                        ) { onSelect(config) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextMuted, fontFamily = AppFont)
            }
        },
    )
}


/**
 * One library in the switcher: its own mark, on glass that carries its colour.
 *
 * The rows used to be the same grey pill with a generic server-rack icon, so three
 * self-hosted servers read as three copies of one entry and the eye had to read every
 * name. Now each row is a frosted tile — a translucent fill with a hairline and a
 * faint top highlight, so it reads as glass rather than a flat card — with the
 * server's own colour ([serverKindColor]) bleeding in from behind the mark and fading
 * out across the row. The active one bleeds harder and takes the accent's check, so
 * "which one am I on" is answered before anything is read.
 */
@Composable
private fun LibrarySwitchRow(
    config: com.engabd.sendpin.library.ServerConfig,
    subtitle: String,
    isActive: Boolean,
    accent: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    val brand = serverKindColor(config.kind)
    val shape = RoundedCornerShape(14.dp)
    // The bleed: strongest behind the mark, gone by two-thirds of the way across.
    val bleed = androidx.compose.ui.graphics.Brush.horizontalGradient(
        0f to brand.copy(alpha = if (isActive) 0.34f else 0.20f),
        0.65f to brand.copy(alpha = 0.04f),
        1f to androidx.compose.ui.graphics.Color.Transparent,
    )
    // The sheen: a whisper of white down from the top edge, which is what makes a
    // translucent fill read as a pane rather than a tint.
    val sheen = androidx.compose.ui.graphics.Brush.verticalGradient(
        0f to androidx.compose.ui.graphics.Color.White.copy(alpha = 0.10f),
        0.45f to androidx.compose.ui.graphics.Color.Transparent,
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Glass)
            .background(bleed)
            .background(sheen)
            .border(
                1.dp,
                if (isActive) brand.copy(alpha = 0.6f) else brand.copy(alpha = 0.28f),
                shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The mark sits in its own small pane, so a logo with a transparent
        // background has something to be seen against and a generic glyph gets the
        // same footprint as a brand mark.
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(brand.copy(alpha = 0.18f))
                .border(1.dp, brand.copy(alpha = 0.35f), RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center,
        ) {
            ServerKindGlyph(config.kind, tint = brand, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                config.displayName,
                color = TextPrimary, fontFamily = AppFont,
                fontWeight = FontWeight.Bold, fontSize = 14.sp,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                color = TextMuted, fontFamily = AppFont,
                fontSize = 11.sp,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        if (isActive) {
            Icon(
                Icons.Default.CheckCircle, contentDescription = "Active",
                tint = accent, modifier = Modifier.size(20.dp),
            )
        }
    }
}
