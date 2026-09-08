package com.engabd.sendpin.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.engabd.sendpin.library.ProviderAppCredentials
import com.engabd.sendpin.library.ServerConfig
import com.engabd.sendpin.tidal.TidalClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Tidal's device sign-in, the Plex row's shape: one button, a browser round trip,
 * and the result written straight into the config. Differences are Tidal's — the
 * user is shown a code as well as a URL (device authorization grant), the poll is
 * bounded at five minutes like Plex's, and the finished token set lands in the
 * config's options rather than in the single `token` field, because Tidal's session
 * is a *pair* plus expiry, user id and country.
 *
 * Shared with the onboarding wizard, which offers the same three streaming accounts
 * the Libraries screen does — a first-run Tidal sign-in is this row, not a second
 * implementation of the device flow.
 */
@Composable
internal fun TidalSignInRow(
    config: ServerConfig,
    accent: Color,
    scope: CoroutineScope,
    onSave: (ServerConfig) -> Unit,
) {
    val context = LocalContext.current
    var working by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val hasToken = config.option(ServerConfig.OPT_TIDAL_ACCESS_TOKEN) != null

    OledButton(
        when {
            working -> "Waiting for Tidal…"
            hasToken -> "Signed in — sign in again"
            else -> "Sign in with Tidal"
        },
        enabled = !working,
        accent = accent,
    ) {
        working = true
        status = null
        scope.launch {
            try {
                val client = TidalClient(
                    clientId = ProviderAppCredentials.tidalClientId(config),
                    clientSecret = ProviderAppCredentials.tidalClientSecret(config),
                )
                if (client.clientId.isBlank() || client.clientSecret.isBlank()) {
                    // Nothing to sign in *with*: this build shipped without a Tidal
                    // registration and the form's own pair is still empty. Said
                    // here rather than left to a 401 from Tidal, which reads like
                    // the account was refused.
                    status = "Add this app's Tidal client id and secret above first."
                    return@launch
                }
                val (deviceCode, userCode, verificationUrl) = client.startDeviceLogin()
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(verificationUrl))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                status = "Enter this code on the Tidal page: $userCode"
                var tokens: TidalClient.TokenResponse? = null
                var attempts = 0
                while (tokens == null && attempts < 150) {
                    delay(2000)
                    tokens = client.pollDeviceLoginOnce(deviceCode)
                    attempts++
                }
                val signedIn = tokens
                if (signedIn != null) {
                    // One merged option map, one onConfig call: the callback both
                    // persists and connects, so calling it twice would connect twice.
                    var updated = config
                        .withOption(ServerConfig.OPT_TIDAL_ACCESS_TOKEN, signedIn.accessToken)
                        .withOption(ServerConfig.OPT_TIDAL_REFRESH_TOKEN, signedIn.refreshToken.orEmpty())
                        .withOption(
                            ServerConfig.OPT_TIDAL_TOKEN_EXPIRES_AT,
                            (System.currentTimeMillis() / 1000 + signedIn.expiresIn).toString(),
                        )
                    signedIn.userId?.let { updated = updated.withOption(ServerConfig.OPT_TIDAL_USER_ID, it) }
                    signedIn.countryCode?.let { updated = updated.withOption(ServerConfig.OPT_TIDAL_COUNTRY_CODE, it) }
                    onSave(updated)
                    status = "Signed in."
                } else {
                    status = "Timed out waiting for Tidal. Try again."
                }
            } catch (e: Exception) {
                status = e.message ?: "Couldn't reach Tidal"
            } finally {
                working = false
            }
        }
    }
    Note(
        status ?: if (hasToken) "Signed in. Sign in again if playback ever stops working."
        else "Opens Tidal in your browser; enter the code shown here to approve this device.",
    )
}
