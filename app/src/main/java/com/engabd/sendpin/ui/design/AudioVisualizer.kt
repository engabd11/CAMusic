package com.engabd.sendpin.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.audio.AnalysisFrame
import com.engabd.sendpin.audio.AudioAnalysisTap
import com.engabd.sendpin.service.PlaybackOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.exp

/**
 * The latest [AnalysisFrame] from whichever player is actually making sound right now.
 *
 * [com.engabd.sendpin.audio.AudioAnalysisTap.frames] is per-tap, and each player
 * ([com.engabd.sendpin.audio.LocalPlayer], the Sendspin engine) owns its own tap.
 * This resolves "which tap" the same way [PlaybackOwner] already resolves "which
 * player" for transport and Light Sync, so a visualizer doesn't have to re-derive
 * that answer a third time — and it follows the *sound* owner, not the session
 * owner, since a paused player isn't producing frames to show.
 */
@Composable
fun rememberActiveAnalysisFrame(): State<AnalysisFrame?> {
    val tap = rememberActiveTap()
    val frameFlow = remember(tap) { tap?.frames ?: MutableStateFlow(null) }
    return frameFlow.collectAsStateWithLifecycle()
}

/** The tap owned by whichever player is actually making sound right now. */
@Composable
private fun rememberActiveTap(): AudioAnalysisTap? {
    val app = SendpinApp.instance
    val owner by app.playbackOwner.state.collectAsStateWithLifecycle()
    return remember(owner.soundOwner, owner.sendspinTap) {
        when (owner.soundOwner) {
            PlaybackOwner.Who.LOCAL -> app.localPlayer.audioAnalysisTap
            PlaybackOwner.Who.SENDSPIN -> owner.sendspinTap?.first
            PlaybackOwner.Who.NONE -> null
        }
    }
}

/**
 * A live frequency-bar visualizer, reading the 16-band melbank [AudioAnalysisTap][
 * com.engabd.sendpin.audio.AudioAnalysisTap] already produces at ~50 Hz for Light
 * Sync. No FFT, no allocation of new analysis here — just a render of data that
 * already exists.
 *
 * Fills whatever slot it's given (the Now Playing cover's, when the listener taps
 * it) rather than sitting as a small bar over the artwork — so it's drawn with no
 * background of its own, letting `MeltBackdrop` show through around and behind the
 * bars exactly as it does behind `LyricsPane`.
 *
 * Bars mirror out from the centre — band 0 innermost, the highest band outermost on
 * both sides — which reads as a deliberate "equalizer" shape at full height rather
 * than a scrolling strip. Levels ease toward each new frame instead of snapping to
 * it, since the raw melbank updates hard enough at ~50 Hz to read as flicker at this
 * size. A second, blurred pass of the same bars underneath gives them a soft glow
 * consistent with the glass surfaces elsewhere in the app (see `Backdrop.kt`).
 */
@Composable
fun AudioVisualizer(modifier: Modifier = Modifier, color: Color = LocalAccent.current) {
    val tap = rememberActiveTap()
    val frame by rememberActiveAnalysisFrame()

    // Analysis only ran while Light Sync was on, so on a phone with no bridge
    // configured this drew a flat line for ever — and this PR promotes it from an
    // opt-in strip to a tap on the cover, where that dead state is far more
    // visible. Ask for frames while it is on screen and give them back after.
    DisposableEffect(tap) {
        tap?.setUiActive(true)
        onDispose { tap?.setUiActive(false) }
    }

    val levels = remember { FloatArray(BAND_COUNT) }
    // The bars are plain data; this is what the draw phase actually depends on.
    // Reading it *inside* the draw lambda below — rather than easing `levels`
    // during composition, which is what this replaced — is the whole point: a
    // FloatArray is not snapshot state, so a draw that only read it had nothing
    // to invalidate on and painted its first frame for ever. `AmbientRain` in
    // this same package is the pattern.
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        var lastNanos = 0L
        while (true) {
            withFrameNanos { nanos ->
                val dt = if (lastNanos == 0L) 0f else (nanos - lastNanos) / 1_000_000_000f
                lastNanos = nanos
                val bands = frame?.melbank
                val a = if (dt <= 0f) 0f else 1f - exp(-dt / SMOOTHING_TAU_S)
                var moved = false
                for (i in levels.indices) {
                    val target = bands?.getOrNull(i)?.coerceIn(0f, 1f) ?: 0f
                    val next = levels[i] + (target - levels[i]) * a
                    if (kotlin.math.abs(next - levels[i]) > 1e-4f) moved = true
                    levels[i] = next
                }
                if (moved) tick++
            }
        }
    }

    Box(modifier) {
        Canvas(Modifier.fillMaxSize().blur(28.dp)) {
            tick
            drawMirroredBars(levels, color.copy(alpha = 0.9f))
        }
        Canvas(Modifier.fillMaxSize()) {
            tick
            drawMirroredBars(levels, color)
        }
    }
}

private fun DrawScope.drawMirroredBars(levels: FloatArray, color: Color) {
    if (size.width <= 0f || size.height <= 0f) return
    val barWidth = size.width / (levels.size * 2)
    val centre = size.width / 2f
    for (i in levels.indices) {
        val l = levels[i]
        val h = (size.height * l).coerceAtLeast(size.height * MIN_HEIGHT_FRACTION)
        val barColor = color.copy(alpha = (0.35f + 0.55f * l) * color.alpha)
        val rect = Size(barWidth * 0.82f, h)
        val corner = CornerRadius(barWidth * 0.3f)
        // Right half, band 0 innermost; left half is its mirror.
        drawRoundRect(
            color = barColor,
            topLeft = Offset(centre + i * barWidth, size.height - h),
            size = rect,
            cornerRadius = corner,
        )
        drawRoundRect(
            color = barColor,
            topLeft = Offset(centre - (i + 1) * barWidth, size.height - h),
            size = rect,
            cornerRadius = corner,
        )
    }
}

private const val BAND_COUNT = 16

/**
 * Time constant for the bars' easing.
 *
 * A time constant rather than a per-frame fraction, so the bars move at the same
 * speed whatever the display refresh rate is — the raw melbank updates hard
 * enough at ~50 Hz to read as flicker at this size.
 */
private const val SMOOTHING_TAU_S = 0.045f

/** A faint resting height so silence reads as "ready", not as missing bars. */
private const val MIN_HEIGHT_FRACTION = 0.015f
