package com.engabd.sendpin.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engabd.sendpin.ma.MaLyrics
import com.engabd.sendpin.ui.design.LocalAccent
import com.engabd.sendpin.ui.design.LocalReducedMotion
import com.engabd.sendpin.ui.design.Motion
import com.engabd.sendpin.ui.theme.AppFont
import com.engabd.sendpin.ui.theme.TextFaint
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextSecondary
import com.engabd.sendpin.ui.viewmodel.NowPlayingViewModel
import com.engabd.sendpin.ui.viewmodel.NowPlayingViewModel.Load
import kotlin.math.abs
import kotlinx.coroutines.delay

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
    val load by viewModel.lyrics.collectAsStateWithLifecycle()
    val accent = LocalAccent.current
    val offsetMs by viewModel.lyricsOffsetMs.collectAsStateWithLifecycle()

    // The pane says when it is on screen; the view model decides when to fetch.
    //
    // It used to fetch for itself, keyed on `currentItem?.itemId` — but that is null for
    // the whole of a local session, so on Navidrome, Jellyfin and offline the key never
    // changed, the effect never re-ran, and every track after the first sat on a spinner
    // until the pane was closed and reopened. Even on Music Assistant it was a race
    // against the view model's own reset. Both are gone: see
    // [NowPlayingViewModel.setLyricsOpen].
    DisposableEffect(viewModel) {
        viewModel.setLyricsOpen(true)
        onDispose { viewModel.setLyricsOpen(false) }
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        when (val l = load) {
            // Idle is "nothing has been asked for yet", which lasts a frame or two while
            // the view model gets going. A spinner for it is what made a load that never
            // happened indistinguishable from one in flight.
            Load.Idle -> Unit

            Load.Loading ->
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

/**
 * How far either side of the sung line the emphasis reaches, in lines.
 *
 * Asymmetric, because the two directions are not doing the same job. What is coming
 * is worth reading — you glance ahead to know the next line before it arrives — so it
 * stays legible for a good four lines. What has been sung has done its work and can
 * recede faster. A symmetric falloff makes the pane read as a window sliding over a
 * page; this makes it read as a song moving through one.
 */
private const val FALLOFF_AHEAD = 4.4f
private const val FALLOFF_BEHIND = 2.6f

/**
 * Opacity of a line at the far edge of [FALLOFF_AHEAD] / [FALLOFF_BEHIND], and of one
 * immediately beside the sung line.
 *
 * Both well under the sung line's 1.0. The hierarchy is carried by three things at
 * once — size, colour and opacity — so that no single one of them has to shout.
 */
private const val ALPHA_FAR = 0.16f
private const val ALPHA_NEAR = 0.5f

/**
 * How far out of focus a distant line goes.
 *
 * Depth of field, and the reason the dimming reads as *soft* rather than as merely
 * dark: a line that is only faint still has crisp edges asking to be read, while one
 * that is faint and slightly out of focus sits behind the glass with the backdrop.
 * Set as a `renderEffect` from inside the same `graphicsLayer` block that carries the
 * scale and the alpha, so it moves with them and costs no recomposition. minSdk is 31,
 * so the RenderEffect is always available.
 */
private val MAX_BLUR = 2.4.dp

/** Below this the blur is invisible and not worth a layer that has to composite. */
private const val MIN_BLUR_PX = 1.5f

/**
 * How long after the finger leaves before the sung line comes back to the middle.
 *
 * Long enough to read ahead a verse without the pane snatching the list back, short
 * enough that you never have to put it back yourself.
 */
private const val RESUME_AFTER_MS = 3_000L

/**
 * A move of more than this many lines is a seek, the pane opening mid-song, or a
 * return from a long manual scroll — a jump rather than a step. Travel is pointless
 * when the destination is nowhere near, so both the scroll and the emphasis land on
 * it instead of animating there.
 */
private const val JUMP_LINES = 4

/**
 * How much bigger the sung line is than the rest.
 *
 * Applied by shrinking everything else rather than growing the active line: the layout
 * is measured at the *large* size, so the line that is being sung is drawn at its
 * natural width — nothing to clip against the sides of the pane, and no re-wrap when a
 * line takes its turn. Scaling down also keeps the glyphs sharp, which scaling a
 * rasterised layer up does not.
 */
private const val ACTIVE_RATIO = 1.35f
private const val RESTING_SCALE = 1f / ACTIVE_RATIO

/** Type size, leading and the gaps around a line. See [LyricTextStyle]. */
private val LINE_PADDING = 3.dp
private val LINE_GAP = 4.dp

/** A blank line in the source is a verse break, and gets read as one. */
private val VERSE_GAP = 14.dp

/**
 * One style for every line, whichever state it is in.
 *
 * Two decisions worth spelling out, because both are the difference between lyrics
 * that read as typography and lyrics that read as a list of strings:
 *
 *  - **A single weight.** The sung line used to be `ExtraBold` against `SemiBold`,
 *    which is emphasis a reader has to decode as well as see, and — because a weight
 *    change alters glyph advances — it could re-wrap a line that sat near the edge of
 *    the measure at the exact moment that line took its turn, moving every line under
 *    it while a scroll animation was running over the top. Emphasis is now entirely
 *    size, colour and opacity, none of which touch the layout. One voice, three ways
 *    of raising it.
 *  - **Leading distributed evenly.** Android's default puts all of a line box's spare
 *    leading *below* the text, so a wrapped two-line lyric sits high in its own box
 *    and the gap to the next line is visibly larger than the gap inside the pair.
 *    `LineHeightStyle.Alignment.Center` splits it, which is what makes a wrapped line
 *    look like one thing.
 *
 * `LineBreak.Paragraph` runs the slower, whole-paragraph line breaker rather than the
 * greedy one. It costs nothing at this length and gives a far more even rag on the
 * wrapped lines, which at this size is the difference between a stray one-word second
 * line and a balanced pair.
 */
private val LyricTextStyle = TextStyle(
    fontFamily = AppFont,
    fontWeight = FontWeight.Bold,
    // Sized for the *sung* line; every other line is scaled back down to
    // RESTING_SCALE of it. See ACTIVE_RATIO for why that way round.
    fontSize = 22.sp,
    lineHeight = 31.sp,
    letterSpacing = (-0.2).sp,
    textAlign = TextAlign.Center,
    lineBreak = LineBreak.Paragraph,
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
    ),
)

/**
 * The spring the pane scrolls on.
 *
 * Critically damped, unlike the `Motion.spatialSlow()` it replaces. The app's spatial
 * springs are deliberately a little under-damped so a pushed surface settles with a
 * trace of overshoot — and that trace is exactly wrong here, because overshooting a
 * line of text carries the words past the middle of the pane and brings them back,
 * which reads as a wobble in the writing rather than as momentum in a surface.
 */
private val LyricScroll: AnimationSpec<Float> = spring(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = 260f,
    // In pixels, so the default 0.01 would keep the spring alive long past the point
    // anything is moving on screen.
    visibilityThreshold = 0.5f,
)

/** The emphasis travelling between lines, timed to arrive with the scroll. */
private val FocusTravel: AnimationSpec<Float> =
    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 240f)

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

    // Where the emphasis is, as a *fractional* line index, and the single driver for
    // how every line on screen is drawn.
    //
    // This is what replaced a per-line `animateFloatAsState(isActive)` plus a distance
    // to the active line computed from an Int. Only the two lines swapping places were
    // ever animated; every other line's dimming jumped a whole step the instant the
    // index changed, so a pane that was meant to glide re-graded itself top to bottom
    // on each new line. With one animated position, distance is continuous for
    // everybody and the focus *travels* down the pane. Read inside `graphicsLayer`
    // below, which means it costs a layer invalidation per frame and no recomposition.
    val focus = remember(lines) { Animatable(0f) }
    val reducedMotion = LocalReducedMotion.current
    var seeded by remember(lines) { mutableStateOf(false) }
    LaunchedEffect(active, lines) {
        if (active < 0) return@LaunchedEffect
        val target = active.toFloat()
        if (!seeded || abs(target - focus.value) > JUMP_LINES) {
            seeded = true
            focus.snapTo(target)
        } else {
            focus.animateTo(target, FocusTravel)
        }
    }

    // Who is driving the scroll. The song does, until a finger says otherwise — and
    // then the song takes it back [RESUME_AFTER_MS] after the finger has gone, so
    // reading ahead costs a moment rather than a manual scroll back.
    var following by remember(lines) { mutableStateOf(true) }
    var dragging by remember { mutableStateOf(false) }

    // Drags, not `isScrollInProgress`: the scrolling this pane does itself would
    // otherwise read as the user's and switch the auto-scroll off permanently.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> { dragging = true; following = false }
                is DragInteraction.Stop, is DragInteraction.Cancel -> dragging = false
            }
        }
    }
    LaunchedEffect(dragging, following) {
        if (dragging || following) return@LaunchedEffect
        delay(RESUME_AFTER_MS)
        following = true
    }

    // Keyed on `following` as well as the line, so handing control back re-centres
    // straight away instead of waiting for the next line to be sung.
    LaunchedEffect(active, following, viewportPx) {
        if (!following || active < 0 || viewportPx == 0) return@LaunchedEffect
        val jumped = previous < 0 || abs(active - previous) > JUMP_LINES
        previous = active
        listState.centreOn(active, viewportPx, instant = jumped)
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .onSizeChanged { viewportPx = it.height }
            // The words fade out at the edges rather than being cut by them, so the
            // pane reads as a window onto the song instead of a clipped list. Two
            // stops per edge rather than one: a single linear ramp still has a corner
            // in it where it meets full opacity, and at this depth the corner is
            // visible as a band. DstIn needs its own layer to blend against, or it
            // punches through to whatever is behind the pane instead of masking the
            // text.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.07f to Color.Black.copy(alpha = 0.35f),
                        0.18f to Color.Black,
                        0.82f to Color.Black,
                        0.93f to Color.Black.copy(alpha = 0.35f),
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
            horizontal = 18.dp,
            vertical = with(density) { (viewportPx / 2).toDp() },
        ),
        verticalArrangement = Arrangement.spacedBy(LINE_GAP),
    ) {
        // Keyed by position and typed by shape. A lyric pane is two kinds of row —
        // a line of words and the blank that separates two verses — and without a
        // contentType the list recycled a Spacer's slot into a Text's and rebuilt
        // both from scratch. The index is the right key here rather than the text:
        // a chorus repeats its lines verbatim, so the words are not unique, and the
        // list is replaced wholesale when the song changes.
        itemsIndexed(
            lines,
            key = { i, _ -> i },
            contentType = { _, line -> if (line.text.isBlank()) "gap" else "line" },
        ) { i, line ->
            if (line.text.isBlank()) {
                Spacer(Modifier.height(VERSE_GAP))
            } else {
                val isActive = i == active && lyrics.synced
                // Colour is the one part of the hierarchy that cannot be set from
                // inside a layer block, so it keeps a spring of its own. Two states
                // rather than a continuum, and critically damped, so it lands a little
                // ahead of the size — brightness arriving first is what makes the line
                // feel picked out rather than pushed forward.
                val activeness by animateFloatAsState(
                    targetValue = if (isActive) 1f else 0f,
                    animationSpec = Motion.effects(),
                    label = "lyricActive",
                )
                val resting = if (!lyrics.synced) TextSecondary else if (i < active) TextFaint else TextMuted
                Text(
                    line.text,
                    style = LyricTextStyle,
                    color = lerp(resting, accent, activeness),
                    modifier = Modifier
                        .fillMaxWidth()
                        // graphicsLayer *outside* the padding, so the layer's bounds
                        // include it: a blur is clipped to the layer it is set on, and
                        // a layer measured tight to the glyphs would cut the softness
                        // off square at the top and bottom of every dimmed line.
                        .graphicsLayer {
                            if (!lyrics.synced) {
                                // Nothing to be in focus, so nothing is out of it.
                                scaleX = RESTING_SCALE
                                scaleY = RESTING_SCALE
                                return@graphicsLayer
                            }
                            val offset = i - focus.value
                            // 1 on the line being sung, 0 a whole line away. Both
                            // neighbours are part-lit while the focus is between them,
                            // which is what makes the emphasis hand over instead of
                            // blinking from one line to the next.
                            val lit = smoothstep(1f - abs(offset).coerceAtMost(1f))
                            val reach = if (offset < 0f) FALLOFF_BEHIND else FALLOFF_AHEAD
                            val near = smoothstep(1f - (abs(offset) / reach).coerceIn(0f, 1f))

                            val s = RESTING_SCALE + (1f - RESTING_SCALE) * lit
                            scaleX = s
                            scaleY = s
                            alpha = ALPHA_FAR +
                                (ALPHA_NEAR - ALPHA_FAR) * near +
                                (1f - ALPHA_NEAR) * lit
                            // A Gaussian blur is an offscreen layer per line, recomputed
                            // every frame the focus moves — the most expensive thing on
                            // this screen by a wide margin, and it is pure depth cueing.
                            // Reduced motion drops it and keeps the dimming, which is the
                            // same information without the per-frame cost.
                            val blur = if (reducedMotion) 0f else MAX_BLUR.toPx() * (1f - near)
                            renderEffect =
                                if (blur > MIN_BLUR_PX) BlurEffect(blur, blur, TileMode.Decal)
                                else null
                        }
                        .padding(vertical = LINE_PADDING),
                )
            }
        }
    }
}

/**
 * A linear ramp has a corner at each end, and a corner in a falloff is a visible edge
 * in the dimming. This is the standard smoothstep — same value at 0 and 1, zero slope
 * at both — so the emphasis eases out of the sung line and into the background rather
 * than running out at a stroke.
 */
private fun smoothstep(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

/**
 * Put line [index] in the middle of a pane [viewportPx] tall.
 *
 * The subtraction that matters is `viewportStartOffset`. An item's `offset` is measured
 * from the start of the list's *content*, and this list carries half a pane of top
 * content padding so that the first line can reach the middle — which puts the content's
 * start half a pane above the top edge of the pane, and makes `viewportStartOffset`
 * negative by exactly that much. Centring on the raw offset therefore aimed half a pane
 * low and parked every sung line on the bottom edge, under the fade, where the pane
 * looked like it had simply stopped following the song.
 *
 * A line that isn't laid out has no size to centre on, so it is scrolled to first and
 * then corrected — by then it has a measured height, and the correction is the few
 * pixels between the top of a line and its middle.
 */
private suspend fun LazyListState.centreOn(index: Int, viewportPx: Int, instant: Boolean) {
    if (layoutInfo.visibleItemsInfo.none { it.index == index }) {
        if (instant) scrollToItem(index) else animateScrollToItem(index)
    }
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val delta = (item.offset - layoutInfo.viewportStartOffset + item.size / 2f) - viewportPx / 2f
    if (abs(delta) < 1f) return
    // Anything further than half a pane away is a jump however we got here; animating
    // it would drag the whole lyric past the reader on the way.
    if (instant || abs(delta) > viewportPx / 2f) scrollBy(delta)
    else animateScrollBy(delta, LyricScroll)
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
