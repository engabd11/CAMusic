package com.engabd.sendpin.ui.screens

import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.game.GameNote
import com.engabd.sendpin.game.Judgement
import com.engabd.sendpin.ui.design.LocalAccent
import com.engabd.sendpin.ui.design.Motion
import com.engabd.sendpin.ui.design.TitleGap
import com.engabd.sendpin.ui.design.navBarInset
import com.engabd.sendpin.ui.theme.AppFont
import com.engabd.sendpin.ui.theme.Ink
import com.engabd.sendpin.ui.theme.TextFaint
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextPrimary
import com.engabd.sendpin.ui.viewmodel.RhythmGameViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * Rhythm Lights: a four-lane note highway that darkens the room and hands the
 * lights to the player.
 *
 * ## Why this is drawn rather than laid out
 *
 * The board was a `Box` per note with an `offset` — which meant the whole screen
 * recomposed and relaid-out on every frame, for a scene that is a few dozen shapes
 * on a fixed background. Everything below the HUD is one [Canvas] now, and the
 * clock it reads is a plain `mutableLongStateOf` touched only inside draw lambdas:
 * a frame invalidates the *draw* phase and nothing above it. That is what leaves
 * room in the frame budget for the perspective, the glows and the bursts.
 *
 * ## The perspective
 *
 * Lanes converge on a vanishing point above the top of the board, and a note's
 * distance is mapped through `1 / (1 + k·z)` rather than linearly, so notes crawl
 * in at the horizon and accelerate into the hit line. That non-linearity *is* the
 * genre's look, and it is also the honest one: it gives the player most of their
 * reading time while the note is far away and the fewest pixels of ambiguity where
 * the timing actually matters.
 *
 * ## What the lights do here
 *
 * Nothing, unless the player earns it — see
 * [com.engabd.sendpin.hue.DirectLightSync.setGameMode]. The show goes on running
 * underneath for as long as this screen is open, held down to a dim floor; a struck
 * note opens the gate and the room does exactly what that moment of the music would
 * always have made it do. So the reward for playing well is the real light show,
 * arriving in the pieces the player earns.
 *
 * This screen's own effects are free to be as busy as they like; the promise is
 * about the room, not the glass.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Composable
fun RhythmGameScreen(
    onBack: () -> Unit,
    onOpenCalibration: () -> Unit = {},
    viewModel: RhythmGameViewModel = viewModel(),
) {
    val context = LocalContext.current
    val view = LocalView.current
    val accent = LocalAccent.current
    val app = context.applicationContext as SendpinApp

    // Enter game mode for exactly as long as this screen is on screen. The room
    // coming back up is not optional, so it is tied to the composable's lifetime
    // rather than to the back button — a process-level navigation that never runs
    // `onBack` still runs this.
    DisposableEffect(viewModel) {
        viewModel.start()
        onDispose { viewModel.stop() }
    }

    // Finish (persist the run, publish the results) on the back press, before
    // the composable leaves — the onDispose path is the emergency exit that
    // must not lose the score to a process-level navigation.
    val back = {
        viewModel.finish()
        onBack()
    }

    // Timing settings feed the judged clock: offset, difficulty scale, haptics.
    // Collected for the screen's lifetime so a mid-session change applies from
    // the next tap rather than the next launch.
    LaunchedEffect(viewModel) {
        val app2 = context.applicationContext as SendpinApp
        val s = com.engabd.sendpin.data.AppSettings(app2)
        combine(
            s.gameTimingOffsetMs,
            s.gameDifficulty,
            s.gameHaptics,
        ) { offset, difficulty, haptics ->
            Triple(offset, com.engabd.sendpin.game.DIFFICULTY_SCALE[difficulty] ?: 1f, haptics)
        }.collect { (offset, scale, haptics) ->
            viewModel.applyTiming(offset, scale, haptics)
        }
    }

    DisposableEffect(Unit) {
        val window = (view.context as? android.app.Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // Follow the *source*, not whichever tap happened to be live at composition:
    // the feed changes under the game when a different backend takes over.
    //
    // Stopped with the lifecycle, not merely with the composition. The board is
    // advanced from the `withFrameMillis` loop below, which stops the moment the
    // window stops rendering — so a plain collect here would go on charting notes
    // into a game nothing was retiring them from, and the list would grow for as
    // long as the app sat in the background with this screen on the stack.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(app, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            app.activeLightSyncSource
                .flatMapLatest { it.tap.frames }
                .filterNotNull()
                .collect { frame ->
                    // How long until this frame is audible. The chart is written in
                    // audible time — see NoteGenerator — so this is the one number
                    // that decides whether the hit line lands on the beat or behind
                    // it.
                    val source = app.activeLightSyncSource.value
                    val lead = source.lead
                    viewModel.onFrame(
                        frame,
                        lead.leadMs?.toLong() ?: 0L,
                        leadKnown = lead.leadMs != null,
                    )
                }
        }
    }

    // Deliberately the State, not its value: read inside the draw lambda below so a
    // note appearing invalidates the draw phase rather than recomposing the screen.
    val notesState = viewModel.notes.collectAsStateWithLifecycle()
    val hud by viewModel.hud.collectAsStateWithLifecycle()
    val judgement by viewModel.judgement.collectAsStateWithLifecycle()
    val result by viewModel.result.collectAsStateWithLifecycle()

    // The frame clock. Written every frame and read only inside draw lambdas and the
    // tap handler, so nothing here recomposes at 60 Hz.
    var nowMs by remember { mutableLongStateOf(viewModel.nowMs()) }
    LaunchedEffect(viewModel) {
        while (true) {
            withFrameMillis {
                val t = viewModel.nowMs()
                nowMs = t
                viewModel.tick(t)
            }
        }
    }

    // Screen-side effects. Plain lists, not snapshot state: they are mutated and read
    // on the main thread only, and the canvas is already being invalidated every
    // frame by the clock above — making these observable would buy a recomposition
    // per hit and change nothing on screen.
    val bursts = remember { ArrayList<Burst>() }
    val lanePress = remember { LongArray(LANES) }
    // When the last multiplier step happened, on the game's clock. Read inside
    // draw lambdas only; writing it recomposes nothing.
    val milestoneAtState = remember { mutableLongStateOf(0L) }

    LaunchedEffect(judgement?.id) {
        val event = judgement ?: return@LaunchedEffect
        if (bursts.size > MAX_BURSTS) bursts.subList(0, bursts.size - MAX_BURSTS).clear()
        bursts.add(Burst(event.lane, event.atMs, event.judgement))
    }

    // Stamp the milestone when the multiplier steps up - the ripple is the
    // screen's own celebration of the run, distinct from the per-hit burst.
    LaunchedEffect(hud.multiplier) {
        if (hud.multiplier > 1) milestoneAtState.value = viewModel.nowMs()
    }

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
                CircleBtn(Icons.AutoMirrored.Filled.ArrowBack, "Back", back)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
                    Text(
                        "Rhythm Lights",
                        color = TextPrimary, fontFamily = AppFont,
                        fontWeight = FontWeight.ExtraBold, fontSize = 19.sp,
                    )
                    Text(
                        // Says what the room is doing, because the room going dark on
                        // entry is otherwise indistinguishable from Light Sync having
                        // dropped out.
                        when {
                            !hud.locked -> "Finding the beat…"
                            hud.bpm > 0f -> "Room dimmed · hit the notes to light it · ${hud.bpm.toInt()} BPM"
                            else -> "Room dimmed · hit the notes to light it"
                        },
                        color = TextMuted, fontFamily = AppFont,
                        fontWeight = FontWeight.SemiBold, fontSize = 11.sp,
                    )
                }
                // Calibration lives one tap away from the game, not buried in
                // settings: the moment the game feels off is the moment to fix it.
                androidx.compose.material3.TextButton(onClick = onOpenCalibration) {
                    Text(
                        "Timing",
                        color = accent, fontFamily = AppFont,
                        fontWeight = FontWeight.Bold, fontSize = 13.sp,
                    )
                }
            }

            HudRow(
                score = hud.score,
                combo = hud.combo,
                multiplier = hud.multiplier,
                accuracy = hud.accuracy,
                accent = accent,
            )

            Box(Modifier.weight(1f).fillMaxWidth()) {
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(viewModel) {
                            // Not `detectTapGestures`: that resolves one pointer at a
                            // time and fires on release. A rhythm game needs the press
                            // itself — the release is tens of milliseconds of pure
                            // penalty — and needs two lanes struck at once to both
                            // count. Initial pass, so the press is seen before any
                            // ancestor can claim it.
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    for (change in event.changes) {
                                        if (!change.pressed || change.isConsumed) continue
                                        if (change.previousPressed) continue
                                        // One clock read for both, so the pad lights
                                        // at exactly the instant the tap is judged.
                                        val at = viewModel.nowMs()
                                        val lane = laneAt(change.position.x, size.width.toFloat())
                                        lanePress[lane] = at
                                        viewModel.tap(lane, at)
                                        change.consume()
                                    }
                                }
                            }
                        },
                ) {
                    drawHighway(
                        now = nowMs,
                        notes = notesState.value,
                        bursts = bursts,
                        lanePress = lanePress,
                        lookAheadMs = viewModel.lookAheadMs,
                        accent = accent,
                        combo = hud.combo,
                        multiplier = hud.multiplier,
                        milestoneAt = milestoneAtState.value,
                    )
                }

                JudgementBanner(
                    eventId = judgement?.id,
                    judgement = judgement?.judgement,
                    combo = judgement?.comboAfter ?: 0,
                    accent = accent,
                    modifier = Modifier.align(Alignment.Center),
                )

                // Results sheet: published when the player backs out with a score
                // on the board. Dismiss = leave the game (the run is already saved).
                result?.let { r ->
                    ResultsSheet(
                        result = r,
                        accent = accent,
                        onDismiss = back,
                    )
                }

                if (!hud.locked && hud.score == 0) {
                    Text(
                        "Play something, then tap the pads as the notes reach the line.",
                        color = TextFaint, fontFamily = AppFont,
                        fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 40.dp),
                    )
                }

                // The backend could not measure an output latency (capture/cast
                // feeds), so the chart runs in analysis time and the whole game
                // is shifted by the real latency. Telling the player beats them
                // guessing whether the game or their speakers are wrong.
                val leadKnown by viewModel.leadKnown.collectAsStateWithLifecycle()
                if (!leadKnown && hud.locked) {
                    Text(
                        "Output latency unknown on this source — timing may feel off",
                        color = TextFaint, fontFamily = AppFont,
                        fontWeight = FontWeight.SemiBold, fontSize = 11.sp,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp),
                    )
                }
            }

            Spacer(Modifier.height(navBarInset()))
        }
    }
}

// ── HUD ─────────────────────────────────────────────────────────────────────

@Composable
private fun HudRow(
    score: Int,
    combo: Int,
    multiplier: Int,
    accuracy: Float?,
    accent: Color,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("SCORE", color = TextFaint, fontFamily = AppFont, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(
                score.toString(),
                color = TextPrimary, fontFamily = AppFont,
                fontWeight = FontWeight.ExtraBold, fontSize = 26.sp,
            )
        }

        // The combo is the number the player is actually watching, so it is the one
        // that grows: the multiplier stepping up is the reward, and a badge that
        // changes size is how that reads without a word of explanation.
        val comboScale = if (combo >= 10) 1f + min(0.35f, (multiplier - 1) * 0.12f) else 1f
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("COMBO", color = TextFaint, fontFamily = AppFont, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    combo.toString(),
                    color = if (combo > 0) accent else TextMuted,
                    fontFamily = AppFont,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 26.sp,
                    modifier = Modifier.graphicsLayer(scaleX = comboScale, scaleY = comboScale),
                )
                if (multiplier > 1) {
                    Text(
                        "  ${multiplier}x",
                        color = accent, fontFamily = AppFont,
                        fontWeight = FontWeight.ExtraBold, fontSize = 15.sp,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
            }
        }

        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text("ACCURACY", color = TextFaint, fontFamily = AppFont, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(
                accuracy?.let { "${(it * 100).toInt()}%" } ?: "—",
                color = TextPrimary, fontFamily = AppFont,
                fontWeight = FontWeight.ExtraBold, fontSize = 26.sp,
            )
        }
    }
}

/**
 * The word for the last thing that happened, thrown up over the highway.
 *
 * Keyed on the event id rather than the judgement: two Perfects in a row are two
 * animations, and keying on the value would play one.
 */
@Composable
private fun JudgementBanner(
    eventId: Long?,
    judgement: Judgement?,
    combo: Int,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(eventId) {
        if (eventId == null) return@LaunchedEffect
        anim.snapTo(1f)
        // A critically-damped spring, not a tween: Motion.effects() is the house
        // spec for appearance (alpha/colour) changes, and this banner is pure
        // appearance - punch out and settle, no overshoot past full opacity.
        anim.animateTo(0f, Motion.effects())
    }
    val t = anim.value
    if (t <= 0.01f || judgement == null) return

    val colour = when (judgement) {
        Judgement.PERFECT -> accent
        Judgement.GREAT -> Color(0xFF6FD3FF)
        Judgement.GOOD -> Color(0xFFBFC7D4)
        Judgement.MISS -> Color(0xFFE05656)
    }
    Column(
        modifier.graphicsLayer(
            alpha = t,
            // Punches out and settles rather than simply fading: the fade alone was
            // invisible against a board that is itself full of moving light.
            scaleX = 0.85f + t * 0.35f,
            scaleY = 0.85f + t * 0.35f,
            translationY = -(1f - t) * 40f,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            judgement.label.uppercase(),
            color = colour, fontFamily = AppFont,
            fontWeight = FontWeight.ExtraBold, fontSize = 30.sp,
        )
        if (judgement != Judgement.MISS && combo >= COMBO_CALLOUT) {
            Text(
                "$combo COMBO",
                color = colour.copy(alpha = 0.75f), fontFamily = AppFont,
                fontWeight = FontWeight.Bold, fontSize = 13.sp,
            )
        }
    }
}

// ── The board ───────────────────────────────────────────────────────────────

/** One hit or miss, still animating on the glass. */
private class Burst(val lane: Int, val atMs: Long, val judgement: Judgement)

/**
 * Draw the whole scene for one frame.
 *
 * Ordered back to front — horizon, rails, beat lines, notes, frets, bursts — because
 * several stages draw with [BlendMode.Plus] and additive light only reads correctly
 * over what is already there.
 */
private fun DrawScope.drawHighway(
    now: Long,
    notes: List<GameNote>,
    bursts: List<Burst>,
    lanePress: LongArray,
    lookAheadMs: Long,
    accent: Color,
    combo: Int,
    multiplier: Int,
    milestoneAt: Long,
) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return

    val hitY = h * HIT_LINE
    val vpY = -h * VANISHING_ABOVE
    val centreX = w / 2f

    fun perspAt(progress: Float): Float {
        val z = (1f - progress).coerceIn(-0.2f, 1.4f)
        return 1f / (1f + PERSPECTIVE * z)
    }

    fun yAt(progress: Float) = vpY + (hitY - vpY) * perspAt(progress)

    fun laneCentreAtLine(lane: Int) = centreX + (lane - (LANES - 1) / 2f) * (w * HALF_SPAN * 2f / LANES)

    fun xAt(lane: Int, progress: Float): Float {
        val p = perspAt(progress)
        return centreX + (laneCentreAtLine(lane) - centreX) * p
    }

    // How much light the frets are throwing right now, from the most recent press in
    // each lane. Also drives the rail glow, so the whole board answers a tap.
    fun pressGlow(lane: Int): Float {
        val age = (now - lanePress[lane]).toFloat()
        if (age < 0f || age > PRESS_GLOW_MS) return 0f
        return 1f - age / PRESS_GLOW_MS
    }

    // ── Horizon and floor ───────────────────────────────────────────────────

    drawRect(
        brush = Brush.verticalGradient(
            0f to Color(0xFF0B1020),
            0.55f to Color(0xFF05060C),
            1f to Color(0xFF000000),
        ),
    )

    // A wash of the accent sitting on the horizon, brightening with the multiplier —
    // the board itself gets hotter as the run gets longer.
    val heat = ((multiplier - 1) / 3f).coerceIn(0f, 1f)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(accent.copy(alpha = 0.20f + heat * 0.22f), Color.Transparent),
            center = Offset(centreX, yAt(0f)),
            radius = w * 0.8f,
        ),
        radius = w * 0.8f,
        center = Offset(centreX, yAt(0f)),
        blendMode = BlendMode.Plus,
    )

    // ── The highway itself ──────────────────────────────────────────────────

    val farP = 0f
    val nearP = 1.12f // a little past the line, so the deck runs under the frets
    val leftFar = xAt(0, farP) - laneHalfWidth(w) * perspAt(farP)
    val rightFar = xAt(LANES - 1, farP) + laneHalfWidth(w) * perspAt(farP)
    val leftNear = xAt(0, nearP) - laneHalfWidth(w) * perspAt(nearP)
    val rightNear = xAt(LANES - 1, nearP) + laneHalfWidth(w) * perspAt(nearP)

    val deck = Path().apply {
        moveTo(leftFar, yAt(farP))
        lineTo(rightFar, yAt(farP))
        lineTo(rightNear, yAt(nearP))
        lineTo(leftNear, yAt(nearP))
        close()
    }
    drawPath(
        deck,
        brush = Brush.verticalGradient(
            colors = listOf(Color(0x0AFFFFFF), Color(0x14FFFFFF), Color(0x22FFFFFF)),
            startY = yAt(farP),
            endY = yAt(nearP),
        ),
    )

    // Lane rails, converging. Each carries its lane's colour, lit by recent presses.
    for (i in 0..LANES) {
        val lane = i.coerceAtMost(LANES - 1)
        val edge = if (i == LANES) 1f else -1f
        val xFar = xAt(lane, farP) + edge * laneHalfWidth(w) * perspAt(farP)
        val xNear = xAt(lane, nearP) + edge * laneHalfWidth(w) * perspAt(nearP)
        val glow = if (i == LANES) pressGlow(LANES - 1) else pressGlow(lane)
        val railColour = if (i == LANES) laneColour(LANES - 1) else laneColour(lane)
        drawLine(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, railColour.copy(alpha = 0.35f + glow * 0.5f)),
                startY = yAt(farP),
                endY = yAt(nearP),
            ),
            start = Offset(xFar, yAt(farP)),
            end = Offset(xNear, yAt(nearP)),
            strokeWidth = 1.5f + glow * 2.5f,
            blendMode = BlendMode.Plus,
        )
    }

    // Lane floors, so a struck lane lights along its whole length.
    for (lane in 0 until LANES) {
        val glow = pressGlow(lane)
        if (glow <= 0f) continue
        val column = Path().apply {
            moveTo(xAt(lane, farP) - laneHalfWidth(w) * perspAt(farP), yAt(farP))
            lineTo(xAt(lane, farP) + laneHalfWidth(w) * perspAt(farP), yAt(farP))
            lineTo(xAt(lane, nearP) + laneHalfWidth(w) * perspAt(nearP), yAt(nearP))
            lineTo(xAt(lane, nearP) - laneHalfWidth(w) * perspAt(nearP), yAt(nearP))
            close()
        }
        drawPath(
            column,
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, laneColour(lane).copy(alpha = 0.30f * glow)),
                startY = yAt(0.3f),
                endY = yAt(nearP),
            ),
            blendMode = BlendMode.Plus,
        )
    }

    // ── Bar lines ───────────────────────────────────────────────────────────
    //
    // Drawn from the downbeats already in the chart rather than from a second copy
    // of the beat clock: one source of truth for where the bar is, and the lines
    // cannot drift away from the notes they are supposed to be measuring.
    for (note in notes) {
        if (!note.downbeat) continue
        val p = progressOf(note, now, lookAheadMs)
        if (p < -0.05f || p > nearP) continue
        val y = yAt(p)
        val persp = perspAt(p)
        drawLine(
            color = Color.White.copy(alpha = 0.10f * persp),
            start = Offset(xAt(0, p) - laneHalfWidth(w) * persp, y),
            end = Offset(xAt(LANES - 1, p) + laneHalfWidth(w) * persp, y),
            strokeWidth = 1f + persp,
        )
    }

    // ── Notes ───────────────────────────────────────────────────────────────
    //
    // Far ones first, so a near note overlaps the one behind it rather than the
    // other way round.
    for (note in notes.sortedByDescending { it.triggerTimeMs }) {
        val p = progressOf(note, now, lookAheadMs)
        if (p < -0.05f || p > nearP) continue
        drawNote(note, p, ::xAt, ::yAt, ::perspAt, w)
    }

    // ── Frets ───────────────────────────────────────────────────────────────

    val fretR = laneHalfWidth(w) * FRET_RADIUS
    for (lane in 0 until LANES) {
        val cx = xAt(lane, 1f)
        val glow = pressGlow(lane)
        val colour = laneColour(lane)

        // The pad's own halo, always faintly on so the target is findable, and
        // hot for a moment after it is struck.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(colour.copy(alpha = 0.35f + glow * 0.55f), Color.Transparent),
                center = Offset(cx, hitY),
                radius = fretR * (2.2f + glow),
            ),
            radius = fretR * (2.2f + glow),
            center = Offset(cx, hitY),
            blendMode = BlendMode.Plus,
        )
        drawCircle(
            color = Color.Black.copy(alpha = 0.55f),
            radius = fretR,
            center = Offset(cx, hitY),
        )
        drawCircle(
            color = colour.copy(alpha = 0.9f),
            radius = fretR * (1f + glow * 0.12f),
            center = Offset(cx, hitY),
            style = Stroke(width = 3f + glow * 4f),
        )
        drawCircle(
            color = colour.copy(alpha = 0.15f + glow * 0.5f),
            radius = fretR * (1f + glow * 0.12f),
            center = Offset(cx, hitY),
        )
    }

    // The hit line itself, brightening with the combo so a long run is visible in
    // peripheral vision while the player is watching the notes.
    val lineHeat = (combo / 40f).coerceIn(0f, 1f)
    drawLine(
        brush = Brush.horizontalGradient(
            colors = listOf(
                Color.Transparent,
                accent.copy(alpha = 0.5f + lineHeat * 0.4f),
                Color.Transparent,
            ),
        ),
        start = Offset(xAt(0, 1f) - fretR * 2f, hitY),
        end = Offset(xAt(LANES - 1, 1f) + fretR * 2f, hitY),
        strokeWidth = 2f + lineHeat * 3f,
        blendMode = BlendMode.Plus,
    )

    // At a multiplier step (10/20/30 combo) an accent ripple washes out from the
    // hit line's centre. Stamped when the multiplier steps and drawn here entirely
    // from draw-phase state: no recomposition for an effect the canvas already
    // redraws every frame.
    val milestoneAge = now - milestoneAt
    if (milestoneAge in 0..MILESTONE_MS) {
        val mt = milestoneAge.toFloat() / MILESTONE_MS
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    accent.copy(alpha = 0.35f * (1f - mt)),
                    Color.Transparent,
                ),
                center = Offset(centreX, hitY),
                radius = w * (0.25f + mt * 0.65f),
            ),
            radius = w * (0.25f + mt * 0.65f),
            center = Offset(centreX, hitY),
            blendMode = BlendMode.Plus,
        )
    }

    // ── Bursts ──────────────────────────────────────────────────────────────

    for (burst in bursts) {
        val age = (now - burst.atMs).toFloat()
        if (age < 0f || age > BURST_MS) continue
        val t = age / BURST_MS
        val cx = xAt(burst.lane, 1f)
        val colour = when (burst.judgement) {
            Judgement.MISS -> Color(0xFFE05656)
            Judgement.PERFECT -> accent
            else -> laneColour(burst.lane)
        }

        if (burst.judgement == Judgement.MISS) {
            // A miss gets a short inward shudder rather than an expanding ring: it
            // has to read as *not* the thing a hit does, at a glance, mid-song.
            val shake = sin(t * 28f) * (1f - t) * fretR * 0.35f
            drawCircle(
                color = colour.copy(alpha = 0.5f * (1f - t)),
                radius = fretR * 1.1f,
                center = Offset(cx + shake, hitY),
                style = Stroke(width = 4f),
            )
            continue
        }

        // Expanding ring.
        drawCircle(
            color = colour.copy(alpha = (1f - t) * 0.7f),
            radius = fretR * (1f + t * 2.6f),
            center = Offset(cx, hitY),
            style = Stroke(width = (1f - t) * 6f + 1f),
            blendMode = BlendMode.Plus,
        )
        // Core flash.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = (1f - t) * 0.8f), Color.Transparent),
                center = Offset(cx, hitY),
                radius = fretR * 1.8f,
            ),
            radius = fretR * 1.8f,
            center = Offset(cx, hitY),
            blendMode = BlendMode.Plus,
        )
        // Sparks, thrown back up the lane the note came down.
        val sparks = if (burst.judgement == Judgement.PERFECT) 7 else 4
        for (i in 0 until sparks) {
            val spread = (i - (sparks - 1) / 2f) / sparks
            val sx = cx + spread * fretR * 3f * t
            val sy = hitY - t * h * 0.16f * (0.6f + abs(spread))
            drawCircle(
                color = colour.copy(alpha = (1f - t) * (1f - t) * 0.9f),
                radius = (1f - t) * fretR * 0.18f + 1f,
                center = Offset(sx, sy),
                blendMode = BlendMode.Plus,
            )
        }
    }
}

/** One gem, in perspective. */
private fun DrawScope.drawNote(
    note: GameNote,
    progress: Float,
    xAt: (Int, Float) -> Float,
    yAt: (Float) -> Float,
    perspAt: (Float) -> Float,
    boardWidth: Float,
) {
    val persp = perspAt(progress)
    val cx = xAt(note.lane, progress)
    val cy = yAt(progress)
    val halfW = laneHalfWidth(boardWidth) * persp * NOTE_WIDTH
    val halfH = halfW * NOTE_ASPECT
    val colour = laneColour(note.lane)

    // Fade in at the horizon rather than popping into existence.
    val alpha = ((progress + 0.08f) * 6f).coerceIn(0f, 1f)

    // Glow first, under the body.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(colour.copy(alpha = 0.45f * alpha * (0.4f + note.intensity * 0.6f)), Color.Transparent),
            center = Offset(cx, cy),
            radius = halfW * 2.6f,
        ),
        radius = halfW * 2.6f,
        center = Offset(cx, cy),
        blendMode = BlendMode.Plus,
    )

    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                lighten(colour, 0.45f).copy(alpha = alpha),
                colour.copy(alpha = alpha),
                darken(colour, 0.35f).copy(alpha = alpha),
            ),
            startY = cy - halfH,
            endY = cy + halfH,
        ),
        topLeft = Offset(cx - halfW, cy - halfH),
        size = Size(halfW * 2f, halfH * 2f),
        cornerRadius = CornerRadius(halfH * 0.7f, halfH * 0.7f),
    )

    // A specular strip along the top edge. Cheap, and it is most of what makes a
    // flat rounded rectangle read as an object with a surface.
    drawRoundRect(
        color = Color.White.copy(alpha = 0.5f * alpha),
        topLeft = Offset(cx - halfW * 0.72f, cy - halfH * 0.62f),
        size = Size(halfW * 1.44f, halfH * 0.42f),
        cornerRadius = CornerRadius(halfH * 0.3f, halfH * 0.3f),
    )

    drawRoundRect(
        color = Color.White.copy(alpha = 0.75f * alpha),
        topLeft = Offset(cx - halfW, cy - halfH),
        size = Size(halfW * 2f, halfH * 2f),
        cornerRadius = CornerRadius(halfH * 0.7f, halfH * 0.7f),
        style = Stroke(width = 1f + persp * 2f),
    )
}

// ── Results ─────────────────────────────────────────────────────────────────

/**
 * The run's results, shown when the player leaves the game with a score.
 *
 * A modal sheet rather than an overlay on the board: the run is over, the room
 * has come back up, and this is the moment the score is actually looked at —
 * plus the record it just set, if it did.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ResultsSheet(
    result: RhythmGameViewModel.RunResult,
    accent: Color,
    onDismiss: () -> Unit,
) {
    val hud = result.hud
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (result.newRecord) {
                Text(
                    "NEW RECORD",
                    color = accent, fontFamily = AppFont,
                    fontWeight = FontWeight.ExtraBold, fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Text(
                hud.score.toString(),
                color = TextPrimary, fontFamily = AppFont,
                fontWeight = FontWeight.ExtraBold, fontSize = 44.sp,
            )
            Text(
                buildString {
                    append("${hud.perfect} perfect · ${hud.great} great · ${hud.good} good")
                    if (hud.missed > 0) append(" · ${hud.missed} missed")
                },
                color = TextMuted, fontFamily = AppFont,
                fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 20.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                Stat("BEST COMBO", hud.bestCombo.toString())
                Stat("ACCURACY", hud.accuracy?.let { "${(it * 100).toInt()}%" } ?: "—")
                Stat("PLAYS", result.record.plays.toString())
            }
            if (result.trackKey == null) {
                Text(
                    "Scores save when a track is identified",
                    color = TextFaint, fontFamily = AppFont,
                    fontWeight = FontWeight.SemiBold, fontSize = 11.sp,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            color = TextPrimary, fontFamily = com.engabd.sendpin.ui.theme.MonoFont,
            fontWeight = FontWeight.Bold, fontSize = 17.sp,
        )
        Text(
            label,
            color = TextFaint, fontFamily = AppFont,
            fontWeight = FontWeight.Bold, fontSize = 9.sp,
        )
    }
}

// ── Geometry and palette ────────────────────────────────────────────────────

/** How long the combo-milestone ripple runs, in ms. */
private const val MILESTONE_MS = 700L

/** 0 at the horizon, 1 at the hit line, more than 1 once it has fallen past. */
private fun progressOf(note: GameNote, now: Long, lookAheadMs: Long): Float =
    1f - (note.triggerTimeMs - now).toFloat() / lookAheadMs

private fun laneHalfWidth(boardWidth: Float) = boardWidth * HALF_SPAN / LANES

/** Which lane a touch at [x] belongs to. The board is split evenly, edge to edge. */
private fun laneAt(x: Float, width: Float): Int {
    if (width <= 0f) return 0
    return ((x / width) * LANES).toInt().coerceIn(0, LANES - 1)
}

private fun laneColour(lane: Int): Color = when (lane) {
    0 -> Color(0xFFFF3B4E) // kick
    1 -> Color(0xFF3D8BFF) // snare
    2 -> Color(0xFFFFC01E) // hat
    else -> Color(0xFF5BE85B) // melody
}

private fun lighten(c: Color, amount: Float) = Color(
    red = c.red + (1f - c.red) * amount,
    green = c.green + (1f - c.green) * amount,
    blue = c.blue + (1f - c.blue) * amount,
    alpha = c.alpha,
)

private fun darken(c: Color, amount: Float) = Color(
    red = c.red * (1f - amount),
    green = c.green * (1f - amount),
    blue = c.blue * (1f - amount),
    alpha = c.alpha,
)

private const val LANES = 4

/** Where the hit line sits down the board. Low, so the pads land under the thumbs. */
private const val HIT_LINE = 0.84f

/** How far above the top of the board the lanes converge, as a fraction of its height. */
private const val VANISHING_ABOVE = 0.22f

/** Strength of the depth curve. Higher is a longer, flatter horizon. */
private const val PERSPECTIVE = 3.2f

/** Half the highway's width at the hit line, as a fraction of the board. */
private const val HALF_SPAN = 0.46f

private const val NOTE_WIDTH = 0.82f
private const val NOTE_ASPECT = 0.42f
private const val FRET_RADIUS = 0.72f

private const val PRESS_GLOW_MS = 220f
private const val BURST_MS = 460f
private const val MAX_BURSTS = 24

/** Combo at which the banner starts calling it out. */
private const val COMBO_CALLOUT = 5
