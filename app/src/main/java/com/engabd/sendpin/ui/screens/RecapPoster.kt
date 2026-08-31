package com.engabd.sendpin.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.compose.material3.Text
import com.engabd.sendpin.ui.design.LocalPalette
import com.engabd.sendpin.ui.theme.*
import com.engabd.sendpin.ui.viewmodel.StatsViewModel
import com.engabd.sendpin.ui.viewmodel.formatListeningTime
import java.io.File
import java.io.FileOutputStream

/**
 * A shareable picture of what someone has been listening to.
 *
 * Everything on it is computed on this phone from its own play history and its own
 * offline analysis — the dominant keys and the tempo come from the same scanner
 * that drives the light show, not from a service that watched. That is the whole
 * reason it is worth making: a recap from a streaming service is a summary of what
 * their servers logged, and this is a summary of records you own, measured by
 * software you are running.
 *
 * Rendered from the same [StatsViewModel.State] the Stats screen already has, so
 * there is no second pass over the database and nothing here can disagree with the
 * screen it was opened from.
 */
@Composable
fun RecapPoster(
    state: StatsViewModel.State,
    layer: GraphicsLayer,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    Column(
        modifier
            // Recorded into a layer so it can be saved as a PNG exactly as drawn.
            // drawWithContent rather than a screenshot API: this captures the
            // composable, so what is shared is what is on screen even where the
            // poster is taller than the display.
            .drawWithContent {
                layer.record { this@drawWithContent.drawContent() }
                drawLayer(layer)
            }
            .clip(RoundedCornerShape(22.dp))
            .background(
                Brush.linearGradient(
                    listOf(palette.swatch(0).copy(alpha = 0.35f), Ink),
                    start = Offset.Zero,
                    end = Offset(0f, Float.POSITIVE_INFINITY),
                ),
            )
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column {
            Text(
                state.window.label.uppercase(),
                color = palette.swatch(1), fontFamily = MonoFont,
                fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.4.sp,
            )
            Text(
                "In sound",
                color = TextPrimary, fontFamily = AppFont,
                fontWeight = FontWeight.ExtraBold, fontSize = 32.sp, letterSpacing = (-1).sp,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Figure(formatListeningTime(state.totalListeningMs), "listened")
            Figure(state.plays.toString(), "plays")
            Figure(state.distinctArtists.toString(), "artists")
        }

        if (state.topArtists.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Label("Most played")
                state.topArtists.take(5).forEachIndexed { i, artist ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${i + 1}",
                            color = palette.swatch(i + 1), fontFamily = MonoFont,
                            fontWeight = FontWeight.Bold, fontSize = 13.sp,
                            modifier = Modifier.width(22.dp),
                        )
                        Text(
                            artist.artist,
                            color = TextPrimary, fontFamily = AppFont,
                            fontWeight = FontWeight.Bold, fontSize = 15.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            artist.plays.toString(),
                            color = TextFaint, fontFamily = MonoFont, fontSize = 12.sp,
                        )
                    }
                }
            }
        }

        // The half only this app can draw: key and tempo, measured here.
        val key = state.dominantKeys.firstOrNull()
        val bpm = state.bpmHistogram.maxByOrNull { it.second }
        if (key != null || bpm != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                key?.let { Figure(it.first, "your key") }
                bpm?.let { Figure(it.first, "your tempo") }
            }
        }

        if (state.longestStreakDays > 1 || state.losslessShare != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                if (state.longestStreakDays > 1) {
                    Figure("${state.longestStreakDays}", "day streak")
                }
                state.losslessShare?.let {
                    Figure("${(it * 100).toInt()}%", "lossless")
                }
            }
        }

        Text(
            "CAMusic · measured on this phone, from your own library",
            color = TextFaint, fontFamily = AppFont, fontSize = 10.sp,
        )
    }
}

@Composable
private fun Figure(value: String, caption: String) {
    Column {
        Text(
            value,
            color = TextPrimary, fontFamily = AppFont,
            fontWeight = FontWeight.ExtraBold, fontSize = 22.sp,
        )
        Text(
            caption,
            color = TextMuted, fontFamily = AppFont,
            fontWeight = FontWeight.SemiBold, fontSize = 11.sp,
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text.uppercase(),
        color = TextMuted, fontFamily = MonoFont,
        fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.2.sp,
    )
}

/**
 * Write [bitmap] into the cache and hand it to the share sheet.
 *
 * Through a FileProvider, so what leaves is a per-URI read-only grant that dies
 * with the receiving activity rather than a file on shared storage. Nothing is
 * uploaded anywhere by this app; where the picture goes next is the share sheet's
 * business and the listener's choice.
 *
 * @return an error message, or null when the sheet was opened.
 */
fun shareRecap(context: Context, bitmap: Bitmap): String? = try {
    val dir = File(context.cacheDir, "shared").apply { mkdirs() }
    // One fixed name, overwritten: a recap is a snapshot of right now, and a cache
    // that accumulated one PNG per share would grow without anything trimming it.
    val file = File(dir, "camusic-recap.png")
    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

    val uri = FileProvider.getUriForFile(context, "${context.packageName}.shared", file)
    context.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "Share your recap",
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    null
} catch (e: Exception) {
    e.message ?: "Couldn't share that"
}

/** The recorded layer as a bitmap, or null if it has not been drawn yet. */
suspend fun GraphicsLayer.toShareableBitmap(): Bitmap? =
    runCatching { toImageBitmap().asAndroidBitmap() }.getOrNull()
