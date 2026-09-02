package com.engabd.sendpin.audio

import android.content.Context
import android.media.AudioDeviceInfo
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.engabd.sendpin.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The second deck: a throwaway player that carries the *outgoing* track's last few
 * seconds while the main player has already moved on to the next one.
 *
 * ## Why there has to be a second player at all
 *
 * [LocalPlayer] is one `ExoPlayer` with one output, so two tracks cannot overlap
 * through it — which is why the fade it has always had is a *sequential* one: down
 * at the end of a track, up at the start of the next. That is honest and it is also
 * the thing DJ Radio cannot use, because for the length of the fade the room is at
 * or near silence, and "no silent moments between songs" is the entire promise.
 * [BeatAlignedFade] made the seam land on a beat, which helped; it could not make
 * the seam stop being a hole.
 *
 * ## The trick
 *
 * The obvious arrangement — start the *next* track early on a second player — is the
 * one that cannot work here, because the main player owns the media session, the
 * notification, the Light Sync tap and every DSP stage. The incoming track has to be
 * on the main player or half the app follows the wrong song.
 *
 * So the decks are the other way round. At the crossfade point:
 *
 *  1. This deck, already rolling silently on the *same* track at the *same*
 *     position, comes up to full.
 *  2. The main player skips to the next track and ramps up from zero.
 *  3. This deck ramps down over the same window and releases itself.
 *
 * The listener hears one continuous piece of music: the tail of the old track under
 * the head of the new one. The main player, the session, the tap and the lights all
 * follow the incoming track from the moment the transition starts, which is right —
 * that is the song the listener is arriving at.
 *
 * ## The pre-roll, and why it is not optional
 *
 * The deck is armed [PREROLL_MS] before the swap and starts playing *at volume zero*,
 * seeked to wherever the main player is. Starting it at the swap instead would mean
 * paying decoder and `AudioTrack` start latency at the exact moment the outgoing
 * track is supposed to be at full volume — a hole a hundred-odd milliseconds wide,
 * in the one place this whole class exists to remove one.
 *
 * Rolling early costs a drift instead: two independent decoders on one file do not
 * agree to the sample. [align] takes one measurement of that drift and, if it is
 * large enough to matter, spends a single seek on it while the deck is still silent
 * — so the hand-over is a soft edit inside the outgoing track rather than a stutter
 * in it.
 *
 * ## What the tail gives up
 *
 * This deck decodes straight to the mixer: no equaliser, no Lo-fi, no vinyl noise,
 * and no analysis tap. Rebuilding the whole signal path for four seconds of a song
 * that is already fading out would double the DSP cost of every transition for an
 * effect nobody can pick out under an incoming track. The one thing it does honour
 * is ReplayGain, because that is a *level*, and getting the level wrong is audible
 * immediately — see [start]'s `gain`.
 */
@OptIn(UnstableApi::class)
class CrossfadeDeck(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    private var player: ExoPlayer? = null
    private var ramp: Job? = null

    /** True from [start] until the fade finishes or something cancels it. */
    val active: Boolean get() = player != null

    /**
     * Roll the tail of [source] silently from [positionMs], ready to be brought up
     * by [handOver].
     *
     * [gain] is the level the tail should reach at full — the main player's own
     * ReplayGain factor for the outgoing track, times the user's volume. Passed in
     * rather than worked out here because the deck has no idea which track it is
     * carrying beyond a URL.
     *
     * Returns false if the deck could not be built, in which case the caller must
     * fall back to a plain sequential fade rather than skipping into silence.
     */
    fun start(
        source: String,
        positionMs: Long,
        gain: Float,
        device: AudioDeviceInfo?,
        speed: Float,
    ): Boolean {
        cancel()
        val built = runCatching { build(device, speed) }.getOrNull() ?: return false
        player = built
        return runCatching {
            built.setMediaItem(MediaItem.fromUri(source))
            built.volume = 0f
            built.seekTo(positionMs.coerceAtLeast(0))
            built.prepare()
            built.play()
            targetGain = gain.coerceIn(0f, 1f)
            true
        }.getOrElse {
            cancel()
            false
        }
    }

    /** Where [handOver] will start the tail's ramp from. */
    private var targetGain: Float = 1f

    /** [align] has already spent its one correction on this deck. */
    private var aligned = false

    /**
     * Nudge the deck toward [mainPositionMs] while it is still silent.
     *
     * Called each tick of the pre-roll. Two decoders started at different moments on
     * the same file drift by however long each took to fill its first buffer, and a
     * jump-cut of a couple of hundred milliseconds inside the outgoing track *is*
     * audible at full volume — a stutter or a swallowed word, right where the mix is
     * supposed to be seamless. A seek here costs nothing audible: the deck is at
     * volume zero, so the correction lands in silence.
     *
     * **Once, and only if the drift is worth it.** The obvious version — correct
     * on every tick until the two agree — never converges and makes things worse:
     * a seek stalls the deck for about as long as the drift it was correcting, so
     * the next tick measures the same gap again and seeks again, and the deck
     * spends the whole pre-roll re-buffering to arrive further behind than if it
     * had been left alone. One correction takes out a bad start; the residue is
     * tens of milliseconds inside a track that is about to be faded out under
     * another one, which is not a thing anybody can hear.
     */
    fun align(mainPositionMs: Long) {
        val p = player ?: return
        if (aligned || ramp != null) return   // hand-over begun: the tail is its own song now
        val drift = mainPositionMs - runCatching { p.currentPosition }.getOrDefault(mainPositionMs)
        if (abs(drift) > ALIGN_TOLERANCE_MS) {
            aligned = true
            runCatching { p.seekTo(mainPositionMs.coerceAtLeast(0)) }
        }
    }

    /**
     * Bring the tail up and take it down again over [seconds], then release.
     *
     * The curve is equal *power* — `cos` here against the main player's `sin` — not
     * equal amplitude. Two uncorrelated pieces of music summed at half amplitude each
     * are audibly quieter than either at full, which is the dip in the middle that
     * makes a linear crossfade sound like a fade to nothing and back; squares that
     * sum to one is the standard fix and the one a mixing desk uses.
     */
    fun handOver(seconds: Float) {
        val p = player ?: return
        val window = seconds.coerceIn(0.5f, MAX_WINDOW_S)
        ramp?.cancel()
        ramp = scope.launch {
            val steps = (window * 1000f / RAMP_TICK_MS).toInt().coerceAtLeast(1)
            for (i in 0..steps) {
                if (!isActive) break
                val x = i.toFloat() / steps
                runCatching { p.volume = targetGain * cos(x * HALF_PI) }
                delay(RAMP_TICK_MS)
            }
            // Not [cancel]: that cancels `ramp`, which is this coroutine, and a
            // method that ends by cancelling its own caller only works by accident
            // of where the suspension points happen to be. Release the deck
            // directly, and only if it is still the one this ramp was driving —
            // a skip during the fade may already have replaced it.
            if (player === p) {
                runCatching { p.stop(); p.release() }
                player = null
                ramp = null
            }
        }
    }

    /** Drop the deck at once — a skip, a pause, a seek, or the fade having finished. */
    fun cancel() {
        ramp?.cancel()
        ramp = null
        aligned = false
        player?.let { p -> runCatching { p.stop(); p.release() } }
        player = null
    }

    private fun build(device: AudioDeviceInfo?, speed: Float): ExoPlayer {
        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        // A few seconds of one track, and the last few at that. The main player's
        // 30-second minimum buffer would have this deck sit there filling a buffer
        // it will never reach the end of, and `bufferForPlaybackMs` is what decides
        // how long the pre-roll takes to actually make sound — the one number here
        // that has to be small.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 4_000,
                /* maxBufferMs = */ 20_000,
                /* bufferForPlaybackMs = */ 250,
                /* bufferForPlaybackAfterRebufferMs = */ 500,
            )
            .build()
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        return ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(DefaultDataSource.Factory(context, httpFactory)),
            )
            // handleAudioFocus = false, deliberately. The main player already holds
            // focus for this playback; a second request from the same process is the
            // in-app focus fight [PlaybackOwner] exists to prevent, and it would
            // arrive at the worst possible moment — mid-transition.
            .setAudioAttributes(attrs, /* handleAudioFocus = */ false)
            .build()
            .also { p ->
                p.setSeekParameters(SeekParameters.EXACT)
                // The tail has to run at whatever the main player is running at, or
                // Lo-fi's slow-down (or a podcast speed) makes the two copies walk
                // apart during the pre-roll faster than [align] can pull them back.
                runCatching { p.setPlaybackSpeed(speed) }
                device?.let { d -> runCatching { p.setPreferredAudioDevice(d) } }
            }
    }

    companion object {
        /**
         * How long the deck rolls silently before the swap.
         *
         * Long enough to cover a cold decoder and one [align] correction; short
         * enough that a listener skipping tracks quickly does not leave a string of
         * these behind. Two seconds is roughly four ticks of [LocalPlayer]'s
         * position loop.
         */
        const val PREROLL_MS = 2_000L

        /**
         * Drift worth spending [align]'s one correction on.
         *
         * A fifth of a second is where a jump inside the outgoing track stops being
         * a soft edit and starts being a stutter. Below it, correcting costs more
         * than it buys — see [align].
         */
        const val ALIGN_TOLERANCE_MS = 200L

        /** How often the fade-out steps. 40 ms is 25 steps a second — inaudibly smooth. */
        private const val RAMP_TICK_MS = 40L

        private const val MAX_WINDOW_S = 15f

        private const val HALF_PI = (Math.PI / 2).toFloat()

        private val USER_AGENT: String = "CAMusic/${BuildConfig.VERSION_NAME} (Android)"
    }
}

/**
 * When a DJ Radio crossfade should start, and how loud each side of it should be.
 *
 * Split out from [CrossfadeDeck] because it is arithmetic and arithmetic can be
 * tested: the deck needs an Android `Context` and a live decoder, and none of the
 * decisions below need either.
 */
object CrossfadeSchedule {

    /**
     * Whether a track running [positionMs] into [durationMs] should be handed over
     * now, for a [seconds]-long crossfade.
     *
     * A track shorter than three windows is left alone: at four seconds of fade a
     * ten-second interlude would spend most of its life in a transition, and the
     * same guard already governs the sequential fade in [LocalPlayer.fadeAt].
     */
    fun shouldHandOver(positionMs: Long, durationMs: Long, seconds: Int): Boolean {
        if (seconds <= 0 || durationMs <= 0) return false
        val window = seconds * 1000L
        if (durationMs < window * 3) return false
        val remaining = durationMs - positionMs
        return remaining in 0..window
    }

    /** Whether the tail deck should be rolled up now, ahead of [shouldHandOver]. */
    fun shouldArm(positionMs: Long, durationMs: Long, seconds: Int): Boolean {
        if (seconds <= 0 || durationMs <= 0) return false
        val window = seconds * 1000L
        if (durationMs < window * 3) return false
        val remaining = durationMs - positionMs
        return remaining in 0..(window + CrossfadeDeck.PREROLL_MS)
    }

    /**
     * The incoming track's level [positionMs] into it, for a [seconds] crossfade.
     *
     * `sin` against the tail's `cos`, so the two sum to constant power — see
     * [CrossfadeDeck.handOver]. Unlike the sequential fade this has no fade-*out*
     * half at all: the outgoing track is a different player's problem now, and the
     * main player leaves its track early rather than riding it down.
     */
    fun fadeInAt(positionMs: Long, seconds: Int): Float {
        if (seconds <= 0) return 1f
        val window = seconds * 1000f
        if (positionMs >= window) return 1f
        val x = (positionMs.coerceAtLeast(0) / window).coerceIn(0f, 1f)
        return sin(x * (Math.PI / 2).toFloat()).coerceIn(0f, 1f)
    }
}
