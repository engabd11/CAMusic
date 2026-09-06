package com.engabd.sendpin.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.engabd.sendpin.car.CarBrowseOptions
import com.engabd.sendpin.car.CarBrowseStyle
import com.engabd.sendpin.car.CarLibraryAbilities
import com.engabd.sendpin.car.CarRowStyle
import com.engabd.sendpin.car.CarShelf
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.ui.design.a
import com.engabd.sendpin.ui.theme.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The package name Android Auto's phone-side projection runs under. */
private const val GEARHEAD = "com.google.android.projection.gearhead"

/**
 * Android Auto: what the car will show, how it is laid out, and where a track tapped
 * there plays.
 *
 * This page used to say, in as many words, that there was nothing to configure — the
 * browse tree was built from the servers under Media Providers and that was that.
 * That was true and it was the wrong answer. A head unit is not a phone: the screen
 * is 6 to 10 inches at arm's length, the person reading it is driving, and how many
 * rows fit, how large the targets are and how deep the tree goes are decisions that
 * depend on the *car* rather than on the library. Those are the decisions below.
 *
 * The four cards go in the order the questions arrive: does it work at all, what does
 * it look like, what is in it, and what do the buttons do. Every default is the tree
 * exactly as it shipped, so an install that never opens this page is unchanged.
 */
@Composable
internal fun AndroidAutoCard(settings: AppSettings, accent: Color) {
    val context = LocalContext.current
    val servers by settings.servers.collectAsStateWithLifecycle(initialValue = emptyList())
    val options by settings.carBrowseOptions.collectAsStateWithLifecycle(initialValue = CarBrowseOptions())

    // Queried rather than remembered: Auto can be installed or updated while this
    // screen sits open, and the answer is only interesting at the moment it is read.
    // The manifest declares a <queries> entry for this package, which is what makes
    // it visible at all on Android 11 and later.
    val autoInstalled = remember(context) {
        runCatching { context.packageManager.getPackageInfo(GEARHEAD, 0) }.isSuccess
    }

    val visible = options.libraries(servers) { it.id }

    SettingsCard(
        title = "Android Auto",
        lead = "Your libraries, in the car, on the car's own screen.",
        info = "CAMusic offers Android Auto a browse tree built from the servers set up under " +
            "Media Providers — a folder per library, then the same shelves the Library tab " +
            "has. Search works from the steering wheel too.\n\nThere is nothing to switch on. " +
            "Auto connects to the app by itself when the phone is plugged into a car that " +
            "supports it.\n\nWhat the three cards below change is how that tree is laid out " +
            "on the car's screen — how large the rows are, which shelves are in it and in " +
            "what order, and what the transport buttons do. Nothing here can add music the " +
            "phone does not already have: add a server under Media Providers and it is in " +
            "the car on the next trip.",
    ) {
        StatusPanel {
            StatusRow(
                "Android Auto",
                if (autoInstalled) "Installed on this phone" else "Not installed",
            )
            StatusRow(
                "Libraries in the car",
                when (visible.size) {
                    0 -> "None yet"
                    1 -> "1 · ${visible.first().displayName}"
                    else -> "${visible.size} · ${visible.joinToString(", ") { it.displayName }}"
                },
            )
            StatusRow(
                "First screen",
                if (options.flattenSingleLibrary && visible.size == 1) {
                    "${visible.first().displayName}'s shelves"
                } else {
                    "One folder per library"
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
            info = "The browse tree, search, the cover-art provider and the session facade are " +
                "written and compile, and the unit tests cover the id scheme and the layout " +
                "rules that connect them. What has not happened is a trip in a real car, or a " +
                "pass through Google's Desktop Head Unit.\n\nSo treat this as untested rather " +
                "than broken: if a folder is empty in the car that has music on the phone, if " +
                "a cover never appears, or if a tapped track does nothing, that is worth " +
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

/**
 * How the car draws a row.
 *
 * Four switches and a picture of what they do. The picture is not decoration: none
 * of these can be checked without a car, and a driver reading "Category style" has
 * no way to know it means round artwork until they are on a motorway finding out.
 */
@Composable
internal fun AndroidAutoLayoutCard(settings: AppSettings, accent: Color, scope: CoroutineScope) {
    val options by settings.carBrowseOptions.collectAsStateWithLifecycle(initialValue = CarBrowseOptions())

    SettingsCard(
        title = "Layout on the car's screen",
        lead = "How large the rows are, and what they carry.",
        info = "Android Auto draws a browse row in one of four shapes, and the app tells it " +
            "which to use. A grid row is a large cover with the name under it — few per " +
            "screen, easy to hit. A list row is a line of text — many per screen, small " +
            "targets. \"Category\" is the same pair with round artwork, which is the car's " +
            "own convention for a person or a genre rather than a record.\n\nAdaptive picks " +
            "per shelf: covers get a grid, names get a list. The other two override that " +
            "everywhere, which is worth doing on a small head unit (compact) or from a " +
            "driver's seat you cannot lean towards (big covers).\n\nTip: whatever you choose, " +
            "the car has the last word. A head unit is free to ignore a hint it cannot draw.",
    ) {
        FieldLabel("Row shape")
        SegmentedToggleRow(
            labels = CarBrowseStyle.entries.map { it.label },
            selectedIndex = CarBrowseStyle.entries.indexOf(options.style),
            onSelect = { index ->
                scope.launch { settings.setCarBrowseStyle(CarBrowseStyle.entries[index]) }
            },
        )

        CarScreenPreview(options, accent)

        ToggleRow(
            "Round artwork for artists",
            "Artists and genres as circles, the way the car draws people everywhere else.",
            options.peopleAsCircles, accent,
            onChange = { scope.launch { settings.setCarPeopleAsCircles(it) } },
        )

        ToggleRow(
            "Group the shelves",
            "Heads them \"For you\", \"Favourites\" and \"Library\" instead of one flat column.",
            options.groupTitles, accent,
            onChange = { scope.launch { settings.setCarGroupTitles(it) } },
        )

        ToggleRow(
            "Album art on browse rows",
            "Covers in the car, fetched as the car asks for them.",
            options.artwork, accent,
            info = "The tree shipped with no artwork at all, and that was a security decision " +
                "rather than an oversight: a Subsonic or Jellyfin cover URL carries your " +
                "account credentials in its query string, and handing one to Android Auto " +
                "would hand them to Android Auto.\n\nSo the URL never leaves. What the car is " +
                "given is an opaque address that only this app can resolve, and the app " +
                "fetches the cover itself and passes on the picture. No credential crosses, " +
                "and no other app on the phone can read the address.\n\nCost: one image " +
                "fetch per row the first time a folder is opened, cached afterwards. Turn it " +
                "off on a metered connection, or if you would rather the car showed names " +
                "alone.",
            onChange = { scope.launch { settings.setCarArtwork(it) } },
        )

        ToggleRow(
            "Skip the folder for a single library",
            "With one library set up, its shelves are the car's first screen.",
            options.flattenSingleLibrary, accent,
            info = "With one library configured, the car's first screen was that library's " +
                "name and nothing else — a tap that told you what you already knew, on every " +
                "single trip.\n\nThis puts its shelves at the root instead, so the first tap " +
                "in the car is \"Recently added\". Add a second library and the folders come " +
                "back on their own; nothing needs changing here.",
            onChange = { scope.launch { settings.setCarFlattenSingleLibrary(it) } },
        )
    }
}

/**
 * What is in the tree: which libraries, which shelves, and how much of each.
 *
 * Order matters more here than it does on the phone. Android Auto tells the app how
 * many rows its root can hold — usually four — and everything past that goes behind
 * a "More libraries" folder, so which library is *first* decides which one is one
 * tap away and which is three.
 */
@Composable
internal fun AndroidAutoContentCard(settings: AppSettings, accent: Color, scope: CoroutineScope) {
    val options by settings.carBrowseOptions.collectAsStateWithLifecycle(initialValue = CarBrowseOptions())
    val servers by settings.servers.collectAsStateWithLifecycle(initialValue = emptyList())

    val libraryIds = servers.map { it.id }
    val shelfKeys = CarShelf.entries.map { it.key }

    SettingsCard(
        title = "What the car shows",
        lead = "Which libraries and shelves reach the car, and in what order.",
        info = "Android Auto tells the app how many rows fit on its root screen — four on " +
            "most head units. Libraries past that are still reachable, behind a \"More " +
            "libraries\" folder, but they cost two extra taps to get to. Putting the one you " +
            "actually listen to in the car at the top is the single most useful thing on this " +
            "page.\n\nShelves work the same way inside a library, and are set once for every " +
            "library rather than per server: a shelf a given library cannot fill is simply " +
            "left out of that library's folder.\n\nTip: switching a library off here does not " +
            "touch it anywhere else. It stays on the phone, in the Library tab, exactly as it " +
            "was.",
    ) {
        FieldLabel("Libraries")
        if (servers.isEmpty()) {
            Note("Nothing set up yet. Add a library under Media Providers & Accounts.")
        } else {
            OrderedPicker(
                keys = CarBrowseOptions.displayOrder(options.libraryIds, libraryIds),
                enabled = CarBrowseOptions.enabledOrder(options.libraryIds, libraryIds),
                accent = accent,
                title = { id -> servers.firstOrNull { it.id == id }?.displayName ?: id },
                subtitle = { id -> servers.firstOrNull { it.id == id }?.kind?.label.orEmpty() },
                onToggle = { id ->
                    scope.launch {
                        settings.setCarLibraries(CarBrowseOptions.toggle(options.libraryIds, libraryIds, id))
                    }
                },
                onMove = { id, delta ->
                    scope.launch {
                        settings.setCarLibraries(CarBrowseOptions.move(options.libraryIds, libraryIds, id, delta))
                    }
                },
            )
        }

        CardDivider()

        FieldLabel("Shelves inside a library")
        OrderedPicker(
            keys = CarBrowseOptions.displayOrder(options.shelfKeys, shelfKeys),
            enabled = CarBrowseOptions.enabledOrder(options.shelfKeys, shelfKeys),
            accent = accent,
            title = { key -> CarShelf.byKey(key)?.title ?: key },
            subtitle = { key -> CarShelf.byKey(key)?.group?.title ?: "" },
            onToggle = { key ->
                scope.launch {
                    settings.setCarShelves(CarBrowseOptions.toggle(options.shelfKeys, shelfKeys, key))
                }
            },
            onMove = { key, delta ->
                scope.launch {
                    settings.setCarShelves(CarBrowseOptions.move(options.shelfKeys, shelfKeys, key, delta))
                }
            },
        )

        CardDivider()

        FieldLabel("Items per shelf")
        SegmentedToggleRow(
            labels = CarBrowseOptions.SHELF_ITEM_CHOICES.map { it.toString() },
            selectedIndex = nearestIndex(CarBrowseOptions.SHELF_ITEM_CHOICES, options.shelfItemLimit),
            onSelect = { index ->
                scope.launch { settings.setCarShelfItemLimit(CarBrowseOptions.SHELF_ITEM_CHOICES[index]) }
            },
        )
        Note(
            "A shelf loads this many; track shelves load twice as many.",
            title = "Items per shelf",
            info = "Every item is a row to scroll past at a red light, and every item is also " +
                "something the phone has to ask the server for before the folder can open. " +
                "Ten is a shelf you can take in at a glance; two hundred is the whole library " +
                "and a slower first open on a mobile connection.\n\nTrack shelves get double, " +
                "because a track is one line and an album is a tile — the same number of each " +
                "is not the same amount of scrolling.",
        )

        OledButton("Reset the car's layout", accent = accent, outline = true) {
            scope.launch { settings.resetCarBrowseOptions() }
        }
    }
}

/**
 * The car's own transport row.
 *
 * One setting, and it is off by default on purpose. Android Auto draws previous /
 * play / next without being asked; rewind and fast-forward are two more targets on a
 * screen being glanced at from the driver's seat, worth their place on a two-hour DJ
 * set or a podcast and not on a three-minute song.
 */
@Composable
internal fun AndroidAutoTransportCard(settings: AppSettings, accent: Color, scope: CoroutineScope) {
    val options by settings.carBrowseOptions.collectAsStateWithLifecycle(initialValue = CarBrowseOptions())

    SettingsCard(
        title = "The car's playback screen",
        lead = "What the buttons beside play and skip do.",
        info = "The car's now-playing screen mirrors whatever this app is playing, wherever it " +
            "is playing from, and its buttons address that player through the same path the " +
            "phone's own controls use.\n\nRewind and fast-forward are the one part of it that " +
            "is a choice. Off, the car shows previous / play / next, which is what a car " +
            "should show for music. On, it also shows a pair that jump within the current " +
            "track by the amount you pick — the difference between usable and unusable on a " +
            "long mix or a podcast.\n\nTip: the change reaches a car that is already plugged " +
            "in. There is no need to unplug and reconnect.",
    ) {
        FieldLabel("Rewind and fast-forward")
        SegmentedToggleRow(
            labels = CarBrowseOptions.SEEK_CHOICES.map { if (it == 0) "Off" else "${it}s" },
            selectedIndex = nearestIndex(CarBrowseOptions.SEEK_CHOICES, options.seekSeconds),
            onSelect = { index ->
                scope.launch { settings.setCarSeekSeconds(CarBrowseOptions.SEEK_CHOICES[index]) }
            },
        )
        Note(
            if (options.seekSeconds == 0) {
                "The car shows previous, play and next."
            } else {
                "The car also shows a ${options.seekSeconds}-second jump each way."
            },
        )
    }
}

// ── The pickers ───────────────────────────────────────────────────────────

/**
 * A list you can switch entries off in and reorder, without a drag.
 *
 * Deliberately arrows rather than drag-and-drop. This list is short, it is edited
 * once and then forgotten, and a drag handle inside a vertically scrolling settings
 * page is the control that most often does the wrong thing under a thumb. Two 40dp
 * buttons cannot be ambiguous.
 *
 * The last enabled entry's checkbox is greyed rather than removed: a selection of
 * nothing and a selection of everything are stored identically (see
 * [CarBrowseOptions.enabledOrder]), so switching the last one off would silently
 * turn everything back on.
 */
@Composable
private fun OrderedPicker(
    keys: List<String>,
    enabled: List<String>,
    accent: Color,
    title: (String) -> String,
    subtitle: (String) -> String,
    onToggle: (String) -> Unit,
    onMove: (String, Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        keys.forEach { key ->
            val on = key in enabled
            val index = enabled.indexOf(key)
            OrderedPickerRow(
                title = title(key),
                subtitle = subtitle(key),
                checked = on,
                accent = accent,
                canUncheck = !on || enabled.size > 1,
                canMoveUp = on && index > 0,
                canMoveDown = on && index >= 0 && index < enabled.size - 1,
                onToggle = { onToggle(key) },
                onMove = { delta -> onMove(key, delta) },
            )
        }
    }
}

@Composable
private fun OrderedPickerRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    accent: Color,
    canUncheck: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggle: () -> Unit,
    onMove: (Int) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            // Tapping anywhere on the row is the toggle; the row goes inert only
            // when this is the last thing left switched on.
            .clickable(enabled = !checked || canUncheck) { onToggle() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // The tick box. 22dp of mark inside a row that is 40dp tall, so the whole
        // row is the target rather than the box.
        Box(
            Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (checked) accent else Color.Transparent)
                .border(1.dp, if (checked) accent else Hairline, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(Icons.Default.Check, null, tint = Ink, modifier = Modifier.size(15.dp))
        }

        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (checked) TextPrimary else TextMuted,
                fontFamily = AppFont,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    color = TextFaint,
                    fontFamily = MonoFont,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
        }

        MoveButton(Icons.Default.KeyboardArrowUp, "Move up", canMoveUp) { onMove(-1) }
        MoveButton(Icons.Default.KeyboardArrowDown, "Move down", canMoveDown) { onMove(1) }
    }
}

@Composable
private fun MoveButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (enabled) Glass else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            description,
            tint = if (enabled) TextSecondary else TextFaint.a(0.4f),
            modifier = Modifier.size(20.dp),
        )
    }
}

// ── The preview ───────────────────────────────────────────────────────────

/**
 * A small, honest picture of what a library folder becomes in the car.
 *
 * Honest in the sense that matters: it is drawn from the *same* [CarBrowseOptions]
 * the browse tree is built from, and the shelves in it are the ones a Music
 * Assistant library would actually offer, so a switch that changes nothing here
 * changes nothing in the car either. It is not a pixel-accurate head unit — no two
 * head units agree on that anyway — it is the shape of the answer.
 *
 * Each row is a shelf as the car will draw it, with a strip on the right standing in
 * for what is *inside* that shelf. Both halves matter and they are set by different
 * switches: the row is the folder style, the strip is the shelf's own content style,
 * and that split is exactly the thing about Android Auto's layout hints that is
 * easiest to get wrong. See [com.engabd.sendpin.car.CarContentStyle].
 */
@Composable
private fun CarScreenPreview(options: CarBrowseOptions, accent: Color) {
    // A representative library rather than the user's own: the point of the preview
    // is the *shape*, and a library with two shelves configured would show a picture
    // that says nothing about the row style being chosen.
    val shelves = remember(options.shelfKeys) {
        options.shelves(
            CarShelf.offeredBy(CarLibraryAbilities(musicAssistant = true, favourites = true, playlists = true)),
        ).take(PREVIEW_ROWS)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Ink2)
            .border(1.dp, Hairline, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
            Text(
                "CAMusic",
                color = TextSecondary,
                fontFamily = AppFont,
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "In the car",
                color = TextFaint,
                fontFamily = MonoFont,
                style = MaterialTheme.typography.labelSmall,
            )
        }

        var lastGroup: String? = null
        shelves.forEach { shelf ->
            if (options.groupTitles && shelf.group.title != lastGroup) {
                lastGroup = shelf.group.title
                Text(
                    shelf.group.title.uppercase(),
                    color = TextFaint,
                    fontFamily = MonoFont,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            PreviewShelfRow(
                title = shelf.title,
                rowStyle = options.folderStyle(),
                contentStyle = options.shelfChildStyle(shelf),
                artwork = options.artwork,
                accent = accent,
            )
        }
    }
}

/**
 * One shelf: the row on the left, a stand-in for what is inside it on the right.
 */
@Composable
private fun PreviewShelfRow(
    title: String,
    rowStyle: CarRowStyle,
    contentStyle: CarRowStyle,
    artwork: Boolean,
    accent: Color,
) {
    val rowIsGrid = rowStyle == CarRowStyle.GRID || rowStyle == CarRowStyle.CATEGORY_GRID
    val contentIsGrid = contentStyle == CarRowStyle.GRID || contentStyle == CarRowStyle.CATEGORY_GRID
    val contentIsRound = contentStyle == CarRowStyle.CATEGORY_GRID || contentStyle == CarRowStyle.CATEGORY_LIST

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(if (rowIsGrid) 34.dp else 20.dp)
                .clip(RoundedCornerShape(5.dp))
                .border(1.dp, Hairline, RoundedCornerShape(5.dp)),
        )
        Text(
            title,
            color = TextPrimary,
            fontFamily = AppFont,
            style = if (rowIsGrid) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // What is behind the row: three tiles, round or square, large or small, and
        // filled only when covers are switched on — which is the actual visible
        // difference in the car.
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            val shape = if (contentIsRound) CircleShape else RoundedCornerShape(3.dp)
            val size = if (contentIsGrid) 18.dp else 10.dp
            repeat(3) {
                Box(
                    Modifier
                        .size(size)
                        .clip(shape)
                        .background(if (artwork) accent.a(0.35f) else Color.Transparent)
                        .border(1.dp, if (artwork) Color.Transparent else Hairline, shape),
                )
            }
        }
    }
}

/**
 * Which of [choices] a stored value belongs to.
 *
 * The nearest rather than an exact match, and never -1. A stored figure that is not
 * on the dial — one written by an older build, or clamped on the way in — would
 * otherwise light up the first segment and quietly misreport the setting.
 */
private fun nearestIndex(choices: List<Int>, value: Int): Int {
    var best = 0
    for (i in choices.indices) {
        if (kotlin.math.abs(choices[i] - value) < kotlin.math.abs(choices[best] - value)) best = i
    }
    return best
}

/** Enough rows to show the grouping and the row shape, few enough to stay a preview. */
private const val PREVIEW_ROWS = 5
