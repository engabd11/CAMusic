package com.engabd.sendpin.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.library.ServerKind
import com.engabd.sendpin.ma.LibraryViewModel
import com.engabd.sendpin.ui.design.LocalAccent
import com.engabd.sendpin.ui.design.navBarInset
import com.engabd.sendpin.ui.screens.settings.*
import com.engabd.sendpin.ui.theme.*
import com.engabd.sendpin.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The top level of Settings.
 *
 * Everything used to live on one scroll, then on eight flat categories grouped by
 * *which class implements it* — which is why Music Assistant's address lived under
 * "Servers" while the choice of whether to browse it lived under "Library", and why
 * the Hue bridge and Home Assistant sat at the bottom of a page about music servers.
 *
 * The sections are now the questions someone actually arrives with:
 *
 * - **Libraries** — where the music comes from, as a list of servers rather than a
 *   two-way switch that could not express a third.
 * - **CAMusic player** — this phone as something Music Assistant plays *to*.
 * - **Playback & audio** — everything between the file and the speaker.
 * - **Downloads** — music kept on the phone.
 * - **Light Sync** — the plumbing behind the light show (the show itself is a tab).
 * - **Appearance** — how the app looks and how the player behaves.
 * - **About**.
 *
 * Two levels deep, not one: a server has its own page, and so does the Hue bridge.
 * Both are routed by [detail], hoisted alongside [section] so that re-tapping the
 * Settings tab comes all the way back to the index.
 */
enum class SettingsSection(
    val title: String,
    /** What is actually behind the row. A one-word category is a guess until opened. */
    val subtitle: String,
    val icon: ImageVector,
) {
    LIBRARIES(
        "Libraries",
        "Your music servers, and which one the Library tab browses",
        Icons.Default.LibraryMusic,
    ),
    // No PLAYER section. This phone as a Music Assistant player is set up on that
    // server's own page, under Libraries — name, stream format, gapless, status and
    // announcements together with the server they all describe a registration with.
    // A top-level entry that only pointed at that page was one more place to look
    // before finding the settings, not one fewer.
    AUDIO(
        "Playback & audio",
        "Output device, streaming quality, loudness and what happens between tracks",
        Icons.Default.GraphicEq,
    ),
    DOWNLOADS(
        "Downloads",
        "Offline copies, when to fetch them and how much room they may take",
        Icons.Default.Download,
    ),
    LIGHTS(
        "Light Sync",
        "Home Assistant or the Hue Bridge, and reading tracks ahead of the show",
        Icons.Default.Lightbulb,
    ),
    APPEARANCE(
        "Appearance",
        "Theme, accent colour, the Now Playing layout and lyrics timing",
        Icons.Default.Palette,
    ),
    ABOUT(
        "About",
        "Version and source code",
        Icons.Default.Info,
    ),
}

@Composable
fun SettingsScreen(
    viewModel: PlayerViewModel = viewModel(),
    /**
     * The *shared* library view model. Passed in rather than resolved with
     * `viewModel()` here: inside a `NavHost` destination that builds a second instance
     * scoped to this back-stack entry, so editing a server's credentials in Settings
     * would connect a client the Library tab never sees.
     */
    libraryViewModel: LibraryViewModel,
    /**
     * Which category is open, or null for the index.
     *
     * Hoisted rather than held here so the tab bar can close it: tapping Settings
     * while a category is open comes back to the index, the same way tapping Library
     * while browsing comes back to the shelves.
     */
    section: SettingsSection? = null,
    onSection: (SettingsSection?) -> Unit = {},
    /** The page *within* a section — a server, or the Hue bridge. */
    detail: String? = null,
    onDetail: (String?) -> Unit = {},
    /**
     * Downloads get a screen of their own rather than a sub-page here: it is a list
     * to search and sort, which is a screen, not a settings card.
     */
    onOpenDownloads: () -> Unit = {},
) {
    // Back unwinds one level at a time, so a server's page returns to the server list
    // rather than all the way out of Settings.
    BackHandler(enabled = section != null) {
        if (detail != null) onDetail(null) else onSection(null)
    }

    val accent = LocalAccent.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember(context) { AppSettings(context) }

    // Home Assistant's credentials are read once and held here rather than inside the
    // Light Sync section, so navigating into the bridge page and back doesn't lose a
    // half-typed token.
    var haUrl by remember { mutableStateOf("") }
    var haToken by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        haUrl = settings.haUrl.first()
        haToken = settings.haToken.first()
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (section != null) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextSecondary,
                        modifier = Modifier.size(24.dp).clip(CircleShape).clickable {
                            if (detail != null) onDetail(null) else onSection(null)
                        },
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    headerTitle(section, detail), color = TextPrimary, fontFamily = AppFont,
                    fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, letterSpacing = (-0.5).sp,
                )
            }

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = navBarInset() + 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (section == null) {
                    // The index. Every row says what lives behind it.
                    items(SettingsSection.entries, key = { it.name }, contentType = { "category" }) { s ->
                        NavRow(s.icon, s.title, s.subtitle, accent) { onSection(s); onDetail(null) }
                    }
                } else {
                    // One item per section: each is a page of cards, and a page is not
                    // a list worth recycling. The index above is, and gets `items`.
                    item(key = section.name) {
                        when (section) {
                            SettingsSection.LIBRARIES -> LibrariesSection(
                                settings = settings,
                                libraryVm = libraryViewModel,
                                accent = accent,
                                scope = scope,
                                detail = detail,
                                onDetail = onDetail,
                            )

                            SettingsSection.AUDIO -> AudioSection(viewModel, settings, accent, scope)

                            SettingsSection.DOWNLOADS ->
                                DownloadsSection(libraryViewModel, settings, accent, scope, onOpenDownloads)

                            SettingsSection.LIGHTS -> LightSyncSection(
                                settings = settings,
                                accent = accent,
                                scope = scope,
                                detail = detail,
                                onDetail = onDetail,
                                haUrl = haUrl,
                                onHaUrl = { haUrl = it },
                                haToken = haToken,
                                onHaToken = { haToken = it },
                            )

                            SettingsSection.APPEARANCE -> AppearanceSection(settings, accent, scope)

                            SettingsSection.ABOUT -> AboutSection(accent)
                        }
                    }
                }
            }
        }
    }
}

/**
 * What the header says.
 *
 * A detail page under a section is still that section as far as the user is concerned
 * — but "Libraries" over a form for one server is less use than naming the thing in
 * front of them, so the sub-pages that have a name of their own use it.
 */
@Composable
private fun headerTitle(section: SettingsSection?, detail: String?): String = when {
    section == null -> "Settings"
    section == SettingsSection.LIBRARIES && detail == PICK_ROUTE -> "Add a server"
    section == SettingsSection.LIBRARIES && detail != null -> "Server"
    section == SettingsSection.LIGHTS && detail == BRIDGE_ROUTE -> "Bridge & analysis"
    else -> section.title
}
