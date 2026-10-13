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
import com.engabd.sendpin.discovery.PlayerIdentity
import com.engabd.sendpin.plex.PlexAuth
import com.engabd.sendpin.ui.design.DiscoveredServerRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Plex PIN sign-in, done in place of a password field, on Settings →
 * Libraries and in onboarding alike.
 *
 * `AuthStyle.LINKED_ACCOUNT` has no fields of its own to render (there is no
 * password this app is allowed to see), so this fills that space instead: a
 * button that mints a PIN, opens plex.tv in the browser with it pre-filled, and
 * polls [PlexAuth] until the user finishes signing in there or gives up. See
 * [PlexAuth]'s own docs for why this is a whole flow rather than a token
 * exchange like Jellyfin's.
 *
 * The moment sign-in succeeds, [PlexAuth.listResources] asks plex.tv which Plex
 * Media Servers are on the account, remote ones included, and offers them as
 * [DiscoveredServerRow]s below the button. [onUrl] fills the address field
 * above the same way a LAN pick does; [onToken] carries the sign-in itself.
 */
@Composable
internal fun PlexSignInRow(
    hasToken: Boolean,
    onToken: (String) -> Unit,
    onUrl: (String) -> Unit,
    accent: Color,
    scope: CoroutineScope,
) {
    val context = LocalContext.current
    var working by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var resources by remember { mutableStateOf<List<PlexAuth.PlexResource>>(emptyList()) }

    OledButton(
        when {
            working -> "Waiting for plex.tv…"
            hasToken -> "Signed in — sign in again"
            else -> "Sign in with Plex"
        },
        enabled = !working,
        accent = accent,
    ) {
        working = true
        status = null
        resources = emptyList()
        scope.launch {
            val clientId = PlayerIdentity.getPlayerId(context)
            try {
                val pin = PlexAuth.requestPin(clientId)
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(PlexAuth.authUrl(pin, clientId)))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                var token: String? = null
                var attempts = 0
                // Plex's own apps poll every couple of seconds; ten minutes covers a
                // slow browser round trip without polling for ever if the tab is
                // abandoned.
                while (token == null && attempts < 300) {
                    delay(2000)
                    token = PlexAuth.pollPin(pin, clientId)
                    attempts++
                }
                val signedIn = token
                if (signedIn != null) {
                    onToken(signedIn)
                    resources = PlexAuth.listResources(signedIn, clientId)
                    // The address field sits above this button, so that is where
                    // these point: "below" sent people looking the wrong way.
                    status = if (resources.isEmpty()) {
                        "Signed in. Enter your server's address above."
                    } else {
                        "Signed in. Pick a server below, or enter its address above."
                    }
                } else {
                    status = "Timed out waiting for plex.tv. Try again."
                }
            } catch (e: Exception) {
                status = e.message ?: "Couldn't reach plex.tv"
            } finally {
                working = false
            }
        }
    }
    Note(
        status ?: if (hasToken) "Signed in. Sign in again if playback ever stops working."
        else "Opens plex.tv in your browser to sign in, then comes back here on its own.",
    )
    resources.forEach { resource ->
        DiscoveredServerRow(resource.name, resource.url, "PL", accent) { onUrl(resource.url) }
    }
}
