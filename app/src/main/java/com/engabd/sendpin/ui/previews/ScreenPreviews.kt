package com.engabd.sendpin.ui.previews

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engabd.sendpin.ui.design.AlbumArt
import com.engabd.sendpin.ui.design.AlbumPalette
import com.engabd.sendpin.ui.design.Bloom
import com.engabd.sendpin.ui.design.CastGlow
import com.engabd.sendpin.ui.design.GlassCard
import com.engabd.sendpin.ui.design.GradientAvatar
import com.engabd.sendpin.ui.design.HSlider
import com.engabd.sendpin.ui.design.IconChip
import com.engabd.sendpin.ui.design.LocalAccent
import com.engabd.sendpin.ui.design.LocalPalette
import com.engabd.sendpin.ui.design.MeltBackdrop
import com.engabd.sendpin.ui.design.PlayButton
import com.engabd.sendpin.ui.design.Pill
import com.engabd.sendpin.ui.design.QualityPill
import com.engabd.sendpin.ui.design.RingProgress
import com.engabd.sendpin.ui.design.SectionLabel
import com.engabd.sendpin.ui.design.SegmentedToggle
import com.engabd.sendpin.ui.design.ToggleChip
import com.engabd.sendpin.ui.theme.DefaultAccent
import com.engabd.sendpin.ui.theme.Ink
import com.engabd.sendpin.ui.theme.SendspinTheme
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextPrimary

/*
 * Compose Preview catalogue for the Sendpin/CAMusic design system.
 *
 * These previews render in Android Studio's Split/Design pane without
 * needing a device or a running server.  They cover the building blocks
 * (pills, chips, cards, buttons) and a few composed layouts so you can
 * iterate on typography, spacing, colour, and shape quickly.
 *
 * How to use
 * ----------
 * 1. Open any file below in Android Studio.
 * 2. Look for the "Split" or "Design" button in the top-right of the
 *    code editor and click it.
 * 3. The preview renders instantly.  Change a parameter (e.g. fontSize,
 *    padding, border colour) and it refreshes in ~1 s.
 * 4. For interactive previews, click the "Start Interactive Mode" icon
 *    in the preview gutter.
 *
 * To add a new preview for a screen, copy one of the patterns below,
 * swap in your composable, and provide mock state via the preview
 * wrapper.  If the screen needs a ViewModel, create a lightweight fake
 * that extends AndroidViewModel or extract a stateless Content composable
 * and preview that instead.
 */

/** Wraps every preview in the OLED theme + default accent/palette locals. */
@Composable
fun SendspinPreview(
    accent: Color = DefaultAccent,
    palette: AlbumPalette = AlbumPalette(),
    content: @Composable () -> Unit,
) {
    SendspinTheme {
        CompositionLocalProvider(
            LocalAccent provides accent,
            LocalPalette provides palette,
        ) {
            Box(Modifier.fillMaxSize().background(Ink)) {
                content()
            }
        }
    }
}

/* ─── Design tokens ─── */

@Preview(name = "Theme – OLED dark", device = "id:pixel_5")
@Composable
fun PreviewOledTheme() {
    SendspinTheme {
        Box(Modifier.fillMaxSize().background(Ink)) {
            Column(Modifier.padding(24.dp)) {
                Text("Headline", color = TextPrimary, fontSize = 22.sp)
                Spacer(Modifier.height(8.dp))
                Text("Body on OLED black", color = TextMuted, fontSize = 14.sp)
                Spacer(Modifier.height(16.dp))
                Pill(text = "Accent pill", selected = true)
            }
        }
    }
}

/* ─── Individual components ─── */

@Preview(name = "Pill – selected", backgroundColor = 0xFF000000, showBackground = true)
@Composable
fun PreviewPillSelected() {
    SendspinPreview {
        Pill(text = "Selected", selected = true)
    }
}

@Preview(name = "Pill – unselected", backgroundColor = 0xFF000000, showBackground = true)
@Composable
fun PreviewPillUnselected() {
    SendspinPreview {
        Pill(text = "Unselected", selected = false)
    }
}

@Preview(name = "ToggleChip", backgroundColor = 0xFF000000, showBackground = true)
@Composable
fun PreviewToggleChip() {
    SendspinPreview {
        Row {
            ToggleChip(text = "Shuffle", selected = true, onClick = {})
            Spacer(Modifier.width(8.dp))
            ToggleChip(text = "Repeat", selected = false, onClick = {})
        }
    }
}

@Preview(name = "SegmentedToggle", backgroundColor = 0xFF000000, showBackground = true)
@Composable
fun PreviewSegmentedToggle() {
    SendspinPreview {
        SegmentedToggle(
            options = listOf("Queue", "Lyrics", "Similar"),
            selected = 0,
            onSelect = {},
        )
    }
}

@Preview(name = "IconChip", backgroundColor = 0xFF000000, showBackground = true)
@Composable
fun PreviewIconChip() {
    SendspinPreview {
        IconChip(
            icon = Icons.Default.PlayArrow,
            label = "Play",
            active = false,
            onClick = {},
        )
    }
}

@Preview(name = "QualityPill – lossless", backgroundColor = 0xFF000000, showBackground = true)
@Composable
fun PreviewQualityPillLossless() {
    SendspinPreview {
        QualityPill(text = "24 / 96", hiRes = true, lossless = true)
    }
}

@Preview(name = "QualityPill – lossy", backgroundColor = 0xFF000000, showBackground = true)
@Composable
fun PreviewQualityPillLossy() {
    SendspinPreview {
        QualityPill(text = "320 kbps", hiRes = false, lossless = false)
    }
}

@Preview(name = "GlassCard", backgroundColor = 0xFF000000, showBackground = true)
@Composable
fun PreviewGlassCard() {
    SendspinPreview {
        GlassCard {
            Column(Modifier.padding(16.dp)) {
                Text("Card title", color = TextPrimary, fontSize = 16.sp)
                Spacer(Modifier.height(4.dp))
                Text("Subtitle line", color = TextMuted, fontSize = 12.sp)
            }
        }
    }
}

@Preview(name = "PlayButton – playing", backgroundColor = 0xFF000000, showBackground = true)
@Composable
fun PreviewPlayButtonPlaying() {
    SendspinPreview {
        PlayButton(playing = true, onClick = {})
    }
}

@Preview(name = "PlayButton – paused", backgroundColor = 0xFF000000, showBackground = true)
@Composable
fun PreviewPlayButtonPaused() {
    SendspinPreview {
        PlayButton(playing = false, onClick = {})
    }
}

@Preview(name = "RingProgress", backgroundColor = 0xFF000000, showBackground = true)
@Composable
fun PreviewRingProgress() {
    SendspinPreview {
        RingProgress(progress = 0.65f)
    }
}

@Preview(name = "GradientAvatar", backgroundColor = 0xFF000000, showBackground = true)
@Composable
fun PreviewGradientAvatar() {
    SendspinPreview {
        GradientAvatar(letter = "A", index = 3)
    }
}

@Preview(name = "SectionLabel", backgroundColor = 0xFF000000, showBackground = true)
@Composable
fun PreviewSectionLabel() {
    SendspinPreview {
        SectionLabel(text = "Recently played")
    }
}

@Preview(name = "HSlider", backgroundColor = 0xFF000000, showBackground = true)
@Composable
fun PreviewHSlider() {
    SendspinPreview {
        HSlider(
            value = 0.4f,
            onValueChange = {},
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

@Preview(name = "Bloom + CastGlow", backgroundColor = 0xFF000000, showBackground = true)
@Composable
fun PreviewBloomAndGlow() {
    SendspinPreview(accent = Color(0xFFD9A85C)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Bloom(color = DefaultAccent, size = 240.dp, x = 0.dp, y = (-40).dp, alpha = 0.5f)
            CastGlow(
                color = DefaultAccent,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                blurRadius = 24.dp,
                alpha = 0.3f,
            )
        }
    }
}

/* ─── Composed layouts (screen-like) ─── */

@Preview(name = "Now-Playing transport row", backgroundColor = 0xFF000000, showBackground = true)
@Composable
fun PreviewTransportRow() {
    SendspinPreview {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp),
        ) {
            IconChip(
                icon = Icons.Default.SkipPrevious,
                contentDescription = "Prev",
                active = false,
                onClick = {},
            )
            Spacer(Modifier.width(16.dp))
            PlayButton(playing = true, onClick = {}, size = 68.dp)
            Spacer(Modifier.width(16.dp))
            IconChip(
                icon = Icons.Default.SkipNext,
                contentDescription = "Next",
                active = false,
                onClick = {},
            )
        }
    }
}

@Preview(name = "Mini player bar", backgroundColor = 0xFF000000, showBackground = true)
@Composable
fun PreviewMiniPlayer() {
    SendspinPreview {
        GlassCard {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Box(Modifier.size(40.dp)) {
                    AlbumArt(url = null, glow = DefaultAccent, modifier = Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Track Title", color = TextPrimary, fontSize = 13.sp, maxLines = 1)
                    Text("Artist Name", color = TextMuted, fontSize = 11.sp, maxLines = 1)
                }
                Spacer(Modifier.width(8.dp))
                PlayButton(playing = true, onClick = {}, size = 36.dp)
            }
        }
    }
}

@Preview(name = "Library shelf header", backgroundColor = 0xFF000000, showBackground = true)
@Composable
fun PreviewLibraryShelfHeader() {
    SendspinPreview {
        Column(Modifier.padding(16.dp)) {
            SectionLabel(text = "Favourite albums")
            Spacer(Modifier.height(8.dp))
            Row {
                repeat(3) {
                    Column(Modifier.padding(end = 8.dp)) {
                        Box(Modifier.size(120.dp)) {
                            AlbumArt(url = null, glow = DefaultAccent, modifier = Modifier.fillMaxSize())
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("Album", color = TextPrimary, fontSize = 11.sp, maxLines = 1)
                        Text("Artist", color = TextMuted, fontSize = 10.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Preview(name = "Settings row", backgroundColor = 0xFF000000, showBackground = true)
@Composable
fun PreviewSettingsRow() {
    SendspinPreview {
        GlassCard {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Text("Libraries", color = TextPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text("3 servers", color = TextMuted, fontSize = 13.sp)
            }
        }
    }
}

@Preview(name = "Full-screen component board", device = "id:pixel_5")
@Composable
fun PreviewComponentBoard() {
    SendspinPreview {
        Column(
            Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            SectionLabel(text = "Component board")
            Spacer(Modifier.height(16.dp))
            Row {
                Pill(text = "Selected", selected = true)
                Spacer(Modifier.width(8.dp))
                Pill(text = "Unselected", selected = false)
            }
            Spacer(Modifier.height(12.dp))
            SegmentedToggle(
                options = listOf("Queue", "Lyrics", "Similar"),
                selected = 1,
                onSelect = {},
            )
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlayButton(playing = false, onClick = {})
                Spacer(Modifier.width(16.dp))
                RingProgress(progress = 0.75f)
                Spacer(Modifier.width(16.dp))
                GradientAvatar(letter = "M", index = 5)
            }
            Spacer(Modifier.height(16.dp))
            GlassCard {
                Column(Modifier.padding(16.dp)) {
                    Text("GlassCard content", color = TextPrimary, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    QualityPill(text = "24 / 192", hiRes = true, lossless = true)
                }
            }
            Spacer(Modifier.height(16.dp))
            HSlider(
                value = 0.5f,
                onChange = {},
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
    }
}

/* ─── Screen previews (stateless wrappers) ───
 *
 * Full screens (LibraryScreen, NowPlayingScreen, etc.) need ViewModels that
 * are tightly coupled to SendpinApp.  For those, the recommended workflow is:
 *
 *   1. Extract a stateless *Content* composable from the screen.
 *   2. Preview the Content composable with hand-written mock state.
 *   3. Keep the Screen as the thin ViewModel-binding layer.
 *
 * Below are examples for the two screens that are already stateless enough
 * to preview directly.
 */

@Preview(name = "Empty state", backgroundColor = 0xFF000000, showBackground = true)
@Composable
fun PreviewEmptyState() {
    SendspinPreview {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(120.dp))
            Text("No tracks", color = TextPrimary, fontSize = 18.sp)
            Spacer(Modifier.height(4.dp))
            Text("This album appears to be empty.", color = TextMuted, fontSize = 13.sp)
        }
    }
}

@Preview(name = "Error state", backgroundColor = 0xFF000000, showBackground = true)
@Composable
fun PreviewErrorState() {
    SendspinPreview {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(120.dp))
            Text("Connection failed", color = TextPrimary, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            Pill(text = "Retry", selected = true, onClick = {})
        }
    }
}
