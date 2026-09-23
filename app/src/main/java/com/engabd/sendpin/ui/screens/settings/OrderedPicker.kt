package com.engabd.sendpin.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.engabd.sendpin.ui.design.a
import com.engabd.sendpin.ui.theme.AppFont
import com.engabd.sendpin.ui.theme.Glass
import com.engabd.sendpin.ui.theme.Hairline
import com.engabd.sendpin.ui.theme.Ink
import com.engabd.sendpin.ui.theme.MonoFont
import com.engabd.sendpin.ui.theme.TextFaint
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextPrimary
import com.engabd.sendpin.ui.theme.TextSecondary

// The pickers, lifted out of AndroidAutoSettings.kt when the library's own category
// row turned out to want exactly the same control. Unchanged apart from being
// widened to `internal` — the behaviour below, especially the last-entry rule, is
// subtle enough to be worth having in one place rather than two.

/**
 * A list you can switch entries off in and reorder, without a drag.
 *
 * Deliberately arrows rather than drag-and-drop. This list is short, it is edited
 * once and then forgotten, and a drag handle inside a vertically scrolling settings
 * page is the control that most often does the wrong thing under a thumb. Two 40dp
 * buttons cannot be ambiguous.
 *
 * The last enabled entry's checkbox is greyed rather than removed: a selection of
 * nothing and a selection of everything are stored identically (see
 * [CarBrowseOptions.enabledOrder]), so switching the last one off would silently
 * turn everything back on.
 */
@Composable
internal fun OrderedPicker(
    keys: List<String>,
    enabled: List<String>,
    accent: Color,
    title: (String) -> String,
    subtitle: (String) -> String,
    onToggle: (String) -> Unit,
    onMove: (String, Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        keys.forEach { key ->
            val on = key in enabled
            val index = enabled.indexOf(key)
            OrderedPickerRow(
                title = title(key),
                subtitle = subtitle(key),
                checked = on,
                accent = accent,
                canUncheck = !on || enabled.size > 1,
                canMoveUp = on && index > 0,
                canMoveDown = on && index >= 0 && index < enabled.size - 1,
                onToggle = { onToggle(key) },
                onMove = { delta -> onMove(key, delta) },
            )
        }
    }
}

@Composable
private fun OrderedPickerRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    accent: Color,
    canUncheck: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggle: () -> Unit,
    onMove: (Int) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            // Tapping anywhere on the row is the toggle; the row goes inert only
            // when this is the last thing left switched on.
            .clickable(enabled = !checked || canUncheck) { onToggle() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // The tick box. 22dp of mark inside a row that is 40dp tall, so the whole
        // row is the target rather than the box.
        Box(
            Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (checked) accent else Color.Transparent)
                .border(1.dp, if (checked) accent else Hairline, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(Icons.Default.Check, null, tint = Ink, modifier = Modifier.size(15.dp))
        }

        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (checked) TextPrimary else TextMuted,
                fontFamily = AppFont,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    color = TextFaint,
                    fontFamily = MonoFont,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
        }

        MoveButton(Icons.Default.KeyboardArrowUp, "Move up", canMoveUp) { onMove(-1) }
        MoveButton(Icons.Default.KeyboardArrowDown, "Move down", canMoveDown) { onMove(1) }
    }
}

@Composable
private fun MoveButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (enabled) Glass else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            description,
            tint = if (enabled) TextSecondary else TextFaint.a(0.4f),
            modifier = Modifier.size(20.dp),
        )
    }
}

