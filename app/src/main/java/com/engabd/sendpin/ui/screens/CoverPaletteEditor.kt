package com.engabd.sendpin.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.hue.CoverPaletteOverride
import com.engabd.sendpin.hue.extractAlbumColours
import com.engabd.sendpin.ui.design.HSlider
import com.engabd.sendpin.ui.design.HideBottomChrome
import com.engabd.sendpin.ui.design.LocalAccent
import com.engabd.sendpin.ui.design.dismissOnDragDown
import com.engabd.sendpin.ui.design.systemNavInset
import com.engabd.sendpin.ui.design.TitleGap
import com.engabd.sendpin.ui.design.a
import com.engabd.sendpin.ui.theme.*
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
 * ## Seeding
 *
 * From [extractAlbumColours] — the engine's own v2 extractor — rather than a
 * lookalike written for the preview. The editor is for *adjusting* what the room is
 * doing, and a preview computed a different way would start the user from colours
 * the room was never showing.
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
    BackHandler(onBack = onClose)

    val context = LocalContext.current
    val accent = LocalAccent.current

    // The palette being edited: colour and its raw share. Raw, not normalised, so a
    // slider does not shove every other row about while it is under the finger — the
    // percentages shown are normalised for display on each frame instead.
    var swatches by remember { mutableStateOf<List<Swatch>>(emptyList()) }
    var pickingIndex by remember { mutableStateOf<Int?>(null) }
    var loaded by remember { mutableStateOf(false) }
    // What the cover itself says, kept so "Use the cover's colours" can go back to it
    // without a second decode.
    var extracted by remember { mutableStateOf<List<Swatch>>(emptyList()) }

    LaunchedEffect(coverUrl, existing) {
        val fromCover = withContext(Dispatchers.IO) {
            if (coverUrl.isNullOrBlank()) return@withContext null
            try {
                val request = ImageRequest.Builder(context)
                    .data(coverUrl)
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

    if (pickingIndex != null) {
        val index = pickingIndex!!
        ColorPickerDialog(
            initial = swatches.getOrElse(index) { Swatch(Color.DarkGray, 0.25f) }.color,
            onDismiss = { pickingIndex = null },
            onSelect = { color ->
                swatches = swatches.mapIndexed { i, s -> if (i == index) s.copy(color = color) else s }
                pickingIndex = null
            },
        )
    }

    // Dismiss scrim.
    Box(
        Modifier
            .matchParentSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClose() }
    )

    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .dismissOnDragDown(onClose)
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
            Text(
                "Light Sync colours",
                color = TextPrimary, fontFamily = AppFont,
                fontWeight = FontWeight.ExtraBold, fontSize = 16.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(albumName.takeIf { it.isNotBlank() }, artistName?.takeIf { it.isNotBlank() })
                    .joinToString(" · ")
                    .ifBlank { "The colours this album lights the room with." },
                color = TextMuted, fontFamily = AppFont, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(14.dp))

            // The palette as one bar, in proportion. The percentages are abstract in
            // a list of sliders and obvious here — this is what the room will be.
            PaletteBar(swatches)
            Spacer(Modifier.height(16.dp))

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
                            .border(2.dp, if (pickingIndex == i) accent else Hairline, CircleShape)
                            .clickable { pickingIndex = i },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Edit, "Change this colour",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(15.dp),
                        )
                    }
                    HSlider(
                        value = (swatch.weight / MAX_WEIGHT).coerceIn(0f, 1f),
                        onChange = { v ->
                            swatches = swatches.mapIndexed { j, s ->
                                if (j == i) s.copy(weight = v * MAX_WEIGHT) else s
                            }
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
                    Icon(
                        Icons.Default.Close,
                        if (swatches.size > CoverPaletteOverride.MIN_COLOURS) "Remove this colour" else null,
                        tint = if (swatches.size > CoverPaletteOverride.MIN_COLOURS) TextMuted else TextFaint,
                        modifier = Modifier
                            .size(18.dp)
                            .clickable(enabled = swatches.size > CoverPaletteOverride.MIN_COLOURS) {
                                swatches = swatches.filterIndexed { j, _ -> j != i }
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
                        swatches = swatches + Swatch(seed, share)
                    }
                }
                SmallAction("Even", accent, Modifier.weight(1f)) {
                    swatches = swatches.map { it.copy(weight = 1f) }
                }
                if (extracted.isNotEmpty()) {
                    SmallAction("From cover", accent, Modifier.weight(1f)) {
                        swatches = extracted
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
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** One colour and how much of the room it should hold. [weight] is a raw share. */
private data class Swatch(val color: Color, val weight: Float)

/** The palette drawn in proportion, so the percentages mean something at a glance. */
@Composable
private fun PaletteBar(swatches: List<Swatch>) {
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
        swatches.forEach { s ->
            // A zero-weight colour is left out rather than given a sliver: it is the
            // user saying "none of this", and a hairline of it would read as a bug.
            val share = s.weight / total
            if (share <= 0f) return@forEach
            Box(Modifier.weight(share).fillMaxHeight().background(s.color))
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

@Composable
private fun ColorPickerDialog(
    initial: Color,
    onDismiss: () -> Unit,
    onSelect: (Color) -> Unit,
) {
    // A deliberately simple picker: a grid of preset swatches plus a custom
    // HSV slider. Full HSV colour wheels belong in a dedicated component; this
    // keeps the sheet small and the touch targets large.
    val presets = remember {
        listOf(
            Color(0xFFFF0000), Color(0xFFFF7F00), Color(0xFFFFFF00), Color(0xFF00FF00),
            Color(0xFF0000FF), Color(0xFF4B0082), Color(0xFF9400D3), Color(0xFFFF1493),
            Color(0xFF00FFFF), Color(0xFFFFA500), Color(0xFF32CD32), Color(0xFF1E90FF),
            Color(0xFFFFFFFF), Color(0xFF808080), Color(0xFF000000), Color(0xFF8B4513),
        )
    }
    var customHue by remember { mutableStateOf(0f) }
    var customSat by remember { mutableStateOf(1f) }
    var customVal by remember { mutableStateOf(1f) }

    BackHandler(onBack = onDismiss)

    Box(
        Modifier
            .fillMaxSize()
            .background(Ink.a(0.85f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.85f)
                .clip(RoundedCornerShape(20.dp))
                .background(Ink2)
                .border(1.dp, Hairline, RoundedCornerShape(20.dp))
                .padding(18.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Pick a colour",
                color = TextPrimary, fontFamily = AppFont,
                fontWeight = FontWeight.ExtraBold, fontSize = 16.sp,
            )
            Spacer(Modifier.height(16.dp))

            val preview = hsvToComposeColor(customHue, customSat, customVal)
            Box(
                Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(preview)
                    .border(2.dp, Hairline, CircleShape),
            )

            Spacer(Modifier.height(16.dp))
            Text("Hue", color = TextMuted, fontFamily = AppFont, fontSize = 12.sp)
            HSlider(
                value = customHue / 360f,
                onChange = { customHue = it * 360f },
                modifier = Modifier.padding(vertical = 8.dp),
            )
            Text("Saturation", color = TextMuted, fontFamily = AppFont, fontSize = 12.sp)
            HSlider(
                value = customSat,
                onChange = { customSat = it },
                modifier = Modifier.padding(vertical = 8.dp),
            )
            Text("Brightness", color = TextMuted, fontFamily = AppFont, fontSize = 12.sp)
            HSlider(
                value = customVal,
                onChange = { customVal = it },
                modifier = Modifier.padding(vertical = 8.dp),
            )

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                OledButton("Cancel", LocalAccent.current, outline = true, modifier = Modifier.weight(1f)) { onDismiss() }
                Spacer(Modifier.width(10.dp))
                OledButton("Choose this", LocalAccent.current, modifier = Modifier.weight(1f)) { onSelect(preview) }
            }
        }
    }
}

private fun hsvToComposeColor(h: Float, s: Float, v: Float): Color {
    val i = (h / 60f).toInt() % 6
    val f = h / 60f - (h / 60f).toInt()
    val p = (v * (1f - s) * 255f).toInt()
    val q = (v * (1f - f * s) * 255f).toInt()
    val t = (v * (1f - (1f - f) * s) * 255f).toInt()
    val vv = (v * 255f).toInt()
    val (r, g, b) = when (i) {
        0 -> Triple(vv, t, p)
        1 -> Triple(q, vv, p)
        2 -> Triple(p, vv, t)
        3 -> Triple(p, q, vv)
        4 -> Triple(t, p, vv)
        else -> Triple(vv, p, q)
    }
    return Color(0xFF000000 or ((r.coerceIn(0, 255) shl 16).toLong()) or ((g.coerceIn(0, 255) shl 8).toLong()) or b.coerceIn(0, 255).toLong())
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
