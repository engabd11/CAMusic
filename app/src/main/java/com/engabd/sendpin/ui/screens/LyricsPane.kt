package com.engabd.sendpin.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engabd.sendpin.ma.MaLyrics
import com.engabd.sendpin.ui.design.LocalAccent
import com.engabd.sendpin.ui.design.Motion
import com.engabd.sendpin.ui.theme.AppFont
import com.engabd.sendpin.ui.theme.TextFaint
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextSecondary
import com.engabd.sendpin.ui.viewmodel.NowPlayingViewModel
import com.engabd.sendpin.ui.viewmodel.NowPlayingViewModel.Load

/**
 * Lyrics as part of the player, not a sheet over it: this takes the album art's
 * place in the layout and scrolls itself as the song moves, so the words are
 * simply what you're looking at while the track plays.
 *
 * [positionMs] is the interpolated playhead the screen is already tracking for the
 * scrubber, which is what keeps the highlight moving between server polls.
 */
@Composable
fun LyricsPane(
    viewModel: NowPlayingViewModel,
    positionMs: Long,
    modifier: Modifier = Modifier,
) {
    val load by viewModel.lyrics.collectAsState()
    val accent = LocalAccent.current
    val currentItem by viewModel.currentItem.collectAsState()
    val offsetMs by viewModel.lyricsOffsetMs.collectAsState()

    // Keyed on the track, not on `Unit`. A new track resets the lyrics to Idle in the
    // view model, but a `LaunchedEffect(Unit)` had already run for the life of this
    // pane and would not run again — so with the lyrics open, every track after the
    // first sat on a spinner that had nothing behind it until the pane was closed and
    // reopened. Re-keying makes the pane follow the track it is sitting on.
    LaunchedEffect(currentItem?.itemId) {
        if (viewModel.lyrics.value is Load.Idle) viewModel.loadLyrics()
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        when (val l = load) {
            is Load.Loading, Load.Idle ->
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = accent)

            is Load.Failed -> Notice(Icons.Default.CloudOff, l.message)

            is Load.Ready -> {
                val lyrics: MaLyrics? = l.value
                if (lyrics == null || lyrics.lines.none { it.text.isNotBlank() }) {
                    Notice(Icons.Default.Lyrics, "No lyrics for this track.")
                } else if (!lyrics.synced && lyrics.text.length < 20 && !lyrics.text.contains(" ")) {
                    // A single short word is usually an error response, not lyrics.
                    Notice(Icons.Default.Lyrics, "No lyrics for this track.")
                } else {
                    SyncedLyrics(lyrics, positionMs, accent, offsetMs)
                }
            }
        }
    }
}

/**
 * How far ahead of the stamp the highlight moves.
 *
 * The line used to light up exactly on its timestamp, which reads *late* the moment a
 * scroll animation sits on top of it: by the time the line has travelled to the middle
 * of the pane the singer is already into it. Moving the decision forward by about the
 * time that travel takes puts the word and the highlight together.
 */
private const val LEAD_MS = 200L

/** Lines further than this from the active one are fully dimmed. */
private const val FALLOFF = 4f

@Composable
private fun SyncedLyrics(
    lyrics: MaLyrics,
    positionMs: Long,
    accent: Color,
    offsetMs: Int,
) {
    val lines = lyrics.lines
    // The line in force: the last one whose timestamp has passed, read slightly into
    // the future so the scroll starts before the vocal rather than chasing it. The
    // user offset is signed — a negative value means "these lyrics run early".
    val head = positionMs + LEAD_MS + offsetMs
    val active = if (!lyrics.synced) -1 else {
        val i = lines.indexOfLast { it.atMs <= head }.coerceAtLeast(0)
        // A blank line holds an index but has nothing to highlight, so land on the
        // last line that says something instead of lighting up a gap.
        if (lines.getOrNull(i)?.text.isNullOrBlank()) {
            (i downTo 0).firstOrNull { lines[it].text.isNotBlank() } ?: i
        } else i
    }

    val listState = rememberLazyListState()
    var previous by remember(lines) { mutableIntStateOf(-1) }
    // Measured, not assumed: centring needs the viewport's real height, and the pane
    // is whatever is left after the rest of the player has taken its share.
    var viewportPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    // Half a line, near enough, for the one case where the target hasn't been laid out
    // and its real height isn't knowable yet.
    val halfLinePx = with(density) { 20.dp.roundToPx() }

    LaunchedEffect(active, viewportPx) {
        if (active < 0 || viewportPx == 0) return@LaunchedEffect
        val jumped = previous < 0 || kotlin.math.abs(active - previous) > 4
        previous = active

        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == active }
        if (item == null) {
            // Off screen entirely — a seek, or the pane opening mid-song. Get there at
            // once rather than animating through the whole lyric, during which the
            // highlighted line is nowhere near the screen and the pane looks like it
            // has lost sync. It has not; it is still travelling. The offset is an
            // estimate; the next line change centres it exactly.
            listState.scrollToItem(active, -(viewportPx / 2 - halfLinePx))
            return@LaunchedEffect
        }
        // Centre it: the distance from the item's middle to the viewport's middle.
        val delta = (item.offset + item.size / 2f) - viewportPx / 2f
        if (jumped) listState.scrollBy(delta) else listState.animateScrollBy(delta, Motion.spatialSlow())
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .onSizeChanged { viewportPx = it.height }
            // The words fade out at the edges rather than being cut by them, so the
            // pane reads as a window onto the song instead of a clipped list. DstIn
            // needs its own layer to blend against, or it punches through to whatever
            // is behind the pane instead of masking the text.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.10f to Color.Black,
                        0.90f to Color.Black,
                        1f to Color.Transparent,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
        state = listState,
        // Half the pane top and bottom, so the first and last lines can reach the
        // middle. Without it the opening line sits at the top until the song has
        // scrolled far enough to lift it, and the closing line never centres at all.
        contentPadding = PaddingValues(
            horizontal = 8.dp,
            vertical = with(density) { (viewportPx / 2).toDp() },
        ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        itemsIndexed(lines) { i, line ->
            if (line.text.isBlank()) {
                Spacer(Modifier.height(12.dp))
            } else {
                val isActive = i == active && lyrics.synced
                // One driver for size, colour and opacity, so they move together
                // instead of the text snapping between two states. Scaling through
                // graphicsLayer rather than changing fontSize keeps the *layout* height
                // fixed — a font-size change relayouts the list under the scroll that
                // is animating over it, which is the shudder this replaces.
                val activeness by animateFloatAsState(
                    targetValue = if (isActive) 1f else 0f,
                    animationSpec = Motion.effects(),
                    label = "lyricActive",
                )
                // Distance from the sung line, as a focus falloff either side of it.
                val distance = if (active < 0) 0f else (kotlin.math.abs(i - active) / FALLOFF).coerceIn(0f, 1f)
                val resting = if (!lyrics.synced) TextSecondary else if (i < active) TextFaint else TextMuted
                Text(
                    line.text,
                    color = lerp(resting, accent, activeness),
                    fontFamily = AppFont,
                    fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.SemiBold,
                    fontSize = 17.sp,
                    lineHeight = 25.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .graphicsLayer {
                            val s = 1f + 0.18f * activeness
                            scaleX = s
                            scaleY = s
                            alpha = if (lyrics.synced) {
                                (1f - 0.55f * distance).coerceAtLeast(0.3f)
                            } else 1f
                        },
                )
            }
        }
    }
}

@Composable
private fun Notice(icon: androidx.compose.ui.graphics.vector.ImageVector, message: String) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = TextFaint, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(10.dp))
        Text(
            message, color = TextMuted, fontFamily = AppFont, fontSize = 12.sp,
            lineHeight = 18.sp, textAlign = TextAlign.Center,
        )
    }
}
