package com.engabd.sendpin.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.data.DetailStyle
import com.engabd.sendpin.data.DiscographyLayout
import com.engabd.sendpin.data.PageShelf
import com.engabd.sendpin.data.TileStyle
import com.engabd.sendpin.ui.design.ToggleChip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/*
 * Settings for how the album and artist pages, and the library, are dressed — see
 * com.engabd.sendpin.data.PageLook. Every default is the app as it shipped.
 */

private val DefaultShelves = PageShelf.entries.filter { it.default }.toSet()

/** Classic or Gallery heroes for both detail pages. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PageStyleCard(settings: AppSettings, scope: CoroutineScope) {
    val style by settings.detailStyle.collectAsStateWithLifecycle(initialValue = DetailStyle.CLASSIC)
    SettingsCard(
        title = "Page style",
        lead = "How the top of an album or artist page is laid out.",
        info = "Both styles are painted from the cover's own colours, as the rest of the " +
            "app is — only the arrangement changes.\n\nClassic is the centred cover and " +
            "title the pages have always had.\n\nGallery gives an album its sleeve with the " +
            "record sliding out from behind it, the label printed in the sleeve's colours " +
            "(it turns while that album plays), and larger type. An artist gets a " +
            "full-width portrait in a duotone of the palette, with the name across it.",
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DetailStyle.entries.forEach { s ->
                ToggleChip(s.label, s == style) { scope.launch { settings.setDetailStyle(s) } }
            }
        }
        Note(
            when (style) {
                DetailStyle.CLASSIC -> "The pages as they have always been."
                DetailStyle.GALLERY -> "Sleeve and record on albums; a duotone banner on artists."
            },
        )
    }
}

/** The optional sections of one page. */
@Composable
private fun ShelvesCard(
    settings: AppSettings,
    accent: Color,
    scope: CoroutineScope,
    page: PageShelf.Page,
    title: String,
    lead: String,
    extra: @Composable () -> Unit = {},
) {
    val on by settings.enabledShelves.collectAsStateWithLifecycle(initialValue = DefaultShelves)
    SettingsCard(
        title = title,
        lead = lead,
        info = "Each section can be switched on or off. The ones that were already on the " +
            "page start on; the new ones start off, so nothing changes until you ask.\n\n" +
            "\"Your listening\" counts plays on this phone, from the same history as the " +
            "Stats screen — a library server's own play count is a different number.",
    ) {
        PageShelf.entries.filter { it.page == page }.forEach { shelf ->
            ToggleRow(
                title = shelf.title,
                subtitle = shelf.gist,
                checked = shelf in on,
                accent = accent,
            ) { enabled -> scope.launch { settings.setShelfEnabled(shelf, enabled) } }
        }
        extra()
    }
}

@Composable
internal fun AlbumShelvesCard(settings: AppSettings, accent: Color, scope: CoroutineScope) =
    ShelvesCard(
        settings, accent, scope, PageShelf.Page.ALBUM,
        title = "Album page",
        lead = "What appears below the track list.",
    )

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ArtistShelvesCard(settings: AppSettings, accent: Color, scope: CoroutineScope) {
    val layout by settings.discographyLayout.collectAsStateWithLifecycle(initialValue = DiscographyLayout.LIST)
    ShelvesCard(
        settings, accent, scope, PageShelf.Page.ARTIST,
        title = "Artist page",
        lead = "The sections of an artist's page, and how their records are listed.",
    ) {
        Note("Records are listed as")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DiscographyLayout.entries.forEach { l ->
                ToggleChip(l.label, l == layout) { scope.launch { settings.setDiscographyLayout(l) } }
            }
        }
    }
}

/** The library's optional dress: tile style, the spotlight, the backdrop. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LibraryExtrasCard(settings: AppSettings, accent: Color, scope: CoroutineScope) {
    val tiles by settings.libraryTileStyle.collectAsStateWithLifecycle(initialValue = TileStyle.CLASSIC)
    val spotlight by settings.librarySpotlight.collectAsStateWithLifecycle(initialValue = false)
    val backdrop by settings.libraryBackdrop.collectAsStateWithLifecycle(initialValue = false)
    SettingsCard(
        title = "Library extras",
        lead = "Covers, a featured record, and the album's colours behind it all.",
        info = "All three use the covers' own colours, the same as Now Playing and the album " +
            "pages.\n\nGallery tiles round the covers off and pool each one's colour " +
            "beneath it; the colours are read from the covers as they appear, and kept.\n\n" +
            "Spotlight puts one record from what is new in the library at the top, the " +
            "same one all day.\n\nBackdrop washes the library in the colours of what is " +
            "playing — or of the spotlight, when nothing is.",
    ) {
        Note("Album covers")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TileStyle.entries.forEach { t ->
                ToggleChip(t.label, t == tiles) { scope.launch { settings.setLibraryTileStyle(t) } }
            }
        }
        ToggleRow(
            title = "Spotlight",
            subtitle = if (spotlight) "On — a record from what is new, at the top of the library" else "Off",
            checked = spotlight,
            accent = accent,
        ) { on -> scope.launch { settings.setLibrarySpotlight(on) } }
        ToggleRow(
            title = "Album-colour backdrop",
            subtitle = if (backdrop) "On — the library takes the playing record's colours" else "Off — the plain page wash",
            checked = backdrop,
            accent = accent,
        ) { on -> scope.launch { settings.setLibraryBackdrop(on) } }
    }
}
