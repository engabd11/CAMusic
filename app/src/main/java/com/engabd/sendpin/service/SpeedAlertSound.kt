package com.engabd.sendpin.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
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
 *
 * ## Why it has to take audio focus
 *
 * This used to play on `STREAM_NOTIFICATION` and ask for nothing. Both halves of
 * that are wrong in a car, and together they are why the alert could fire with
 * music playing and never be heard:
 *
 *  * **Nothing ducked.** No focus request means no player anywhere — this app's own
 *    ExoPlayer, the Music Assistant engine, or another app entirely — is ever told
 *    to get out of the way, so a short tone arrived underneath a track at full
 *    volume and was simply lost in it.
 *  * **It was on the wrong route.** The notification stream is not the media route.
 *    Over a car's Bluetooth link, with music holding the A2DP stream, a
 *    notification-stream sound is at the mercy of the phone and the head unit: it is
 *    commonly attenuated to nothing, or played out of the phone's own speaker in the
 *    driver's pocket, or dropped.
 *
 * So the alert asks for `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` with
 * `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` — the same request a satnav's "turn left"
 * makes, which is exactly what this is and exactly the request every player and
 * every head unit already knows how to answer by turning the music down for a
 * moment. The clip itself then plays with those same attributes, so it goes out the
 * route the music is on and the driver hears it in the car rather than in their
 * pocket. `Playback`'s own announcement path made the same move for the same reason;
 * see `requestAudioFocus` there.
 *
 * Focus is held for the length of the sound and a short tail, then given back, which
 * is what brings the music up again.
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
     * What the alert sounds like to the rest of the system: a navigation-style
     * announcement, not a notification and not media.
     */
    private val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val handler = Handler(Looper.getMainLooper())

    /** Held for as long as a sound is playing, and only then. Guarded by `this`. */
    private var focusRequest: AudioFocusRequest? = null
    private var player: MediaPlayer? = null
    private var tone: ToneGenerator? = null

    /**
     * A focus listener is required for a request to be granted at all, and this one
     * has nothing to do: the sound is a fraction of a second long, and pausing it
     * partway through because something else spoke first would leave the driver with
     * half a warning. It is here so the platform has somewhere to deliver to.
     */
    private val focusListener = AudioManager.OnAudioFocusChangeListener { }

    /**
     * Play [id]'s sound once, ducking whatever is playing for its duration.
     *
     * Self-contained — a caller (the real alert, or the settings screen's preview
     * button) needs to hold nothing before or after this call — and safe to call
     * again while one is still sounding: the second supersedes the first rather than
     * stacking a second focus request on top of it.
     */
    @Synchronized
    fun play(context: Context, id: String) {
        val app = context.applicationContext
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return

        // Whatever is still going belongs to a previous alert. Tear it down first, so
        // there is one sound and one focus request at a time.
        release()
        handler.removeCallbacksAndMessages(null)

        requestFocus(audio)

        val resId = OPTIONS.firstOrNull { it.id == id }?.resId
        val playedFor = if (resId == null) playTone() else playClip(app, resId)
        if (playedFor == null) {
            // Nothing is going to sound, so nothing should be holding the music down.
            abandonFocus(audio)
            return
        }
        // The tail is what stops the music snapping back up over the last of the
        // beep. A clip releases focus from its own completion callback instead; this
        // is the backstop for a callback that never arrives.
        handler.postDelayed({ synchronized(this) { release(); abandonFocus(audio) } }, playedFor + FOCUS_TAIL_MS)
    }

    /** @return how long the sound will run for, in ms, or null if it could not start. */
    private fun playTone(): Long? {
        val tg = runCatching {
            // STREAM_MUSIC, not STREAM_NOTIFICATION: the music route is the one
            // reaching the car, and the ducking focus held above is what makes room
            // on it. See the class doc.
            ToneGenerator(AudioManager.STREAM_MUSIC, TONE_VOLUME)
        }.getOrNull() ?: return null
        tone = tg
        val ok = runCatching { tg.startTone(ToneGenerator.TONE_PROP_BEEP, TONE_DURATION_MS) }.getOrDefault(false)
        if (!ok) { tone = null; runCatching { tg.release() }; return null }
        return TONE_DURATION_MS.toLong()
    }

    private fun playClip(context: Context, @RawRes resId: Int): Long? {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val session = audio?.generateAudioSessionId() ?: AudioManager.AUDIO_SESSION_ID_GENERATE
        // The four-argument create is the whole point: the two-argument one builds a
        // MediaPlayer with default (media) attributes, which is neither an
        // announcement nor duckable by anything.
        val mp = runCatching { MediaPlayer.create(context, resId, ATTRIBUTES, session) }.getOrNull()
            ?: return null
        player = mp
        mp.setOnCompletionListener {
            synchronized(this) {
                // A short hold past the end, then hand the route back. Same tail as
                // the timeout above, and whichever gets there first wins — both paths
                // are idempotent.
                handler.postDelayed({
                    synchronized(this) {
                        release()
                        (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.let(::abandonFocus)
                    }
                }, FOCUS_TAIL_MS)
            }
        }
        mp.setOnErrorListener { _, _, _ -> true }
        val started = runCatching { mp.start(); true }.getOrDefault(false)
        if (!started) { release(); return null }
        // `duration` is -1 for a clip whose length the decoder has not worked out.
        // The backstop below *releases* the player, so guessing short would cut the
        // warning off; the completion callback is what normally ends this.
        val reported = runCatching { mp.duration }.getOrDefault(-1)
        return (if (reported > 0) reported.toLong() else DEFAULT_CLIP_MS).coerceAtMost(MAX_CLIP_MS)
    }

    private fun requestFocus(audio: AudioManager) {
        if (focusRequest != null) return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(ATTRIBUTES)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener(focusListener, handler)
            .build()
        // The sound plays either way. A denial means something is holding exclusive
        // focus — a phone call — and in that case the music is already down; what
        // must not happen is the alert going silent because focus was refused.
        runCatching { audio.requestAudioFocus(req) }
        focusRequest = req
    }

    private fun abandonFocus(audio: AudioManager) {
        val req = focusRequest ?: return
        focusRequest = null
        runCatching { audio.abandonAudioFocusRequest(req) }
    }

    /** Drop whatever is currently making noise. Idempotent. */
    private fun release() {
        player?.let { mp -> runCatching { mp.setOnCompletionListener(null); mp.reset(); mp.release() } }
        player = null
        tone?.let { tg -> runCatching { tg.release() } }
        tone = null
    }

    private const val TONE_VOLUME = 85 // 0..100 of the media stream, under a ducked track
    private const val TONE_DURATION_MS = 260

    /**
     * How long focus is held past the end of the sound.
     *
     * Without it the music comes back up while the tail of the beep is still in the
     * air, and the two collide on exactly the sound the driver is meant to notice.
     */
    private const val FOCUS_TAIL_MS = 400L

    /** Assumed length of a clip that will not report one. Generous on purpose. */
    private const val DEFAULT_CLIP_MS = 4_000L

    /** A clip this long has gone wrong; the timeout is a backstop, not a duration. */
    private const val MAX_CLIP_MS = 10_000L
}
