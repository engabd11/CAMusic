package com.engabd.sendpin.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engabd.sendpin.hue.ambience.AmbienceEffect
import com.engabd.sendpin.ui.design.CastGlow
import com.engabd.sendpin.ui.design.DisclosureChevron
import com.engabd.sendpin.ui.design.ExperimentalBadge
import com.engabd.sendpin.ui.design.GlassCard
import com.engabd.sendpin.ui.design.LocalPalette
import com.engabd.sendpin.ui.design.Motion
import com.engabd.sendpin.ui.design.a
import com.engabd.sendpin.ui.design.pressScale
import com.engabd.sendpin.ui.design.rememberPressScale
import com.engabd.sendpin.ui.theme.Glass
import com.engabd.sendpin.ui.theme.Hairline
import com.engabd.sendpin.ui.theme.HairlineSoft
import com.engabd.sendpin.ui.theme.Ink
import com.engabd.sendpin.ui.theme.TextFaint
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextPrimary
import com.engabd.sendpin.ui.theme.TextSecondary

/**
 * The parts the Light Sync page is built from.
 *
 * Both routes to the lights — the Hue bridge directly, and Home Assistant's syncoV2 —
 * are the same page to look at and the same page to use, so the hero, the pills and the
 * tab strip live here and each screen composes its own from them. Split out for the
 * reason [SavedShows] was: [LightSyncScreen] is the file people actually read, and it
 * does not get shorter by having more of this in it.
 */

// --- the hero's two pills -------------------------------------------------

/**
 * One of the two big controls at the top of the page: the room, and the level.
 *
 * These were a `SectionLabel` over a horizontally scrolling row of chips each, which
 * gave the two things nearly everybody came to change exactly the same weight as the
 * seventh tunable slider. They are the page's primary controls and now look like it —
 * large, side by side, and lit by the album rather than by chrome.
 *
 * [tint] is a palette swatch rather than the accent, and the two pills take different
 * ones on purpose: side by side and identically coloured they read as one segmented
 * control with a split down the middle, which is not what they are. Taking them from
 * [LocalPalette] rather than picking colours means they still belong to the record
 * that is playing, like everything else on the page.
 *
 * Expanding happens in the caller, below the row, rather than in a popup: the contents
 * are a scrolling row of areas or a ladder of intensities with a paragraph under it,
 * and neither fits in a menu without becoming a worse version of what it already was.
 */
@Composable
internal fun LightPill(
    label: String,
    value: String,
    icon: ImageVector,
    tint: Color,
    expanded: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val press = rememberPressScale()
    val glow by animateColorAsState(
        if (expanded) tint else tint.a(0.55f), Motion.effects(), label = "pillGlow",
    )
    Box(modifier.pressScale(press)) {
        // The bloom sits behind the card, offset down, so the pill reads as lit from
        // within rather than outlined — the same treatment the play button gets.
        CastGlow(
            color = glow,
            shape = RoundedCornerShape(20.dp),
            blurRadius = 22.dp,
            alpha = if (expanded) 0.34f else 0.18f,
            offsetY = 8.dp,
        )
        GlassCard(
            radius = 20.dp,
            fill = if (expanded) tint.a(0.16f) else Glass,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 68.dp)
                .clickable(onClick = onClick, interactionSource = press.interactions, indication = null),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(icon, null, tint = tint, modifier = Modifier.size(13.dp))
                        Text(
                            label.uppercase(),
                            color = TextFaint,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        value,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                DisclosureChevron(expanded = expanded, tint = TextMuted, size = 18.dp)
            }
        }
    }
}

/**
 * The panel a pill opens, directly under the pill row.
 *
 * `expandVertically` on [Motion.contentSize] and a fade on [Motion.effects] — the same
 * pairing the Light Sync settings page uses for its own disclosures, so an expander
 * behaves identically wherever it is met.
 */
@Composable
internal fun PillPanel(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(Motion.contentSize()) + fadeIn(Motion.effects()),
        exit = shrinkVertically(Motion.contentSize()) + fadeOut(Motion.effects()),
    ) {
        Column(Modifier.fillMaxWidth().padding(top = 14.dp)) { content() }
    }
}

// --- the tab strip --------------------------------------------------------

/**
 * Which part of the page is on show.
 *
 * The page had thirteen sections in one column, every one of them opened by a label and
 * a 22dp gap, which is a list rather than a page: the colour picker, the brightness
 * ceiling, the saved shows, the five light-show layers, seven tunable sliders and the
 * speaker offset all read as equally important and equally far away.
 *
 * Only the selected tab's items are emitted, so this is also the cheapest thing on the
 * screen — the sections that are not on show are not composed, measured or collecting.
 *
 * [SHOWS] is absent on the Home Assistant route, which has neither saved shows nor the
 * light-show layers. A tab that opens on nothing is worse than one that is not offered.
 */
internal enum class LightTab(val label: String) {
    LOOK("Look"),
    SHOWS("Shows"),
    TUNING("Tuning"),
}

/**
 * The tab strip.
 *
 * Deliberately not `SegmentedToggle`: that draws a filled slab per option and is built
 * for two or three settings values, and this is navigation — it sits under two large
 * lit tiles, and a third heavy control there flattens the hierarchy the tiles just
 * established. An underline keeps the weight on the tiles.
 */
@Composable
internal fun LightTabs(
    tabs: List<LightTab>,
    selected: LightTab,
    accent: Color,
    modifier: Modifier = Modifier,
    onSelect: (LightTab) -> Unit,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        tabs.forEach { tab ->
            val on = tab == selected
            val tint by animateColorAsState(
                if (on) accent else TextMuted, Motion.effects(), label = "tabTint",
            )
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onSelect(tab) }
                    .padding(vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(
                    tab.label,
                    color = tint,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold,
                    fontSize = 13.sp,
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .clip(CircleShape)
                        .background(if (on) accent else Color.Transparent),
                )
            }
        }
    }
}

// --- the two feature tiles ------------------------------------------------

/** The height both tiles stand at, so the row reads as a pair rather than a stagger. */
private val TileHeight = 152.dp

/**
 * Ambience, as a tile that shows what it is.
 *
 * It was a single row with a chevron and one sentence, which said nothing about the
 * nine effects behind it — a thunderstorm, a fireplace, an aurora, a train — all of
 * which are the reason to tap it. The tile draws the catalogue instead, and when one is
 * running it stops being an invitation and becomes the control for the thing happening
 * in the room.
 *
 * [running] is the wire name from `DirectLightSync.ambienceRunning`, so the tile
 * follows a show started from anywhere — the effects screen, a previous session, or the
 * sleep timer ending one — rather than only what was tapped here.
 */
@Composable
internal fun AmbienceTile(
    running: String?,
    accent: Color,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onStop: () -> Unit,
) {
    val effect = running?.let { AmbienceEffect.fromWire(it) }
    val live = effect != null
    val press = rememberPressScale()

    Box(modifier.pressScale(press)) {
        if (live) {
            CastGlow(
                color = accent, shape = RoundedCornerShape(20.dp),
                blurRadius = 26.dp, alpha = 0.30f, offsetY = 10.dp,
            )
        }
        GlassCard(
            radius = 20.dp,
            fill = if (live) accent.a(0.14f) else Glass,
            modifier = Modifier
                .fillMaxWidth()
                .height(TileHeight)
                .clickable(onClick = onOpen, interactionSource = press.interactions, indication = null),
        ) {
            Column(
                Modifier.fillMaxSize().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "Ambience",
                        color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                    )
                    ExperimentalBadge()
                }

                if (effect != null) {
                    // Running: the effect itself, large, and a way to stop it. Nobody
                    // opening this page mid-thunderstorm wants to be offered a menu.
                    Row(
                        Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(11.dp),
                    ) {
                        Box(
                            Modifier.size(44.dp).clip(RoundedCornerShape(14.dp))
                                .background(accent.a(0.20f))
                                .border(1.dp, accent.a(0.45f), RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                ambienceIcon(effect), null,
                                tint = accent, modifier = Modifier.size(22.dp),
                            )
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                effect.title,
                                color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "Running",
                                color = accent, fontWeight = FontWeight.Bold, fontSize = 11.sp,
                            )
                        }
                    }
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(11.dp))
                            .border(1.dp, accent.a(0.5f), RoundedCornerShape(11.dp))
                            .clickable(onClick = onStop)
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Icon(Icons.Default.Stop, null, tint = accent, modifier = Modifier.size(14.dp))
                            Text("Stop", color = accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                } else {
                    // Idle: the catalogue, so the tile answers "what is in there".
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        // Deduplicated by glyph, not `take(9)`. Two pairs of effects
                        // share an icon by design - the two fireworks recordings, and
                        // the two storms - so a straight nine drew the same picture
                        // twice and read as a rendering fault. Six also fits the tile,
                        // where nine spilled its last row behind the caption; the count
                        // is in the caption, which is the honest place for it.
                        AmbienceEffect.entries.distinctBy { ambienceIcon(it) }.take(6)
                            .chunked(3).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                row.forEach { e ->
                                    Box(
                                        Modifier.size(28.dp).clip(RoundedCornerShape(9.dp))
                                            .background(Ink.a(0.35f))
                                            .border(1.dp, HairlineSoft, RoundedCornerShape(9.dp)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            ambienceIcon(e), null,
                                            tint = TextSecondary, modifier = Modifier.size(15.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Text(
                        "Nine rooms, with their own sound. No music needed.",
                        color = TextFaint, fontSize = 11.sp, lineHeight = 14.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Rhythm Lights, as a tile that shows the game.
 *
 * The old row said "Tap along and flash the room." and nothing else — no hint that it
 * is a falling-note game, and no reason to come back to it a second time. The lane
 * motif says what it is at a glance, and [best] gives it the thing every score attack
 * needs: a number to beat, for the track that is on right now.
 */
@Composable
internal fun RhythmTile(
    best: Int?,
    accent: Color,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
) {
    val press = rememberPressScale()
    val palette = LocalPalette.current

    Box(modifier.pressScale(press)) {
        GlassCard(
            radius = 20.dp,
            fill = Glass,
            modifier = Modifier
                .fillMaxWidth()
                .height(TileHeight)
                .clickable(onClick = onOpen, interactionSource = press.interactions, indication = null),
        ) {
            Column(
                Modifier.fillMaxSize().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Rhythm Lights",
                    color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                )

                // Three lanes with notes falling toward a lit hit line. Static: this is
                // a picture of the game, and a tile that animates on a page nobody is
                // looking at is a frame budget spent on decoration.
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(0.30f, 0.62f, 0.16f).forEachIndexed { lane, drop ->
                            Box(
                                Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(Ink.a(0.35f))
                                    .border(1.dp, HairlineSoft, RoundedCornerShape(7.dp)),
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 5.dp)
                                        .padding(top = (drop * 46).dp)
                                        .height(9.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(palette.swatch(lane)),
                                )
                            }
                        }
                    }
                    // The hit line, where a note has to be struck.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 6.dp)
                            .height(2.dp)
                            .clip(CircleShape)
                            .background(accent),
                    )
                }

                Text(
                    if (best != null) "Best on this track  $best" else "Tap along and flash the room.",
                    color = if (best != null) accent else TextFaint,
                    fontWeight = if (best != null) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The two tiles, side by side and equal.
 *
 * Together rather than as two separate calls because their equal weight is the point —
 * these are the page's two "go and do something" features, and one of them being wider
 * would say one of them matters more.
 */
@Composable
internal fun FeatureTiles(
    ambienceRunning: String?,
    rhythmBest: Int?,
    accent: Color,
    onOpenAmbience: () -> Unit,
    onStopAmbience: () -> Unit,
    onOpenRhythm: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AmbienceTile(
            running = ambienceRunning,
            accent = accent,
            modifier = Modifier.weight(1f),
            onOpen = onOpenAmbience,
            onStop = onStopAmbience,
        )
        RhythmTile(
            best = rhythmBest,
            accent = accent,
            modifier = Modifier.weight(1f),
            onOpen = onOpenRhythm,
        )
    }
}

/** A hairline between a pill panel and whatever follows it. */
@Composable
internal fun PanelDivider() {
    Spacer(Modifier.height(12.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
    Spacer(Modifier.height(12.dp))
}
