package com.engabd.sendpin.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
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
 * The deck is armed seconds before the swap (see [SmartCrossfade.prerollFor]) and
 * starts playing *at volume zero*, seeked to wherever the main player is. Starting it
 * at the swap instead would mean paying decoder and `AudioTrack` start latency at the
 * exact moment the outgoing track is supposed to be at full volume — a hole a
 * hundred-odd milliseconds wide, in the one place this whole class exists to remove
 * one. And a head start is not a guarantee, which is what [ready] is for: the swap
 * waits on the deck genuinely making sound rather than on a stopwatch.
 *
 * Rolling early costs a drift instead: two independent decoders on one file do not
 * agree to the sample, and this one started late by however long it took to open.
 * [align] measures that drift once the deck is actually making sound and runs the
 * deck a little fast or slow, while it is still silent, until the two agree — so
 * the hand-over is a clean switch between two copies of the same moment rather
 * than a stutter, or worse, the last couple of seconds of the song played twice.
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
     * The deck is actually making sound, not still opening a connection.
     *
     * The gate on the whole hand-over, and the fix for the failure that made a
     * crossfade sound like a cut: [start] returning true only means a player was
     * built and told to play. A track streamed from a server across the house can
     * still be filling its first buffer seconds later, and swapping to a deck in
     * that state left the outgoing side silent for the length of the mix — a cut,
     * plus a slow fade-in on the new track, which is precisely what was reported.
     *
     * `isPlaying` rather than `playbackState == READY` alone, because READY with
     * `playWhenReady` still pending is a deck that is ready to start and has not.
     */
    val ready: Boolean
        get() = player?.let { it.playbackState == Player.STATE_READY && it.isPlaying } ?: false

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
            baseSpeed = speed
            servoSpeed = 1f
            burstEndsAt = 0L
            seeked = false
            bursts = 0
            misaligned = false
            quietUntil = 0L
            true
        }.getOrElse {
            cancel()
            false
        }
    }

    /** The level the tail is at, or ramps down from — see [setGain]. */
    @Volatile
    private var targetGain: Float = 1f

    /**
     * Follow a change to the level the tail should be at.
     *
     * The gain handed to [start] is the main player's *at that moment*. A listener
     * turning the volume down during the mix is turning down the song they can hear
     * — which for the length of the fade is mostly this one — and a tail that held
     * its old level would carry on at the old volume for several seconds, over an
     * incoming track that had obeyed. Applied on the ramp's next step; before the
     * hand-over the deck is silent, so there is nothing to change.
     */
    fun setGain(gain: Float) {
        targetGain = gain.coerceIn(0f, 1f)
    }

    /** The main player's speed, which the tail has to match once aligned. */
    private var baseSpeed = 1f

    /** The factor [align] is currently running the deck at, over [baseSpeed]. 1 between bursts. */
    private var servoSpeed = 1f

    /** `SystemClock.elapsedRealtime()` at which the running burst ends, or 0 for none. */
    private var burstEndsAt = 0L

    /** [align] has spent its one seek on this deck. */
    private var seeked = false

    /** Bursts [align] has run on this deck; see [MAX_BURSTS]. */
    private var bursts = 0

    /**
     * The deck is out of step with the main player by more than a tail can hide,
     * and there is no time left to fix it.
     *
     * A stream that took the whole pre-roll to open comes up seconds behind, after
     * the point the hand-over was planned for. Swapping to it would play those
     * seconds again at full volume — the glitch this class exists to remove — so
     * the caller should abandon the transition and let the boundary play out
     * gapless instead. A worse mix and a perfectly good join.
     */
    @Volatile
    var misaligned = false
        private set

    /**
     * `SystemClock.elapsedRealtime()` after which the clock can be believed again
     * — a seek or a burst has to play out before the position means anything.
     */
    private var quietUntil = 0L

    /**
     * Pull the deck into step with the main player, at [mainPositionMs] and due to
     * hand over at [handOverAtMs], while the deck is still silent.
     *
     * Called each tick of the pre-roll. Two decoders started at different moments on
     * the same file drift by however long the second took to fill its first buffer,
     * and a jump-cut of a couple of hundred milliseconds inside the outgoing track
     * *is* audible at full volume — a stutter or a swallowed word, right where the
     * mix is supposed to be seamless. A correction here costs nothing audible: the
     * deck is at volume zero.
     *
     * **Only measured while the deck is making sound.** Until then its reported
     * position is the one it was *told* to start from, which does not move while
     * the main player's does — so the "drift" grew a tick at a time from nothing
     * and, past a tolerance, used to buy a seek on a deck that had not started.
     * That seek restarted the open, the deck came up later still, and the one
     * correction was spent: the tail then trailed the main player by the whole
     * start-up time, and at the hand-over the room heard the last seconds of the
     * song again. That was the "end of the song repeats" glitch, and it is worst
     * exactly where the pre-roll is longest — a track streamed from a server.
     *
     * **Speed, not seeks.** A seek is the obvious correction and the wrong one: the
     * deck comes back from it late by whatever it cost, and what it costs is a
     * connection re-opened on a server that may be across the house — measured on
     * one link at 1.9 s, then 1.1 s, then 0.2 s for three seeks in a row. Nothing
     * that variable can be aimed. But a deck nobody can hear can run at any speed
     * it likes, so the gap is closed by running the deck fast (or slow, if it is
     * ahead) for exactly as long as closing it takes. No re-buffering, no round
     * trips, and it works however slow the link is. The one seek left
     * ([SEEK_ABOVE_MS]) is for a start so late that even four times speed could
     * not close it in the pre-roll that remains.
     *
     * **In bursts, not a loop.** A speed change reaches the output only once the
     * audio already queued in the sink has played — around half a second on a
     * phone — and the player's clock does not show it until then either. A loop
     * that watches the clock and adjusts the speed each tick is a controller with
     * half a second of dead time: it overshoots, swings back, and takes six
     * seconds to stop hunting, which is more than a local file's pre-roll has.
     * So each correction is open-loop: at speed `f`, `drift / (f − 1)` of wall
     * time consumes exactly `drift` more media than the main player does in the
     * same time, whatever the dead time, because the start and the end of the
     * burst pass through the same queue. Then the deck is back at the base speed,
     * the queue is left to play out ([QUIET_MS]), and the residue is measured
     * and burst away in turn. A burst or two lands inside [TOLERANCE_MS].
     *
     * **Frozen before the hand-over.** No burst starts unless it, and the quiet
     * after it, fit ahead of [FREEZE_MS] before the swap — so the deck is at the
     * base speed, time-stretcher out of the chain, by the time anyone hears it.
     */
    fun align(mainPositionMs: Long, handOverAtMs: Long) {
        val p = player ?: return
        if (ramp != null) return   // hand-over begun: the tail is its own song now
        val now = SystemClock.elapsedRealtime()
        if (burstEndsAt != 0L) {
            if (now < burstEndsAt) return
            // The burst has run its length. Back to the base speed, and let the
            // queue play out before believing the clock again.
            burstEndsAt = 0L
            setServo(p, 1f)
            quietUntil = now + QUIET_MS
            return
        }
        if (!ready || now < quietUntil) return
        val remaining = handOverAtMs - mainPositionMs
        val drift = mainPositionMs - runCatching { p.currentPosition }.getOrDefault(mainPositionMs)
        if (abs(drift) <= TOLERANCE_MS || bursts >= MAX_BURSTS) return
        if (!seeked && abs(drift) > SEEK_ABOVE_MS) {
            seeked = true
            quietUntil = now + QUIET_MS
            Log.d(TAG, "align: deck ${drift}ms behind, seeking")
            runCatching { p.seekTo(mainPositionMs.coerceAtLeast(0)) }
            return
        }
        // The gentlest speed that closes the gap in a burst of at least
        // [BURST_MIN_MS] — so a small residue is a slight, long lean rather than a
        // lurch — capped at what the time-stretcher and the buffer can keep up with.
        val factor = (1f + drift / BURST_MIN_MS.toFloat()).coerceIn(SERVO_MIN, SERVO_MAX)
        val burstMs = (abs(drift) / abs(factor - 1f)).toLong()
        // A burst cut off by the freeze is worse than none: it leaves the deck
        // mid-stretch with the gap half closed. Only start one with time to finish.
        if (remaining < FREEZE_MS + QUIET_MS + burstMs) {
            misaligned = abs(drift) > MAX_RESIDUE_MS
            Log.d(TAG, "align: ${drift}ms behind with ${remaining}ms left, leaving it" +
                if (misaligned) " — too far out to hand over" else "")
            quietUntil = Long.MAX_VALUE
            return
        }
        Log.d(TAG, "align: deck ${drift}ms behind, ${burstMs}ms burst at ${"%.2f".format(factor)}x")
        bursts++
        burstEndsAt = now + burstMs
        setServo(p, factor)
    }

    private fun setServo(p: ExoPlayer, factor: Float) {
        if (factor == servoSpeed) return
        servoSpeed = factor
        runCatching { p.setPlaybackSpeed(baseSpeed * factor) }
    }

    /**
     * Bring the tail up and take it down again over [seconds], then release.
     * [mainPositionMs] is where the main player is at this instant, for the log.
     *
     * The curve is equal *power* — `cos` here against the main player's `sin` — not
     * equal amplitude. Two uncorrelated pieces of music summed at half amplitude each
     * are audibly quieter than either at full, which is the dip in the middle that
     * makes a linear crossfade sound like a fade to nothing and back; squares that
     * sum to one is the standard fix and the one a mixing desk uses.
     */
    fun handOver(seconds: Float, mainPositionMs: Long) {
        val p = player ?: return
        val window = seconds.coerceIn(0.5f, MAX_WINDOW_S)
        // The residue [align] left, logged rather than acted on: this is the one
        // number that says whether a transition was clean, and it is only knowable
        // from a device.
        val residue = mainPositionMs - runCatching { p.currentPosition }.getOrDefault(mainPositionMs)
        Log.d(TAG, "hand-over: ${window}s window, tail ${residue}ms behind")
        // Should already be so — see [align]'s freeze — but the tail must not go
        // out time-stretched if the swap came early.
        setServo(p, 1f)
        ramp?.cancel()
        ramp = scope.launch {
            // Stepped against the clock, not counted. `delay` promises *at least*
            // its argument, and a hundred of them run long by whatever the
            // scheduler added to each — a four-second tail that took four and a
            // half. The incoming side ramps against the playhead, so the two halves
            // drifted apart and the constant-power sum below stopped being one.
            val windowMs = window * 1000f
            val startedAt = SystemClock.elapsedRealtime()
            while (isActive) {
                val x = ((SystemClock.elapsedRealtime() - startedAt) / windowMs).coerceIn(0f, 1f)
                runCatching { p.volume = targetGain * SmartCrossfade.fadeOutAt(x) }
                if (x >= 1f) break
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
        servoSpeed = 1f
        burstEndsAt = 0L
        seeked = false
        bursts = 0
        misaligned = false
        quietUntil = 0L
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
                // StreamSchemeResolver rewrites only uris whose scheme a streaming
                // source registered (see [StreamSchemes]); everything else passes
                // through unchanged.
                DefaultMediaSourceFactory(StreamSchemeResolver.factory(
                    DefaultDataSource.Factory(context, httpFactory),
                )),
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
         * Inside this, the two copies are in step and are left alone.
         *
         * Sixty milliseconds is about what a burst can be trusted to — the sink
         * applies a speed change to whole chunks, so any burst lands a few tens of
         * milliseconds either side of its aim, and chasing less is chasing noise.
         * It is also well under the shortest note anyone is mixing on, so a switch
         * between the two copies inside it is not a thing anybody can hear.
         */
        const val TOLERANCE_MS = 60L

        /**
         * The most the tail may be out of step at the hand-over and still be
         * handed over — see [misaligned]. A quarter-second jump inside a tail that
         * is being faded under another track is a soft edit; past it, it is the
         * end of the song heard twice.
         */
        const val MAX_RESIDUE_MS = 250L

        /**
         * Bursts before [align] gives up and leaves the residue. Each one lands
         * within a chunk or so of its aim, so the first two do the work; past four
         * the deck is flip-flopping across the tolerance on clock jitter, and the
         * time-stretcher going in and out of the chain is not free either.
         */
        const val MAX_BURSTS = 4

        /**
         * The shortest burst worth running, which is also what sets how gentle a
         * small correction is: a 100 ms gap is closed at 1.25× over 400 ms rather
         * than at 4× over 33 ms.
         *
         * Gentle matters, because a burst's error is the speed change landing on a
         * decoder-chunk boundary rather than exactly where it was asked — up to a
         * chunk (a FLAC block is ~90 ms) at each end, scaled by how far the speed
         * was from 1. At 4× that is most of a quarter-second either way; at 1.25×
         * it is inside the tolerance. So the big gap gets one fast burst and the
         * residue gets slow ones.
         */
        const val BURST_MIN_MS = 400L

        /** The deck's speed range over the main player's, while catching up. */
        const val SERVO_MIN = 0.5f
        const val SERVO_MAX = 4f

        /**
         * A gap a burst would not close in time gets the one seek. At four times
         * speed the deck gains three seconds a second; a stream's pre-roll leaves
         * a few seconds after a slow start, and a local file's rather less, so
         * past this the seek's round trip is the cheaper of the two.
         */
        const val SEEK_ABOVE_MS = 4_000L

        /**
         * How long after a burst before the clock is believed again.
         *
         * A speed change is applied where the sink's queue has reached, not where
         * the listener has, and the position reported reflects it only once the
         * queue has played out — measured at around 450 ms on a phone, with the
         * clock still catching up for a few hundred more. Read before that, it
         * shows a gap the burst has already closed, and the next burst overshoots.
         */
        const val QUIET_MS = 900L

        /**
         * How long before the hand-over the deck must be back at the base speed.
         *
         * The same queue again: the last burst's end has to reach the output before
         * the tail is audible, or the first thing the room hears of it is the
         * time-stretcher letting go.
         */
        const val FREEZE_MS = 500L

        /** How often the fade-out steps. 40 ms is 25 steps a second — inaudibly smooth. */
        private const val RAMP_TICK_MS = 40L

        private const val MAX_WINDOW_S = 15f

        private val USER_AGENT: String = "CAMusic/${BuildConfig.VERSION_NAME} (Android)"

        private const val TAG = "CrossfadeDeck"
    }
}
