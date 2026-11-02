package com.engabd.sendpin.ui.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.engabd.sendpin.hue.HueBridgeClient
import com.engabd.sendpin.ui.theme.Hairline
import com.engabd.sendpin.ui.theme.PositiveGreen
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextPrimary
import com.engabd.sendpin.ui.theme.ErrorRed
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sin

enum class BridgeLinkPhase { Waiting, Linked, Failed }

private val LedBlue = Color(0xFF4DA6FF)
private val Skin = Color(0xFFEBCDB7)

/**
 * The "press the button on your bridge" moment, shown rather than described.
 *
 * A top-down Hue Bridge with a fingertip pressing its round button on a loop, ringed by
 * the time left to do it. [BridgeLinkPhase.Linked] lights the button green and draws a
 * tick; [BridgeLinkPhase.Failed] turns the ring amber and shakes the bridge once.
 * [attempt] restarts the countdown for a retry of the same phase.
 *
 * Drawn on a Canvas rather than shipped as a video or Lottie so it follows the accent,
 * scales to any screen and costs nothing in the APK. With reduced motion the finger
 * stays on the button instead of looping; the countdown keeps running, since it is
 * information rather than decoration.
 */
@Composable
fun BridgeLinkPrompt(
    phase: BridgeLinkPhase,
    attempt: Int,
    accent: Color,
    modifier: Modifier = Modifier,
    /** Why a [BridgeLinkPhase.Failed] failed, when it was not simply a missed press. */
    failure: String? = null,
    windowMs: Long = HueBridgeClient.LINK_WINDOW_MS,
) {
    val reduced = LocalReducedMotion.current

    // Off the frame clock, not an animation spec: "remove animations" would snap a spec
    // straight to zero.
    var remaining by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(phase, attempt) {
        if (phase != BridgeLinkPhase.Waiting) return@LaunchedEffect
        remaining = 1f
        val start = withFrameMillis { it }
        while (remaining > 0f) {
            withFrameMillis { now -> remaining = (1f - (now - start).toFloat() / windowMs).coerceAtLeast(0f) }
        }
    }
    val secondsLeft by remember(windowMs) { derivedStateOf { ceil(remaining * windowMs / 1000f).toInt() } }

    val tick = remember { Animatable(0f) }
    val burst = remember { Animatable(0f) }
    val shake = remember { Animatable(0f) }
    LaunchedEffect(phase, attempt) {
        when (phase) {
            BridgeLinkPhase.Waiting -> { tick.snapTo(0f); burst.snapTo(0f) }
            BridgeLinkPhase.Linked -> {
                burst.snapTo(0f)
                coroutineScope {
                    launch { burst.animateTo(1f, tween(900, easing = LinearOutSlowInEasing)) }
                    tick.animateTo(1f, tween(480, delayMillis = 150, easing = FastOutSlowInEasing))
                }
            }
            BridgeLinkPhase.Failed -> {
                tick.snapTo(0f)
                shake.snapTo(0f)
                shake.animateTo(0f, spring(dampingRatio = 0.22f, stiffness = 900f), initialVelocity = 700f)
            }
        }
    }

    val stateColor by animateColorAsState(
        when (phase) {
            BridgeLinkPhase.Waiting -> accent
            BridgeLinkPhase.Linked -> PositiveGreen
            // Not amber: it sits too close to the default gold accent to read as a change.
            BridgeLinkPhase.Failed -> ErrorRed
        },
        Motion.effects(), label = "linkColour",
    )
    val fingerAlpha by animateFloatAsState(
        if (phase == BridgeLinkPhase.Waiting) 1f else 0f, Motion.effects(), label = "finger",
    )
    val miss by animateFloatAsState(
        if (phase == BridgeLinkPhase.Failed) 1f else 0f, Motion.effects(), label = "miss",
    )
    val loop = if (phase == BridgeLinkPhase.Waiting && !reduced) {
        rememberInfiniteTransition(label = "press").animateFloat(
            0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "pressCycle",
        ).value
    } else null
    val track = Hairline

    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Decorative: the headline under it says the same thing to TalkBack.
        Canvas(Modifier.size(196.dp).clearAndSetSemantics {}) {
            val s = size.minDimension
            val c = center
            val b = s * 0.50f
            val br = b * 0.27f

            // Finger height above the button: 1 hovering, 0 pressed.
            val h = loop?.let(::liftAt) ?: 0f
            val press = if (phase == BridgeLinkPhase.Waiting) 1f - h else 0f

            // Halo and countdown ring.
            val ringR = s * 0.46f
            drawCircle(
                Brush.radialGradient(listOf(stateColor.a(0.10f + 0.12f * max(press, tick.value)), Color.Transparent), c, ringR),
                ringR, c,
            )
            drawCircle(lerp(track, stateColor.a(0.45f), miss), ringR, c, style = Stroke(s * 0.018f))
            val sweep = 360f * (remaining + (1f - remaining) * burst.value)
            if (sweep > 0.5f) {
                drawArc(
                    stateColor, -90f, sweep, false,
                    topLeft = Offset(c.x - ringR, c.y - ringR), size = Size(ringR * 2, ringR * 2),
                    style = Stroke(s * 0.026f, cap = StrokeCap.Round),
                )
            }

            translate(left = shake.value) {
                drawBridge(b, br, press, stateColor, tick.value)
                if (miss > 0.01f) drawRetryArrow(br * 0.42f, b * 0.035f, stateColor.a(miss))
            }

            // Ripples off the button as it goes down, and one wide burst on success.
            if (loop != null) {
                for (k in 0..1) {
                    val r = (loop - 0.46f) / 0.42f - k * 0.2f
                    if (r in 0f..1f) {
                        drawCircle(
                            accent.a(0.6f * (1f - r)), br * (1.05f + 1.4f * r), c,
                            style = Stroke(b * 0.022f * (1f - r) + 1f),
                        )
                    }
                }
            }
            if (burst.value in 0.001f..0.999f) {
                drawCircle(
                    PositiveGreen.a(0.7f * (1f - burst.value)), br * (1f + 2.6f * burst.value), c,
                    style = Stroke(b * 0.03f * (1f - burst.value) + 1f),
                )
            }

            if (fingerAlpha > 0.01f) drawFinger(c + Offset(b * 0.03f, b * 0.05f), b, s, h, fingerAlpha)
        }

        val title = when (phase) {
            BridgeLinkPhase.Waiting -> "Press the button on your bridge"
            BridgeLinkPhase.Linked -> "Bridge linked"
            BridgeLinkPhase.Failed -> if (failure == null) "The button was not pressed in time" else "Could not link the bridge"
        }
        val body = when (phase) {
            BridgeLinkPhase.Waiting ->
                "The big round button on top. No need to come back to the phone, it is " +
                    "watching for it. ${secondsLeft}s left."
            BridgeLinkPhase.Linked -> "All set. Next, pick the entertainment area to light up."
            BridgeLinkPhase.Failed -> failure ?: "Press the round button on top of the bridge, then try again."
        }
        Text(
            title,
            color = TextPrimary,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Text(
            body,
            color = TextMuted,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

/** Hover with a slight bob, drop, hold while the ripples go out, lift. */
private fun liftAt(t: Float): Float = when {
    t < 0.30f -> 1f - 0.06f * sin(t / 0.30f * Math.PI.toFloat())
    t < 0.46f -> 1f - FastOutSlowInEasing.transform((t - 0.30f) / 0.16f)
    t < 0.68f -> 0f
    t < 0.90f -> FastOutSlowInEasing.transform((t - 0.68f) / 0.22f)
    else -> 1f
}

private fun DrawScope.drawBridge(b: Float, br: Float, press: Float, stateColor: Color, tick: Float) {
    val c = center
    val tl = Offset(c.x - b / 2, c.y - b / 2)
    val corner = b * 0.24f

    // A soft shadow from stacked translucent outlines; Canvas has no cheap blur.
    for (i in 1..5) {
        val spread = b * 0.012f * i
        drawRoundRect(
            Color.Black.copy(alpha = 0.06f),
            topLeft = tl + Offset(-spread, b * 0.05f - spread),
            size = Size(b + spread * 2, b + spread * 2),
            cornerRadius = CornerRadius(corner + spread),
        )
    }
    drawRoundRect(
        Brush.verticalGradient(listOf(Color(0xFFFAFAF8), Color(0xFFDAD9D5)), tl.y, tl.y + b),
        tl, Size(b, b), CornerRadius(corner),
    )
    drawRoundRect(
        Color.Black.copy(alpha = 0.10f), tl, Size(b, b), CornerRadius(corner),
        style = Stroke(b * 0.008f),
    )

    // The three status lights along the top edge.
    val led = lerp(LedBlue, PositiveGreen, tick)
    for (i in -1..1) {
        val p = Offset(c.x + i * b * 0.11f, tl.y + b * 0.13f)
        drawCircle(led.copy(alpha = 0.30f), b * 0.032f, p)
        drawCircle(led, b * 0.015f, p)
    }

    // The link button, sinking a touch and lighting up while pressed.
    val r = br * (1f - 0.05f * press)
    drawCircle(Color.Black.copy(alpha = 0.10f), br * 1.07f, c)
    drawCircle(
        Brush.radialGradient(
            listOf(Color.White, Color(0xFFE2E1DD)),
            center = c + Offset(-br * 0.25f, -br * 0.3f), radius = br * 1.5f,
        ),
        r, c,
    )
    drawCircle(Color.Black.copy(alpha = 0.10f * press), r, c)
    drawCircle(Color.Black.copy(alpha = 0.06f), r * 0.55f, c, style = Stroke(b * 0.008f))
    drawCircle(stateColor.copy(alpha = 0.25f + 0.65f * max(press, tick)), r, c, style = Stroke(b * 0.024f))

    if (tick > 0f) {
        drawCircle(PositiveGreen.copy(alpha = tick), r * 0.98f, c)
        val path = Path().apply {
            moveTo(c.x - r * 0.42f, c.y + r * 0.02f)
            lineTo(c.x - r * 0.12f, c.y + r * 0.32f)
            lineTo(c.x + r * 0.45f, c.y - r * 0.30f)
        }
        val measure = PathMeasure().apply { setPath(path, false) }
        val drawn = Path()
        measure.getSegment(0f, measure.length * tick, drawn, true)
        drawPath(drawn, Color.White, style = Stroke(b * 0.05f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** A circular "go again" arrow on the button, clockwise, head at the top right. */
private fun DrawScope.drawRetryArrow(radius: Float, width: Float, colour: Color) {
    val c = center
    drawArc(
        colour, 20f, 280f, false,
        topLeft = Offset(c.x - radius, c.y - radius), size = Size(radius * 2, radius * 2),
        style = Stroke(width, cap = StrokeCap.Round),
    )
    val end = Math.toRadians(300.0)
    val n = Offset(kotlin.math.cos(end).toFloat(), sin(end).toFloat())
    val t = Offset(-n.y, n.x) // clockwise tangent
    val p = c + n * radius
    val head = width * 1.9f
    drawPath(
        Path().apply {
            moveTo((p + t * head).x, (p + t * head).y)
            lineTo((p + n * head).x, (p + n * head).y)
            lineTo((p - n * head).x, (p - n * head).y)
            close()
        },
        colour,
    )
}

/**
 * A fingertip reaching in from the lower right, its pad at [tip]. Lift [h] makes it
 * larger and throws its shadow further off, which is how height reads from above.
 */
private fun DrawScope.drawFinger(tip: Offset, b: Float, s: Float, h: Float, alpha: Float) {
    val w = b * 0.30f
    val len = s * 0.55f
    val scale = 1f + 0.10f * h

    fun shape(at: Offset, colour: Color, detail: Boolean) = withTransform({
        rotate(-35f, pivot = at)
        scale(scale, scale, pivot = at)
    }) {
        val top = at.y - w * 0.45f
        // Fades out along its length, so it reads as a fingertip and not a stick.
        drawRoundRect(
            Brush.verticalGradient(
                0f to colour, 0.5f to colour, 1f to colour.copy(alpha = 0f),
                startY = top, endY = top + len,
            ),
            topLeft = Offset(at.x - w / 2, top), size = Size(w, len), cornerRadius = CornerRadius(w / 2),
        )
        if (detail) {
            drawRoundRect(
                Color.White.copy(alpha = 0.45f * alpha),
                topLeft = Offset(at.x - w * 0.28f, top + w * 0.10f),
                size = Size(w * 0.56f, w * 0.62f), cornerRadius = CornerRadius(w * 0.28f),
            )
            drawLine(
                Color.Black.copy(alpha = 0.10f * alpha),
                Offset(at.x - w * 0.24f, top + w * 1.4f), Offset(at.x + w * 0.24f, top + w * 1.4f),
                strokeWidth = s * 0.006f, cap = StrokeCap.Round,
            )
        }
    }

    shape(
        tip + Offset(b * 0.015f + b * 0.10f * h, b * 0.02f + b * 0.12f * h),
        Color.Black.copy(alpha = (0.24f - 0.10f * h) * alpha),
        detail = false,
    )
    shape(tip, Skin.copy(alpha = alpha), detail = true)
}
