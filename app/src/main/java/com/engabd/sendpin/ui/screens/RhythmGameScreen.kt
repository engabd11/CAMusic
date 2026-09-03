package com.engabd.sendpin.ui.screens

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.game.NoteKind
import com.engabd.sendpin.ui.design.LocalAccent
import com.engabd.sendpin.ui.viewmodel.RhythmGameViewModel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.sample
import androidx.compose.ui.unit.IntSize

/**
 * A four-lane rhythm tiles game that follows the music and flashes the Hue room
 * when the player hits notes accurately.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RhythmGameScreen(
    onBack: () -> Unit,
    viewModel: RhythmGameViewModel = viewModel(),
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val view = LocalView.current
    var boardSize by remember { mutableStateOf(IntSize.Zero) }
    val boardWidthPx = with(density) { boardSize.width.toDp() }
    val boardHeightPx = with(density) { boardSize.height.toDp() }

    // Keep the screen on while playing.
    DisposableEffect(Unit) {
        val window = (view.context as? android.app.Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // Subscribe to the active audio analysis tap so notes are generated from
    // whatever is currently playing on this phone.
    val app = context.applicationContext as SendpinApp
    val frame by remember {
        app.activeLightSyncSource.map { it.tap.frames }.collectAsStateWithLifecycle(
            initialValue = null,
        )
    }
    LaunchedEffect(frame) {
        val f = frame ?: return@LaunchedEffect
        val pos = (app.activeLightSyncSource.value.lead.positionMs ?: System.currentTimeMillis())
        viewModel.onFrame(f, pos)
    }

    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val score by viewModel.score.collectAsStateWithLifecycle()
    val combo by viewModel.combo.collectAsStateWithLifecycle()
    val lastHit by viewModel.lastHit.collectAsStateWithLifecycle()
    val accent = LocalAccent.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Rhythm Lights", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        containerColor = Color.Black,
    ) { insets -
        Column(
            Modifier
                .fillMaxSize()
                .padding(insets)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                ScoreColumn("Score", score.toString())
                ScoreColumn("Combo", combo.toString())
                ScoreColumn("Last", lastHit ?: "")
            }
            Spacer(Modifier.height(12.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFF0A0A0A))
                    .onSizeChanged { boardSize = it }
            ) {
                val laneCount = 4
                val hitLine = 0.85f
                // Draw lane separators.
                for (i in 1 until laneCount) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .width(1.dp)
                            .alpha(0.2f)
                            .background(Color.White)
                            .offset(x = boardWidthPx * (i / laneCount.toFloat()))
                    )
                }
                // Hit line.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .alpha(0.6f)
                        .background(accent)
                        .offset(y = boardHeightPx * hitLine)
                )

                // Notes.
                notes.forEach { note ->
                    val laneWidth = 1f / laneCount
                    val x = note.lane * laneWidth
                    val travel = ((note.triggerTimeMs - System.currentTimeMillis()) / 2000f).coerceIn(0f, 1f)
                    val y = travel * hitLine
                    val color = noteColor(note.kind, accent)
                    Box(
                        Modifier
                            .fillMaxWidth(laneWidth * 0.8f)
                            .height(32.dp)
                            .offset(
                                x = boardWidthPx * (x + laneWidth * 0.1f),
                                y = boardHeightPx * y,
                            )
                            .clip(RoundedCornerShape(8.dp))
                            .background(color)
                    )
                }

                // Invisible tap receivers, one per lane.
                Row(Modifier.fillMaxSize()) {
                    repeat(laneCount) { lane ->
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .pointerInput(Unit) {
                                    detectTapGestures {
                                        val pos = app.activeLightSyncSource.value.lead.positionMs ?: System.currentTimeMillis()
                                        viewModel.tap(lane, pos)
                                    }
                                }
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "Tap the lanes as the notes reach the line.",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ScoreColumn(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
        Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 24.sp)
    }
}

private fun noteColor(kind: NoteKind, accent: Color): Color = when (kind) {
    NoteKind.KICK -> Color(0xFFFF5252)
    NoteKind.SNARE -> Color(0xFF448AFF)
    NoteKind.HAT -> Color(0xFFFFD740)
    NoteKind.MELODY -> accent
}
