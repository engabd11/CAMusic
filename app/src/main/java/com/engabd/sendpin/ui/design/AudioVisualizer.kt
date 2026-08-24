package com.engabd.sendpin.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.audio.AnalysisFrame
import com.engabd.sendpin.service.PlaybackOwner
import kotlinx.coroutines.flow.MutableStateFlow

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
    val app = SendpinApp.instance
    val owner by app.playbackOwner.state.collectAsStateWithLifecycle()
    val tap = remember(owner.soundOwner, owner.sendspinTap) {
        when (owner.soundOwner) {
            PlaybackOwner.Who.LOCAL -> app.localPlayer.audioAnalysisTap
            PlaybackOwner.Who.SENDSPIN -> owner.sendspinTap?.first
            PlaybackOwner.Who.NONE -> null
        }
    }
    val frameFlow = remember(tap) { tap?.frames ?: MutableStateFlow(null) }
    return frameFlow.collectAsStateWithLifecycle()
}

/**
 * A live frequency-bar visualizer, reading the 16-band melbank [AudioAnalysisTap][
 * com.engabd.sendpin.audio.AudioAnalysisTap] already produces at ~50 Hz for Light
 * Sync. No FFT, no allocation here — just a render of data that already exists.
 */
@Composable
fun AudioVisualizer(modifier: Modifier = Modifier, color: Color = LocalAccent.current) {
    val frame by rememberActiveAnalysisFrame()
    Canvas(modifier) {
        val bands = frame?.melbank ?: return@Canvas
        if (bands.isEmpty()) return@Canvas
        val barWidth = size.width / bands.size
        bands.forEachIndexed { i, level ->
            val l = level.coerceIn(0f, 1f)
            val h = size.height * l
            drawRoundRect(
                color = color.copy(alpha = 0.3f + 0.5f * l),
                topLeft = Offset(i * barWidth + barWidth * 0.1f, size.height - h),
                size = Size(barWidth * 0.8f, h),
                cornerRadius = CornerRadius(barWidth * 0.2f),
            )
        }
    }
}
