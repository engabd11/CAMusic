package com.engabd.sendpin.tv.design

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import com.engabd.sendpin.ui.design.LocalAccent
import com.engabd.sendpin.ui.theme.Glass
import com.engabd.sendpin.ui.theme.Ink3
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextPrimary

/**
 * The D-pad focus affordance layer for the tv flavor: thin wrappers over
 * `androidx.tv.material3`'s Card/Button (the component library adds the
 * focused-item scale/glow TV UX expects, tuned out of the box — see the TV
 * plan's design-approach section) bound to this app's own [SendspinTheme]
 * tokens rather than tv-material's separate colour-scheme concept, so a TV
 * screen still reads as CAMusic and not as a stock tv-material sample. Only
 * the components are borrowed here; theming stays the app's own throughout.
 */

/** A focusable tile — the library grid, queue rows, the nav rail. */
@Composable
fun TvTile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = MaterialTheme.shapes.medium,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val accent = LocalAccent.current
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = CardDefaults.shape(shape = shape, focusedShape = shape),
        colors = CardDefaults.colors(
            containerColor = Ink3,
            contentColor = TextPrimary,
            focusedContainerColor = Glass,
            focusedContentColor = TextPrimary,
            pressedContainerColor = Glass,
            pressedContentColor = TextPrimary,
        ),
        scale = CardDefaults.scale(scale = 1f, focusedScale = 1.06f, pressedScale = 1.02f),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = androidx.compose.foundation.BorderStroke(2.dp, accent),
                shape = shape,
            ),
        ),
        content = content,
    )
}

/** A focusable action button — transport controls, the rail's items, form submit. */
@Composable
fun TvButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val accent = LocalAccent.current
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        contentPadding = contentPadding,
        colors = ButtonDefaults.colors(
            containerColor = Glass,
            contentColor = TextPrimary,
            focusedContainerColor = accent,
            focusedContentColor = Color.Black,
            pressedContainerColor = accent,
            pressedContentColor = Color.Black,
            disabledContainerColor = Glass,
            disabledContentColor = TextMuted,
        ),
        content = content,
    )
}

/**
 * Hands DPAD_DOWN/UP off to Compose's normal focus-traversal explicitly.
 *
 * Verified live on the Google TV emulator during this feature's build: a
 * focused `TextField` swallows a plain DPAD_DOWN/UP key press itself (cursor-
 * row handling) before Compose's focus-traversal dispatch ever sees it, so
 * `Modifier.focusProperties { down = ... }` alone is a no-op on a text field —
 * the key event never reaches what consults that property. This intercepts
 * the key upstream of the field's own handling instead. Needed on every text
 * field a TV screen wants D-pad navigation to leave; not needed on ordinary
 * buttons/cards, which do not consume directional keys themselves.
 */
fun Modifier.tvDpadExitField(): Modifier = composed {
    val focusManager = LocalFocusManager.current
    onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
        when (event.key) {
            Key.DirectionDown -> { focusManager.moveFocus(FocusDirection.Down); true }
            Key.DirectionUp -> { focusManager.moveFocus(FocusDirection.Up); true }
            else -> false
        }
    }
}
