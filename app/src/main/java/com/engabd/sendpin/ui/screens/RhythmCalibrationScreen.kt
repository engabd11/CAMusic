package com.engabd.sendpin.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.ui.design.LocalAccent
import com.engabd.sendpin.ui.design.TitleGap
import com.engabd.sendpin.ui.design.navBarInset
import com.engabd.sendpin.ui.theme.AppFont
import com.engabd.sendpin.ui.theme.Ink
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextPrimary
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest

/**
 * Tap-timing calibration for Rhythm Lights.
 *
 * The chart can be exact and the windows fair, and the game can still feel wrong:
 * touch latency, a Bluetooth speaker, a slow display — none of it is visible to
 * the analyser, and all of it shifts the player's taps relative to the beat. This
 * screen measures it: the music keeps playing, a circle pulses on the beat from
 * the *engine's own grid* (the same clock the chart and the lights ride), and the
 * player taps when they hear it. The median of twelve deltas is the offset.
 *
 * Median, not mean: one wildly-early or wildly-late tap — attention lapsing, a
 * notification — is an outlier, and a mean lets it drag the result. Twelve taps
 * because the median of fewer wobbles; more than twelve is patience, not accuracy.
 *
 * The player can tap anywhere on the board. Nothing here punishes: there is no
 * score, and taps landing nowhere near a beat are discarded as warm-up — the
 * screen's job is to measure, not to test.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Composable
fun RhythmCalibrationScreen(
    onBack: () -> Unit,
    viewModel: com.engabd.sendpin.ui.viewmodel.RhythmCalibrationViewModel = viewModel(),
) {
    val app = LocalContext.current.applicationContext as SendpinApp
    val accent = LocalAccent.current

    DisposableEffect(viewModel) {
        viewModel.start()
        onDispose { viewModel.stop() }
    }

    // The same frame feed the game screen uses: the engine's grid is what both
    // the chart and this pulse ride, so calibrating against anything else would
    // measure a different clock than the one the game judges against.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(app, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            app.activeLightSyncSource
                .flatMapLatest { it.tap.frames }
                .filterNotNull()
                .collect { frame ->
                    val source = app.activeLightSyncSource.value
                    viewModel.onFrame(
                        frame,
                        source.lead.leadMs?.toLong() ?: 0L,
                        leadKnown = source.lead.leadMs != null,
                    )
                }
        }
    }

    val state by viewModel.state.collectAsStateWithLifecycle()

    // The pulse: full at the beat, falling to rest between beats. Driven by the
    // beat phase the view model publishes at the analysis rate — a withFrameMillis
    // loop would smooth it further, but 50 updates a second is already smoother
    // than the eye tracks a 96 dp disc.
    val pulse = 1f + 0.35f * (1f - state.beatPhase)

    Box(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 18.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                }
                Spacer(Modifier.width(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(TitleGap)) {
                    Text(
                        "Timing calibration",
                        color = TextPrimary, fontFamily = AppFont,
                        fontWeight = FontWeight.ExtraBold, fontSize = 19.sp,
                    )
                    Text(
                        when {
                            !state.locked -> "Play something with a clear beat…"
                            state.done -> "Applied ${state.appliedOffsetMs} ms — timing centred"
                            else -> "Tap when the circle pulses · ${state.taps} of 12"
                        },
                        color = TextMuted, fontFamily = AppFont,
                        fontWeight = FontWeight.SemiBold, fontSize = 11.sp,
                    )
                }
            }

            // The tap surface. The whole board is the target: calibration is about
            // WHEN, not where, and a thumb-sized circle would measure aim.
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(viewModel) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                for (change in event.changes) {
                                    if (change.pressed && !change.previousPressed) {
                                        viewModel.tap(viewModel.nowMs())
                                        change.consume()
                                    }
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(96.dp)
                        .graphicsLayer {
                            scaleX = pulse
                            scaleY = pulse
                            alpha = 0.35f + 0.65f * (1f - state.beatPhase)
                        }
                        .background(accent, shape = CircleShape),
                )
                if (state.done) {
                    Text(
                        "Done — timing centred",
                        color = TextPrimary, fontFamily = AppFont,
                        fontWeight = FontWeight.Bold, fontSize = 15.sp,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp),
                    )
                }
            }

            Spacer(Modifier.height(navBarInset()))
        }
    }
}
