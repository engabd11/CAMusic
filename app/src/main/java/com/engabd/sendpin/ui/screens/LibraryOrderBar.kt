package com.engabd.sendpin.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.engabd.sendpin.ma.LibraryOrder
import com.engabd.sendpin.ui.design.ToggleChip
import com.engabd.sendpin.ui.theme.AppFont
import com.engabd.sendpin.ui.theme.Ink2
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextPrimary

/**
 * Sort and filter for a library category, as one row of chips above the list.
 *
 * Only what the list's own data supports is offered (see [LibraryOrder.options]): no
 * Year sort on a list without years, no Downloaded filter when nothing is on the
 * phone. The choice is kept per category, so Albums stays sorted by year while Songs
 * stays as the server sends it.
 */
@Composable
internal fun LibraryOrderBar(
    order: LibraryOrder,
    options: LibraryOrder.Options,
    shown: Int,
    total: Int,
    onChange: (LibraryOrder) -> Unit,
    onReshuffle: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChipMenu(
                label = "Sort: " + if (order.sort == LibraryOrder.Sort.DEFAULT) "Default" else order.sort.label,
                selected = order.sort != LibraryOrder.Sort.DEFAULT,
                entries = options.sorts.map { it.label to it },
                onPick = { picked ->
                    if (picked == LibraryOrder.Sort.RANDOM && order.sort == LibraryOrder.Sort.RANDOM) onReshuffle()
                    onChange(order.copy(sort = picked, descending = if (picked == order.sort) order.descending else false))
                },
            )
            if (order.sort != LibraryOrder.Sort.DEFAULT && order.sort != LibraryOrder.Sort.RANDOM) {
                val label = when (order.sort) {
                    LibraryOrder.Sort.YEAR -> if (order.descending) "Newest first" else "Oldest first"
                    else -> if (order.descending) "Z to A" else "A to Z"
                }
                ToggleChip(label, selected = order.descending) { onChange(order.copy(descending = !order.descending)) }
            }
            if (order.sort == LibraryOrder.Sort.RANDOM) {
                ToggleChip("Shuffle again", selected = false) { onReshuffle() }
            }
            if (options.canFavourites || order.favouritesOnly) {
                ToggleChip("Favourites", selected = order.favouritesOnly) { onChange(order.copy(favouritesOnly = !order.favouritesOnly)) }
            }
            if (options.canDownloaded || order.downloadedOnly) {
                ToggleChip("On this phone", selected = order.downloadedOnly) { onChange(order.copy(downloadedOnly = !order.downloadedOnly)) }
            }
            if (options.genres.isNotEmpty() || order.genre != null) {
                ChipMenu(
                    label = order.genre ?: "Genre",
                    selected = order.genre != null,
                    entries = listOf("Any genre" to null) + options.genres.map { it to it },
                    onPick = { onChange(order.copy(genre = it)) },
                )
            }
            if (options.decades.size > 1 || order.decade != null) {
                ChipMenu(
                    label = order.decade?.let { "${it}s" } ?: "Decade",
                    selected = order.decade != null,
                    entries = listOf("Any decade" to null) + options.decades.map { "${it}s" to it },
                    onPick = { onChange(order.copy(decade = it)) },
                )
            }
            if (!order.isDefault) {
                ToggleChip("Reset", selected = false) { onChange(LibraryOrder()) }
            }
        }
        if (order.filtering) {
            Text(
                "$shown of $total",
                color = TextMuted, fontFamily = AppFont,
                modifier = Modifier.padding(top = 6.dp, start = 2.dp),
            )
        }
    }
}

/** A chip that opens a short list to pick from. */
@Composable
private fun <T> ChipMenu(label: String, selected: Boolean, entries: List<Pair<String, T>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ToggleChip(label, selected = selected) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Ink2) {
            entries.forEach { (text, value) ->
                DropdownMenuItem(
                    text = { Text(text, color = TextPrimary, fontFamily = AppFont) },
                    onClick = {
                        open = false
                        onPick(value)
                    },
                )
            }
        }
    }
}
