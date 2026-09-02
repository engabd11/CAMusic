package com.engabd.sendpin.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engabd.sendpin.ui.design.LocalPalette
import com.engabd.sendpin.ui.design.a
import com.engabd.sendpin.ui.design.pressScale
import com.engabd.sendpin.ui.design.rememberAccent
import com.engabd.sendpin.ui.design.rememberPressScale
import com.engabd.sendpin.ui.theme.AppFont
import com.engabd.sendpin.ui.theme.Ink
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextPrimary
import kotlin.math.sin

/**
 * The one control in the library that does not browse anything: press it and the
 * room has music in it.
 *
 * Everything else on this screen is a way of *finding* something, which assumes the
 * listener already knows what they want. This is the other mood — and it has to look
 * like the other mood, or it reads as one more shelf. So it is the only element on
 * the page built out of the album palette's own colours rather than glass over ink,
 * the only one that moves while it sits there, and the only one that spans the grid
 * on its own.
 *
 * The equaliser bars are not decoration in the usual sense: they are the difference
 * between a button that says a set is running and a button that *looks* like a set
 * is running, and they stop dead when it is not.
 */
@Composable
internal fun DjRadioCard(
    running: Boolean,
    /** Seconds of overlap between tracks, for the line that says what this will do. */
    crossfadeSeconds: Int,
    /** The join is planned off the track analysis rather than off the clock. */
    smartFade: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = rememberAccent()
    val palette = LocalPalette.current
    // A second hue from the cover rather than a fixed one: the card is the loudest
    // thing on the page and a hard-coded gradient would clash with half the library.
    val companion = palette.swatch(2)
    val press = rememberPressScale()
    val shape = RoundedCornerShape(20.dp)

    // The sweep only exists while a set is running. An always-animating card at the
    // top of a scrolling library is a battery cost and a distraction; this is a
    // status light, and a status light that is always on says nothing.
    val phase = if (running) {
        val motion = rememberInfiniteTransition(label = "dj")
        motion.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(SWEEP_PERIOD_MS, easing = LinearEasing),
                RepeatMode.Restart,
            ),
            label = "sweep",
        ).value
    } else {
        0f
    }

    // What the button is about to do, in three words. "Smart mix" says the join comes
    // from the analysis; a number says it comes from the clock. Both are more use
    // than the word "crossfade", which does not distinguish them.
    val mixLabel = when {
        smartFade -> "smart mix"
        crossfadeSeconds > 0 -> "${crossfadeSeconds}s crossfade"
        else -> "straight in"
    }

    val glow by animateFloatAsState(
        targetValue = if (running) 1f else 0f,
        label = "glow",
    )

    Box(
        modifier
            .fillMaxWidth()
            .pressScale(press)
            .height(88.dp)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        accent.a(0.34f + 0.16f * glow),
                        companion.a(0.20f + 0.14f * glow),
                        accent.a(0.10f),
                    ),
                    start = Offset.Zero,
                    end = Offset.Infinite,
                )
            )
            .border(1.dp, accent.a(0.36f + 0.24f * glow), shape)
            .clickable(
                interactionSource = press.interactions,
                indication = null,
                onClick = if (running) onStop else onStart,
            ),
    ) {
        // The oversized glyph half off the corner, the same trick the category tiles
        // use — it reads as artwork rather than as a second icon competing with the
        // one in the row.
        Icon(
            Icons.Default.GraphicEq, null,
            tint = accent.a(0.16f),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(x = 22.dp)
                .size(104.dp),
        )

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            EqualiserMark(running = running, phase = phase, tint = accent)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    "DJ Radio",
                    color = TextPrimary,
                    fontFamily = AppFont,
                    fontWeight = FontWeight.Black,
                    fontSize = 19.sp,
                    letterSpacing = 0.4.sp,
                )
                Text(
                    when {
                        running -> "Mixing on · $mixLabel · tap to stop"
                        else -> "One tap, one mood, no gaps · $mixLabel"
                    },
                    color = TextMuted,
                    fontFamily = AppFont,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(if (running) accent else accent.a(0.22f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (running) Icons.Default.Stop else Icons.Default.GraphicEq,
                    if (running) "Stop DJ Radio" else "Start DJ Radio",
                    tint = if (running) Ink else accent,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
    }
}

/**
 * Five bars that dance while a set is running and stand still when it is not.
 *
 * Drawn from one animating phase and a per-bar offset rather than five independent
 * animations: five infinite transitions on a card that lives at the top of a lazy
 * grid is five things recomposing on every frame, and the whole effect is one sine
 * wave read at five places.
 */
@Composable
private fun EqualiserMark(running: Boolean, phase: Float, tint: Color) {
    Row(
        Modifier.width(26.dp).height(26.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.5.dp),
    ) {
        repeat(BARS) { i ->
            // Resting heights when nothing is playing: a static bar chart, which
            // still reads as "sound" without pretending to be live.
            val rest = RESTING[i]
            val height = if (!running) rest else {
                val x = (phase + i * 0.19f) * 2f * Math.PI.toFloat()
                0.55f + 0.45f * sin(x)
            }
            Box(
                Modifier
                    .width(3.dp)
                    .height(26.dp)
                    .graphicsLayer {
                        // Scaled rather than resized: a height change relayouts the
                        // row every frame, a scale is a draw-time transform.
                        scaleY = height.coerceIn(0.12f, 1f)
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (running) tint else tint.a(0.55f)),
            )
        }
    }
}

private const val BARS = 5

/** The still pose: uneven, so a stopped card still looks like sound rather than a grid. */
private val RESTING = floatArrayOf(0.42f, 0.78f, 0.30f, 0.62f, 0.48f)

/** One pass of the sweep across the bars. Slow enough to read as a pulse, not a flicker. */
private const val SWEEP_PERIOD_MS = 1_400
