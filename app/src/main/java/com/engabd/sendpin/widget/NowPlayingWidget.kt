package com.engabd.sendpin.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.Image
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.updateAll
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.engabd.sendpin.R
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.service.PlaybackOwner
import com.engabd.sendpin.ui.theme.DefaultAccent
import com.engabd.sendpin.ui.theme.Ink
import com.engabd.sendpin.ui.theme.Ink2
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextPrimary

/**
 * What's playing, on the home screen.
 *
 * The one surface in the app that costs nothing to look at: no unlock, no launch, no
 * tab. Deliberately the *same three controls* as the driving bar and the mini player,
 * routed through the same [PlaybackOwner] — a widget that addressed the wrong player
 * would be the mini bar's old bug on a surface with no way to see what it was doing.
 *
 * Glance rather than hand-built `RemoteViews`: the layout is a row of controls beside
 * a title, which RemoteViews can express only with a lot of XML and cannot share a
 * line of state-reading code with the rest of the app.
 *
 * ## Why it collects rather than reading a snapshot
 *
 * `provideGlance` suspends for as long as the widget has a session, and Glance keeps
 * the composition alive across it — so collecting the player flows here is not a
 * convenience, it is what makes the widget *live*: a track change repaints it with
 * no push from anywhere.
 *
 * These flows are all state-shaped rather than tick-shaped — `PlaybackOwner.state` is
 * `distinctUntilChanged` and the rest change on a track boundary or a play/pause —
 * so a repaint per emission is a handful per song, not the per-position-tick cost
 * that would make a live widget expensive. The playhead is deliberately not shown,
 * and that is most of why this is affordable.
 *
 * Reading `StateFlow.value` here instead would be a snapshot Compose cannot observe:
 * the widget would show whatever was true when the system last happened to ask.
 * Android lint says so directly (`StateFlowValueCalledInComposition`), and it is
 * right — the first version of this file did exactly that and was wrong for the
 * reason lint gives.
 */
class NowPlayingWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { WidgetBody() }
    }

    @Composable
    private fun WidgetBody() {
        val app = SendpinApp.instance
        val owner by app.playbackOwner.state.collectAsState()
        val isLocal = owner.sessionOwner == PlaybackOwner.Who.LOCAL
        val maNow by app.maNowPlaying.now.collectAsState()
        val localTrack by app.localPlayer.current.collectAsState()
        val localPlaying by app.localPlayer.playing.collectAsState()

        val title = when {
            isLocal -> localTrack?.title.orEmpty()
            else -> maNow?.title.orEmpty()
        }
        val subtitle = when {
            isLocal -> localTrack?.artist.orEmpty()
            else -> maNow?.artist.orEmpty()
        }
        val playing = if (isLocal) localPlaying else owner.sendspinPlaying ||
            maNow?.isPlaying == true

        Column(
            GlanceModifier
                .fillMaxSize()
                .background(ColorProvider(Ink))
                .cornerRadius(20.dp)
                .padding(14.dp),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            Text(
                title.ifBlank { "Nothing playing" },
                style = TextStyle(
                    color = ColorProvider(if (title.isBlank()) TextMuted else TextPrimary),
                    fontWeight = FontWeight.Bold,
                    fontSize = androidx.compose.ui.unit.TextUnit(15f, androidx.compose.ui.unit.TextUnitType.Sp),
                ),
                maxLines = 1,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = TextStyle(
                        color = ColorProvider(TextMuted),
                        fontSize = androidx.compose.ui.unit.TextUnit(12f, androidx.compose.ui.unit.TextUnitType.Sp),
                    ),
                    maxLines = 1,
                )
            }
            Spacer(GlanceModifier.size(10.dp))
            Row(
                GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.Vertical.CenterVertically,
            ) {
                WidgetButton(R.drawable.ic_driving_prev, "Previous", PreviousAction::class.java)
                Spacer(GlanceModifier.width(8.dp))
                WidgetButton(
                    // One glyph for both, as the PiP action uses: a widget is
                    // recomposed on a schedule the system owns, so a play triangle
                    // can be a second or two out of date, and a control that lies
                    // about the current state is worse than one that names the action.
                    R.drawable.ic_driving_play_pause,
                    if (playing) "Pause" else "Play",
                    PlayPauseAction::class.java,
                    accent = true,
                )
                Spacer(GlanceModifier.width(8.dp))
                WidgetButton(R.drawable.ic_driving_next, "Next", NextAction::class.java)
            }
        }
    }

    @Composable
    private fun WidgetButton(
        iconRes: Int,
        description: String,
        action: Class<out ActionCallback>,
        accent: Boolean = false,
    ) {
        Image(
            provider = ImageProvider(iconRes),
            contentDescription = description,
            colorFilter = androidx.glance.ColorFilter.tint(
                ColorProvider(if (accent) DefaultAccent else TextPrimary),
            ),
            modifier = GlanceModifier
                .size(44.dp)
                .background(ColorProvider(Ink2))
                .cornerRadius(22.dp)
                .padding(10.dp)
                .clickable(actionRunCallback(action)),
        )
    }

    companion object {
        /**
         * Repaint every placed instance.
         *
         * Belt and braces beside the live collection in [WidgetBody]. That covers a
         * widget whose session is active; an action callback runs in a worker, and
         * whether a session is active at that moment is the launcher's business
         * rather than ours. A redundant update costs one binder round trip; a
         * missing one leaves the bar showing the previous track after the user has
         * just pressed next on it, which is the single most obvious way for a widget
         * to look broken.
         */
        suspend fun refresh(context: Context) {
            runCatching {
                NowPlayingWidget().updateAll(context)
            }
        }
    }
}

/** The manifest entry point. Glance's receiver does the rest. */
class NowPlayingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NowPlayingWidget()
}

// ── Actions ──────────────────────────────────────────────────────────────
//
// Each routes through PlaybackOwner and then repaints. Three classes rather than one
// with a parameter because `actionRunCallback` identifies the callback by its class,
// and a shared class would need its parameter carried in ActionParameters — more
// moving parts than three four-line classes.

class PlayPauseAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: androidx.glance.action.ActionParameters,
    ) {
        SendpinApp.instance.playbackOwner.playPause()
        NowPlayingWidget.refresh(context)
    }
}

class NextAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: androidx.glance.action.ActionParameters,
    ) {
        SendpinApp.instance.playbackOwner.next()
        NowPlayingWidget.refresh(context)
    }
}

class PreviousAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: androidx.glance.action.ActionParameters,
    ) {
        SendpinApp.instance.playbackOwner.previous()
        NowPlayingWidget.refresh(context)
    }
}
