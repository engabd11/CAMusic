package com.engabd.sendpin.service

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import androidx.annotation.RawRes
import com.engabd.sendpin.R
import com.engabd.sendpin.data.AppSettings

/**
 * The catalogue of sounds [SpeedMonitor]'s alert can play, and how to play one.
 *
 * One place for this rather than two, because the settings screen needs the exact
 * same "play this id" behaviour for its preview button that [SpeedMonitor] needs
 * for the real alert — duplicating a `MediaPlayer`/`ToneGenerator` dance in both
 * places is how they'd quietly drift apart.
 */
object SpeedAlertSound {

    /** The original synthesised beep — no bundled clip behind it. */
    const val TONE = AppSettings.SPEED_ALERT_TONE

    data class Option(val id: String, val label: String, @RawRes val resId: Int?)

    /** In picker order. [TONE] first, since it was the only sound before this existed. */
    val OPTIONS = listOf(
        Option(TONE, "Classic beep", null),
        Option("notification1", "Notification 1", R.raw.speed_alert_notification_1),
        Option("notification2", "Notification 2", R.raw.speed_alert_notification_2),
        Option("notification3", "Notification 3", R.raw.speed_alert_notification_3),
        Option("notification4", "Notification 4", R.raw.speed_alert_notification_4),
    )

    /** [OPTIONS]' label for [id], or the [TONE] label if [id] names nothing here. */
    fun labelFor(id: String): String = OPTIONS.firstOrNull { it.id == id }?.label ?: OPTIONS.first().label

    /**
     * Play [id]'s sound once, on the notification stream, and release whatever it
     * built once playback ends. Self-contained — a caller (the real alert, or the
     * settings screen's preview button) needs to hold nothing before or after this
     * call.
     */
    fun play(context: Context, id: String) {
        val resId = OPTIONS.firstOrNull { it.id == id }?.resId
        if (resId == null) {
            playTone()
        } else {
            playClip(context, resId)
        }
    }

    /** A single short, gentle tone — not an alarm. See the plan's own safety note. */
    private fun playTone() {
        val tg = runCatching {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, TONE_VOLUME)
        }.getOrNull() ?: return
        runCatching { tg.startTone(ToneGenerator.TONE_PROP_BEEP, TONE_DURATION_MS) }
        // ToneGenerator has no completion callback, so release it once the tone has
        // had time to finish rather than leaking it for the process lifetime.
        Handler(Looper.getMainLooper()).postDelayed({ tg.release() }, TONE_DURATION_MS.toLong() + 100)
    }

    private fun playClip(context: Context, @RawRes resId: Int) {
        runCatching {
            MediaPlayer.create(context, resId)?.apply {
                setOnCompletionListener { it.release() }
                setOnErrorListener { mp, _, _ -> mp.release(); true }
                start()
            }
        }
    }

    private const val TONE_VOLUME = 60 // 0..100, a notification-level tone, not a klaxon
    private const val TONE_DURATION_MS = 200
}
