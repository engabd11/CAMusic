package com.engabd.sendpin.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engabd.sendpin.ma.LibraryViewModel.Backend
import com.engabd.sendpin.ui.design.*
import com.engabd.sendpin.ui.theme.*
import com.engabd.sendpin.library.ServerConfig
import com.engabd.sendpin.library.ServerKind

/**
 * The library's chrome: the title row, the search field and the small controls
 * beside them.
 *
 * Split out of `LibraryScreen.kt` — see the note at the top of that file. This half
 * is everything above the grid, and it changes for entirely different reasons from
 * the grid itself: a search affordance moves because search changed, not because a
 * tile did.
 */

@Composable
internal fun Header(
    title: String,
    showBack: Boolean,
    backend: Backend,
    activeServerConfig: ServerConfig?,
    query: String,
    onQuery: (String) -> Unit,
    onBack: () -> Unit,
    onSearch: (String) -> Unit,
    onSonicSearch: (String) -> Unit,
    onClearSearch: () -> Unit,
    /** Whether the search field holds focus — the screen's back handler needs to know. */
    onSearchFocus: (Boolean) -> Unit = {},
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    searching: Boolean = false,
    /** Callback when the library badge is clicked — opens library switch overlay. */
    onLibraryBadgeClick: (() -> Unit)? = null,
) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextSecondary,
                    modifier = Modifier.size(24.dp).clip(CircleShape).clickable(onClick = onBack),
                )
                Spacer(Modifier.width(12.dp))
            }
            // Title and backend tag share the slack inside their own row, so the tag
            // keeps hugging the title and Refresh still lands in the corner. Giving
            // the title and a spacer a weight each in the outer row would have capped
            // a long node title at half the width to make room for whitespace.
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title, color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.ExtraBold,
                    fontSize = 26.sp, letterSpacing = (-0.5).sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(10.dp))
                // Which backend is on is a *setting* now, not a control that lives
                // here — two places to change it meant the greyed-out Speakers and
                // Lights tabs could disagree with what the library was browsing.
                // Made clickable and larger: tap to switch libraries without going to Settings.
                BackendTag(
                    kind = activeServerConfig?.kind,
                    fallbackIcon = if (backend == Backend.SUBSONIC) Icons.Default.LibraryMusic else Icons.Default.Speaker,
                    label = activeServerConfig?.displayName ?: if (backend == Backend.SUBSONIC) "Library" else "Music Assistant",
                    onClick = onLibraryBadgeClick,
                )
            }
            onRefresh?.let {
                Spacer(Modifier.width(10.dp))
                RefreshButton(refreshing = refreshing, onClick = it)
            }
        }
        Spacer(Modifier.height(12.dp))
        SearchField(query, onQuery, onSearch, onClearSearch, searching, onSearchFocus)
        // Sonic search: the same box, read as a description of a *sound* rather
        // than a name. Finding music belongs in the library, not behind the
        // player's queue button.
        if (query.isNotBlank() && backend == Backend.MA) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallChip(Icons.Default.GraphicEq, "Sounds like this") { onSonicSearch(query) }
            }
        }
    }
}

/**
 * Re-read the library from the server.
 *
 * Spins while the fetch is in flight and ignores taps until it lands, so a user who
 * doesn't see an instant change can't stack up half a dozen identical requests.
 */
@Composable
private fun RefreshButton(refreshing: Boolean, onClick: () -> Unit) {
    // Upright and still when motion is off. Compose *suspends* an InfiniteTransition at
    // a duration scale of 0 rather than ending it, so without this the icon freezes at
    // whatever angle it had reached — a permanently crooked refresh glyph, which reads
    // as a broken screen rather than as a disabled animation. See LocalReducedMotion.
    val reducedMotion = LocalReducedMotion.current
    val spin = rememberInfiniteTransition(label = "refresh")
    val spinAngle by spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(Motion.SPINNER_PERIOD_MS, easing = LinearEasing)),
        label = "refreshAngle",
    )
    val angle = if (reducedMotion) 0f else spinAngle
    Icon(
        Icons.Default.Refresh,
        contentDescription = "Refresh library",
        tint = if (refreshing) LocalAccent.current else TextSecondary,
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .clickable(enabled = !refreshing, onClick = onClick)
            .padding(6.dp)
            .graphicsLayer { rotationZ = if (refreshing) angle else 0f },
    )
}

/**
 * A quiet read-only badge naming the library backend Settings has selected.
 *
 * [kind]'s real logo is used when there is one — the same mark `LibrariesSettings`'
 * provider picker uses (see `ServerKindGlyph`), so a Jellyfin library reads as "the
 * Jellyfin one" here too, not just in Settings where it was chosen. [kind] is null
 * when there is no [ServerConfig] to read one from yet, in which case [fallbackIcon]
 * draws instead — the same edge case [label] already falls back for.
 */
@Composable
private fun BackendTag(kind: ServerKind?, fallbackIcon: ImageVector, label: String, onClick: (() -> Unit)? = null) {
    val hasClick = onClick != null
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(100))
            .background(if (hasClick) LocalAccent.current.copy(alpha = 0.15f) else Glass)
            .border(1.dp, if (hasClick) LocalAccent.current.copy(alpha = 0.4f) else HairlineSoft, RoundedCornerShape(100))
            .then(if (hasClick) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        val tint = if (hasClick) TextPrimary else TextFaint
        if (kind != null) {
            ServerKindGlyph(kind, tint = tint, modifier = Modifier.size(12.dp))
        } else {
            Icon(fallbackIcon, null, tint = tint, modifier = Modifier.size(12.dp))
        }
        Text(
            label, color = if (hasClick) TextPrimary else TextFaint, fontFamily = AppFont, fontWeight = FontWeight.Bold,
            fontSize = 11.sp, letterSpacing = 0.6.sp, maxLines = 1,
        )
    }
}

/** A compact labelled action chip. */
@Composable
private fun SmallChip(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(100)).background(Glass)
            .border(1.dp, Hairline, RoundedCornerShape(100))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(icon, null, tint = LocalAccent.current, modifier = Modifier.size(14.dp))
        Text(label, color = TextSecondary, fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 11.sp)
    }
}

@Composable
private fun SearchField(
    query: String,
    onQuery: (String) -> Unit,
    onSearch: (String) -> Unit,
    onClear: () -> Unit,
    searching: Boolean = false,
    onFocus: (Boolean) -> Unit = {},
) {
    val focus = LocalFocusManager.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(inkOn(0.045f))
            .border(1.dp, Hairline, RoundedCornerShape(13.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // The magnifier becomes the progress indicator while a query is in flight.
        // Results now arrive as the user types, so the "working" signal has to sit in
        // the field itself — anything over the list would flash on every keystroke,
        // and the list is deliberately left showing the previous answer until the new
        // one lands.
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            if (searching) {
                CircularProgressIndicator(
                    color = LocalAccent.current,
                    strokeWidth = 1.5.dp,
                    modifier = Modifier.size(14.dp),
                )
            } else {
                Icon(Icons.Default.Search, null, tint = TextMuted, modifier = Modifier.size(16.dp))
            }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(
                    "Search artists, albums, tracks…", color = TextFaint,
                    fontFamily = AppFont, fontSize = 14.sp, maxLines = 1,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = { onQuery(it); if (it.isBlank()) onClear() },
                singleLine = true,
                textStyle = TextStyle(color = TextPrimary, fontFamily = AppFont, fontSize = 14.sp),
                cursorBrush = SolidColor(LocalAccent.current),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch(query); focus.clearFocus() }),
                // Reported upward, not acted on here: the screen's back handler is what
                // needs it, so that putting the keyboard away stops short of leaving
                // the search and taking the typed query with it. Also cleared on
                // dispose — a field that leaves composition while focused would
                // otherwise leave the screen believing the keyboard is still up.
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { onFocus(it.isFocused) },
            )
            DisposableEffect(Unit) { onDispose { onFocus(false) } }
        }
        if (query.isNotEmpty()) {
            Icon(
                Icons.Default.Close, "Clear", tint = TextMuted,
                modifier = Modifier.size(16.dp).clip(CircleShape)
                    .clickable { onClear(); focus.clearFocus() },
            )
        }
    }
}

// --- browse ---------------------------------------------------------------
