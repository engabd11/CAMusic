package com.engabd.sendpin.widget

import android.content.Context
import androidx.compose.runtime.Composable
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
 * ## Why it reads state directly rather than being pushed to
 *
 * `provideContent` runs whenever the system asks the widget to compose, and the
 * process is already alive for anything that could change what it shows —
 * [com.engabd.sendpin.service.SendspinConnectionService] keeps it that way. So the
 * widget reads the current answer at compose time and is nudged by [refresh] when
 * that answer changes, rather than maintaining a second copy of the player state in
 * widget storage that could go stale in its own way.
 */
class NowPlayingWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { WidgetBody() }
    }

    @Composable
    private fun WidgetBody() {
        val app = SendpinApp.instance
        val owner = app.playbackOwner.state.value
        val isLocal = owner.sessionOwner == PlaybackOwner.Who.LOCAL
        val maNow = app.maNowPlaying.now.value
        val localTrack = app.localPlayer.current.value

        val title = when {
            isLocal -> localTrack?.title.orEmpty()
            else -> maNow?.title.orEmpty()
        }
        val subtitle = when {
            isLocal -> localTrack?.artist.orEmpty()
            else -> maNow?.artist.orEmpty()
        }
        val playing = if (isLocal) app.localPlayer.playing.value else owner.sendspinPlaying ||
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
         * Called after a transport action rather than on every playback event: a
         * widget update is a binder round trip through the launcher, and doing one
         * per position tick would be a cost paid sixty times a minute for a surface
         * showing a title.
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
