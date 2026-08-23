package com.engabd.sendpin.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.ma.LibraryViewModel
import com.engabd.sendpin.ui.design.LocalAccent
import com.engabd.sendpin.ui.design.LocalPalette
import com.engabd.sendpin.ui.design.Motion
import com.engabd.sendpin.ui.design.navBarInset
import com.engabd.sendpin.ui.screens.settings.*
import com.engabd.sendpin.ui.theme.*
import com.engabd.sendpin.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

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
        // Driving is named here because the section index is the only place it can be
        // found from: it is one card among four, and someone looking for it is looking
        // for a feature rather than for an audio setting.
        "Output device, loudness, what happens between tracks, and driving controls",
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
    STATS(
        "Listening stats",
        "Your recent top artists, total listening time, and format breakdown",
        Icons.Default.BarChart,
    ),
    BACKUP(
        "Backup & restore",
        "Export or import all settings and servers as encrypted file",
        Icons.Default.Backup,
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
    /** Stats gets a screen of its own for the same reason. */
    onOpenStats: () -> Unit = {},
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

    val state = rememberSettingsOverview(settings, libraryViewModel)

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
                    // Nothing set up yet: the index reads identically whether the app is
                    // configured or completely empty, and a new user's first question is
                    // not which of six categories to browse.
                    if (state.servers == 0) {
                        item(key = "get-started") {
                            GetStartedCard(accent) { onSection(SettingsSection.LIBRARIES); onDetail(null) }
                        }
                    }
                    // The index. Every row says what is behind it *right now* — a static
                    // description of a category is a definition, and the reader already
                    // knows what "Downloads" means; what they do not know is whether
                    // theirs are taking a gigabyte or whether the bridge ever paired.
                    items(SettingsSection.entries, key = { it.name }, contentType = { "category" }) { s ->
                        // Each row takes its own swatch from the album palette, the same
                        // way the library's category tiles do — so eight rows read as a
                        // set of places rather than eight repetitions of one accent, and
                        // both screens are visibly tinted by the record on the player.
                        val hue = LocalPalette.current.swatch(s.ordinal)
                        val tint by animateColorAsState(hue, Motion.effects(), label = "sectionHue")
                        // Stats opens its own full-screen view rather than a settings sub-page.
                        if (s == SettingsSection.STATS) {
                            NavRow(s.icon, s.title, state.subtitleFor(s) ?: s.subtitle, tint, onClick = onOpenStats)
                        } else {
                            NavRow(s.icon, s.title, state.subtitleFor(s) ?: s.subtitle, tint) {
                                onSection(s); onDetail(null)
                            }
                        }
                    }
                } else {
                    // One item per section: each is a page of cards, and a page is not
                    // a list worth recycling. The index above is, and gets `items`.
                    // Keyed on the section, not on `section.name`, so drilling from one
                    // section into another — which the Libraries page can do — is a
                    // transition rather than a redraw of a slot that never changed.
                    item(key = "section") {
                        AnimatedContent(
                            targetState = section,
                            transitionSpec = {
                                fadeIn(Motion.fadeThroughIn()) togetherWith
                                    fadeOut(Motion.fadeThroughOut()) using SizeTransform(clip = false)
                            },
                            label = "settingsSection",
                        ) { section ->
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

                            // No STATS branch. It is intercepted in the index above and
                            // opens its own full-screen view, so it never arrives here —
                            // the branch that used to sit here said so in a comment and
                            // was otherwise empty.
                            SettingsSection.STATS -> Unit

                            SettingsSection.BACKUP -> BackupSection(settings, accent, scope)

                            SettingsSection.APPEARANCE -> AppearanceSection(settings, accent, scope)

                            SettingsSection.ABOUT -> AboutSection(accent, onOpenStats)
                        }
                        }
                    }
                }
            }
        }
    }
}

// ── The index's live state ────────────────────────────────────────────────

/**
 * What each settings category currently *is*, for the row that points at it.
 *
 * Gathered in one place rather than per row: every value here already exists as a flow
 * somebody else is collecting, and six rows each opening their own subscription to
 * answer one line of text would be six collectors on the index of a screen the user is
 * usually passing through.
 */
private data class SettingsOverview(
    val servers: Int = 0,
    val activeLibrary: String? = null,
    val libraryStatus: String? = null,
    val downloadCount: Int = 0,
    val downloadBytes: Long = 0,
    val lightSyncSummary: String? = null,
    val appearanceSummary: String? = null,
) {
    /** The live line for [section], or null to fall back to its written description. */
    fun subtitleFor(section: SettingsSection): String? = when (section) {
        SettingsSection.LIBRARIES -> when {
            servers == 0 -> "No music library yet, start here"
            else -> listOfNotNull(activeLibrary, libraryStatus).joinToString(" · ")
                .takeIf { it.isNotBlank() }
        }
        SettingsSection.DOWNLOADS -> when (downloadCount) {
            0 -> "Nothing kept on this phone yet"
            else -> "$downloadCount ${if (downloadCount == 1) "track" else "tracks"} · " +
                formatBytes(downloadBytes)
        }
        SettingsSection.LIGHTS -> lightSyncSummary
        SettingsSection.APPEARANCE -> appearanceSummary
        // Nothing about the audio path or the app itself changes often enough to be
        // worth a live line, and a row that reads "Output: Phone speaker" would be
        // wrong every time headphones are plugged in while this screen is not open.
        SettingsSection.AUDIO, SettingsSection.STATS, SettingsSection.BACKUP, SettingsSection.ABOUT -> null
    }
}

@Composable
private fun rememberSettingsOverview(
    settings: AppSettings,
    libraryViewModel: LibraryViewModel,
): SettingsOverview {
    val servers by settings.servers.collectAsStateWithLifecycle(initialValue = emptyList())
    val activeId by settings.activeServerId.collectAsStateWithLifecycle(initialValue = "")
    val ready by libraryViewModel.ready.collectAsStateWithLifecycle()
    val connecting by libraryViewModel.connecting.collectAsStateWithLifecycle()
    val offline by libraryViewModel.offline.collectAsStateWithLifecycle()
    val downloads by libraryViewModel.downloads.collectAsStateWithLifecycle()
    val mode by settings.lightSyncMode.collectAsStateWithLifecycle(initialValue = AppSettings.MODE_HA)
    val bridgeIp by settings.hueBridgeIp.collectAsStateWithLifecycle(initialValue = "")
    val haAddress by settings.haUrl.collectAsStateWithLifecycle(initialValue = "")
    val themeKey by settings.theme.collectAsStateWithLifecycle(initialValue = ThemeChoice.OLED.key)
    val accentKey by settings.accentSource.collectAsStateWithLifecycle(initialValue = AccentChoice.ALBUM.key)

    // Stat-ing the files is disk work, so it happens off the main thread and only when
    // the index of downloads actually changes.
    var bytes by remember { mutableStateOf(0L) }
    LaunchedEffect(downloads) {
        bytes = withContext(Dispatchers.IO) { libraryViewModel.downloadBytes() }
    }

    val active = servers.firstOrNull { it.id == activeId }
    return SettingsOverview(
        servers = servers.size,
        activeLibrary = active?.displayName,
        libraryStatus = when {
            active == null -> null
            connecting -> "Connecting…"
            offline -> "Offline, playing downloads"
            ready -> "Connected"
            else -> "Not connected"
        },
        downloadCount = downloads.size,
        downloadBytes = bytes,
        lightSyncSummary = if (mode == AppSettings.MODE_DIRECT) {
            if (bridgeIp.isNotBlank()) "Hue Bridge · paired" else "Hue Bridge · not paired yet"
        } else {
            if (haAddress.isNotBlank()) "Home Assistant · $haAddress" else "Home Assistant · not set up"
        },
        appearanceSummary = "${ThemeChoice.from(themeKey).label} · ${AccentChoice.from(accentKey).label}",
    )
}

/**
 * The one card a phone with no library needs.
 *
 * Six categories, all of them about parts of a thing that has not been set up, is a
 * poor first screen: the app cannot browse, play, download or light anything until a
 * server exists, and none of the six rows says so.
 */
@Composable
private fun GetStartedCard(accent: androidx.compose.ui.graphics.Color, onAddLibrary: () -> Unit) {
    SettingsCard(
        title = "Start here",
        lead = "Add a library and the rest of the app comes alive.",
        info = "CAMusic plays your own music, from your own server. Music Assistant drives " +
            "speakers around the house and keeps one queue across them. Navidrome, Subsonic and " +
            "Jellyfin play on this phone, with downloads that work with no network at " +
            "all.\n\nYou can add more than one and switch between them. Nothing gets thrown " +
            "away when you do.\n\nTip: if you are not sure which you have, start with the " +
            "server you already sign in to from a browser. Music Assistant is the one with a " +
            "speakers page.",
    ) {
        OledButton("Add a music library", accent = accent, onClick = onAddLibrary)
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

// ── Backup & Restore section ─────────────────────────────────────────────

/**
 * Export every setting — including saved servers — to an encrypted JSON file, or
 * restore one. The password never leaves this device: it exists only to derive
 * [com.engabd.sendpin.data.PortableCrypto]'s key, which is why losing it means
 * losing the backup — there is nothing to reset it with.
 */
@Composable
private fun BackupSection(settings: AppSettings, accent: Color, scope: CoroutineScope) {
    val context = LocalContext.current
    var exportPrompt by remember { mutableStateOf(false) }
    var importUri by remember { mutableStateOf<Uri?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    // Held between the picker's launch and its callback — CreateDocument hands off
    // to a separate activity, and the composition (with its remember state) is
    // what survives that round trip, not any local variable in the click handler.
    var exportPassword by remember { mutableStateOf<String?>(null) }

    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val password = exportPassword
        exportPassword = null
        if (uri == null || password == null) return@rememberLauncherForActivityResult
        scope.launch {
            status = try {
                val blob = settings.exportSettings(password)
                if (blob == null) {
                    "Export failed"
                } else {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        OutputStreamWriter(out).use { it.write(blob) }
                    }
                    "Exported"
                }
            } catch (e: Exception) {
                "Export failed: ${e.message}"
            }
        }
    }
    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        importUri = uri
    }

    SettingsCard(
        title = "Backup & restore",
        lead = "Every setting, including saved servers, to an encrypted file.",
        info = "Export every setting, saved servers and their logins included, to a single " +
            "encrypted file. Restore it on a new install and the app comes back exactly as it " +
            "was.\n\nThe password is yours alone. It is not stored anywhere, not in the file " +
            "and not on this phone, so there is no way to recover a backup without it.\n\nTip: " +
            "take one before changing servers or reinstalling. It is the only copy of your Hue " +
            "pairing, which otherwise means pressing the button on the bridge again.",
    ) {
        OledButton(text = "Export settings", accent = accent, outline = true) { exportPrompt = true }
        Spacer(Modifier.height(8.dp))
        OledButton(text = "Import settings", accent = accent, outline = true) {
            openDoc.launch(arrayOf("application/json", "*/*"))
        }
        status?.let { Spacer(Modifier.height(6.dp)); Note(it, warn = it.startsWith("Export failed") || it.startsWith("Import failed")) }
    }

    if (exportPrompt) {
        PasswordPromptDialog(
            title = "Export settings",
            note = "This password encrypts the file. Choose one you'll remember, it can't be reset.",
            confirmLabel = "Export",
            onDismiss = { exportPrompt = false },
            onConfirm = { password ->
                exportPrompt = false
                exportPassword = password
                createDoc.launch("camusic-settings-backup.json")
            },
        )
    }

    importUri?.let { uri ->
        PasswordPromptDialog(
            title = "Import settings",
            note = "This replaces your saved servers and other settings on this device.",
            confirmLabel = "Import",
            onDismiss = { importUri = null },
            onConfirm = { password ->
                importUri = null
                scope.launch {
                    status = try {
                        val text = context.contentResolver.openInputStream(uri)?.use { input ->
                            BufferedReader(InputStreamReader(input)).readText()
                        }
                        if (text == null) {
                            "Import failed: couldn't read that file"
                        } else if (settings.importSettings(text, password)) {
                            "Imported, restart the app for everything to take effect"
                        } else {
                            "Import failed: wrong password, or not a CAMusic backup"
                        }
                    } catch (e: Exception) {
                        "Import failed: ${e.message}"
                    }
                }
            },
        )
    }
}

@Composable
private fun PasswordPromptDialog(
    title: String,
    note: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val accent = LocalAccent.current
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink2,
        title = { Text(title, color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SecretField(
                    value = password,
                    onChange = { password = it },
                    label = "Password",
                    accent = accent,
                    visible = visible,
                    onVisibilityChange = { visible = it },
                )
                Note(note)
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onConfirm(password) }, enabled = password.isNotBlank()) {
                Text(confirmLabel, color = if (password.isBlank()) TextFaint else accent, fontFamily = AppFont, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextMuted, fontFamily = AppFont)
            }
        },
    )
}
