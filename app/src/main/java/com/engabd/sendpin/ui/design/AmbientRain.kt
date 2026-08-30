package com.engabd.sendpin.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.engabd.sendpin.ui.theme.TextFaint
import kotlin.math.sin
import kotlin.random.Random

/**
 * A quiet rain-on-glass wash — the one purely decorative touch on the Stats
 * screen, which is otherwise all data. A fixed set of streaks, each with its own
 * speed and drift, advanced by a single frame loop and read directly inside
 * [Canvas]'s draw scope, so only this `Canvas` redraws every frame — never the
 * chart list sitting beside it (see [Streak], a plain `remember`ed value, not
 * Compose `State`, so nothing but the draw phase depends on time moving at all).
 *
 * Degrades to one still frame under [LocalReducedMotion] rather than disappearing
 * outright — the app's own Reduced-motion documentation promises settled, resolved
 * states in place of continuous motion, not a blank space where one was.
 */
@Composable
fun AmbientRain(modifier: Modifier = Modifier, color: Color = TextFaint) {
    val reducedMotion = LocalReducedMotion.current
    val streaks = remember { List(STREAK_COUNT) { Streak(Random(it)) } }
    var timeS by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(reducedMotion) {
        if (reducedMotion) return@LaunchedEffect
        var lastNanos = 0L
        while (true) {
            withFrameNanos { nanos ->
                if (lastNanos != 0L) timeS += (nanos - lastNanos) / 1_000_000_000f
                lastNanos = nanos
            }
        }
    }

    Canvas(modifier) {
        for (streak in streaks) drawStreak(streak, timeS, color)
    }
}

/** One streak's own speed, length and sideways drift — fixed for its lifetime. */
private class Streak(rng: Random) {
    val xFrac = rng.nextFloat()
    val speed = 0.10f + rng.nextFloat() * 0.10f   // screen-heights per second
    val length = 0.05f + rng.nextFloat() * 0.05f  // screen-heights
    val phase = rng.nextFloat()
    val alpha = 0.08f + rng.nextFloat() * 0.14f
    val driftHz = 0.05f + rng.nextFloat() * 0.05f
    val driftAmp = rng.nextFloat() * 0.012f
}

private fun DrawScope.drawStreak(streak: Streak, timeS: Float, color: Color) {
    // Runs slightly past both edges so a streak fades in/out at the top and
    // bottom rather than popping into existence mid-screen.
    val yFrac = ((streak.phase + timeS * streak.speed) % 1.2f) - 0.1f
    val drift = sin((timeS * streak.driftHz + streak.phase) * 2f * Math.PI.toFloat()) * streak.driftAmp
    val x = (streak.xFrac + drift) * size.width
    val yTop = ((yFrac - streak.length) * size.height).coerceIn(0f, size.height)
    val yBottom = (yFrac * size.height).coerceIn(0f, size.height)
    if (yBottom <= yTop) return
    drawLine(
        color = color.copy(alpha = streak.alpha),
        start = Offset(x, yTop),
        end = Offset(x, yBottom),
        strokeWidth = 1.4f,
    )
}

private const val STREAK_COUNT = 28
