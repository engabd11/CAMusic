package com.engabd.sendpin.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import com.engabd.sendpin.protocol.ClockSync
import com.engabd.sendpin.protocol.StreamStartPlayerInfo

/**
 * ExoPlayer-based Sendspin (MA) playback engine - see `docs/exoplayer-upgrade-plan.md`.
 * Was opt-in behind a toggle while it was being validated against the original,
 * hand-built `MediaCodec`+`AudioTrack` engine; that engine has since been removed
 * as dead code (it produced no audio on the hardware that mattered) and this one
 * is now the only MA engine - see [com.engabd.sendpin.service.Playback.startSendspin].
 *
 * What this gets MA playback that the old engine couldn't: [AudioAnalysisTap] sits
 * in this player's render chain exactly like it does for [LocalPlayer], via the
 * same [TapRenderersFactory] - direct Hue Bridge Light Sync becomes possible for
 * MA the same way it already works for local playback. This is wired up:
 * [com.engabd.sendpin.SendpinApp.activeLightSyncSource] reactively switches
 * [com.engabd.sendpin.hue.DirectLightSync] between `localPlayer`'s tap and
 * [audioAnalysisTap] based on which backend is actually playing (see
 * `Playback.maAudioSource`, which this class publishes into) - so lights follow
 * MA playback once this engine is what's rendering it.
 *
 * Also gets ExoPlayer's gapless. GC-immune output is a second, separate opt-in
 * on top of this one: [useOboe] (default off) swaps [TapRenderersFactory] for
 * [OboeRenderersFactory], routing decoded PCM through [SendspinNativeOutput]
 * (the ported Massdroid Oboe engine) instead of the platform `AudioTrack`. Off,
 * this trades the old engine's tuned `AudioTrack` pacing for one still on the
 * JVM thread, in exchange for a real ExoPlayer pipeline; on, it adds native
 * timeline drift correction, a dynamic-range compressor and dither - but only
 * 16-bit PCM is supported ([OboeAudioSink.configure] throws otherwise), so
 * combining [useOboe] with [bitPerfect] is a real gap, not yet handled.
 *
 * One instance per Sendspin connection (see [com.engabd.sendpin.service.Playback.startSendspin]).
 * Unlike the old hand-rolled engine - which ran its own decode thread and could be
 * driven from anywhere - ExoPlayer may only be built and touched on the thread
 * that built it, and [com.engabd.sendpin.service.Playback] drives its engine
 * from a `Dispatchers.Default` scope, `AudioManager` focus callbacks, and the
 * Sendspin client's ingest coroutine - none of which is reliably the main
 * thread. Every method here is safe to call from any of them: field-only state
 * ([submit], the volume/mute setters' bookkeeping) applies immediately, and the
 * actual [ExoPlayer] calls are posted to the main thread via [runOnMain]. That
 * makes those calls asynchronous with respect to the caller - deliberately, since
 * synchronously blocking an arbitrary caller thread on a main-thread post risks a
 * deadlock if that thread is ever waited on *from* the main thread.
 */
@OptIn(UnstableApi::class)
class SendspinExoEngine(
    private val context: Context,
    private val clock: ClockSync,
    /**
     * Called once [onPlayerError] has retried [MAX_CONSECUTIVE_ERRORS] times without
     * reaching [Player.STATE_READY]. The engine has stopped retrying by the time this
     * fires - the caller decides what recovery (if any) makes sense, e.g. forcing the
     * Sendspin client to reconnect so Music Assistant gets a fresh chance to (re)send
     * `stream/start`.
     */
    private val onFatalError: () -> Unit = {},
) : SendspinPlaybackEngine {

    val audioLead = AudioLead()
    val audioAnalysisTap = AudioAnalysisTap(audioLead)

    /**
     * Read once, when the player is (re)built - see [LocalPlayer.buildPlayer]'s
     * identical note. A change takes effect on the next stream, not the current one.
     */
    @Volatile override var bitPerfect: Boolean = false

    /**
     * Experimental, on top of [SendspinExoEngine] itself being experimental: GC-immune
     * native output via Oboe instead of the platform `AudioTrack`. Read once, when
     * the player is (re)built - same timing as [bitPerfect]. See the class doc for
     * why the two don't currently combine.
     */
    @Volatile var useOboe: Boolean = false

    /**
     * The live player, rebuilt on demand after a [release].
     *
     * A `by lazy` here was a one-way door: released once, every later [start] posted to
     * a dead playback thread and the engine was deaf for the rest of the connection.
     * That is reachable through entirely ordinary use, and by this app rather than a
     * foreign one — playing a Navidrome track makes `LocalPlayer`'s ExoPlayer request
     * `AUDIOFOCUS_GAIN`, which evicts the Sendspin path's own focus request, and
     * `Playback.focusListener` treats a permanent loss as "stop and release". The
     * connection stays up and `stream/start` keeps arriving, so nothing looks wrong from
     * outside; the logs read `Ignoring messages sent after release` and `sending message
     * to a Handler on a dead thread`, once per track, forever.
     *
     * The sink that drives this engine captures the instance directly, so nulling a
     * field in `Playback` could not have helped — recovery has to live here. Rebuilding
     * is also the right answer whoever released it and for whatever reason: a released
     * player is simply not something [start] can use.
     *
     * Only ever touched inside [runOnMain], so the rebuild happens on the thread
     * ExoPlayer requires and needs no further synchronisation.
     */
    private var livePlayer: ExoPlayer? = null
    private val player: ExoPlayer
        get() = livePlayer ?: buildPlayer().also { livePlayer = it }

    // Only constructed when useOboe is actually on (see buildPlayer) - the native
    // library is loaded lazily too (SendspinNativeOutput.ensureLibrary), so a
    // build with useOboe off never touches the .so at all.
    private var nativeOutput: SendspinNativeOutput? = null

    /**
     * Whether this player is in a Sendspin group - selects [SendspinSyncDataSource]
     * (server-clock scheduled) vs [SendspinDirectDataSource] (instant start). Set
     * by the caller from `group/update`'s `group_id` before the next [start].
     */
    @Volatile var grouped: Boolean = false

    @Volatile override var staticDelayMs: Int = 0
        set(value) {
            field = value
            (currentDataSource as? SendspinSyncDataSource)?.staticDelayMs = value
        }

    private var preferredOutputDevice: AudioDeviceInfo? = null
    private var currentDataSource: SendspinDataSource? = null
    private var userVolume = 1f
    private var syncMuted = false

    /**
     * The tail has been given up on: discarded outright, or drained to its cap.
     *
     * This is what takes the volume to zero, and only [cutTail] sets it. `stream/end`
     * used to do so directly, which is the whole of the "TTS gets cut off at the end"
     * report: that message is identical for a pause, a track boundary and the end of
     * an announcement, and muting on all three took the last words off the
     * announcements. The caller decides which it was — see [StreamEndPolicy] — and a
     * pause still goes silent the instant [cutTail] is called.
     *
     * Muting rather than stopping, still: the decoder and audio track stay alive
     * either way, which is what keeps a real track boundary gapless.
     */
    private var tailMuted = false

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Run [action] on the main thread - immediately if already there, posted
     * otherwise. Every touch of [player] goes through this: it's what lets every
     * public method here be called from whatever thread the caller happens to be
     * on (see the class doc).
     */
    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    private fun effectiveVolume() = if (syncMuted || tailMuted) 0f else userVolume

    /**
     * Consecutive [onPlayerError] calls since the last [Player.STATE_READY] or fresh
     * [start]. What bounds the retry below - see its doc for why an unbounded retry
     * is not a hypothetical risk.
     */
    private var consecutiveErrors = 0

    /**
     * A `Player.Listener` is what makes a source/renderer error visible at all -
     * without one, a fatal [PlaybackException] leaves the player silently in
     * [Player.STATE_IDLE] forever: no sound, no exception surfaced anywhere
     * Playback.kt would see it.
     *
     * [onPlayerError] retries via [ExoPlayer.prepare] (the recovery ExoPlayer's own
     * docs recommend for a transient source error) with a widening backoff, up to
     * [MAX_CONSECUTIVE_ERRORS] times, then calls [onFatalError] and stops. This used
     * to retry immediately and unconditionally - "enough as long as whatever caused
     * the error doesn't recur" - but on-device it *did* recur, every time: a stuck
     * [SendspinDataSource] with nothing to feed ExoPlayer produced
     * `ERROR_CODE_TIMEOUT` ("stuck buffering with no progress"), the immediate
     * `prepare()` hit the identical stuck source, and the pair produced 311,760
     * identical log lines over 58 straight minutes - a genuine busy-loop, not a
     * hypothetical one, burning CPU and battery the whole time with no audio and
     * nothing user-visible. A bounded, backed-off retry turns that into a handful of
     * quick attempts and then a clean give-up.
     */
    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            Log.e("SendspinExoEngine", "player error: ${error.errorCodeName}", error)
            consecutiveErrors++
            if (consecutiveErrors > MAX_CONSECUTIVE_ERRORS) {
                Log.e(
                    "SendspinExoEngine",
                    "giving up after $consecutiveErrors consecutive errors without reaching STATE_READY",
                )
                onFatalError()
                return
            }
            val delayMs = RETRY_BACKOFF_MS[(consecutiveErrors - 1).coerceIn(0, RETRY_BACKOFF_MS.lastIndex)]
            mainHandler.postDelayed({ player.prepare() }, delayMs)
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) consecutiveErrors = 0
            // A stream that produces no sound leaves a distinctive trail here and
            // nowhere else: BUFFERING for ever means the data source is not feeding
            // ExoPlayer, IDLE means nothing was ever prepared, READY means the
            // pipeline is running and the silence is downstream of it (gain, route,
            // or the Oboe sink). Without this the three are indistinguishable.
            Log.i("SendspinExoEngine", "playback state = ${stateName(state)}")
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            Log.i("SendspinExoEngine", "isPlaying = $isPlaying (volume=${effectiveVolume()})")
        }
    }

    /**
     * Stop using the Oboe sink for the rest of this session.
     *
     * Called by [OboeAudioSink] the first time its ring takes nothing at all for long
     * enough that back pressure cannot explain it — the failure PR #59 documents, whose
     * root cause is still open. Whatever that cause turns out to be, a path that emits
     * no sound must not be the one the listener is left on: the current stream fails
     * loudly (the sink throws, which lands in [playerListener]), and the player is torn
     * down so the *next* `stream/start` builds a platform-sink player instead.
     *
     * Session-scoped on purpose. The user's `useOboeOutput` preference is not touched:
     * they asked for the experiment, this is one device on one stream, and silently
     * rewriting a setting is not this code's decision to make. Restarting the app tries
     * again, which is the right amount of persistence for something opt-in and off by
     * default.
     */
    private fun abandonOboe() {
        if (!useOboe) return
        useOboe = false
        Log.e(
            "SendspinExoEngine",
            "the Oboe output stalled - falling back to the platform sink for this session " +
                "(useOboeOutput is unchanged and will be tried again next launch)",
        )
        runOnMain {
            // Same teardown as [release], for the same reason: the player is released
            // *and cleared*, so the next start() builds a fresh one — this time without
            // OboeRenderersFactory, since useOboe is now false.
            livePlayer?.release()
            livePlayer = null
            nativeOutput?.release()
            nativeOutput = null
        }
    }

    private fun stateName(state: Int) = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "state($state)"
    }

    /**
     * True while the current stream is a spoken announcement rather than music.
     *
     * Set by `Playback` from [StreamClassifier] before [start], and applied to the
     * player's own [AudioAttributes] there. `USAGE_ASSISTANT` + `CONTENT_TYPE_SPEECH`
     * is what asks the platform to duck whatever else is playing rather than fight it
     * for the output, which is the difference between an announcement being audible
     * over a video and being silent.
     */
    @Volatile
    var speech: Boolean = false

    private fun attrsFor(speech: Boolean): AudioAttributes = AudioAttributes.Builder()
        .setUsage(if (speech) C.USAGE_ASSISTANT else C.USAGE_MEDIA)
        .setContentType(if (speech) C.AUDIO_CONTENT_TYPE_SPEECH else C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    private fun buildPlayer(): ExoPlayer {
        val attrs = attrsFor(speech)
        val renderers = if (useOboe) {
            val output = SendspinNativeOutput().also { nativeOutput = it }
            val sink = OboeAudioSink(
                nativeOutput = output,
                tap = audioAnalysisTap,
                driftCorrection = { currentDataSource is SendspinSyncDataSource },
                currentDataSource = { currentDataSource },
                onStalled = ::abandonOboe,
            )
            OboeRenderersFactory(context, sink)
        } else {
            TapRenderersFactory(context, audioAnalysisTap, audioLead)
                .setEnableAudioFloatOutput(bitPerfect) as TapRenderersFactory
        }
        // Shallow on purpose: SendspinSyncDataSource.read() is *usually* the pacing
        // mechanism (it blocks until each frame is due), so a deep ExoPlayer buffer
        // on top of that would only add latency, not safety margin - see the plan's
        // risk #2. "Usually" is load-bearing: HeadGate falls back to PLAY_NOW
        // (unscheduled - every frame handed over the moment it's queued, exactly
        // like the solo/[SendspinDirectDataSource] path) whenever the clock filter
        // hasn't reconverged within its 3s budget, which happens often enough after
        // an idle→active transition (every pause→resume) to not be an edge case.
        // With nothing pacing the loader on that path, maxBufferMs stopped being
        // Shallow on purpose: SendspinSyncDataSource.read() is itself the pacing
        // mechanism (it blocks until each frame is due), so a deep ExoPlayer
        // buffer on top of that would only add latency, not safety margin - see
        // the plan's risk #2. Keep the buffer shallow so the analysis tap sees
        // audio promptly - a deep buffer here means the lights wait seconds
        // before reacting when MA starts playing.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 500,
                /* maxBufferMs = */ 1_000,
                /* bufferForPlaybackMs = */ 200,
                /* bufferForPlaybackAfterRebufferMs = */ 500,
            )
            .build()
        return ExoPlayer.Builder(context, renderers)
            .setLoadControl(loadControl)
            // Playback.kt already owns focus arbitration for the Sendspin path
            // (see its focusListener) - ExoPlayer handling it too would be two
            // owners fighting over the same AudioManager request.
            .setAudioAttributes(attrs, /* handleAudioFocus = */ false)
            .build()
            .also { p ->
                p.volume = effectiveVolume()
                p.addListener(playerListener)
                preferredOutputDevice?.let { d -> runCatching { p.setPreferredAudioDevice(d) } }
            }
    }

    override fun start(format: StreamStartPlayerInfo) {
        // A fresh stream/start is a clean slate - errors from whatever track just
        // ended (or a since-fixed connection) should not count against this one.
        consecutiveErrors = 0
        val dataSource: SendspinDataSource = if (grouped) {
            SendspinSyncDataSource(format, clock).also { it.staticDelayMs = staticDelayMs }
        } else {
            SendspinDirectDataSource(format)
        }
        // Synchronous and immediate: a submit() for this stream can arrive on the
        // ingest thread before the runOnMain below has even run, and it needs
        // somewhere to queue to. ExoPlayer doesn't start pulling from it until
        // the main-thread block completes moments later, so the ordering is safe
        // either way.
        currentDataSource = dataSource
        tailMuted = false
        val speechNow = speech
        runOnMain {
            // Applied here, before prepare(), rather than baked in at build time.
            // Changing attributes on a live player flushes and rebuilds the
            // AudioTrack; doing it at the one moment there is no audio to interrupt
            // costs nothing, and media3 no-ops when the value has not changed, so a
            // run of ordinary music pays nothing at all.
            player.setAudioAttributes(attrsFor(speechNow), /* handleAudioFocus = */ false)
            player.volume = effectiveVolume()
            player.setMediaSource(SendspinMediaSource.create(dataSource, format))
            player.prepare()
            player.play()
        }
    }

    override val queuedFrames: Int get() = currentDataSource?.queuedFrames ?: 0

    override fun submit(frame: ByteArray) {
        val (serverTsUs, payload) = SendspinAudioFrame.parse(frame) ?: return
        currentDataSource?.submit(serverTsUs, payload)
    }

    /**
     * `stream/end` — which the protocol sends identically for a pause, a track
     * boundary and the end of an announcement. [drain] is the caller's verdict on
     * which; see [StreamEndPolicy] for how it is reached.
     */
    override fun endOfStream(drain: Boolean) {
        if (drain) {
            // Signal only. [awaitNextFrame] goes on serving what is already queued
            // and then reports end-of-input, so the tail is heard — which for an
            // announcement is the last words of it.
            currentDataSource?.signalEndOfStream()
            return
        }
        cutTail()
    }

    /**
     * Drop the tail and go silent.
     *
     * Discard *then* signal: the queue has to be empty when [awaitNextFrame] next
     * checks, or it goes on serving the backlog it was just told to abandon. Without
     * the discard, pausing took as long as the server's read-ahead to fall silent —
     * measured at over 20 s on-device. See [SendspinDataSource.discardQueued].
     *
     * Muting rather than stopping, for the same reason it always was: a hard stop
     * would take the decoder and audio track with it, and rebuilding those is what a
     * real track boundary must not have to do.
     */
    override fun cutTail() {
        if (tailMuted) return
        currentDataSource?.apply { discardQueued(); signalEndOfStream() }
        tailMuted = true
        runOnMain { player.volume = effectiveVolume() }
    }

    /** `stream/clear` - a seek or track jump: the current source is now wrong. */
    override fun flush() {
        currentDataSource?.signalEndOfStream()
        currentDataSource = null
        runOnMain { player.stop() }
    }

    override fun setVolume(v: Float) {
        userVolume = v.coerceIn(0f, 1f)
        runOnMain { player.volume = effectiveVolume() }
    }

    /** Mute/unmute for clock-convergence reasons - see [SyncGate]. */
    override fun setSyncMuted(muted: Boolean) {
        if (syncMuted == muted) return
        syncMuted = muted
        runOnMain { player.volume = effectiveVolume() }
    }

    override fun setPreferredDevice(device: AudioDeviceInfo?) {
        preferredOutputDevice = device
        runOnMain { runCatching { player.setPreferredAudioDevice(device) } }
    }

    override fun release() {
        currentDataSource?.signalEndOfStream()
        currentDataSource = null
        runOnMain {
            // Released *and cleared*, so the next start() builds a fresh one rather than
            // posting to this one's dead thread — see [livePlayer]. Deliberately not
            // `player.release()`, which would build a player only to destroy it if this
            // engine had never started one.
            livePlayer?.release()
            livePlayer = null
            nativeOutput?.release()
            nativeOutput = null
        }
    }

    private companion object {
        /** Retries before [onFatalError] - see [playerListener]'s doc for why bounded. */
        const val MAX_CONSECUTIVE_ERRORS = 4

        /** Widening backoff between retries, indexed by (consecutiveErrors - 1). */
        val RETRY_BACKOFF_MS = longArrayOf(500L, 1_500L, 4_000L, 8_000L)
    }
}
