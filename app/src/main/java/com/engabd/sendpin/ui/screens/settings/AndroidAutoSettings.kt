package com.engabd.sendpin.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.engabd.sendpin.data.AppSettings

/** The package name Android Auto's phone-side projection runs under. */
private const val GEARHEAD = "com.google.android.projection.gearhead"

/**
 * Android Auto: what the car will show, and where a track tapped there plays.
 *
 * There is nothing to *configure* here, and that is deliberate rather than an
 * omission. Auto binds to `CarMediaLibraryService` on its own, builds its browse tree
 * from the servers already set up under Media Providers, and plays to this phone
 * whatever the Speakers screen has selected. Every one of those is decided somewhere
 * else, so a switch here would be a second place to answer a question that already
 * has an answer.
 *
 * What was missing was any way to find out whether it was going to work before
 * getting in the car — the feature shipped with no presence in Settings at all, so
 * the only test was a drive. This page answers the three questions that a drive
 * would: is Auto installed, is there anything for it to show, and where will the
 * music come out.
 */
@Composable
internal fun AndroidAutoCard(settings: AppSettings, accent: Color) {
    val context = LocalContext.current
    val servers by settings.servers.collectAsStateWithLifecycle(initialValue = emptyList())

    // Queried rather than remembered: Auto can be installed or updated while this
    // screen sits open, and the answer is only interesting at the moment it is read.
    // The manifest declares a <queries> entry for this package, which is what makes
    // it visible at all on Android 11 and later.
    val autoInstalled = remember(context) {
        runCatching { context.packageManager.getPackageInfo(GEARHEAD, 0) }.isSuccess
    }

    SettingsCard(
        title = "Android Auto",
        lead = "Your libraries, in the car, on the car's own screen.",
        info = "CAMusic offers Android Auto a browse tree built from the servers set up under " +
            "Media Providers — one folder per server, then the same shelves the Library tab " +
            "has. Search works from the steering wheel too.\n\nThere is nothing to switch on. " +
            "Auto connects to the app by itself when the phone is plugged into a car that " +
            "supports it, and the car decides how the folders are laid out.\n\nTip: what " +
            "appears in the car is exactly what is set up under Media Providers. Add a server " +
            "there and it is in the car on the next trip; there is no separate car library.",
    ) {
        StatusPanel {
            StatusRow(
                "Android Auto",
                if (autoInstalled) "Installed on this phone" else "Not installed",
            )
            StatusRow(
                "Libraries in the car",
                when (servers.size) {
                    0 -> "None yet"
                    1 -> "1 · ${servers.first().displayName}"
                    else -> "${servers.size} · ${servers.joinToString(", ") { it.displayName }}"
                },
            )
            StatusRow("Music plays on", "This phone, always")
        }

        if (!autoInstalled) {
            Note(
                "Android Auto is not on this phone, so nothing here can be tried until a car " +
                    "asks for it. It comes built in on most phones from Android 10 onward.",
            )
        }

        if (servers.isEmpty()) {
            Note(
                "With no library set up, the car has nothing to browse. Add one under Media " +
                    "Providers & Accounts and it appears in the car on the next trip.",
                warn = true,
            )
        }

        Note(
            "A track tapped in the car always plays on this phone.",
            title = "Where the music comes out",
            info = "The car's own transport controls, its now-playing screen and its steering " +
                "wheel buttons all address whatever is making the sound. In this app that can " +
                "be a speaker in another room, because the Speakers screen lets you send a " +
                "queue anywhere on the network — and a track tapped from the driver's seat " +
                "starting in the kitchen would be, at best, a surprise.\n\nSo the car path is " +
                "deliberately fixed to this phone. For a Music Assistant track it also moves " +
                "the Speakers selection here, so the car's controls address the player it just " +
                "started rather than the one at home.\n\nTip: this is why the Speakers screen " +
                "may have moved when you get out of the car. It is the same one selection, not " +
                "a second one.",
        )

        Note(
            "Not yet driven.",
            title = "Status of this feature",
            info = "The browse tree, search and the session facade are written and compile, and " +
                "the unit tests cover the id scheme that connects them. What has not happened " +
                "is a trip in a real car, or a pass through Google's Desktop Head Unit.\n\nSo " +
                "treat this as untested rather than broken: if a folder is empty in the car " +
                "that has music on the phone, or a tapped track does nothing, that is worth " +
                "reporting — see Diagnostics.",
        )

        if (autoInstalled) {
            OledButton("Open Android Auto settings", accent = accent, outline = true) {
                runCatching {
                    context.startActivity(
                        context.packageManager.getLaunchIntentForPackage(GEARHEAD)
                            ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$GEARHEAD")),
                    )
                }
            }
        }
    }
}
