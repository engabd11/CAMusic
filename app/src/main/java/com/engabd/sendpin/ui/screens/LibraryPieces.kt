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
internal fun LibraryShelf(text: String) {
    Box(Modifier.padding(top = 12.dp, bottom = 2.dp)) { SectionLabel(text) }
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
