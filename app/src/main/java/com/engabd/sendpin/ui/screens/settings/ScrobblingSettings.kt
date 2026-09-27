package com.engabd.sendpin.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.scrobble.LastFmClient
import com.engabd.sendpin.scrobble.ListenBrainzClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * ListenBrainz and Last.fm, alongside the library server's own play counts.
 *
 * Both are off until set up. A listen counts at half the track or four minutes — the
 * convention both services use — and one that cannot be sent (offline, the service
 * down) waits in a queue and goes when the network comes back.
 */
@Composable
internal fun ScrobblingCard(settings: AppSettings, accent: Color, scope: CoroutineScope, advanced: Boolean) {
    val scrobbler = remember { SendpinApp.instance.playbackReporter.scrobbler }
    val pending by scrobbler.pending.collectAsStateWithLifecycle()

    ListenBrainzCard(settings, accent, advanced)
    LastFmCard(settings, accent, advanced)

    SettingsCard(
        title = "Waiting to be sent",
        lead = if (pending == 0) "Nothing waiting — every listen has been delivered."
        else "$pending listen${if (pending == 1) "" else "s"} waiting for a connection.",
        info = "A listen that could not be sent when it happened — no signal, a server " +
            "restarting, a service having a bad minute — is kept here and sent when a " +
            "network comes back, oldest first. Listens made offline from downloads count " +
            "the same way. A listen a service refuses outright is dropped rather than " +
            "retried for ever.",
    ) {
        if (pending > 0) OledButton("Send now", accent = accent, outline = true) { scope.launch { scrobbler.flush() } }
    }
}

@Composable
private fun ListenBrainzCard(settings: AppSettings, accent: Color, advanced: Boolean) {
    val scope = rememberCoroutineScope()
    val enabled by settings.listenBrainzEnabled.collectAsStateWithLifecycle(initialValue = false)
    val savedToken by settings.listenBrainzToken.collectAsStateWithLifecycle(initialValue = "")
    val savedUrl by settings.listenBrainzUrl.collectAsStateWithLifecycle(initialValue = "")
    val user by settings.listenBrainzUser.collectAsStateWithLifecycle(initialValue = "")
    var token by remember(savedToken) { mutableStateOf(savedToken) }
    var url by remember(savedUrl) { mutableStateOf(savedUrl) }
    var showToken by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<Pair<String, Health>?>(null) }

    SettingsCard(
        title = "ListenBrainz",
        lead = if (enabled && user.isNotBlank()) "Sending listens as $user" else "Open, free listen history from the MetaBrainz foundation",
        info = "Paste the user token from listenbrainz.org/settings. Nothing else is needed — " +
            "no app registration, no password.\n\nMaloja, Koito and multi-scrobbler accept " +
            "ListenBrainz submissions too: point Server address at yours (Advanced settings).",
    ) {
        SecretField(
            value = token, onChange = { token = it }, label = "User token", accent = accent,
            visible = showToken, onVisibilityChange = { showToken = it },
        )
        if (advanced) {
            OledField(url, { url = it }, "Server address", ListenBrainzClient.DEFAULT_URL, accent)
        }
        status?.let { (text, health) -> StatusLine(text, health, accent) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OledButton(if (enabled) "Save" else "Check & turn on", accent = accent, enabled = token.isNotBlank()) {
                status = "Checking the token…" to Health.WORKING
                scope.launch {
                    val name = runCatching { ListenBrainzClient(token.trim(), url).validate() }.getOrNull()
                    if (name != null) {
                        settings.setListenBrainz(true, token.trim(), url, name)
                        status = "Connected as $name" to Health.GOOD
                    } else {
                        status = "That token was not accepted" to Health.BAD
                    }
                }
            }
            if (enabled) OledButton("Turn off", accent = accent, outline = true) {
                scope.launch { settings.setListenBrainz(false, savedToken, savedUrl, user); status = null }
            }
        }
    }
}

@Composable
private fun LastFmCard(settings: AppSettings, accent: Color, advanced: Boolean) {
    val scope = rememberCoroutineScope()
    val scrobbler = remember { SendpinApp.instance.playbackReporter.scrobbler }
    val enabled by settings.lastFmEnabled.collectAsStateWithLifecycle(initialValue = false)
    val session by settings.lastFmSession.collectAsStateWithLifecycle(initialValue = "")
    val user by settings.lastFmUser.collectAsStateWithLifecycle(initialValue = "")
    val savedKey by settings.lastFmApiKey.collectAsStateWithLifecycle(initialValue = "")
    val savedSecret by settings.lastFmApiSecret.collectAsStateWithLifecycle(initialValue = "")
    val savedRoot by settings.lastFmApiRoot.collectAsStateWithLifecycle(initialValue = "")
    val buildHasApp = com.engabd.sendpin.BuildConfig.LASTFM_API_KEY.isNotBlank()
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var key by remember(savedKey) { mutableStateOf(savedKey) }
    var secret by remember(savedSecret) { mutableStateOf(savedSecret) }
    var root by remember(savedRoot) { mutableStateOf(savedRoot) }
    var status by remember { mutableStateOf<Pair<String, Health>?>(null) }

    SettingsCard(
        title = "Last.fm",
        lead = if (enabled && session.isNotBlank()) "Scrobbling as $user" else "Scrobble to Last.fm, or to Libre.fm",
        info = "Sign in with your Last.fm username and password once; the app keeps the " +
            "session Last.fm hands back, never the password.\n\nLast.fm also wants the " +
            "*app* registered. " + (if (buildHasApp) "This build carries a registration, so you can leave the API fields empty. "
            else "This build carries none, so enter the API key and secret of an app you registered at last.fm/api/account/create. ") +
            "\n\nLibre.fm speaks the same API: set API address to https://libre.fm/2.0/ " +
            "(Advanced settings).",
    ) {
        if (enabled && session.isNotBlank()) {
            OledButton("Sign out", accent = accent, outline = true) {
                scope.launch { settings.setLastFm(false, "", ""); status = null }
            }
        } else {
            OledField(username, { username = it }, "Username", "", accent)
            OledField(password, { password = it }, "Password", "", accent, visualTransformation = PasswordVisualTransformation())
            if (!buildHasApp || advanced) {
                OledField(key, { key = it }, "API key", if (buildHasApp) "the build's" else "", accent)
                OledField(secret, { secret = it }, "API secret", if (buildHasApp) "the build's" else "", accent,
                    visualTransformation = PasswordVisualTransformation())
            }
            if (advanced) OledField(root, { root = it }, "API address", LastFmClient.DEFAULT_ROOT, accent)
            status?.let { (text, health) -> StatusLine(text, health, accent) }
            OledButton("Sign in", accent = accent, enabled = username.isNotBlank() && password.isNotBlank()) {
                status = "Signing in…" to Health.WORKING
                scope.launch {
                    settings.setLastFmApp(key, secret, root)
                    val (k, s) = scrobbler.lastFmApp()
                    if (k.isBlank() || s.isBlank()) {
                        status = "An API key and secret are needed first" to Health.WARN
                        return@launch
                    }
                    runCatching { LastFmClient(k, s, apiRoot = root).signIn(username.trim(), password) }
                        .onSuccess { (sk, name) ->
                            settings.setLastFm(true, sk, name)
                            password = ""
                            status = "Signed in as $name" to Health.GOOD
                        }
                        .onFailure { status = (it.message ?: "Sign-in failed") to Health.BAD }
                }
            }
        }
    }
}
