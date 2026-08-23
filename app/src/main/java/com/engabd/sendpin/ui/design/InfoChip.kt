package com.engabd.sendpin.ui.design

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.engabd.sendpin.ui.theme.AppFont
import com.engabd.sendpin.ui.theme.Glass
import com.engabd.sendpin.ui.theme.Hairline
import com.engabd.sendpin.ui.theme.HairlineSoft
import com.engabd.sendpin.ui.theme.Ink2
import com.engabd.sendpin.ui.theme.TextFaint
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextPrimary
import com.engabd.sendpin.ui.theme.TextSecondary
import com.engabd.sendpin.ui.theme.WarnAmber

/**
 * The explanation a control needs, folded away until it is asked for.
 *
 * Half of what this app configures is invisible until it goes wrong — a stream
 * format, a gain mode, a background connection — so the settings screens carried
 * around a hundred blocks of prose explaining themselves. Read once each, then in the
 * way forever: a six-line paragraph under a switch pushed the next three controls off
 * the screen, and finding a setting meant scrolling past the reasons for all the ones
 * before it.
 *
 * So the long form moves in here and the row keeps a short one. The chip is the
 * affordance saying there is more, and it costs one line instead of six.
 *
 * ### Why a Popup and not a sheet
 *
 * The app's five bottom sheets are all `BoxScope` extensions drawn inside their
 * screen's own root Box, and they are shown by state the screen owns. That is the
 * right shape when the screen is what opens them. It is the wrong shape here:
 * descriptions live as deep as SettingsScreen → LibrariesSection → ServerDetail →
 * MaPlayerCards → PlayerSection → SettingsCard → ToggleRow, and hoisting an
 * `infoText: String?` up through six signatures to reach a Box would put plumbing in
 * every one of them for a string that concerns only the last. A Popup brings its own
 * window, so the state stays local to the chip and this works anywhere — including
 * from inside a control that has no idea what it is nested in.
 */
@Composable
fun InfoChip(title: String, text: String, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(
        modifier
            // 30dp of touch around an 18dp mark. The chip has to sit on a title line
            // without setting the line's height, so the target is bought with a
            // transparent border rather than with size.
            .size(30.dp)
            .clip(RoundedCornerShape(50))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = "About $title",
            ) { open = true },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.Info,
            contentDescription = "About $title",
            tint = if (open) TextSecondary else TextFaint,
            modifier = Modifier.size(17.dp),
        )
    }
    if (open) InfoOverlay(title, text) { open = false }
}

/**
 * The card the chip opens: a title, the full text, and a way out.
 *
 * Centred in the window and owing nothing to what opened it — see
 * [WindowCenterPosition]. Not focusable, and this is load-bearing: a focusable popup
 * takes window focus, which puts the activity through its soft-input resize path, and
 * the album art on the player behind — the only weighted child of that column —
 * absorbs the whole delta and visibly shrinks. The scrim handles dismissal and the
 * BackHandler handles the gesture that focusability would otherwise have provided.
 */
@Composable
private fun InfoOverlay(title: String, text: String, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    // Springs in from just under full size. Alpha on an effects spec, scale on a
    // spatial one — see [Motion]. Both are driven off one value that flips on the
    // first frame, so the card is never painted at its target size before it moves.
    var shown by remember { mutableStateOf(false) }
    val enter by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = Motion.effects(),
        label = "infoEnter",
    )
    androidx.compose.runtime.LaunchedEffect(Unit) { shown = true }

    Popup(
        popupPositionProvider = WindowCenterPosition,
        properties = PopupProperties(focusable = false, dismissOnClickOutside = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                )
                .graphicsLayer { alpha = enter },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .widthIn(max = 340.dp)
                    .padding(28.dp)
                    .graphicsLayer {
                        val s = 0.96f + 0.04f * enter
                        scaleX = s
                        scaleY = s
                    }
                    .clip(RoundedCornerShape(20.dp))
                    .background(Ink2)
                    .border(1.dp, Hairline, RoundedCornerShape(20.dp))
                    // Swallow taps so the card does not dismiss under its own content.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { }
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    title, color = TextPrimary, fontFamily = AppFont,
                    fontWeight = FontWeight.ExtraBold, fontSize = 16.sp,
                )
                // Scrolls, because the longest of these runs past six hundred
                // characters and a phone in landscape has nothing like the room.
                Column(
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    text.split(PARAGRAPH).forEach { para ->
                        if (para.startsWith(TIP_PREFIX)) {
                            TipBlock(para.removePrefix(TIP_PREFIX).trim())
                        } else {
                            Text(
                                para,
                                color = TextSecondary,
                                fontFamily = AppFont,
                                fontSize = 13.sp,
                                lineHeight = 19.sp,
                            )
                        }
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(13.dp))
                        .background(Glass)
                        .border(1.dp, Hairline, RoundedCornerShape(13.dp))
                        .clickable(onClick = onDismiss)
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Got it", color = TextPrimary, fontFamily = AppFont,
                        fontWeight = FontWeight.Bold, fontSize = 13.sp,
                    )
                }
            }
        }
    }
}

/**
 * The one paragraph in a description that tells you what to actually do.
 *
 * Written into the text as a "Tip:" paragraph rather than passed as its own parameter,
 * so that a description and its tip stay one string at the call site and cannot drift
 * apart, and so that adding one to an existing description is an edit to the sentence
 * rather than to the signature.
 */
@Composable
private fun TipBlock(text: String) {
    val accent = LocalAccent.current
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(accent.a(0.10f))
            .border(1.dp, accent.a(0.22f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Outlined.Lightbulb,
            contentDescription = "Tip",
            tint = accent,
            modifier = Modifier.size(15.dp),
        )
        Text(text, color = TextSecondary, fontFamily = AppFont, fontSize = 12.sp, lineHeight = 18.sp)
    }
}

/** How a tip is marked in a description. See [TipBlock]. */
private const val TIP_PREFIX = "Tip:"

/** What separates one paragraph of a description from the next. */
private const val PARAGRAPH = "\n\n"

/**
 * A quiet inline explanation with an icon beside it — not an error, just something
 * worth saying where it is being said.
 *
 * One primitive for what was three private copies of the same shape: `NoteRow` on the
 * speakers screen, `WarningBanner` and `DspStateBanner` on the DSP screen. They differ
 * only in tint, which is now [tone].
 *
 * This is for text that has to stay visible — live state, a warning, the reason a
 * control is missing. Documentation belongs behind an [InfoChip] instead.
 */
@Composable
fun InfoNote(
    text: String,
    modifier: Modifier = Modifier,
    tone: Color? = null,
    icon: ImageVector = Icons.Outlined.Info,
) {
    val tint = tone ?: TextFaint
    val toned = tone != null
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (toned) tint.copy(alpha = 0.10f) else Glass)
            .border(1.dp, if (toned) tint.copy(alpha = 0.25f) else HairlineSoft, RoundedCornerShape(12.dp))
            .padding(13.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        // Top, not centre: these run to two and three lines and a centred icon
        // floats away from the sentence it belongs to.
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(15.dp))
        Text(
            text,
            color = if (toned) tint else TextMuted,
            fontFamily = AppFont,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** The amber [InfoNote] tone, for a state that is working but not as intended. */
val InfoWarn: Color @Composable get() = WarnAmber
