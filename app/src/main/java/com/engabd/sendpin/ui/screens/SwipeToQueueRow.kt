package com.engabd.sendpin.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.engabd.sendpin.ui.theme.PositiveGreen
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Wraps a track row with a one-motion swipe: right reveals "add to queue"
 * (green), left reveals "play next" (the caller's accent). Both actions
 * already exist as taps inside `MediaActionsSheet` — this is a faster path to
 * the same two, not a replacement for the sheet's fuller action list.
 *
 * A plain [detectHorizontalDragGestures] rather than `SwipeToDismissBox`: the
 * row is not meant to leave the list once swiped, it snaps back after firing
 * the action, so the dismiss-and-settle contract of that component doesn't
 * fit. Compose's own touch-slop discrimination inside
 * [detectHorizontalDragGestures] is what keeps this from stealing a
 * `LazyColumn`'s vertical scroll — a mostly-vertical drag never resolves to
 * a horizontal one in the first place.
 */
@Composable
fun SwipeToQueueRow(
    accent: Color,
    onAddToQueue: () -> Unit,
    onPlayNext: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val offsetX = remember { Animatable(0f) }
    var widthPx by remember { mutableIntStateOf(0) }
    val triggerPx = with(density) { TRIGGER_DP.dp.toPx() }

    Box(modifier.fillMaxWidth().onSizeChanged { widthPx = it.width }) {
        SwipeActionHint(
            visible = offsetX.value > 1f,
            weight = (offsetX.value / triggerPx).coerceIn(0f, 1f),
            color = PositiveGreen,
            icon = Icons.AutoMirrored.Filled.QueueMusic,
            label = "Add to queue",
            alignment = Alignment.CenterStart,
        )
        SwipeActionHint(
            visible = offsetX.value < -1f,
            weight = (-offsetX.value / triggerPx).coerceIn(0f, 1f),
            color = accent,
            icon = Icons.Filled.SkipNext,
            label = "Play next",
            alignment = Alignment.CenterEnd,
        )

        Box(
            Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val value = offsetX.value
                            scope.launch {
                                when {
                                    value >= triggerPx -> {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onAddToQueue()
                                    }
                                    value <= -triggerPx -> {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onPlayNext()
                                    }
                                }
                                offsetX.animateTo(0f, tween(220))
                            }
                        },
                        onDragCancel = { scope.launch { offsetX.animateTo(0f, tween(220)) } },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val max = widthPx * MAX_DRAG_FRACTION
                            scope.launch {
                                offsetX.snapTo((offsetX.value + dragAmount).coerceIn(-max, max))
                            }
                        },
                    )
                },
        ) { content() }
    }
}

@Composable
private fun BoxScope.SwipeActionHint(
    visible: Boolean,
    weight: Float,
    color: Color,
    icon: ImageVector,
    label: String,
    alignment: Alignment,
) {
    if (!visible) return
    Box(
        Modifier
            .matchParentSize()
            .background(color.copy(alpha = 0.15f + 0.25f * weight)),
        contentAlignment = alignment,
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, label, tint = color.copy(alpha = 0.6f + 0.4f * weight))
            if (weight >= 1f) {
                Text(label, color = color, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

private const val TRIGGER_DP = 88
private const val MAX_DRAG_FRACTION = 0.42f
