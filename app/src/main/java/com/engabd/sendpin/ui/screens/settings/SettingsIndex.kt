package com.engabd.sendpin.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.graphics.vector.ImageVector
import com.engabd.sendpin.ui.screens.SettingsSection

/**
 * One page inside a settings section.
 *
 * Settings used to be two levels where one of them was optional: five categories, of
 * which two had pages underneath and three were a single scroll of everything they
 * held. "Audio Engine & DSP" was nine cards spanning bit-perfect output, ReplayGain,
 * crossfade, DJ radio, road-safety, shake gestures and lyrics timing — six unrelated
 * subjects that shared only the fact that no better home existed.
 *
 * So every section now has pages, and this is what one looks like. The [subtitle] is
 * not optional for the same reason [NavRow]'s is not: a one-word category is a guess
 * until you have opened it once.
 *
 * The [route] is carried in the same hoisted `detail: String?` that already routed a
 * server and the Hue bridge — see `SettingsScreen`. Nothing about the navigation
 * changed; there are simply more places to go.
 */
internal data class SubPage(
    val route: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
)

// ── Audio Engine & DSP ────────────────────────────────────────────────────
internal const val AUDIO_OUTPUT_ROUTE = "audio_output"
internal const val AUDIO_EQ_ROUTE = "audio_eq"
internal const val AUDIO_GAIN_ROUTE = "audio_gain"
internal const val AUDIO_BETWEEN_ROUTE = "audio_between"
internal const val AUDIO_BEHAVIOUR_ROUTE = "audio_behaviour"

// ── Interface & Appearance ────────────────────────────────────────────────
internal const val LOOK_THEME_ROUTE = "look_theme"
internal const val LOOK_PLAYER_ROUTE = "look_player"
internal const val LOOK_MOTION_ROUTE = "look_motion"

// ── Driving & Android Auto ────────────────────────────────────────────────
internal const val DRIVE_MODE_ROUTE = "drive_mode"
internal const val DRIVE_SAFETY_ROUTE = "drive_safety"
internal const val DRIVE_AUTO_ROUTE = "drive_auto"

// ── System, Storage & About ───────────────────────────────────────────────
internal const val SYS_STORAGE_ROUTE = "sys_storage"
internal const val SYS_BACKUP_ROUTE = "sys_backup"
internal const val SYS_DIAGNOSTICS_ROUTE = "sys_diagnostics"
internal const val SYS_ABOUT_ROUTE = "sys_about"

/**
 * The pages behind a section, or an empty list where the section's own body *is* the
 * index.
 *
 * Media Providers is the one of those: its body is already a list of servers, each
 * leading to its own page, and putting a menu of one row in front of that would be a
 * tap that told the reader nothing. Illumination is the other — its transport picker
 * has to be answered before any page below it means anything, so its rows are drawn
 * with that card rather than from here.
 */
internal fun subPagesFor(section: SettingsSection): List<SubPage> = when (section) {
    SettingsSection.PROVIDERS -> emptyList()
    SettingsSection.LIGHTS_SYNC -> emptyList()

    SettingsSection.AUDIO -> listOf(
        SubPage(
            AUDIO_OUTPUT_ROUTE,
            "Output & signal path",
            "Which device the sound leaves by, the sample rate, and what the DAC is given",
            Icons.Default.Tune,
        ),
        SubPage(
            AUDIO_EQ_ROUTE,
            "Equaliser",
            "Ten bands here, and Music Assistant's own parametric DSP for its players",
            Icons.Default.Equalizer,
        ),
        SubPage(
            AUDIO_GAIN_ROUTE,
            "Volume & gain",
            "ReplayGain, so one album does not arrive twice as loud as the last",
            Icons.AutoMirrored.Filled.VolumeUp,
        ),
        SubPage(
            AUDIO_BETWEEN_ROUTE,
            "Between tracks",
            "Smooth transitions, beat-matched fades, and how DJ Radio picks what is next",
            Icons.Default.Shuffle,
        ),
        SubPage(
            AUDIO_BEHAVIOUR_ROUTE,
            "Playback behaviour",
            "Shake and swipe gestures, the visualiser, the auto-queue's taste, lyrics timing",
            Icons.Default.Gesture,
        ),
    )

    SettingsSection.APPEARANCE -> listOf(
        SubPage(
            LOOK_THEME_ROUTE,
            "Theme & accent",
            "How dark the app goes, and where its one accent colour comes from",
            Icons.Default.Palette,
        ),
        SubPage(
            LOOK_PLAYER_ROUTE,
            "Now Playing & seek bar",
            "A tab or an overlay, and how the progress line is drawn",
            Icons.Default.PlayCircle,
        ),
        SubPage(
            LOOK_MOTION_ROUTE,
            "Motion & bloom",
            "How much the app animates, and whether the album lights the screen around it",
            Icons.Default.Animation,
        ),
    )

    SettingsSection.DRIVING -> listOf(
        SubPage(
            DRIVE_MODE_ROUTE,
            "Driving mode",
            "Large controls over the map, and the car whose Bluetooth brings them up",
            Icons.Default.DirectionsCar,
        ),
        SubPage(
            DRIVE_SAFETY_ROUTE,
            "Speed & safety",
            "The speed limit alert, road-noise volume, and what a phone call does",
            Icons.Default.Speed,
        ),
        SubPage(
            DRIVE_AUTO_ROUTE,
            "Android Auto",
            "The car screen's layout, which libraries and shelves reach it, and its buttons",
            Icons.Default.DirectionsCar,
        ),
    )

    SettingsSection.SYSTEM_ABOUT -> listOf(
        SubPage(
            SYS_STORAGE_ROUTE,
            "Downloads & storage",
            "Music kept on the phone, when to fetch it, and how much space it may take",
            Icons.Default.Storage,
        ),
        SubPage(
            SYS_BACKUP_ROUTE,
            "Backup & restore",
            "Every setting and saved server, to one encrypted file",
            Icons.Default.Backup,
        ),
        SubPage(
            SYS_DIAGNOSTICS_ROUTE,
            "Diagnostics",
            "A debug file to attach to a bug report",
            Icons.Default.BugReport,
        ),
        SubPage(
            SYS_ABOUT_ROUTE,
            "About",
            "Version, licence, and where the source lives",
            Icons.Default.Info,
        ),
    )
}

/**
 * The title for an open page, or null when [detail] is not one of this section's own
 * pages — a server id, say, which names itself.
 */
internal fun subPageTitle(section: SettingsSection, detail: String?): String? =
    detail?.let { route -> subPagesFor(section).firstOrNull { it.route == route }?.title }

// ── The top-level index ───────────────────────────────────────────────────

/**
 * One row on the Settings index.
 *
 * Six of the seven are [SettingsSection]s, which open onto pages of controls. The
 * seventh is Listening statistics, which is not a section at all: it opens a screen
 * of charts, the way Downloads does from inside System & Storage.
 *
 * The distinction is why this exists rather than a seventh enum constant. A section
 * is a place with a back arrow and a body; statistics has neither here, and giving it
 * a constant would mean a dead branch in every `when (section)` in the screen — one of
 * which is the restore path for a saved route, where a dead branch is a blank page.
 */
internal sealed interface IndexRow {
    /** Stable across reorders and renames, for the list's item key. */
    val key: String
    val title: String
    val subtitle: String
    val icon: ImageVector

    /** A settings category: its own index of pages, or its own body. */
    data class Category(val section: SettingsSection) : IndexRow {
        override val key get() = section.name
        override val title get() = section.title
        override val subtitle get() = section.subtitle
        override val icon get() = section.icon
    }

    /** The listening history, which is a screen rather than a page of settings. */
    data object Stats : IndexRow {
        override val key = "listening_stats"
        override val title = "Listening statistics"
        override val subtitle =
            "Your top artists, hours listened, when you listen, and the format breakdown"
        override val icon = Icons.Default.BarChart
    }
}

/**
 * The seven rows of the Settings index, in order.
 *
 * Statistics used to be the second card on the "About & statistics" page: three taps
 * from here, underneath the version number and the licence. That is not where anyone
 * looks for what they have been listening to, and it is the one page in Settings that
 * is *read* rather than set — so nobody browsing for a control was going to find it
 * either. It now sits between Driving and System, which is where the reading ends and
 * the housekeeping begins.
 *
 * Built by walking [SettingsSection.entries] and inserting the statistics row *before*
 * a named section rather than at a fixed position, so reordering the enum later moves
 * the sections and leaves this row where it was put.
 */
internal val settingsIndex: List<IndexRow> = buildList {
    SettingsSection.entries.forEach { section ->
        if (section == SettingsSection.SYSTEM_ABOUT) add(IndexRow.Stats)
        add(IndexRow.Category(section))
    }
}
