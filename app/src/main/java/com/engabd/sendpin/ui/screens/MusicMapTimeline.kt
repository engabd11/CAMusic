package com.engabd.sendpin.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.engabd.sendpin.audio.ScanSection
import com.engabd.sendpin.audio.TrackScan
import com.engabd.sendpin.ui.design.LocalAccent

/**
 * A "map" of the current track's structure — one coloured band per [ScanSection],
 * tap to seek there. Only ever shown for a track that has an offline scan; the
 * caller is responsible for that check (there's nothing useful to draw otherwise).
 */
@Composable
fun MusicMapTimeline(
    scan: TrackScan,
    positionMs: Long,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = LocalAccent.current
    val durationS = scan.durationS
    Canvas(
        modifier
            .fillMaxWidth()
            .height(24.dp)
            .pointerInput(scan) {
                if (durationS <= 0f) return@pointerInput
                detectTapGestures { offset ->
                    onSeek((offset.x / size.width).coerceIn(0f, 1f))
                }
            },
    ) {
        if (durationS <= 0f) return@Canvas
        scan.sections.forEach { section ->
            val startX = (section.startS / durationS).coerceIn(0f, 1f) * size.width
            val endX = (section.endS / durationS).coerceIn(0f, 1f) * size.width
            if (endX <= startX) return@forEach
            drawRect(
                color = sectionColor(section.label),
                topLeft = Offset(startX, 0f),
                size = Size(endX - startX, size.height),
            )
        }
        val posFraction = (positionMs / 1000f / durationS).coerceIn(0f, 1f)
        drawLine(
            color = accent,
            start = Offset(posFraction * size.width, 0f),
            end = Offset(posFraction * size.width, size.height),
            strokeWidth = 2.dp.toPx(),
        )
    }
}

/**
 * A stable colour per section label — repeated sections (the chorus coming back)
 * render the same colour each time, rather than the next hue along.
 */
private fun sectionColor(label: Int): Color {
    val hue = (label * 47f) % 360f
    return Color.hsv(hue, 0.45f, 0.7f, alpha = 0.55f)
}
