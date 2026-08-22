package com.engabd.sendpin.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.engabd.sendpin.ma.LibraryViewModel
import com.engabd.sendpin.ma.LibraryViewModel.Backend
import com.engabd.sendpin.subsonic.SavedQueue
import com.engabd.sendpin.ui.design.*
import com.engabd.sendpin.ui.theme.*
import com.engabd.sendpin.library.ServerConfig
import com.engabd.sendpin.local.LocalMediaSource
import com.engabd.sendpin.library.ServerKind

/**
 * The library when it is not a grid of music: connecting, offline, empty, failed, or
 * asking for credentials — plus the bars that sit over a list that *is* music.
 *
 * Split out of `LibraryScreen.kt` — see the note at the top of that file. These are
 * the states a screenshot never shows and every user eventually sees.
 */

@Composable
internal fun PlayAllBar(count: Int, onPlayAll: () -> Unit, onDownloadAll: (() -> Unit)? = null) {
    val accent = LocalAccent.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(accent.a(0.10f))
            .border(1.dp, accent.a(0.28f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(onClick = onPlayAll),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Default.PlayArrow, null, tint = accent, modifier = Modifier.size(18.dp))
            Text(
                "Play all $count tracks", color = accent, fontFamily = AppFont,
                fontWeight = FontWeight.Bold, fontSize = 13.sp,
            )
        }
        onDownloadAll?.let {
            Icon(
                Icons.Default.Download, "Download all", tint = accent,
                modifier = Modifier.size(20.dp).clip(CircleShape).clickable(onClick = it),
            )
        }
    }
}

/**
 * "You were listening to this somewhere else."
 *
 * Navidrome keeps one saved queue per user, and every client that supports it writes
 * to the same slot — so this is genuinely "start on the phone, finish at the desk".
 * Named after the client that left it where the server said which one that was:
 * "Resume from your laptop" is a different offer from "resume from this phone", and
 * the difference is most of why anyone would tap it.
 *
 * An offer, not a prompt. It never blocks the library, it goes away on dismissal, and
 * starting anything here supersedes it.
 */
@Composable
internal fun ResumeCard(saved: SavedQueue, onResume: () -> Unit, onDismiss: () -> Unit) {
    val accent = LocalAccent.current
    val track = saved.tracks.getOrNull(saved.index)
    val minutes = (saved.positionMs / 60_000).toInt()
    val seconds = ((saved.positionMs / 1000) % 60).toInt()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(accent.a(0.10f))
            .border(1.dp, accent.a(0.28f), RoundedCornerShape(14.dp))
            .clickable(onClick = onResume)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Default.History, null, tint = accent,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
            Text(
                saved.changedBy?.takeIf { it.isNotBlank() }
                    ?.let { "Pick up from $it" } ?: "Pick up where you left off",
                color = TextPrimary, fontFamily = AppFont,
                fontWeight = FontWeight.Bold, fontSize = 13.sp,
            )
            Text(
                buildString {
                    track?.let { append(it.name); it.subtitle?.let { a -> append(" · $a") } }
                    append("  ")
                    append("%d:%02d in".format(minutes, seconds))
                },
                color = TextMuted, fontFamily = AppFont, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.Default.Close, "Dismiss", tint = TextMuted,
            modifier = Modifier.size(18.dp).clip(CircleShape).clickable(onClick = onDismiss),
        )
    }
}

/**
 * The server is out of reach and the phone is running on its downloads. This is
 * the feature working, not an error, so it reads as a state rather than a fault —
 * with the one action that matters if the server is actually back.
 */
@Composable
internal fun OfflineNotice(onRetry: () -> Unit) {
    val accent = LocalAccent.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(inkOn(0.04f))
            .border(1.dp, HairlineSoft, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Default.CloudOff, null, tint = TextMuted, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
            Text(
                "Offline", color = TextPrimary, fontFamily = AppFont,
                fontWeight = FontWeight.Bold, fontSize = 13.sp,
            )
            Text(
                "Navidrome isn't reachable - playing your downloads.",
                color = TextMuted, fontFamily = AppFont, fontSize = 11.sp,
            )
        }
        Text(
            "Retry", color = accent, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.clip(RoundedCornerShape(100)).clickable(onClick = onRetry)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

// --- states ---------------------------------------------------------------

/** Placeholder rows while a saved server finishes handshaking. */
@Composable
internal fun ConnectingState() {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(6) { SkeletonRow() }
    }
}

@Composable
internal fun SkeletonRow() {
    RowCard {
        // The sweep goes on each block rather than the row, so it tracks each one's
        // own width — a single sweep across the whole row would cross the 46dp thumb
        // in a couple of frames and then crawl the rest of the way.
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(11.dp)).background(inkOn(0.06f)).shimmer())
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth(0.6f).height(12.dp).clip(RoundedCornerShape(4.dp)).background(inkOn(0.07f)).shimmer())
            Box(Modifier.fillMaxWidth(0.38f).height(10.dp).clip(RoundedCornerShape(4.dp)).background(inkOn(0.05f)).shimmer())
        }
    }
}

@Composable
internal fun SearchEmptyState(
    title: String = "No results",
    body: String = "Try a different search, or check your spelling.",
) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(Glass)
                .border(1.dp, Hairline, RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.Search, null, tint = TextFaint, modifier = Modifier.size(26.dp)) }
        Spacer(Modifier.height(16.dp))
        Text(title, color = inkOn(0.75f), fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            body, color = TextMuted, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.widthIn(max = 240.dp),
        )
    }
}

@Composable
internal fun SearchErrorState(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 70.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(ErrorRed.a(0.10f))
                .border(1.dp, ErrorRed.a(0.28f), RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.ErrorOutline, null, tint = ErrorRed, modifier = Modifier.size(26.dp)) }
        Spacer(Modifier.height(16.dp))
        Text("Couldn't load", color = inkOn(0.8f), fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            message, color = TextMuted, style = MaterialTheme.typography.bodyMedium,
            maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 260.dp),
        )
        Spacer(Modifier.height(18.dp))
        Box(
            Modifier.clip(RoundedCornerShape(100)).background(Glass).border(1.dp, Hairline, RoundedCornerShape(100))
                .clickable(onClick = onRetry).padding(horizontal = 22.dp, vertical = 11.dp),
        ) { Text("Retry", color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
    }
}

// --- connect --------------------------------------------------------------

@Composable
internal fun ConnectForm(viewModel: LibraryViewModel, backend: Backend, activeServerConfig: ServerConfig?) {
    val connecting by viewModel.connecting.collectAsStateWithLifecycle()
    val connError by viewModel.connError.collectAsStateWithLifecycle()
    val accent = LocalAccent.current
    val activeConfig = activeServerConfig

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = navBarInset()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (backend == Backend.MA) {
            val url by viewModel.maUrl.collectAsStateWithLifecycle()
            val user by viewModel.maUser.collectAsStateWithLifecycle()
            val pass by viewModel.maPass.collectAsStateWithLifecycle()
            SectionLabel("Music Assistant server")
            GlassField("Server URL", url, viewModel::setMaUrl, "http://192.168.0.10:8095")
            GlassField("Username", user, viewModel::setMaUser)
            GlassField("Password", pass, viewModel::setMaPass, secret = true)
        } else if (activeConfig?.kind?.needsAddress == false) {
            // Music already on the phone has no address, sign-in or password to ask
            // for. What it *does* need is the audio permission, and until this asked
            // for it the only way through was a form demanding a server URL for a
            // library that has no server.
            val context = LocalContext.current
            var granted by remember { mutableStateOf(LocalMediaSource.hasAudioPermission(context)) }
            val permission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { ok ->
                granted = ok
                if (ok) viewModel.connect()
            }
            SectionLabel(activeConfig.kind.label)
            if (granted) {
                Text(
                    "Reading the music on this phone. The folder to read is picked in Settings → Libraries.",
                    color = TextMuted, style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    "Android has to allow this app to read audio files before anything can be listed. " +
                        "Nothing leaves the phone.",
                    color = TextMuted, style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(15.dp))
                        .border(1.dp, accent.a(0.55f), RoundedCornerShape(15.dp))
                        .clickable { permission.launch(LocalMediaSource.AUDIO_PERMISSION) }
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Allow access", color = accent, fontFamily = AppFont,
                        fontWeight = FontWeight.ExtraBold, fontSize = 15.sp,
                    )
                }
            }
        } else {
            val url by viewModel.navUrl.collectAsStateWithLifecycle()
            val user by viewModel.navUser.collectAsStateWithLifecycle()
            val pass by viewModel.navPass.collectAsStateWithLifecycle()
            val kind = activeConfig?.kind
            val title = kind?.label ?: "Direct server"
            val hint = kind?.urlHint ?: "http://192.168.0.10:4533"
            SectionLabel(title)
            Text(
                "Direct mode plays on this phone and can download for offline — it works even when Music Assistant is down.",
                color = TextMuted, style = MaterialTheme.typography.bodyMedium,
            )
            if (kind == ServerKind.JELLYFIN) {
                Text(
                    "Use the username and password you log into Jellyfin with.",
                    color = TextFaint, style = MaterialTheme.typography.bodySmall,
                )
            }
            GlassField("Server URL", url, viewModel::setNavUrl, hint)
            GlassField("Username", user, viewModel::setNavUser)
            GlassField("Password", pass, viewModel::setNavPass, secret = true)
        }

        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .shadow(20.dp, RoundedCornerShape(15.dp), ambientColor = accent, spotColor = accent)
                .clip(RoundedCornerShape(15.dp))
                .background(accent)
                .clickable(enabled = !connecting) { viewModel.connect() }
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (connecting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Ink)
            else Text("Connect", color = Ink, fontFamily = AppFont, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
        }
        connError?.let {
            Text(it, color = ErrorRed, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun GlassField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String = "",
    secret: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = TextMuted, fontFamily = AppFont, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(13.dp))
                .background(inkOn(0.035f))
                .border(1.dp, Hairline, RoundedCornerShape(13.dp))
                .padding(horizontal = 16.dp, vertical = 15.dp),
        ) {
            if (value.isEmpty() && placeholder.isNotBlank()) {
                Text(placeholder, color = TextFaint, fontFamily = AppFont, fontSize = 14.sp, maxLines = 1)
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = TextPrimary, fontFamily = AppFont, fontSize = 14.sp),
                cursorBrush = SolidColor(LocalAccent.current),
                visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
