package com.engabd.sendpin.service

import com.engabd.sendpin.audio.AudioAnalysisTap
import com.engabd.sendpin.audio.AudioLead
import com.engabd.sendpin.audio.LocalPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn

/**
 * Which of this phone's two players owns the audio output, and how far.
 *
 * The app runs two players in one process — [LocalPlayer] (Navidrome / Jellyfin /
 * downloads) and the Sendspin path ([Playback] plus an engine). They share the
 * audio output, audio focus, the media session, the analysis tap and a single
 * `client_id` with Music Assistant. Almost every rule about how they coexist used
 * to be written as a comment beside one of them and enforced in exactly one place,
 * so when a second place needed the same rule, nothing said so.
 *
 * Five bugs in one afternoon (PR #58) were instances of that shape:
 *
 * | Bug | The rule, and where it wasn't |
 * |---|---|
 * | MA engine went permanently deaf | `LocalPlayer` requests `AUDIOFOCUS_GAIN`; `Playback` read a permanent loss as "another app took over" and *released* — but it was us |
 * | Two clients fought over one `client_id` | One connection per player id, assumed in one place and raced in another |
 * | Light sync ignored MA | Three wirings needed to know MA can drive the tap; two did |
 * | Ambient idle show lost for MA | `active` (session) vs `playing` — the local half had it right, the MA half was written against the comment saying so |
 * | Pause played 20 s on | "`stream/end` means drop the buffer" enforced in one engine, and the other borrowed only its conclusion |
 *
 * The answer to "which player owns the output right now" was inferred, differently,
 * in [com.engabd.sendpin.SendpinApp]'s `activeLightSyncSource`, in its direct-sync
 * gate, in [SendspinService]'s `currentShade`, in [MaNowPlaying]'s `now`, and around
 * [Playback.pauseForLocalPlayback]. This is that answer, in one place, for all of
 * them to read.
 *
 * ## Two questions, deliberately not one
 *
 * The single most repeated mistake in that list was treating *active* and *playing*
 * as the same word, so this type refuses to have one `owner` property:
 *
 *  - [soundOwner] — who is **producing sound**. What the analysis tap, and therefore
 *    Light Sync, has to follow: an engine that is paused is not driving anything.
 *  - [sessionOwner] — who **holds a session**. What the media notification has to
 *    follow: a paused queue still owns the shade, and the ambient idle show still
 *    belongs to whoever is paused rather than to nobody.
 *
 * Reading the wrong one is how the idle show was lost for MA — the direct-sync gate
 * asked "is MA playing" where it meant "does MA have a track loaded", so a pause
 * tore the bridge down two seconds later and the lights snapped back instead of
 * easing into the idle show four seconds after that.
 *
 * Process-scoped, built on [com.engabd.sendpin.SendpinApp].
 */
class PlaybackOwner(
    private val local: LocalPlayer,
    private val sendspin: Playback,
) {

    /** Which player. */
    enum class Who {
        NONE,

        /** [LocalPlayer] — Navidrome, Jellyfin, or an offline download. */
        LOCAL,

        /** The Sendspin path: Music Assistant streaming to *this phone*. */
        SENDSPIN,
    }

    /**
     * The whole answer, as one value, so nothing downstream has to combine flows
     * again and arrive somewhere slightly different.
     */
    data class State(
        val soundOwner: Who,
        val sessionOwner: Who,
        /** [LocalPlayer] has a queue loaded. Paused counts. */
        val localActive: Boolean,
        /** [LocalPlayer] is producing sound. */
        val localPlaying: Boolean,
        /**
         * [LocalPlayer] is fronting a remote player — MPD — rather than decoding
         * itself. See [LocalPlayer.remoteActive]. `localPlaying` stays true either
         * way: MPD is still what the app is playing. This is the second question,
         * the one [soundIsReadable] needs — "is there real PCM behind that" — same
         * shape as [sendspinTap] answering it for the Sendspin side.
         */
        val localRemoteActive: Boolean,
        /** Music Assistant has a track loaded on this phone. Paused counts. */
        val sendspinActive: Boolean,
        /** Music Assistant is streaming to this phone right now. */
        val sendspinPlaying: Boolean,
        /**
         * The tap/lead pair installed in the Sendspin engine's render chain, or null.
         *
         * Set once [com.engabd.sendpin.audio.SendspinNativeEngine] — the only Sendspin
         * engine — is up and has installed its tap; null before that point, or when
         * nothing is connected. So "MA is playing" and "MA's audio can be read" are
         * *different questions*, and opening the light bridge on the first would
         * leave the room reacting to a tap that isn't there yet.
         */
        val sendspinTap: Pair<AudioAnalysisTap, AudioLead>?,
    ) {
        /** Anything at all is making sound on this phone. */
        val anyPlaying: Boolean get() = localPlaying || sendspinPlaying

        /** Anything at all holds a session on this phone. */
        val anyActive: Boolean get() = localActive || sendspinActive

        /**
         * Sound is coming from a source Light Sync can actually read.
         *
         * The Sendspin half needs [sendspinTap]; the local half needs the queue to
         * really be decoding here rather than fronting MPD — see [localRemoteActive].
         * Either way, a `false` here is not "nothing is playing": it is "the *real*
         * show can't run", which is exactly the question the scan-driven feeds
         * ([com.engabd.sendpin.hue.ScanFrameSource],
         * [com.engabd.sendpin.hue.MpdScanFrameSource]) exist to answer instead.
         */
        val soundIsReadable: Boolean get() = when (soundOwner) {
            Who.LOCAL -> !localRemoteActive
            Who.SENDSPIN -> sendspinTap != null
            Who.NONE -> false
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * [LocalPlayer]'s three flags, bundled into one value for the same reason
     * [State] itself exists: `combine` only has typed overloads up to five flows,
     * and [state] below already needs four slots for the Sendspin side.
     */
    private data class LocalFlags(val active: Boolean, val playing: Boolean, val remoteActive: Boolean)

    private val localFlags: StateFlow<LocalFlags> = combine(
        local.active, local.playing, local.remoteActive,
    ) { active, playing, remote -> LocalFlags(active, playing, remote) }
        .stateIn(scope, SharingStarted.Eagerly, LocalFlags(active = false, playing = false, remoteActive = false))

    /**
     * `Eagerly`, not `Lazily`: callers read `.value` synchronously (the audio-focus
     * arbitration below runs on a system callback and cannot suspend), so this has
     * to have emitted before anything can ask.
     */
    val state: StateFlow<State> = combine(
        localFlags,
        sendspin.trackTitle,
        sendspin.isPlaying,
        sendspin.maAudioSource,
    ) { flags, maTitle, maPlaying, maTap ->
        // A *track loaded*, not `isPlaying` — the MA equivalent of
        // `LocalPlayer.active`. `Playback` clears the title on disconnect and on
        // `stream/end` with nothing following, so this goes false exactly when the
        // session really has ended rather than when it merely paused.
        val sendspinActive = maTitle.isNotBlank()
        State(
            soundOwner = when {
                // Sendspin first when both claim to be playing. That state is what
                // `pauseForLocalPlayback` exists to prevent and should never last
                // more than a round trip, but if it does, this is the order the app
                // has always used and there is no reason to flip it here.
                maPlaying -> Who.SENDSPIN
                flags.playing -> Who.LOCAL
                else -> Who.NONE
            },
            sessionOwner = when {
                // Local first for the *session* question, and that asymmetry with
                // `soundOwner` above is deliberate. `LocalPlaybackService` posts its
                // notification off `LocalPlayer.active`, so a local session has to
                // win here or both services would post and the phone would carry two
                // media notifications — which is what `MaNowPlaying.now` returning
                // null under a local session has always been for.
                flags.active -> Who.LOCAL
                sendspinActive -> Who.SENDSPIN
                else -> Who.NONE
            },
            localActive = flags.active,
            localPlaying = flags.playing,
            localRemoteActive = flags.remoteActive,
            sendspinActive = sendspinActive,
            sendspinPlaying = maPlaying,
            sendspinTap = maTap,
        )
    }.distinctUntilChanged().stateIn(
        scope,
        SharingStarted.Eagerly,
        State(
            soundOwner = Who.NONE,
            sessionOwner = Who.NONE,
            localActive = false,
            localPlaying = false,
            localRemoteActive = false,
            sendspinActive = false,
            sendspinPlaying = false,
            sendspinTap = null,
        ),
    )

    // ── Transport, routed to whoever owns the output ─────────────────────────
    //
    // The same three calls the media notification makes, in one place any *other*
    // surface can use. `SendspinService.route` already chose between this phone's
    // Sendspin stream and a remote Music Assistant speaker; it did not know about
    // the local player, because the shade it serves is never showing one — but
    // anything outside the shade has all three cases to handle, and re-deriving the
    // choice per surface is exactly the folklore this class exists to replace.
    //
    // The driving bar is the first caller. It is deliberately not given its own
    // routing: a control that pauses the wrong player while someone is driving is
    // the worst possible place for this to be got wrong.

    private val maNowPlaying get() = com.engabd.sendpin.SendpinApp.instance.maNowPlaying

    fun playPause() = route(
        local = { local.toggle() },
        selfSendspin = { sendspin.onPlayPause() },
        remote = { maNowPlaying.playPause() },
    )

    fun next() = route(
        local = { local.next() },
        selfSendspin = { sendspin.onMediaNext() },
        remote = { maNowPlaying.next() },
    )

    fun previous() = route(
        local = { local.previous() },
        selfSendspin = { sendspin.onMediaPrevious() },
        remote = { maNowPlaying.previous() },
    )

    /**
     * Pause, explicitly — never a toggle. For callers a race must never turn into an
     * accidental resume: auto-pause on a phone call is the one that exists today.
     */
    fun pause() = route(
        local = { local.pause() },
        selfSendspin = { sendspin.onMediaPause() },
        remote = { maNowPlaying.pause() },
    )

    /**
     * Toggle shuffle. Unlike transport, this isn't a Sendspin-vs-remote question —
     * shuffle is a property of the Music Assistant *queue*, the same call whichever
     * player is producing the sound — so both non-local cases route the same way.
     */
    fun toggleShuffle() = route(
        local = { local.setShuffle(!local.shuffle.value) },
        selfSendspin = { maNowPlaying.toggleShuffle() },
        remote = { maNowPlaying.toggleShuffle() },
    )

    /**
     * Send a transport command to whichever player it belongs to.
     *
     * The *session* owner, not the sound owner: a paused player is still the one a
     * play button should start. That is the whole reason those are two properties
     * and not one.
     */
    private inline fun route(local: () -> Unit, selfSendspin: () -> Unit, remote: () -> Unit) {
        if (state.value.sessionOwner == Who.LOCAL) { local(); return }
        // Same precedence the media shade uses: when a remote speaker is the
        // selected Music Assistant player, transport addresses *it*, not this phone.
        // Getting this backwards is what made a headset button pause the phone while
        // a speaker in another room was playing.
        val now = maNowPlaying.now.value
        if (now != null && !now.isSelf) remote() else selfSendspin()
    }

    // ── Internal focus arbitration ───────────────────────────────────────────
    //
    // Two components in one process should not evict each other through
    // `AudioManager`, and these two did.
    //
    // `LocalPlayer`'s ExoPlayer is built with `setAudioAttributes(attrs,
    // handleAudioFocus = true)`, so starting a Navidrome track requests
    // `AUDIOFOCUS_GAIN` — which the platform grants by taking focus off whoever
    // held it, and the holder was this same process's Sendspin path.
    // `Playback.focusListener` then read `AUDIOFOCUS_LOSS` as "another app took
    // over media for good" and released the engine. It was not another app. It
    // was us, one class over, doing exactly what the app asked it to.
    //
    // The collision itself is *wanted*: one player at a time is the rule, and
    // `pauseForLocalPlayback` implements it deliberately from the local side. The
    // damage came from the other side handling the same event destructively at the
    // same moment. So rather than route both through `AudioManager` and hope, the
    // local player says out loud that it is taking over, and the Sendspin side can
    // tell an internal handover from a foreign one.

    @Volatile private var localClaimAtMs: Long = 0L

    /**
     * The local player is about to take the output.
     *
     * Called *before* it starts rather than from its playing state, because
     * ExoPlayer requests focus inside `play()` — the focus loss can therefore reach
     * [Playback] before `LocalPlayer.playing` has flipped, and an arbitration that
     * read the flow would sometimes see `false` and tear the engine down anyway.
     */
    fun noteLocalTakingOver() = noteTakingOutput()

    /**
     * Something in this process is about to claim the audio output.
     *
     * The general form of [noteLocalTakingOver]. The local player was the only
     * in-process claimant when that was written; ambience effects are a second, with
     * exactly the same problem — an `AUDIOFOCUS_GAIN` from inside this app evicts the
     * Sendspin path, which cannot tell that from a rival app and releases its engine.
     * [isInternalHandover] covers both for free, because both leave the same mark.
     */
    fun noteTakingOutput() {
        localClaimAtMs = android.os.SystemClock.elapsedRealtime()
    }

    /**
     * Was a permanent audio-focus loss this process evicting itself?
     *
     * True when the local player announced a takeover within [HANDOVER_WINDOW_MS].
     * A window rather than a flag because there is nothing to clear it on: the
     * platform sends no "and this is who took it" with the loss, so the claim is
     * only meaningful for as long as it takes the callback to arrive — which is
     * milliseconds. Anything later genuinely is another app.
     */
    fun isInternalHandover(): Boolean =
        android.os.SystemClock.elapsedRealtime() - localClaimAtMs < HANDOVER_WINDOW_MS

    private companion object {
        /**
         * How long after the local player claims the output a focus loss still
         * counts as this process evicting itself.
         *
         * Generous by the standard of the thing being measured — the platform
         * dispatches the loss to the previous holder as part of granting the
         * request — and cheap to be wrong about in the safe direction: treating a
         * foreign takeover as internal costs a Sendspin engine that stays built and
         * silent instead of one that is torn down, and the next `stream/start`
         * rebuilds it either way.
         */
        const val HANDOVER_WINDOW_MS = 2_000L
    }
}
