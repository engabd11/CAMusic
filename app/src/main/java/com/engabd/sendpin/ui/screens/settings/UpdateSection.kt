package com.engabd.sendpin.ui.screens.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.engabd.sendpin.BuildConfig
import com.engabd.sendpin.ui.theme.AppFont
import com.engabd.sendpin.ui.theme.TextSecondary
import com.engabd.sendpin.ui.theme.inkOn
import com.engabd.sendpin.update.AppUpdater
import com.engabd.sendpin.update.AppUpdater.State
import com.engabd.sendpin.update.ReleaseInfo

/**
 * "Check for updates", on the About page: the whole of [AppUpdater] as one status
 * line and the one button that moves it on. Nothing is fetched until it is tapped.
 */
@Composable
internal fun UpdateSection(accent: Color) {
    val context = LocalContext.current
    val state by AppUpdater.state.collectAsStateWithLifecycle()

    // Back from "Install unknown apps": carry on if it was allowed.
    LifecycleResumeEffect(Unit) {
        AppUpdater.resumeAfterPermission(context)
        onPauseOrDispose { }
    }

    val unsupported = AppUpdater.unsupportedReason(context)
    if (unsupported != null) {
        Note(unsupported)
        return
    }

    // Guarded: an Android TV box often has no browser, and some have no "Install
    // unknown apps" page, and an unanswered intent would take the app down with it.
    fun open(intent: Intent, otherwise: String) {
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, otherwise, Toast.LENGTH_LONG).show()
        }
    }

    fun openPage(release: ReleaseInfo) =
        open(Intent(Intent.ACTION_VIEW, Uri.parse(release.pageUrl)), "No browser on this device to open the release page")

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (val s = state) {
            State.Idle -> {
                OledButton("Check for updates", accent, outline = true) { AppUpdater.check(context) }
            }
            State.Checking -> {
                StatusLine("Asking GitHub for the latest release…", Health.WORKING, accent)
            }
            is State.UpToDate -> {
                StatusLine(
                    if (s.latest == BuildConfig.VERSION_NAME.substringBefore('-')) "Up to date: ${s.latest} is the latest release"
                    else "Up to date. The latest release is ${s.latest}",
                    Health.GOOD, accent,
                )
                OledButton("Check again", accent, outline = true) { AppUpdater.check(context) }
            }
            is State.Available -> {
                StatusLine("Version ${s.release.version} is available", Health.GOOD, accent)
                ReleaseHeadline(s.release)
                val size = if (s.release.apkSize > 0) " (${formatBytes(s.release.apkSize)})" else ""
                OledButton("Download and install$size", accent) { AppUpdater.downloadAndInstall(context, s.release) }
                OledButton("What's new", accent, outline = true) { openPage(s.release) }
            }
            is State.Downloading -> {
                val pct = s.fraction?.let { " ${(it * 100).toInt()}%" }.orEmpty()
                StatusLine("Downloading ${s.release.version}…$pct", Health.WORKING, accent)
                ProgressTrack(s.fraction, accent)
                Note("You can leave this page; the download carries on.")
            }
            is State.NeedsPermission -> {
                StatusLine("Downloaded. One permission to go", Health.WARN, accent)
                Note(
                    "Android asks before any app other than a store installs updates. " +
                        "Allow CAMusic once on the next page, then come back here.",
                )
                OledButton("Allow installing updates", accent) {
                    open(AppUpdater.permissionIntent(context), "Allow CAMusic under Settings, Apps, Special app access, Install unknown apps")
                }
            }
            is State.Installing -> {
                StatusLine("Waiting for Android to install ${s.release.version}", Health.WORKING, accent)
                // Android does not reopen an app it has just replaced: the process is
                // ended and the user lands wherever they were before (checked on API 36).
                Note("Confirm in Android's dialog. CAMusic closes to update; open it again afterwards. Your settings and library stay.")
                s.confirm?.let { confirm ->
                    OledButton("Open the installer", accent, outline = true) {
                        open(Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "Android's installer could not be opened")
                    }
                }
            }
            is State.Failed -> {
                StatusLine(s.message, Health.BAD, accent)
                val release = s.release
                if (release != null) {
                    OledButton("Try again", accent, outline = true) { AppUpdater.downloadAndInstall(context, release) }
                    OledButton("Open the release page", accent, outline = true) { openPage(release) }
                } else {
                    OledButton("Check again", accent, outline = true) { AppUpdater.check(context) }
                }
            }
        }
    }
}

@Composable
private fun ReleaseHeadline(release: ReleaseInfo) {
    if (release.headline.isBlank()) return
    Text(
        release.headline,
        color = TextSecondary,
        fontFamily = AppFont,
        style = MaterialTheme.typography.bodySmall,
    )
}

/** A thin bar; indeterminate (a quarter, steady) while the size is unknown. */
@Composable
private fun ProgressTrack(fraction: Float?, accent: Color) {
    Box(
        Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)).background(inkOn(0.14f)),
    ) {
        Box(Modifier.fillMaxWidth(fraction ?: 0.25f).height(4.dp).clip(RoundedCornerShape(50)).background(accent))
    }
}
