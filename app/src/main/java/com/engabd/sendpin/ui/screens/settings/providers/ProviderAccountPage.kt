package com.engabd.sendpin.ui.screens.settings.providers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.library.AuthStyle
import com.engabd.sendpin.library.ProviderAccount
import com.engabd.sendpin.library.ProviderAppCredentials
import com.engabd.sendpin.library.ProviderSettings
import com.engabd.sendpin.library.ServerConfig
import com.engabd.sendpin.library.ServerKind
import com.engabd.sendpin.ma.LibraryViewModel
import com.engabd.sendpin.ui.design.ExperimentalBadge
import com.engabd.sendpin.ui.design.GlassCard
import com.engabd.sendpin.ui.design.ProviderSkin
import com.engabd.sendpin.ui.design.ProviderTheme
import com.engabd.sendpin.ui.design.ServerKindGlyph
import com.engabd.sendpin.ui.design.providerSkin
import com.engabd.sendpin.ui.screens.settings.FieldLabel
import com.engabd.sendpin.ui.screens.settings.Health
import com.engabd.sendpin.ui.screens.settings.Note
import com.engabd.sendpin.ui.screens.settings.OledButton
import com.engabd.sendpin.ui.screens.settings.OledField
import com.engabd.sendpin.ui.screens.settings.SecretField
import com.engabd.sendpin.ui.screens.settings.SegmentedToggleRow
import com.engabd.sendpin.ui.screens.settings.SettingsCard
import com.engabd.sendpin.ui.screens.settings.SliderRow
import com.engabd.sendpin.ui.screens.settings.StatusLine
import com.engabd.sendpin.ui.screens.settings.StatusRow
import com.engabd.sendpin.ui.screens.settings.TidalSignInRow
import com.engabd.sendpin.ui.screens.settings.ToggleRow
import com.engabd.sendpin.ui.theme.AppFont
import com.engabd.sendpin.ui.theme.Ink
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextPrimary
import com.engabd.sendpin.ui.theme.TextSecondary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The settings page for a streaming account — Spotify, Qobuz or Tidal.
 *
 * Not the generic server form: an account has no address, nothing to scan, and no
 * "stream quality" the app could ask a server for. What it has is the service's
 * own settings — the knobs its client or engine actually exposes, listed in
 * [ProviderSettings] — and an account to describe. Dressed in the service's own
 * colours and type by [ProviderTheme], so it reads as that service's page.
 *
 * Every control persists on the spot, the way the generic form's stream-quality
 * toggle does, and the ones that only take effect on the next connection say so.
 */
@Composable
internal fun ProviderAccountPage(
    configIn: ServerConfig,
    isNew: Boolean,
    isActive: Boolean,
    libraryVm: LibraryViewModel,
    settings: AppSettings,
    scope: CoroutineScope,
    onDone: () -> Unit,
    /** Re-point the route at the stored server once a new one has been saved. */
    onSaved: (String) -> Unit,
) {
    val skin = providerSkin(configIn.kind) ?: return
    ProviderTheme(skin) { ProviderPageBody(skin, configIn, isNew, isActive, libraryVm, settings, scope, onDone, onSaved) }
}

@Composable
private fun ProviderPageBody(
    skin: ProviderSkin,
    configIn: ServerConfig,
    isNew: Boolean,
    isActive: Boolean,
    libraryVm: LibraryViewModel,
    settings: AppSettings,
    scope: CoroutineScope,
    onDone: () -> Unit,
    onSaved: (String) -> Unit,
) {
    val accent = skin.accent
    val kind = configIn.kind
    var config by remember(configIn.id) { mutableStateOf(configIn) }
    var label by remember(configIn.id) { mutableStateOf(configIn.label) }
    var user by remember(configIn.id) { mutableStateOf(configIn.username) }
    var pass by remember(configIn.id) { mutableStateOf(configIn.password) }
    var secretVisible by remember(configIn.id) { mutableStateOf(false) }
    var confirmRemove by remember(configIn.id) { mutableStateOf(false) }

    val connecting by libraryVm.connecting.collectAsStateWithLifecycle()
    val ready by libraryVm.ready.collectAsStateWithLifecycle()
    val connError by libraryVm.connError.collectAsStateWithLifecycle()

    /** The form as a config, ready to store. Options ride along untouched. */
    fun edited(): ServerConfig {
        val credentialsChanged = user != config.username || pass != config.password
        return config.copy(
            label = label.trim(),
            username = user,
            password = pass,
            // A changed login drops the stored session token for the USER_PASSWORD
            // kinds; Tidal's tokens live in options and belong to the sign-in row.
            token = if (credentialsChanged && kind.auth == AuthStyle.USER_PASSWORD) "" else config.token,
        )
    }

    /** Write [next] into the list, replacing the entry with the same id or appending. */
    suspend fun persist(next: ServerConfig, makeActive: Boolean) {
        config = next
        val list = settings.servers.first()
        settings.saveServers(
            if (list.any { it.id == next.id }) list.map { if (it.id == next.id) next else it } else list + next,
        )
        if (makeActive) settings.setActiveServer(next.id)
    }

    /**
     * Persist one option, without reconnecting: the source reads it when next built.
     * A page for an account not yet saved keeps it in the form instead — the first
     * save is the sign-in, and half a Tidal with no token has no place in the list.
     */
    fun setOption(key: String, value: String) {
        val next = edited().withOption(key, value)
        if (isNew) config = next else scope.launch { persist(next, makeActive = false) }
    }

    /** Save the form and (re)connect, making this the active library. */
    fun saveAndConnect() {
        val next = edited()
        scope.launch {
            persist(next, makeActive = true)
            if (isNew) onSaved(next.id)
            libraryVm.switchTo(next)
            libraryVm.connect()
        }
    }

    val signedIn = when (kind.auth) {
        AuthStyle.LINKED_ACCOUNT -> config.option(ServerConfig.OPT_TIDAL_ACCESS_TOKEN) != null
        else -> user.isNotBlank() && pass.isNotBlank()
    }

    // The account as the service describes it, once this library is connected.
    var account by remember(configIn.id) { mutableStateOf<ProviderAccount?>(null) }
    LaunchedEffect(configIn.id, isActive, ready) {
        account = if (isActive && ready) libraryVm.providerAccount() else null
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Hero(skin, isActive, connecting, ready, connError, signedIn, isNew)

        // ── Account ──────────────────────────────────────────────────────────
        SettingsCard(
            title = "Account",
            lead = when (kind) {
                ServerKind.SPOTIFY -> "A Spotify Premium account. Played by this phone through an embedded Spotify client; " +
                    "it shows up in Spotify Connect under the device name below."
                ServerKind.QOBUZ -> "Your Qobuz login. Streaming rights follow the subscription: Studio and Sublime " +
                    "stream hi-res, Solo streams CD quality."
                ServerKind.TIDAL -> "Signed in on Tidal's own page. HiFi Plus streams up to Max; HiFi stops at CD quality."
                else -> null
            },
        ) {
            when (kind.auth) {
                AuthStyle.LINKED_ACCOUNT -> TidalSignInRow(
                    config = config,
                    accent = accent,
                    scope = scope,
                    onSave = { next ->
                        scope.launch {
                            persist(next, makeActive = true)
                            if (isNew) onSaved(next.id)
                            libraryVm.switchTo(next)
                            libraryVm.connect()
                        }
                    },
                )
                else -> {
                    OledField(user, { user = it }, if (kind == ServerKind.QOBUZ) "Email" else "Username", "", accent)
                    SecretField(pass, { pass = it }, "Password", accent, secretVisible, { secretVisible = it })
                    OledButton(
                        when {
                            connecting && isActive -> "Connecting…"
                            isNew -> "Save & connect"
                            else -> "Save & reconnect"
                        },
                        enabled = signedIn && !(connecting && isActive),
                        accent = accent,
                    ) { saveAndConnect() }
                }
            }
            when {
                isActive -> {
                    val (text, health) = when {
                        connecting -> "Connecting…" to Health.WORKING
                        ready -> "Connected" to Health.GOOD
                        connError != null -> connError!! to Health.BAD
                        !signedIn -> "Not signed in" to Health.IDLE
                        else -> "Not connected" to Health.IDLE
                    }
                    StatusLine(text, health, accent)
                }
                !isNew -> StatusLine("Not the active library", Health.IDLE, accent)
            }
            account?.let { a ->
                Spacer(Modifier.height(2.dp))
                FieldLabel(skinLabel(skin, "Signed in as"))
                a.name?.let { StatusRow("Name", it) }
                a.plan?.let { StatusRow("Plan", it) }
                a.maxQuality?.let { StatusRow("Streams up to", it) }
                a.country?.let { StatusRow("Country", it) }
            }
        }

        // ── Quality, in the service's own tiers ──────────────────────────────
        when (kind) {
            ServerKind.SPOTIFY -> SpotifyCards(skin, config, account, ::setOption)
            ServerKind.QOBUZ -> QobuzQualityCard(skin, config, account, ::setOption)
            ServerKind.TIDAL -> TidalQualityCard(skin, config, account, ::setOption)
            else -> Unit
        }

        // ── App registration (Qobuz / Tidal, only when the build ships none) ─
        if (ProviderAppCredentials.asksUser(kind)) {
            AppRegistrationCard(skin, config, secretVisible, { secretVisible = it }) { next -> config = next }
        }

        // ── This library ─────────────────────────────────────────────────────
        SettingsCard(
            title = "This library",
            lead = "What this account is called in the list, and its place in it.",
        ) {
            OledField(label, { label = it }, "Name (optional)", kind.label, accent)
            if (!isNew) {
                OledButton("Save name", accent = accent, outline = true) {
                    scope.launch { persist(edited(), makeActive = false) }
                }
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

/** The band at the top: the mark, the wordmark, a line of the service's voice, and its state. */
@Composable
private fun Hero(
    skin: ProviderSkin,
    isActive: Boolean,
    connecting: Boolean,
    ready: Boolean,
    connError: String?,
    signedIn: Boolean,
    isNew: Boolean,
) {
    val shape = RoundedCornerShape(skin.cornerRadius.coerceAtLeast(12.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(skin.hero)),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ServerKindGlyph(skin.kind, tint = Color.White, modifier = Modifier.size(52.dp))
                Column {
                    Text(
                        if (skin.kind == ServerKind.TIDAL) skin.kind.label.uppercase() else skin.kind.label,
                        color = Color.White,
                        style = skin.display,
                    )
                    Text(skin.tagline, color = Color.White.copy(alpha = 0.78f), fontFamily = AppFont, style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val (text, tint) = when {
                    !isActive && !isNew -> "Not the active library" to Color.White.copy(alpha = 0.6f)
                    connecting -> "Connecting…" to Color.White
                    ready && isActive -> "Connected" to skin.accent
                    connError != null && isActive -> "Not connected" to Color(0xFFFF6B6B)
                    !signedIn -> "Sign in to start" to Color.White.copy(alpha = 0.7f)
                    else -> "Ready to connect" to Color.White.copy(alpha = 0.7f)
                }
                Box(Modifier.size(7.dp).clip(RoundedCornerShape(100)).background(tint))
                Text(text, color = Color.White.copy(alpha = 0.9f), style = skin.label)
                Spacer(Modifier.width(2.dp))
                ExperimentalBadge()
            }
        }
    }
}

/** A section label in the skin's own small-caps voice. */
private fun skinLabel(skin: ProviderSkin, text: String): String =
    if (skin.kind == ServerKind.TIDAL || skin.kind == ServerKind.SPOTIFY) text.uppercase() else text

// ── Spotify ──────────────────────────────────────────────────────────────────

@Composable
private fun SpotifyCards(
    skin: ProviderSkin,
    config: ServerConfig,
    account: ProviderAccount?,
    setOption: (String, String) -> Unit,
) {
    val accent = skin.accent
    val prefs = ProviderSettings.SpotifyPrefs.from(config)
    SettingsCard(
        title = "Audio quality",
        lead = "What the embedded client asks Spotify for. Very high needs Premium; a free account is served High.",
    ) {
        val tiers = ProviderSettings.SpotifyQuality.entries
        SegmentedToggleRow(
            labels = tiers.map { it.label },
            selectedIndex = tiers.indexOf(prefs.quality),
        ) { setOption(ProviderSettings.OPT_SPOTIFY_QUALITY, tiers[it].key) }
        Note(prefs.quality.detail)
        Note("Applies to the next track.")
    }
    SettingsCard(
        title = "Playback",
        lead = "The embedded Spotify client's own options — the same ones the Spotify app keeps under Playback.",
    ) {
        ToggleRow(
            title = "Normalise volume",
            subtitle = "Even out the loudness between tracks, as Spotify's own app does.",
            checked = prefs.normalise, accent = accent,
        ) { setOption(ProviderSettings.OPT_SPOTIFY_NORMALISE, it.toString()) }
        ToggleRow(
            title = "Autoplay",
            subtitle = "Keep playing similar music when what you queued runs out.",
            checked = prefs.autoplay, accent = accent,
        ) { setOption(ProviderSettings.OPT_SPOTIFY_AUTOPLAY, it.toString()) }
        ToggleRow(
            title = "Preload the next track",
            subtitle = "Fetch what plays next ahead of time, for a gapless join.",
            checked = prefs.preload, accent = accent,
        ) { setOption(ProviderSettings.OPT_SPOTIFY_PRELOAD, it.toString()) }
        FieldLabel(skinLabel(skin, "Crossfade"))
        SliderRow(
            value = prefs.crossfadeSeconds / 12f,
            format = { v -> val s = (v * 12).toInt(); if (s == 0) "Off" else "$s s" },
        ) { v -> setOption(ProviderSettings.OPT_SPOTIFY_CROSSFADE_S, (v * 12).toInt().toString()) }
        FieldLabel(skinLabel(skin, "Device name"))
        var name by remember(config.id) { mutableStateOf(prefs.deviceName) }
        OledField(
            name, { name = it }, "Shown in Spotify Connect", ProviderSettings.DEFAULT_SPOTIFY_DEVICE_NAME, accent,
            trailingIcon = if (name.trim() != prefs.deviceName) {
                { Text("Save", color = accent, fontWeight = FontWeight.Bold, modifier = Modifier.clickable {
                    setOption(ProviderSettings.OPT_SPOTIFY_DEVICE_NAME, name.trim())
                }.padding(end = 6.dp)) }
            } else null,
        )
        Note("Changes here take effect when the Spotify session is next opened — reconnect from the Account card, or restart playback.")
    }
    if (account?.plan != null && account.plan.lowercase() != "premium") {
        SettingsCard(title = "Premium required") {
            Note("This account is ${account.plan}. Spotify only lets third-party clients play on Premium; browsing works, playback will not.", warn = true)
        }
    }
}

// ── Qobuz ────────────────────────────────────────────────────────────────────

@Composable
private fun QobuzQualityCard(
    skin: ProviderSkin,
    config: ServerConfig,
    account: ProviderAccount?,
    setOption: (String, String) -> Unit,
) {
    val chosen = ProviderSettings.QobuzQuality.from(config)
    val tiers = ProviderSettings.QobuzQuality.entries
    SettingsCard(
        title = "Streaming quality",
        lead = "The highest format Qobuz is asked for. Anything a track is not available in falls back to the next tier down, the way Qobuz's own apps do.",
    ) {
        SegmentedToggleRow(
            labels = tiers.map { it.label },
            selectedIndex = tiers.indexOf(chosen),
        ) { setOption(ProviderSettings.OPT_QOBUZ_MAX_FORMAT, tiers[it].formatId.toString()) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (chosen.hiRes) HiResBadge(skin)
            Text(chosen.detail, color = TextSecondary, fontFamily = AppFont, style = MaterialTheme.typography.bodySmall)
        }
        if (account?.hiResAllowed == false && chosen.hiRes) {
            Note("This subscription streams up to CD quality; Hi-Res requests fall back to CD automatically.", warn = true)
        }
        Note("Applies to the next track.")
    }
}

/** Qobuz's gold Hi-Res mark, as its apps print it beside a hi-res album. */
@Composable
private fun HiResBadge(skin: ProviderSkin) {
    Box(
        Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(skin.accent2)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text("Hi-Res", color = Ink, style = skin.label.copy(letterSpacing = 1.sp), fontWeight = FontWeight.Bold)
    }
}

// ── Tidal ────────────────────────────────────────────────────────────────────

@Composable
private fun TidalQualityCard(
    skin: ProviderSkin,
    config: ServerConfig,
    account: ProviderAccount?,
    setOption: (String, String) -> Unit,
) {
    val chosen = ProviderSettings.TidalQuality.from(config)
    val tiers = ProviderSettings.TidalQuality.entries
    SettingsCard(
        title = "Streaming quality",
        lead = "Tidal's own tiers, named as its app names them. A track not available at the chosen tier plays at the next one down.",
    ) {
        SegmentedToggleRow(
            labels = tiers.map { it.label },
            selectedIndex = tiers.indexOf(chosen),
        ) { setOption(ProviderSettings.OPT_TIDAL_QUALITY, tiers[it].wire) }
        Text(chosen.detail, color = TextSecondary, fontFamily = AppFont, style = MaterialTheme.typography.bodySmall)
        account?.maxQuality?.let { max ->
            val cap = tiers.firstOrNull { it.label == max }
            if (cap != null && chosen.ordinal > cap.ordinal) {
                Note("This plan streams up to $max; higher tiers fall back to it automatically.", warn = true)
            }
        }
        Note("Applies to the next track.")
    }
}

// ── App registration ─────────────────────────────────────────────────────────

@Composable
private fun AppRegistrationCard(
    skin: ProviderSkin,
    config: ServerConfig,
    secretVisible: Boolean,
    onSecretVisible: (Boolean) -> Unit,
    onChange: (ServerConfig) -> Unit,
) {
    val accent = skin.accent
    val qobuz = config.kind == ServerKind.QOBUZ
    val idKey = if (qobuz) ServerConfig.OPT_QOBUZ_APP_ID else ServerConfig.OPT_TIDAL_CLIENT_ID
    val secretKey = if (qobuz) ServerConfig.OPT_QOBUZ_APP_SECRET else ServerConfig.OPT_TIDAL_CLIENT_SECRET
    val filled = config.option(idKey) != null
    var open by remember(config.id) { mutableStateOf(!filled) }
    GlassCard(radius = skin.cornerRadius.coerceAtLeast(4.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { open = !open },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("App registration", color = TextPrimary, fontFamily = AppFont, style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (filled) "Set" else "Needed before this account can connect",
                        color = TextMuted, fontFamily = AppFont, style = MaterialTheme.typography.bodySmall,
                    )
                }
                Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = TextMuted)
            }
            AnimatedVisibility(open) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Note(
                        "Separate from your account: ${config.kind.label} identifies the calling app " +
                            "with a pair of its own, and this build carries none. Paste yours and it " +
                            "is kept with this library.",
                    )
                    OledField(
                        config.option(idKey).orEmpty(),
                        { onChange(config.withOption(idKey, it)) },
                        if (qobuz) "App ID" else "Client ID", "", accent,
                    )
                    SecretField(
                        config.option(secretKey).orEmpty(),
                        { onChange(config.withOption(secretKey, it)) },
                        if (qobuz) "App secret" else "Client secret",
                        accent, secretVisible, onSecretVisible,
                    )
                    Note("Saved with the account when you connect.")
                }
            }
        }
    }
}
