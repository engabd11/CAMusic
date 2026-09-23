package com.engabd.sendpin.car

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Why Android Auto is, or is not, going to show this app — answered on the phone.
 *
 * This exists because of how badly the failure reported itself. CAMusic did not appear
 * in the Android Auto launcher in two different cars, and from the phone there was
 * nothing at all to look at: no error, no log, no screen. Ruling out the app's own
 * side took dumping the release APK with `aapt2` and writing an instrumented test that
 * drives the browse service the way a car does — neither of which a user can do, and
 * both of which came back clean.
 *
 * What is left after that is phone-side state, and every part of it *is* readable
 * from here. So it is read, and shown, instead of being guessed at.
 *
 * Pure except for the [PackageManager] it is handed, and split from the composable
 * that draws it so the reasoning can be unit tested without a device — the
 * [Finding] list is the testable half.
 */
object CarReadiness {

    /** Android Auto's phone-side projection package. */
    const val ANDROID_AUTO_PACKAGE = CarConnectionLog.ANDROID_AUTO_PACKAGE

    /** How severely one check reads. */
    enum class Level { OK, WARN, BAD }

    /**
     * One answered question.
     *
     * [detail] is written to be read by someone in a car park with the engine off, so
     * it says what to do rather than what is wrong.
     */
    data class Finding(
        val label: String,
        val value: String,
        val level: Level,
        val detail: String? = null,
    )

    /**
     * Whether Android Auto is installed at all.
     *
     * Queried rather than remembered: it can be installed or updated while this screen
     * is open. The manifest's `<queries>` entry for the package is what makes it
     * visible under Android 11+ package visibility.
     */
    fun autoInstalled(pm: PackageManager): Boolean =
        runCatching { pm.getPackageInfo(ANDROID_AUTO_PACKAGE, 0) }.isSuccess

    /**
     * Whether the system resolves this app's own browse service.
     *
     * This is the exact query Android Auto runs to find media apps. If this comes back
     * false the app cannot appear in a car under any setting, and the cause is on this
     * side — a manifest or a build problem — rather than in the car.
     */
    fun browseServiceResolves(context: Context): Boolean = runCatching {
        val intent = Intent(MEDIA_BROWSER_SERVICE_ACTION).setPackage(context.packageName)
        context.packageManager.queryIntentServices(intent, 0).isNotEmpty()
    }.getOrDefault(false)

    /**
     * Who installed this app, or null when nothing did.
     *
     * Null means it was sideloaded, which is the ordinary state for this app — it
     * ships as an APK. It matters because **Android Auto hides media apps that did
     * not come from a trusted source** unless its own "Unknown sources" developer
     * option is switched on, and that option is not discoverable: it lives behind
     * tapping a version number ten times.
     */
    fun installerOf(context: Context): String? = runCatching {
        context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
    }.getOrNull()

    /**
     * Every check, in the order they matter.
     *
     * Deliberately ordered as a diagnosis rather than as a list of facts: the first
     * thing that can be wrong is that Auto is not there, then that this app is
     * invisible to it, then that Auto is choosing to hide it, then whether a car has
     * in fact ever reached us. Reading top to bottom is the triage.
     */
    fun findings(context: Context, lastConnection: CarConnectionLog.Entry?): List<Finding> {
        val pm = context.packageManager
        val out = mutableListOf<Finding>()

        val installed = autoInstalled(pm)
        out += Finding(
            label = "Android Auto",
            value = if (installed) "Installed on this phone" else "Not installed",
            level = if (installed) Level.OK else Level.WARN,
            detail = if (installed) {
                null
            } else {
                "Nothing here can be tried until a car asks for it. Android Auto comes " +
                    "built in on most phones from Android 10 onward."
            },
        )

        val resolves = browseServiceResolves(context)
        out += Finding(
            label = "This app's car service",
            value = if (resolves) "Visible to the system" else "Not found",
            level = if (resolves) Level.OK else Level.BAD,
            detail = if (resolves) {
                null
            } else {
                "The system cannot see CAMusic's browse service, so no car can either. " +
                    "This is a problem with this build rather than with the car — please " +
                    "report it."
            },
        )

        val installer = installerOf(context)
        out += if (installer == null) {
            Finding(
                label = "Installed from",
                value = "Sideloaded APK",
                level = Level.WARN,
                detail = UNKNOWN_SOURCES_HELP,
            )
        } else {
            Finding(
                label = "Installed from",
                value = installer,
                level = Level.OK,
            )
        }

        out += when {
            lastConnection == null -> Finding(
                label = "Last connection",
                value = "Never",
                level = Level.WARN,
                detail = "Nothing has ever browsed this app's car library — not a car, and " +
                    "not the phone. If you have driven with the phone plugged in since " +
                    "installing this version, that is the finding: the car never reached " +
                    "the app at all, so the next thing to check is the two settings above.",
            )
            CarConnectionLog.isAndroidAuto(lastConnection) -> Finding(
                label = "Last connection",
                value = "A car, ${ago(lastConnection.atMs)}",
                level = Level.OK,
                detail = "A car has reached this app's library. If something looked wrong " +
                    "in the car after that, it is worth reporting — see Diagnostics.",
            )
            else -> Finding(
                label = "Last connection",
                value = "${lastConnection.packageName}, ${ago(lastConnection.atMs)}",
                level = Level.WARN,
                detail = "Something on this phone has browsed the car library, but not " +
                    "Android Auto itself. This app's own screens and the system's media " +
                    "controls both connect the same way, so this does not yet prove a car " +
                    "can.",
            )
        }

        return out
    }

    /**
     * The steps, written out, because they cannot be linked to.
     *
     * There is no intent that opens Android Auto's developer settings — the option is
     * reached by tapping a version string ten times, and Google documents it that way
     * and no other. Anything short of the full path here leaves someone hunting.
     */
    const val UNKNOWN_SOURCES_HELP: String =
        "CAMusic was installed from an APK rather than from a store, and Android Auto " +
            "hides media apps that did not come from a trusted source until you tell it " +
            "otherwise.\n\nTo allow it:\n" +
            "1. Settings › Apps › Android Auto › Additional settings in the app.\n" +
            "2. Scroll to the bottom and tap \"Version and permission info\" ten times.\n" +
            "3. Tap OK on \"Allow development settings?\".\n" +
            "4. Open the ⋮ overflow menu › Developer settings.\n" +
            "5. Turn on \"Unknown sources\".\n\n" +
            "If it is already on and CAMusic still does not appear, the car's app list is " +
            "usually cached from the first time the phone was paired. In Android Auto's " +
            "settings use \"Customize launcher\" and check CAMusic is ticked; if it is not " +
            "even listed there, forget the car under \"Connected cars\", clear Android " +
            "Auto's storage, and plug in again."

    /** A rough, readable age. Precision past a day is not useful for this. */
    private fun ago(atMs: Long): String {
        val mins = (System.currentTimeMillis() - atMs) / 60_000L
        return when {
            mins < 2 -> "just now"
            mins < 60 -> "$mins min ago"
            mins < 60 * 24 -> "${mins / 60} h ago"
            mins < 60 * 24 * 2 -> "yesterday"
            else -> "${mins / (60 * 24)} days ago"
        }
    }

    /**
     * The action Android Auto queries for. Spelled out for the same reason the other
     * browser-protocol strings in this package are — see [CarContentStyle].
     */
    private const val MEDIA_BROWSER_SERVICE_ACTION = "android.media.browse.MediaBrowserService"
}
