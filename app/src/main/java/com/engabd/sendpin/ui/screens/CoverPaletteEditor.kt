package com.engabd.sendpin.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.engabd.sendpin.hue.CoverPaletteOverride
import com.engabd.sendpin.hue.extractAlbumColours
import com.engabd.sendpin.ui.design.HSlider
import com.engabd.sendpin.ui.design.HideBottomChrome
import com.engabd.sendpin.ui.design.LocalAccent
import com.engabd.sendpin.ui.design.dismissOnDragDown
import com.engabd.sendpin.ui.design.systemNavInset
import com.engabd.sendpin.ui.design.a
import com.engabd.sendpin.ui.theme.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A colour editor that lets the user override the palette the light-sync engine
 * extracts from this album's artwork.
 *
 * ## The same shape as what it replaces
 *
 * An extracted palette is *n* colours with population weights — how much of the
 * sleeve each colour actually is — and the engine samples it by those weights, so a
 * colour covering half the cover holds the room for half the time. This editor
 * produces the same thing: a variable number of colours, each with a percentage.
 *
 * That matters more than it looks. An override fixed at four evenly-spaced colours
 * would be a strictly *poorer* description than the one it replaces, so correcting a
 * single wrong hue would silently flatten the timing of the other three — and "the
 * blue is right, there is just far too much of it" is the most common correction
 * anyone actually wants to make and would have been the one thing this could not
 * express.
 *
 * ## One sheet, two pages — never two overlays
 *
 * Picking a colour used to open a second full-screen dialog. It was composed
 * *before* this sheet's own dismiss scrim inside the same `Box`, so the scrim was
 * painted over the top of it: every tap aimed at the picker landed on the scrim
 * instead, which closes the editor, and both the picker and the sheet vanished
 * together on the first touch. There was no way to choose a colour at all.
 *
 * The fix is not to reorder those two — a second stacked overlay would still be a
 * second thing to dismiss, and a sheet whose child dialog can outlive it has more
 * states than it has behaviours. The picker is a *page of this sheet*: the same
 * surface, the same scrim, the same back gesture, swapped in place. Back and the
 * scrim step back to the palette rather than closing anything, so a mis-tap while
 * choosing a colour costs one tap instead of the whole edit.
 *
 * The picker also edits the live swatch as it moves, so the proportion bar above it
 * is the room as it will be — the old dialog held its own colour and applied it only
 * on "Choose this", and it seeded that colour at pure red every time regardless of
 * the swatch being edited.
 *
 * ## Seeding
 *
 * From [extractAlbumColours] — the engine's own v2 extractor — rather than a
 * lookalike written for the preview. The editor is for *adjusting* what the room is
 * doing, and a preview computed a different way would start the user from colours
 * the room was never showing.
 *
 * The seed is pinned to the album the sheet was opened for. It reads what the user
 * has already saved as that arrives (the overrides map is a flow, and its first
 * emission is empty), but stops as soon as the palette has been touched: a track
 * change used to re-run the whole seed and wipe an edit in progress. The other half
 * of that is at the two call sites, which pin the keys the save is filed under to
 * the album the sheet was opened for.
 *
 * Reached from Light Sync's Colour section, and from Now Playing's long-press sheet.
 */
@Composable
fun BoxScope.CoverPaletteEditor(
    /** The album this editor is for, for the sheet's own title. */
    albumName: String,
    artistName: String?,
    /** The cover art URL to seed the initial palette from. */
    coverUrl: String?,
    /** The override already saved for this album, if any: what to open showing. */
    existing: CoverPaletteOverride? = null,
    /** Called when the user saves a new override, or null to clear it. */
    onSave: (CoverPaletteOverride?) -> Unit,
    onClose: () -> Unit,
) {
    HideBottomChrome()

    val context = LocalContext.current
    val accent = LocalAccent.current

    // The palette being edited: colour and its raw share. Raw, not normalised, so a
    // slider does not shove every other row about while it is under the finger — the
    // percentages shown are normalised for display on each frame instead.
    var swatches by remember { mutableStateOf<List<Swatch>>(emptyList()) }
    var picking by remember { mutableStateOf<Picking?>(null) }
    var loaded by remember { mutableStateOf(false) }
    // Set the moment the user changes anything. The seed below is allowed to keep
    // arriving until then — the saved override reaches this composable a frame or
    // two after the sheet opens — and must never run again after it.
    var touched by remember { mutableStateOf(false) }
    // What the cover itself says, kept so "From cover" can go back to it without a
    // second decode.
    var extracted by remember { mutableStateOf<List<Swatch>>(emptyList()) }

    // Pinned at the moment the sheet opened. `coverUrl` follows what is playing, and
    // re-decoding another album's artwork halfway through an edit would leave "From
    // cover" offering colours from a sleeve that is no longer the one being edited.
    val seedCoverUrl = remember { coverUrl }

    LaunchedEffect(existing) {
        if (touched) return@LaunchedEffect
        if (extracted.isEmpty()) {
            val fromCover = withContext(Dispatchers.IO) {
                if (seedCoverUrl.isNullOrBlank()) return@withContext null
                try {
                    val request = ImageRequest.Builder(context)
                        .data(seedCoverUrl)
                        .allowHardware(false) // getPixels needs a software bitmap
                        .size(256)
                        .build()
                    val result = context.imageLoader.execute(request)
                    val bmp = (result as? SuccessResult)?.drawable?.toBitmap()
                        ?: return@withContext null
                    extractAlbumColours(bmp)
                } catch (_: Exception) {
                    null
                }
            }
            extracted = fromCover?.let { palette ->
                palette.colors.mapIndexed { i, rgb ->
                    Swatch(
                        color = Color(rgb.first, rgb.second, rgb.third),
                        weight = palette.weights.getOrElse(i) { 1f / palette.colors.size },
                    )
                }
            }.orEmpty()
        }
        // The decode above suspends, and the user can have started editing across it.
        if (touched) return@LaunchedEffect

        // What the user already saved wins over the cover: this is an editor for an
        // existing correction as much as a way to make a new one, and reopening it
        // has to show what is currently on the room.
        swatches = when {
            existing != null && existing.colors.isNotEmpty() -> {
                val w = existing.normalisedWeights()
                existing.colors.mapIndexed { i, argb ->
                    Swatch(Color(argb or (0xFF shl 24)), w.getOrElse(i) { 1f / existing.colors.size })
                }
            }
            extracted.isNotEmpty() -> extracted
            // No cover, no save: something neutral to start from rather than an
            // empty sheet. This is the offline-with-no-artwork case, and it is
            // exactly the one where hand-picking is the only option there is.
            else -> List(4) { Swatch(Color.DarkGray, 0.25f) }
        }
        loaded = true
    }

    /** Every edit goes through here, so nothing can change the palette without pinning the seed. */
    fun edit(next: List<Swatch>) {
        touched = true
        swatches = next
    }

    // Back steps out of the picker first. Closing the whole editor from inside it
    // would throw away an edit the user is still in the middle of making.
    BackHandler { if (picking != null) picking = null else onClose() }

    // Dismiss scrim. The one scrim in this composable, and the last thing under the
    // sheet rather than the last thing over the picker — see the class doc.
    Box(
        Modifier
            .matchParentSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { if (picking != null) picking = null else onClose() }
    )

    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            // Off while the picker is up: three horizontal sliders stacked in a sheet
            // make a drag that strays off-axis far too likely, and losing the edit to
            // one is not a trade worth making. Passed as a flag rather than by
            // dropping the modifier, so the sheet does not replay its entry animation
            // on every page change.
            .dismissOnDragDown(onClose, enabled = picking == null)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { }
            .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
            .background(Ink2)
            .border(1.dp, Hairline, RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = systemNavInset() + 12.dp)
                .padding(horizontal = 18.dp),
        ) {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(100)).background(Hairline))
            }
            Spacer(Modifier.height(14.dp))

            val editing = picking
            Text(
                if (editing == null) "Light Sync colours" else "Pick a colour",
                color = TextPrimary, fontFamily = AppFont,
                fontWeight = FontWeight.ExtraBold, fontSize = 16.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (editing == null) {
                    listOfNotNull(albumName.takeIf { it.isNotBlank() }, artistName?.takeIf { it.isNotBlank() })
                        .joinToString(" · ")
                        .ifBlank { "The colours this album lights the room with." }
                } else {
                    "Colour ${editing.index + 1} of ${swatches.size}"
                },
                color = TextMuted, fontFamily = AppFont, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(14.dp))

            // The palette as one bar, in proportion, on both pages. The percentages
            // are abstract in a list of sliders and obvious here — this is what the
            // room will be, and it is what makes editing a colour in place worth
            // more than choosing one in a dialog that hides it.
            PaletteBar(swatches, highlight = editing?.index)
            Spacer(Modifier.height(16.dp))

            // Keyed on the whole [Picking] rather than on "is a picker open", so the
            // outgoing page keeps rendering the colour it was editing while it fades
            // instead of flipping to the palette list mid-transition.
            AnimatedContent(
                targetState = editing,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                // `contentKey` is declared as returning a non-null Any, so the
                // palette page needs a key of its own rather than a null one.
                contentKey = { it?.index ?: PALETTE_PAGE_KEY },
                label = "paletteEditorPage",
            ) { page ->
                if (page != null) {
                    PickerPage(
                        index = page.index,
                        current = swatches.getOrNull(page.index)?.color ?: page.original,
                        accent = accent,
                        onColor = { colour ->
                            edit(
                                swatches.mapIndexed { j, s ->
                                    if (j == page.index) s.copy(color = colour) else s
                                }
                            )
                        },
                        onCancel = {
                            // Put back exactly what the swatch was on the way in.
                            // `touched` stays set — reverting one colour is not a
                            // reason to let the seed run over the rest.
                            swatches = swatches.mapIndexed { j, s ->
                                if (j == page.index) s.copy(color = page.original) else s
                            }
                            picking = null
                        },
                        onDone = { picking = null },
                    )
                } else {
                    PalettePage(
                        swatches = swatches,
                        extracted = extracted,
                        loaded = loaded,
                        accent = accent,
                        onEdit = { edit(it) },
                        onPick = { i ->
                            picking = Picking(i, swatches.getOrNull(i)?.color ?: Color.DarkGray)
                        },
                        onSave = onSave,
                        onClose = onClose,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** The colour currently under the picker, and what to put back if it is cancelled. */
private data class Picking(val index: Int, val original: Color)

/** [AnimatedContent]'s key for the palette list, which is the page with no index. */
private const val PALETTE_PAGE_KEY = -1

/** One colour and how much of the room it should hold. [weight] is a raw share. */
private data class Swatch(val color: Color, val weight: Float)

/** The palette list: a row per colour, the set-wide actions, and save. */
@Composable
private fun PalettePage(
    swatches: List<Swatch>,
    extracted: List<Swatch>,
    loaded: Boolean,
    accent: Color,
    onEdit: (List<Swatch>) -> Unit,
    onPick: (Int) -> Unit,
    onSave: (CoverPaletteOverride?) -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        val total = swatches.sumOf { it.weight.toDouble() }.toFloat().coerceAtLeast(0.0001f)
        swatches.forEachIndexed { i, swatch ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(swatch.color)
                        .border(2.dp, Hairline, CircleShape)
                        .clickable { onPick(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Edit, "Change this colour",
                        tint = contrastingInk(swatch.color).a(0.75f),
                        modifier = Modifier.size(15.dp),
                    )
                }
                HSlider(
                    value = (swatch.weight / MAX_WEIGHT).coerceIn(0f, 1f),
                    onChange = { v ->
                        onEdit(
                            swatches.mapIndexed { j, s ->
                                if (j == i) s.copy(weight = v * MAX_WEIGHT) else s
                            }
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${((swatch.weight / total) * 100f).roundToInt()}%",
                    color = TextSecondary, fontFamily = MonoFont,
                    fontWeight = FontWeight.Bold, fontSize = 12.sp,
                    modifier = Modifier.width(38.dp),
                )
                // Removal is only offered while there would still be a palette
                // left. Two colours is the floor: one is not something the engine
                // can interpolate across.
                val removable = swatches.size > CoverPaletteOverride.MIN_COLOURS
                Icon(
                    Icons.Default.Close,
                    if (removable) "Remove this colour" else null,
                    tint = if (removable) TextMuted else TextFaint,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable(enabled = removable) {
                            onEdit(swatches.filterIndexed { j, _ -> j != i })
                        },
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (swatches.size < CoverPaletteOverride.MAX_COLOURS) {
                SmallAction("Add colour", accent, Modifier.weight(1f)) {
                    // A copy of the last colour rather than a random one: it is
                    // about to be edited, and starting from something on the
                    // palette is a shorter trip than starting from grey.
                    val seed = swatches.lastOrNull()?.color ?: Color.DarkGray
                    val share = if (swatches.isEmpty()) 0.25f else total / swatches.size
                    onEdit(swatches + Swatch(seed, share))
                }
            }
            SmallAction("Even", accent, Modifier.weight(1f)) {
                onEdit(swatches.map { it.copy(weight = 1f) })
            }
            if (extracted.isNotEmpty()) {
                SmallAction("From cover", accent, Modifier.weight(1f)) {
                    onEdit(extracted)
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OledButton(
                text = "Use cover's own",
                accent = accent,
                outline = true,
                modifier = Modifier.weight(1f),
            ) {
                onSave(null)
                onClose()
            }
            OledButton(
                text = if (loaded) "Save" else "Loading…",
                accent = accent,
                enabled = loaded && swatches.size >= CoverPaletteOverride.MIN_COLOURS,
                modifier = Modifier.weight(1f),
            ) {
                // Weights are saved raw. Normalisation is the model's job — see
                // CoverPaletteOverride.normalisedWeights — so re-opening the
                // editor shows the same slider positions the user left, not a
                // rescaled set that drifts a little on every visit.
                onSave(
                    CoverPaletteOverride(
                        colors = swatches.map { it.color.toArgb() },
                        mode = CoverPaletteOverride.Mode.OVERRIDE,
                        weights = swatches.map { it.weight },
                    )
                )
                onClose()
            }
        }
    }
}

/**
 * The colour picker, as a page of the same sheet.
 *
 * Hue, saturation and brightness are held here rather than derived from [current]
 * on every frame, because two of the three stop meaning anything at the edges — a
 * fully desaturated colour has no hue to read back, and a black one has neither —
 * and a picker that lost the hue the moment brightness reached zero would be
 * unusable. [current] seeds them once per swatch and the sliders own them after
 * that; every move writes the resulting colour straight back out through [onColor],
 * so the proportion bar above is live.
 */
@Composable
private fun PickerPage(
    index: Int,
    current: Color,
    accent: Color,
    onColor: (Color) -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    // Keyed on the swatch, not on the colour: keying on the colour would reset the
    // sliders to their own output on every drag frame.
    var hsv by remember(index) { mutableStateOf(current.toHsv()) }
    val preview = hsv.toColor()

    fun set(next: Hsv) {
        hsv = next
        onColor(next.toColor())
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(preview)
                    .border(2.dp, Hairline, CircleShape),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    preview.hex(),
                    color = TextPrimary, fontFamily = MonoFont,
                    fontWeight = FontWeight.Bold, fontSize = 14.sp,
                )
                Text(
                    "Tap a preset, or dial it in below.",
                    color = TextMuted, fontFamily = AppFont, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        // Eight presets on one row of large targets. The old dialog built this list
        // and then never drew it, so the only way to reach a colour was three
        // sliders — which is a long way round for "make it red".
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PRESETS.forEach { preset ->
                val selected = preset.toArgb() == preview.toArgb()
                Box(
                    Modifier
                        .weight(1f)
                        .height(34.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(preset)
                        .border(
                            if (selected) 2.dp else 1.dp,
                            if (selected) accent else Hairline,
                            RoundedCornerShape(9.dp),
                        )
                        .clickable { set(preset.toHsv()) },
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        PickerSlider("Hue", accent, hsv.h / 360f, Brush.horizontalGradient(HUE_STOPS)) {
            set(hsv.copy(h = it * 360f))
        }
        PickerSlider(
            "Saturation", accent, hsv.s,
            Brush.horizontalGradient(listOf(Hsv(hsv.h, 0f, hsv.v).toColor(), Hsv(hsv.h, 1f, hsv.v).toColor())),
        ) {
            set(hsv.copy(s = it))
        }
        PickerSlider(
            "Brightness", accent, hsv.v,
            Brush.horizontalGradient(listOf(Color.Black, Hsv(hsv.h, hsv.s, 1f).toColor())),
        ) {
            set(hsv.copy(v = it))
        }

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OledButton("Cancel", accent, outline = true, modifier = Modifier.weight(1f)) { onCancel() }
            OledButton("Use this colour", accent, modifier = Modifier.weight(1f)) { onDone() }
        }
    }
}

/** A labelled [HSlider] over a gradient showing what the slider is actually choosing between. */
@Composable
private fun PickerSlider(
    label: String,
    accent: Color,
    value: Float,
    track: Brush,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = TextMuted, fontFamily = AppFont, fontSize = 11.sp)
        Box(contentAlignment = Alignment.CenterStart) {
            // The gradient is decoration under the slider's own track, so the two
            // read as one control rather than a rail beside a swatch strip.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(track)
                    .border(1.dp, Hairline, RoundedCornerShape(50)),
            )
            HSlider(
                value = value.coerceIn(0f, 1f),
                onChange = onChange,
                accented = false,
                trackHeight = 0.dp,
            )
        }
    }
}

/** The palette drawn in proportion, so the percentages mean something at a glance. */
@Composable
private fun PaletteBar(swatches: List<Swatch>, highlight: Int? = null) {
    val total = swatches.sumOf { it.weight.toDouble() }.toFloat()
    Row(
        Modifier
            .fillMaxWidth()
            .height(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, Hairline, RoundedCornerShape(10.dp)),
    ) {
        if (total <= 0f) {
            Box(Modifier.fillMaxSize().background(Glass))
            return@Row
        }
        swatches.forEachIndexed { i, s ->
            // A zero-weight colour is left out rather than given a sliver: it is the
            // user saying "none of this", and a hairline of it would read as a bug.
            val share = s.weight / total
            if (share <= 0f) return@forEachIndexed
            Box(
                Modifier
                    .weight(share)
                    .fillMaxHeight()
                    .background(s.color)
                    // Which stretch of the room the picker is currently changing.
                    // Without it the bar animates under the sliders with no clue as
                    // to which band is the one moving.
                    .then(
                        if (i == highlight) {
                            Modifier.border(2.dp, contrastingInk(s.color), RoundedCornerShape(2.dp))
                        } else {
                            Modifier
                        }
                    ),
            )
        }
    }
}

@Composable
private fun SmallAction(text: String, accent: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(11.dp))
            .border(1.dp, accent.a(0.45f), RoundedCornerShape(11.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = accent, fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

/**
 * The largest raw share a slider can set.
 *
 * Raw shares are normalised for display and for the engine, so the absolute number
 * is arbitrary — what it buys is a slider that can put one colour well above the
 * others without every other slider having to come down to make room.
 */
private const val MAX_WEIGHT = 2f

/** Black or white, whichever is legible on [on]. Used for icons and outlines drawn over a swatch. */
private fun contrastingInk(on: Color): Color =
    if (on.red * 0.299f + on.green * 0.587f + on.blue * 0.114f > 0.55f) Color.Black else Color.White

/** Eight one-tap colours, spread round the wheel plus white and black. */
private val PRESETS = listOf(
    Color(0xFFFF2D2D), Color(0xFFFF8A00), Color(0xFFFFE500), Color(0xFF2ECC40),
    Color(0xFF00D2FF), Color(0xFF2B5BFF), Color(0xFFB44BFF), Color(0xFFFFFFFF),
)

/** The hue wheel as gradient stops, for the hue slider's own track. */
private val HUE_STOPS = listOf(
    Color(0xFFFF0000), Color(0xFFFFFF00), Color(0xFF00FF00),
    Color(0xFF00FFFF), Color(0xFF0000FF), Color(0xFFFF00FF), Color(0xFFFF0000),
)

/** Hue in degrees, saturation and value in 0..1. */
private data class Hsv(val h: Float, val s: Float, val v: Float)

private fun Hsv.toColor(): Color {
    val hue = ((h % 360f) + 360f) % 360f
    val sat = s.coerceIn(0f, 1f)
    val value = v.coerceIn(0f, 1f)
    val c = value * sat
    val x = c * (1f - abs((hue / 60f) % 2f - 1f))
    val m = value - c
    val (r, g, b) = when ((hue / 60f).toInt()) {
        0 -> Triple(c, x, 0f)
        1 -> Triple(x, c, 0f)
        2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c)
        4 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(r + m, g + m, b + m, 1f)
}

private fun Color.toHsv(): Hsv {
    val r = red
    val g = green
    val b = blue
    val maxC = max(r, max(g, b))
    val minC = min(r, min(g, b))
    val delta = maxC - minC
    val h = when {
        delta < 1e-6f -> 0f
        maxC == r -> 60f * (((g - b) / delta) % 6f)
        maxC == g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }
    return Hsv(((h % 360f) + 360f) % 360f, if (maxC <= 0f) 0f else delta / maxC, maxC)
}

private fun Color.hex(): String {
    fun c(f: Float) = (f.coerceIn(0f, 1f) * 255f).roundToInt()
    return "#%02X%02X%02X".format(c(red), c(green), c(blue))
}

@Composable
private fun OledButton(
    text: String,
    accent: Color,
    enabled: Boolean = true,
    outline: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val fill = if (outline) Glass else accent
    val border = if (outline) Hairline else Color.Transparent
    val label = if (outline) TextMuted else Ink
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(if (enabled) fill else Glass)
            .border(1.dp, if (enabled) border else Hairline, RoundedCornerShape(13.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (enabled) label else TextMuted,
            fontFamily = AppFont, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp,
        )
    }
}
