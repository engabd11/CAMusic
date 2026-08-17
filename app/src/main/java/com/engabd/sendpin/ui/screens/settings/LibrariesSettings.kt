package com.engabd.sendpin.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.library.AuthStyle
import com.engabd.sendpin.library.ServerConfig
import com.engabd.sendpin.library.ServerKind
import com.engabd.sendpin.local.LocalMediaSource
import com.engabd.sendpin.ma.LibraryViewModel
import com.engabd.sendpin.ui.design.GlassCard
import com.engabd.sendpin.ui.design.a
import com.engabd.sendpin.ui.design.SegmentedToggle
import com.engabd.sendpin.ui.design.TitleGap
import com.engabd.sendpin.ui.theme.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext

/**
 * Where the music comes from.
 *
 * This replaces two sections that were asking one question between them. "Servers"
 * held Music Assistant's and Navidrome's credentials; "Library" held a two-way switch
 * deciding which of them the Library tab browsed. Someone looking for "my music
 * servers" had to know that the *address* of one lived in one place and the *choice*
 * of it in another — and the switch could not express a third server at all, which is
 * exactly what this app now has.
 *
 * So: one list. Each server is a card saying what it is, where it is and whether it is
 * the one being browsed — picked with a radio button, because that is the shape of the
 * question — with its own settings a labelled row away. Adding a server is a button
 * rather than a schema change.
 */
@Composable
internal fun LibrariesSection(
    settings: AppSettings,
    libraryVm: LibraryViewModel,
    accent: Color,
    scope: CoroutineScope,
    /** Null for the list; a server id, or a [newServerRoute], for a detail page. */
    detail: String?,
    onDetail: (String?) -> Unit,
) {
    val servers by settings.servers.collectAsStateWithLifecycle(initialValue = emptyList())
    val activeId by settings.activeServerId.collectAsStateWithLifecycle(initialValue = "")

    when {
        detail == null ->
            ServerList(servers, activeId, accent, scope, settings, onDetail)

        detail == PICK_ROUTE ->
            ProviderPicker(accent) { onDetail(newServerRoute(it)) }

        else -> {
            val existing = servers.firstOrNull { it.id == detail }
            // A server being added has no stored config yet, so one is built here —
            // and remembered, because `ServerConfig` mints a fresh id on construction
            // and a new one per recomposition would reset the form under the user.
            val pending = remember(detail) {
                existing ?: ServerConfig(kind = pendingKind(detail) ?: ServerKind.NAVIDROME)
            }
            // Local-only mutable holder, so `config` stays the prop-backed source of truth.
            var config by remember(pending) { mutableStateOf(pending) }

            ServerDetail(
                configIn = config,
                isNew = existing == null,
                isActive = existing != null && existing.id == activeId,
                libraryVm = libraryVm,
                settings = settings,
                accent = accent,
                scope = scope,
                onDone = { onDetail(null) },
                onSaved = {
                    config = existing ?: config
                    onDetail(it)
                },
            )
        }
    }
}

// ── The list ──────────────────────────────────────────────────────────────

@Composable
private fun ServerList(
    servers: List<ServerConfig>,
    activeId: String,
    accent: Color,
    scope: CoroutineScope,
    settings: AppSettings,
    onDetail: (String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingsCard(
            title = "Your libraries",
            lead = "Pick the one the Library tab browses. The others stay set up and keep their " +
                "logins, and downloads play whichever server they came from either way.",
        ) {}

        if (servers.isEmpty()) {
            SettingsCard(
                title = "Nothing set up yet",
                lead = "Add Music Assistant to play to speakers around the house, or a " +
                    "Navidrome, Subsonic or Jellyfin server to browse and play on this phone — " +
                    "with downloads that keep working with no network at all.",
            ) {}
        }

        servers.forEach { server ->
            ServerCard(
                config = server,
                active = server.id == activeId,
                accent = accent,
                onOpen = { onDetail(server.id) },
                onActivate = { scope.launch { settings.setActiveServer(server.id) } },
            )
        }

        OledButton("Add a server", accent = accent, outline = servers.isNotEmpty()) { onDetail(PICK_ROUTE) }

        servers.firstOrNull { it.id == activeId }?.let { active ->
            // A lead rather than a Note inside an otherwise empty card: this card has no
            // controls, so the sentence *is* the card, and every other card here says
            // what it is for in the lead.
            SettingsCard(
                title = "What ${active.displayName} means for the rest of the app",
                lead = if (active.kind.playsLocally)
                    "${active.displayName} plays on this phone. Speaker grouping is a Music " +
                        "Assistant feature, so that tab is off. Light Sync still works, over " +
                        "the Hue Bridge directly."
                else
                    "Music Assistant browses the whole library, plays to any speaker on the " +
                        "network, and keeps grouping and Light Sync available.",
            ) {}
        }
    }
}

/**
 * One server, as a choice and a place to go.
 *
 * The card used to do both jobs through one tap and one button, and neither said which
 * was which: tapping anywhere opened a settings form, while the only button read
 * "Browse this library" — which sounds like it opens the library, and actually changes
 * which server the whole app is pointed at.
 *
 * So the two jobs are now two controls with the idiom each deserves. Picking the active
 * library is a *choice among the servers listed*, which is a radio button — the same one
 * [JellyfinLibraryCard] below uses for the same shape of question — and tapping the row
 * makes it. Opening this server's own settings is going somewhere, so it is a labelled
 * row with a chevron, like every other navigation row in Settings.
 *
 * The consequence of switching is printed under the choice rather than in a footnote
 * below the list, because it is not a small one: it decides whether Speakers works, and
 * where Light Sync gets its audio from.
 */
@Composable
private fun ServerCard(
    config: ServerConfig,
    active: Boolean,
    accent: Color,
    onOpen: () -> Unit,
    onActivate: () -> Unit,
) {
    GlassCard(radius = 18.dp) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    // The active server is already chosen; re-picking it would drop the
                    // connection and rebuild it for no change.
                    .clickable(enabled = !active, onClick = onActivate)
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (active) accent.a(0.16f) else Glass),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        kindIcon(config.kind), null,
                        tint = if (active) accent else TextMuted,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
                    Text(
                        config.displayName, color = TextPrimary, fontFamily = AppFont,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        config.host, color = TextFaint, fontFamily = MonoFont, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (active) "The Library tab is browsing this"
                        else "Tap to browse this instead",
                        color = if (active) accent else TextMuted,
                        fontFamily = AppFont, fontSize = 11.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    if (active) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                    if (active) "Active library" else "Switch to ${config.displayName}",
                    tint = if (active) accent else TextMuted,
                    modifier = Modifier.size(22.dp),
                )
            }

            Box(Modifier.padding(horizontal = 20.dp)) { CardDivider() }

            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpen)
                    .padding(horizontal = 20.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Default.Tune, null, tint = TextMuted, modifier = Modifier.size(17.dp))
                Text(
                    // Named, because two servers of the same kind are common and
                    // "Settings" over a list of three says nothing about which.
                    "${config.displayName} settings",
                    color = TextSecondary, fontFamily = AppFont,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Icon(Icons.Default.ChevronRight, null, tint = TextMuted, modifier = Modifier.size(20.dp))
            }
        }
    }
}

// ── The provider picker ───────────────────────────────────────────────────

/**
 * What can be added, and what is coming.
 *
 * The unbuilt providers are shown rather than hidden. A picker listing four things
 * says "this app supports four things"; one listing four and greying out twelve says
 * "here is where this is going" — which is true, and is the honest answer to someone
 * opening this screen looking for Plex.
 */
@Composable
private fun ProviderPicker(accent: Color, onPick: (ServerKind) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingsCard(
            title = "Add a server",
            lead = "Music Assistant plays to speakers around the house. Everything else is a " +
                "library this phone plays itself — which is what makes downloads, and music " +
                "that keeps working with the network down, possible.",
        ) {}

        ServerKind.available.forEach { kind ->
            NavRow(kindIcon(kind), kind.label, kind.blurb, accent) { onPick(kind) }
        }

        Spacer(Modifier.height(4.dp))
        FieldLabel("Not yet supported")
        Note(
            "Planned adapters rather than maybes — the endpoints and sign-in each one needs " +
                "are written up in docs/providers.md.",
        )
        ServerKind.planned.forEach { kind ->
            Box(Modifier.alpha(0.4f)) {
                NavRow(kindIcon(kind), kind.label, kind.blurb, accent, enabled = false) {}
            }
        }
    }
}

// ── One server's settings ─────────────────────────────────────────────────

/**
 * Everything about one server, in the order someone setting it up needs it: what to
 * call it, how to reach it, whether it answered, then the options that only mean
 * anything once it has.
 *
 * Per-server rather than global is the point. The stream-quality picker used to sit in
 * a shared "Servers" card where it silently meant "Navidrome's" — true right up until
 * there was a second library to ask it of.
 */
@Composable
private fun ServerDetail(
    configIn: ServerConfig,
    isNew: Boolean,
    isActive: Boolean,
    libraryVm: LibraryViewModel,
    settings: AppSettings,
    accent: Color,
    scope: CoroutineScope,
    onDone: () -> Unit,
    /** Re-point the route at the stored server once a new one has been saved. */
    onSaved: (String) -> Unit,
) {
    var config by remember(configIn.id) { mutableStateOf(configIn) }
    var label by remember(config.id) { mutableStateOf(config.label) }
    var url by remember(config.id) { mutableStateOf(config.url) }
    var user by remember(config.id) { mutableStateOf(config.username) }
    var pass by remember(config.id) { mutableStateOf(config.password) }
    var token by remember(config.id) { mutableStateOf(config.token) }
    var secretVisible by remember(config.id) { mutableStateOf(false) }
    var format by remember(config.id) {
        mutableStateOf(config.option(ServerConfig.OPT_STREAM_FORMAT) ?: "raw")
    }
    var confirmRemove by remember(config.id) { mutableStateOf(false) }

    val connecting by libraryVm.connecting.collectAsStateWithLifecycle()
    val ready by libraryVm.ready.collectAsStateWithLifecycle()
    val offline by libraryVm.offline.collectAsStateWithLifecycle()
    val connError by libraryVm.connError.collectAsStateWithLifecycle()

    /**
     * The edited form as a config, ready to store.
     *
     * A changed address or login drops any stored session token. It would otherwise
     * be tried against the new server — and, worse, a token issued by the *old* one
     * looks perfectly valid to the code that decides whether to sign in again.
     */
    fun edited(): ServerConfig {
        val credentialsChanged = url.trim() != config.url || user != config.username || pass != config.password
        return config.copy(
            label = label.trim(),
            url = url.trim(),
            username = user,
            password = pass,
            token = if (credentialsChanged && config.kind.auth != AuthStyle.TOKEN) "" else token,
            options = config.options
                .minus(LocalMediaSource.OPT_FOLDER_URIS)
                .plus(ServerConfig.OPT_STREAM_FORMAT to format),
        )
    }

    /** Write it into the list, replacing the entry with the same id or appending. */
    suspend fun save(makeActive: Boolean, customOptions: Map<String, String>? = null) {
        val next = edited().copy(options = customOptions ?: edited().options)
        val list = settings.servers.first()
        settings.saveServers(
            if (list.any { it.id == next.id }) list.map { if (it.id == next.id) next else it }
            else list + next
        )
        if (makeActive) settings.setActiveServer(next.id)
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingsCard(title = config.kind.label, lead = config.kind.blurb) {
            OledField(label, { label = it }, "Name (optional)", config.kind.label, accent)
            Note("What this server is called in the list. Worth setting once there is more than one.")
        }

        SettingsCard(
            title = "Connection",
            lead = when (config.kind.auth) {
                AuthStyle.OPTIONAL_USER_PASSWORD ->
                    "The address of the server. Credentials only if yours asks for them."
                AuthStyle.TOKEN -> "The address, and a token generated in the server's own settings."
                AuthStyle.LINKED_ACCOUNT -> "Signing in happens on the provider's own page."
                AuthStyle.NONE -> "Nothing to connect to — this reads music already on the phone."
                AuthStyle.USER_PASSWORD -> "The address of the server, and the login you use for it."
            },
        ) {
            if (config.kind.auth != AuthStyle.NONE) {
                OledField(url, { url = it }, "Server address", config.kind.urlHint, accent)
            }
            when (config.kind.auth) {
                AuthStyle.USER_PASSWORD, AuthStyle.OPTIONAL_USER_PASSWORD -> {
                    OledField(user, { user = it }, "Username", "", accent)
                    SecretField(pass, { pass = it }, "Password", accent, secretVisible, { secretVisible = it })
                }
                AuthStyle.TOKEN ->
                    SecretField(token, { token = it }, "Access token", accent, secretVisible, { secretVisible = it })
                else -> Unit
            }

            if (config.kind == ServerKind.LOCAL) {
                LocalFolderCard(config, accent, scope, settings) { next ->
                    config = next
                    scope.launch {
                        save(makeActive = true, customOptions = next.options)
                    }
                    if (isNew) onSaved(config.id)
                    libraryVm.switchTo(next)
                    libraryVm.connect()
                }
            } else {
                OledButton(
                    when {
                        connecting && isActive -> "Connecting…"
                        isNew -> "Save & connect"
                        else -> "Save & reconnect"
                    },
                    enabled = url.isNotBlank() && !(connecting && isActive),
                    accent = accent,
                ) {
                    scope.launch {
                        save(makeActive = true)
                        if (isNew) onSaved(config.id)
                        libraryVm.switchTo(edited())
                        libraryVm.connect()
                    }
                }

                // The status only speaks for the *active* server. A card for a library
                // nothing is connected to would otherwise borrow another one's answer.
                when {
                    isActive -> {
                        val (text, health) = when {
                            connecting -> "Connecting…" to Health.WORKING
                            offline -> "Offline — playing downloads" to Health.WARN
                            ready -> "Connected" to Health.GOOD
                            connError != null -> connError!! to Health.BAD
                            url.isBlank() -> "Not set up" to Health.IDLE
                            else -> "Not connected" to Health.IDLE
                        }
                        StatusLine(text, health, accent)
                    }
                    !isNew -> StatusLine("Not the active library", Health.IDLE, accent)
                }
            }
        }

        // Which of the server's libraries holds the music. Jellyfin only: a Jellyfin
        // server usually has films and television alongside, and browsing the lot as
        // one flat list is not a music library. Setup picks the first music library
        // automatically, which is right for the common single-library server — this is
        // the choice for everyone else, who until now had no way to see or change it.
        if (config.kind == ServerKind.JELLYFIN && isActive && !isNew) {
            JellyfinLibraryCard(
                selectedId = config.option(ServerConfig.OPT_LIBRARY_ID).orEmpty(),
                libraryVm = libraryVm,
                accent = accent,
            ) { libraryId ->
                val next = edited().withOption(ServerConfig.OPT_LIBRARY_ID, libraryId)
                config = next
                scope.launch {
                    save(makeActive = true, customOptions = next.options)
                    libraryVm.switchTo(next)
                    libraryVm.connect()
                }
            }
        }

        // The player this phone registers with *this* server.
        //
        // It used to live in its own top-level "CAMusic player" section, a screen away
        // from the server it belongs to and from the library it plays — so setting up
        // Music Assistant meant filling in an address here, then going elsewhere to say
        // what the player was called and what it should ask for. Server, library and
        // player are one setup, so they are now one page.
        if (config.kind == ServerKind.MUSIC_ASSISTANT && !isNew) {
            MaPlayerCards(settings = settings, accent = accent, scope = scope)
        }

        // Per-server playback options, for the kinds that stream to this phone.
        if (config.kind.playsLocally && config.kind != ServerKind.LOCAL) {
            SettingsCard(
                title = "Stream quality",
                lead = "What this server is asked to send. Downloads always take the original " +
                    "file, whatever this says.",
            ) {
                SegmentedToggle(
                    options = StreamFormatLabels,
                    selectedIndex = StreamFormatValues.indexOf(format).coerceAtLeast(0),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    format = StreamFormatValues[it]
                    // Stored on *this* server only. `saveServers` mirrors it into the
                    // legacy single-format key for the download and play-original
                    // paths, which is the right place for that to happen — writing it
                    // here as well meant editing one server's quality changed the
                    // stream of whichever server was connected.
                    scope.launch { save(makeActive = false) }
                }
                Note(
                    when (format) {
                        "raw" -> "The stored file, untouched. Best quality; needs the bandwidth " +
                            "the file was encoded at."
                        "flac" -> "Transcoded to FLAC. Still lossless, and a predictable size " +
                            "even when the library holds huge hi-res masters."
                        else -> "Transcoded, lossy. Worth it on a slow or metered connection."
                    },
                )
                Note("Applies to the next track.")
            }
        }

        if (!isNew) {
            SettingsCard(
                title = "Remove",
                lead = "Takes this server off the list. Nothing is deleted on the server, and " +
                    "downloads already on the phone keep playing.",
            ) {
                OledButton(
                    if (confirmRemove) "Tap again to remove" else "Remove ${config.displayName}",
                    accent = accent, danger = true,
                ) {
                    if (confirmRemove) {
                        scope.launch {
                            settings.saveServers(settings.servers.first().filterNot { it.id == config.id })
                            onDone()
                        }
                    } else confirmRemove = true
                }
            }
        }
    }
}

// ── Routing ───────────────────────────────────────────────────────────────

/** The detail route for the "which kind?" picker. */
internal const val PICK_ROUTE = "__pick__"

/**
 * A detail route for a server that does not exist yet.
 *
 * The picker chooses a *kind* before there is anything to store, so the kind travels
 * in the route rather than in a second piece of hoisted state — the route is already
 * `rememberSaveable`, so a rotation mid-setup lands back on the right form.
 */
internal fun newServerRoute(kind: ServerKind): String = "$NEW_PREFIX${kind.name}"

private const val NEW_PREFIX = "__new__:"

private fun pendingKind(detail: String): ServerKind? =
    if (detail.startsWith(NEW_PREFIX)) ServerKind.from(detail.removePrefix(NEW_PREFIX)) else null

private fun folderUriKey(configId: String) = "localFolders:$configId"

private fun parseFolderUris(json: String?): List<Uri> {
    if (json.isNullOrBlank()) return emptyList()
    val out = mutableListOf<Uri>()
    val arr = JSONArray(json)
    for (i in 0 until arr.length()) out += Uri.parse(arr.getString(i))
    return out
}

private fun encodeFolderUris(uris: List<Uri>): String = JSONArray(uris.map { it.toString() }).toString()

/**
 * Folder picker for local media.
 *
 * The chosen folder uri is persisted as a JSON array in the [ServerConfig] options
 * under [LocalMediaSource.OPT_FOLDER_URIS]. A persisted permission is taken so the
 * MediaStore query can read files under the tree after the picker closes.
 */
/**
 * This phone as a player on *this* Music Assistant server.
 *
 * Name, codec, what to ask the server for, its own gapless/crossfade config and the
 * announcement keep-alive — all of it describes one phone registered with one server,
 * and all of it is stored in that server's `options` (see `ServerConfig.OPT_PLAYER_NAME`
 * and its neighbours). Rendered here so the server, the library and the player are set
 * up together; the old top-level "CAMusic player" section now points at this page.
 */
@Composable
private fun MaPlayerCards(settings: AppSettings, accent: Color, scope: CoroutineScope) {
    PlayerSection(
        viewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
        settings = settings,
        accent = accent,
        scope = scope,
        // Reaching this composable at all means there is one.
        hasMaServer = true,
    )
    StreamingCard(settings, accent, scope)
}

/**
 * Which Jellyfin library the music lives in.
 *
 * Loaded once, when the card first composes, because it needs a live connection and
 * the answer does not change while the user is looking at it. A server with one music
 * library renders nothing at all — the automatic pick during setup is already correct
 * there, and a picker with a single option is just a control that cannot do anything.
 */
@Composable
private fun JellyfinLibraryCard(
    selectedId: String,
    libraryVm: LibraryViewModel,
    accent: Color,
    onPick: (String) -> Unit,
) {
    var libraries by remember { mutableStateOf<List<com.engabd.sendpin.ma.MaItem>?>(null) }
    LaunchedEffect(Unit) { libraries = libraryVm.jellyfinLibraries() }

    val list = libraries ?: return
    if (list.size < 2) return

    SettingsCard(
        title = "Music library",
        lead = "Which of this server's libraries to browse. The rest of the server — films, " +
            "television — is left alone.",
    ) {
        list.forEach { lib ->
            val selected = lib.itemId == selectedId
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = !selected) { onPick(lib.itemId) }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    if (selected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (selected) accent else TextMuted,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    lib.name,
                    color = if (selected) TextPrimary else TextMuted,
                    fontFamily = AppFont,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Note("Changing this reconnects and reloads the library.")
    }
}

@Composable
private fun LocalFolderCard(
    config: ServerConfig,
    accent: Color,
    scope: CoroutineScope,
    settings: AppSettings,
    onChange: (ServerConfig) -> Unit,
) {
    val context = LocalContext.current
    val uris = remember(config.id) { parseFolderUris(config.option(LocalMediaSource.OPT_FOLDER_URIS)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri: Uri? ->
        treeUri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val next = config.withOption(LocalMediaSource.OPT_FOLDER_URIS, encodeFolderUris(listOf(treeUri)))
        onChange(next)
    }

    SettingsCard(
        title = "Music folder",
        lead = if (uris.isEmpty()) "Pick the folder that holds your music." else "Using ${uris.size} folder${if (uris.size == 1) "" else "s"}.",
    ) {
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                Text(
                    uri.lastPathSegment ?: uri.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
        OledButton(
            if (uris.isEmpty()) "Choose folder" else "Change folder",
            accent = accent,
            outline = uris.isNotEmpty(),
        ) { launcher.launch(null) }
        Note("Only audio files you choose are indexed. The scan happens when you activate this library.")
    }
}

private fun kindIcon(kind: ServerKind): ImageVector = when (kind) {
    ServerKind.MUSIC_ASSISTANT -> Icons.Default.Cloud
    ServerKind.NAVIDROME, ServerKind.SUBSONIC -> Icons.Default.Storage
    ServerKind.JELLYFIN, ServerKind.EMBY, ServerKind.PLEX -> Icons.Default.Dns
    ServerKind.AUDIOBOOKSHELF -> Icons.AutoMirrored.Filled.MenuBook
    ServerKind.KODI -> Icons.Default.Tv
    ServerKind.SMB, ServerKind.WEBDAV -> Icons.Default.Folder
    ServerKind.LOCAL -> Icons.Default.Smartphone
    else -> Icons.Default.CloudQueue
}
