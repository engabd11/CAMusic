package com.engabd.sendpin.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.ui.design.a
import com.engabd.sendpin.ui.screens.CategoryShape
import com.engabd.sendpin.ui.screens.CategorySize
import com.engabd.sendpin.ui.screens.CategoryStyle
import com.engabd.sendpin.ui.screens.GRID_COLUMNS
import com.engabd.sendpin.ui.screens.categoryMetrics
import com.engabd.sendpin.ui.screens.categoryOrder
import com.engabd.sendpin.ui.theme.AppFont
import com.engabd.sendpin.ui.theme.Hairline
import com.engabd.sendpin.ui.theme.Ink2
import com.engabd.sendpin.ui.theme.MonoFont
import com.engabd.sendpin.ui.theme.TextFaint
import com.engabd.sendpin.ui.theme.TextSecondary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * How the library's category buttons look.
 *
 * The row of Artists / Albums / Tracks / Playlists / Genres / Starred / Recently
 * added / Shuffle all is the first thing on the library page and the most-looked-at
 * furniture in the app, and it had exactly one shape: a 96dp gradient tile, two to a
 * row, hard-coded next to the drawing code with no setting anywhere near it.
 *
 * What people want from it genuinely differs. Some want it smaller so the album
 * shelves start higher; some want it calmer; some want it to carry real artwork; and
 * anyone who never uses half the categories wants those gone. So: five shapes, three
 * sizes, four corners, and a list you can reorder and switch entries off in.
 *
 * **Every default is the row exactly as it shipped**, so an install that never opens
 * this page is unchanged.
 *
 * The preview is not decoration. None of these can be judged from a name — "Chips"
 * and "Icon grid" describe nothing to someone who has not seen them — and the
 * alternative is applying a look, leaving Settings, looking, and coming back.
 */
@Composable
internal fun LibraryLookCard(settings: AppSettings, accent: Color, scope: CoroutineScope) {
    val styleKey by settings.libraryCategoryStyle.collectAsStateWithLifecycle(initialValue = "cards")
    val sizeKey by settings.libraryCategorySize.collectAsStateWithLifecycle(initialValue = "regular")
    val shapeKey by settings.libraryCategoryShape.collectAsStateWithLifecycle(initialValue = "soft")

    val style = CategoryStyle.byKey(styleKey)
    val size = CategorySize.byKey(sizeKey)
    val shape = CategoryShape.byKey(shapeKey)

    SettingsCard(
        title = "Library look",
        lead = "The shape and size of the buttons at the top of the library.",
        info = "Those buttons — Artists, Albums, Tracks and the rest — are the first thing " +
            "on the library page, and how much room they take decides how much of your " +
            "music you can see without scrolling.\n\nCards is the original: two large " +
            "gradient tiles a row. Chips squeezes all of them into about three rows, which " +
            "is the densest of the five. List is calmest and handles large system font " +
            "sizes best, because a row can grow downwards where a fixed tile has to clip " +
            "its label. Icon grid is three or four small squares a row. Artwork fills each " +
            "tile with covers from your own library.\n\nNothing here changes what the " +
            "buttons do, and nothing is hidden by choosing a smaller one — use the list " +
            "below for that.",
    ) {
        FieldLabel("Shape")
        SegmentedToggleRow(
            labels = CategoryStyle.entries.map { it.label },
            selectedIndex = CategoryStyle.entries.indexOf(style),
            onSelect = { i ->
                scope.launch { settings.setLibraryCategoryStyle(CategoryStyle.entries[i].key) }
            },
        )

        LibraryLookPreview(style, size, shape, accent)

        Note(style.blurb)

        FieldLabel("Size")
        SegmentedToggleRow(
            labels = CategorySize.entries.map { it.label },
            selectedIndex = CategorySize.entries.indexOf(size),
            onSelect = { i ->
                scope.launch { settings.setLibraryCategorySize(CategorySize.entries[i].key) }
            },
        )

        FieldLabel("Corners")
        SegmentedToggleRow(
            labels = CategoryShape.entries.map { it.label },
            selectedIndex = CategoryShape.entries.indexOf(shape),
            onSelect = { i ->
                scope.launch { settings.setLibraryCategoryShape(CategoryShape.entries[i].key) }
            },
        )

        OledButton("Reset the library look", accent = accent, outline = true) {
            scope.launch { settings.resetLibraryCategoryLook() }
        }
    }
}

/**
 * Which categories appear, and in what order.
 *
 * Separate card from the shape above because it is a different question — that one is
 * "how do these look", this one is "which of them do I want at all". Someone who never
 * opens Genres wants it gone, and someone who lives in Playlists wants it first.
 *
 * The list is every category this app can offer rather than only the ones the current
 * library has: the setting outlives a library switch, and a list that silently forgot
 * your Genres preference because Music Assistant was active would be worse than one
 * showing a row that is currently unused. Categories the live library does not offer
 * simply never appear on the page — see `categoryOrder`.
 */
@Composable
internal fun LibraryCategoriesCard(settings: AppSettings, accent: Color, scope: CoroutineScope) {
    val order by settings.libraryCategoryOrder.collectAsStateWithLifecycle(initialValue = emptyList())
    val hidden by settings.libraryCategoryHidden.collectAsStateWithLifecycle(initialValue = emptySet())

    val all = ALL_CATEGORIES.map { it.first }
    val shown = categoryOrder(all, order, hidden)

    SettingsCard(
        title = "Which buttons, and in what order",
        lead = "Switch off the ones you never open, and put the rest in your own order.",
        info = "Not every library offers every category — Genres needs a server that has " +
            "them, Radio and Podcasts only appear on Music Assistant — so some of these " +
            "will not show up on the library page even when they are switched on here. " +
            "That is the library speaking, not this setting.\n\nThe order is kept across " +
            "libraries, which is why the list shows everything this app can offer rather " +
            "than only what is on screen right now. Switching library would otherwise " +
            "quietly forget half of it.\n\nThe last one left on cannot be switched off: a " +
            "library page with no way into it is not a look.",
    ) {
        OrderedPicker(
            keys = categoryOrder(all, order, emptySet()),
            enabled = shown,
            accent = accent,
            title = { id -> ALL_CATEGORIES.firstOrNull { it.first == id }?.second ?: id },
            subtitle = { id -> ALL_CATEGORIES.firstOrNull { it.first == id }?.third.orEmpty() },
            onToggle = { id ->
                scope.launch {
                    val next = if (id in hidden) hidden - id else hidden + id
                    // Refused rather than silently ignored upstream: an empty
                    // selection and no selection at all store identically, so
                    // hiding the last one would turn everything back on.
                    if (categoryOrder(all, order, next).isNotEmpty() && next.size < all.size) {
                        settings.setLibraryCategoryHidden(next)
                    }
                }
            },
            onMove = { id, delta ->
                scope.launch {
                    val current = categoryOrder(all, order, emptySet()).toMutableList()
                    val from = current.indexOf(id)
                    val to = from + delta
                    if (from >= 0 && to in current.indices) {
                        current.removeAt(from)
                        current.add(to, id)
                        settings.setLibraryCategoryOrder(current)
                    }
                }
            },
        )
    }
}

/**
 * A small, honest picture of the chosen look.
 *
 * Honest in the one sense that matters: the boxes are laid out from the *same*
 * [categoryMetrics] the library page computes, so a switch that changes nothing here
 * changes nothing there. It is not pixel-accurate — no colours, no glyphs, no real
 * names — it is the shape of the answer, which is the part a name cannot convey.
 *
 * Modelled on `CarScreenPreview`, which makes the same argument for the same reason.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryLookPreview(
    style: CategoryStyle,
    size: CategorySize,
    shape: CategoryShape,
    accent: Color,
) {
    val metrics = categoryMetrics(style, size, shape, GRID_COLUMNS)
    // Scaled down, because the preview is a card inside a settings page and the real
    // thing is a full-width grid. The *ratios* are what is being shown.
    val scale = 0.62f
    val h = (metrics.heightDp * scale).dp
    val corner = (metrics.cornerDp * scale).dp.coerceAtMost(h / 2)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Ink2)
            .border(1.dp, Hairline, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Library",
                color = TextSecondary,
                fontFamily = AppFont,
                style = MaterialTheme.typography.labelLarge,
            )
            Box(Modifier.weight(1f))
            Text(
                "${PREVIEW_CATEGORIES} buttons",
                color = TextFaint,
                fontFamily = MonoFont,
                style = MaterialTheme.typography.labelSmall,
            )
        }

        if (metrics.wrapping) {
            // Chips size themselves to their label, so the preview varies the widths
            // rather than drawing eight identical pills — the wrap is the point.
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CHIP_WIDTHS.forEach { w ->
                    Box(
                        Modifier
                            .width((w * scale).dp)
                            .height(h)
                            .clip(RoundedCornerShape(corner))
                            .background(accent.a(0.18f))
                            .border(1.dp, accent.a(0.34f), RoundedCornerShape(corner)),
                    )
                }
            }
        } else {
            // The grid, at the real span: 6 base columns, `span` each, so the row
            // count in the preview is the row count on the page.
            val perRow = (GRID_COLUMNS / metrics.span).coerceAtLeast(1)
            val rows = (PREVIEW_CATEGORIES + perRow - 1) / perRow
            repeat(rows) { r ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    repeat(perRow) { c ->
                        val index = r * perRow + c
                        if (index < PREVIEW_CATEGORIES) {
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(h)
                                    .clip(RoundedCornerShape(corner))
                                    .background(accent.a(0.18f))
                                    .border(1.dp, accent.a(0.34f), RoundedCornerShape(corner)),
                            )
                        } else {
                            // Keeps the last row's boxes the same width as the rest
                            // instead of stretching them across the gap.
                            Box(Modifier.weight(1f).size(0.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Enough to show the wrap and the row count; about what a real library offers. */
private const val PREVIEW_CATEGORIES = 8

/** Representative chip widths, so the preview's wrap looks like the real one. */
private val CHIP_WIDTHS = listOf(76, 74, 68, 82, 70, 74, 116, 92)

/**
 * Every category this app can offer, with the label the library uses and a note on
 * where it comes from.
 *
 * Listed here rather than derived from `LibraryViewModel.rootItems()` because that is
 * a private function of an `AndroidViewModel` that builds its list from the *live*
 * source — so it can only ever report what the current library has, and this list has
 * to outlive a library switch. The ids are the contract between the two; they are the
 * same strings `openCategory` switches on.
 */
private val ALL_CATEGORIES: List<Triple<String, String, String>> = listOf(
    Triple("artists", "Artists", "Every library"),
    Triple("albums", "Albums", "Every library"),
    Triple("tracks", "Tracks", "Libraries that can list songs"),
    Triple("playlists", "Playlists", "Libraries with playlists"),
    Triple("genres", "Genres", "Self-hosted libraries with genres"),
    Triple("starred", "Starred", "Libraries with favourites"),
    Triple("newest", "Recently Added", "Self-hosted libraries"),
    Triple("random", "Shuffle all", "Self-hosted libraries"),
    Triple("radios", "Radio stations", "Music Assistant"),
    Triple("podcasts", "Podcasts", "Music Assistant"),
    Triple("downloads", "Downloads", "Offline, when no server answers"),
)
