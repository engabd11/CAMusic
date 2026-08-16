package com.engabd.sendpin.service

import android.content.Context
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.discovery.PlayerIdentity
import com.engabd.sendpin.ma.MaApiClient
import com.engabd.sendpin.ma.MaParse
import com.engabd.sendpin.ma.MaPlayer
import com.engabd.sendpin.ma.MaQueue
import com.engabd.sendpin.ma.MaRepository
import com.engabd.sendpin.ui.viewmodel.PlayerPositionTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What the **selected** Music Assistant player is doing, at process scope.
 *
 * The app already knew this — in `NowPlayingViewModel`. But a ViewModel dies with the
 * Activity, so nothing outside the UI could answer "is a speaker playing?", and the
 * shade fell back to the connection service's *"Ready — announcements will play
 * here"* while a speaker was in the middle of an album. It also meant
 * [SendspinService]'s MediaSession only ever knew about this phone's own Sendspin
 * stream, so lock-screen and Bluetooth transport addressed the phone no matter which
 * speaker the user had selected.
 *
 * This is the one thing in the app that follows the selected player at process scope,
 * and the one whose transport addresses *that* player.
 *
 * Deliberately left alongside `NowPlayingViewModel` rather than replacing its polling:
 * both read the same shared socket, which costs two extra commands per five seconds,
 * and keeping them apart means the notification can change without touching the Now
 * Playing screen.
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
class MaNowPlaying(private val app: Context) {

    /** Everything the shade needs about the selected player. */
    data class Now(
        val playerId: String,
        val playerName: String,
        val title: String,
        val artist: String,
        val album: String,
        val artworkUrl: String?,
        val durationMs: Long,
        val isPlaying: Boolean,
        /** This phone is the selected player, so the Sendspin path owns the shade. */
        val isSelf: Boolean,
        /** The selected player's volume, 0..100, for the media session to publish. */
        val volumeLevel: Int,
        val muted: Boolean,
    )

    private val settings = AppSettings(app)
    /**
     * Live, not captured — see [PlayerIdentity.getPlayerId]. This one mattered most:
     * `MaNowPlaying` is built once, on `SendpinApp`, and never rebuilt, so a captured
     * id here stayed stale for the whole process — taking `isSelf` (and with it the
     * media shade's entire local-vs-remote routing) with it.
     */
    private val myPlayerId: String get() = PlayerIdentity.getPlayerId(app)
    private val api: MaApiClient = SendpinApp.instance.maApi
    private val repo = MaRepository(api)
    private val localPlayer = SendpinApp.instance.localPlayer

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _players = MutableStateFlow<List<MaPlayer>>(emptyList())
    private val _queues = MutableStateFlow<List<MaQueue>>(emptyList())
    private val _target = MutableStateFlow("")
    private val _backend = MutableStateFlow("ma")

    private val positions = PlayerPositionTracker()

    private fun targetId() = _target.value.ifBlank { myPlayerId }

    /** The queue the selected player is really playing from — a member uses the leader's. */
    private fun streamId(p: MaPlayer?) = p?.syncedTo ?: targetId()

    /**
     * The selected player's state, or null when there is nothing for the shade to say.
     *
     * Null while the local (Navidrome/offline) player holds a session: that has its
     * own notification in [LocalPlaybackService], and two media notifications for one
     * phone is worse than none.
     */
    val now: StateFlow<Now?> =
        combine(_players, _queues, _target, localPlayer.active) { players, _, target, localActive ->
            if (localActive) return@combine null
            val id = target.ifBlank { myPlayerId }
            val p = players.firstOrNull { it.playerId == id } ?: return@combine null
            val np = p.nowPlaying?.takeIf { it.title.isNotBlank() } ?: return@combine null
            Now(
                playerId = id,
                playerName = p.name,
                title = np.title,
                artist = np.artist,
                album = np.album,
                artworkUrl = np.imageUrl,
                durationMs = np.durationMs ?: 0L,
                isPlaying = p.isPlaying,
                isSelf = id == myPlayerId,
                volumeLevel = p.volumeLevel,
                // MA does not surface a separate mute flag on the player; volume 0 is
                // what the session needs to render a muted icon, and unmuting is a
                // volume change either way.
                muted = p.volumeLevel <= 0,
            )
        }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, null)

    private val _positionMs = MutableStateFlow(0L)

    /** The projected playhead for the selected player, in milliseconds. */
    val positionMs: StateFlow<Long> = _positionMs

    /**
     * The last `players/all` and `player_queues/all` this process read.
     *
     * Published so nothing else has to ask for them again. This class already polls
     * both every 5 s *and* refreshes on sampled player/queue events, and the Now
     * Playing and Speakers view models each ran an identical loop of their own — so
     * with the player open, one Music Assistant server was answering three copies of
     * the same two commands every five seconds, plus three copies of every
     * event-driven refresh. There was no shared cache; each went straight to the
     * socket.
     *
     * This is process-scoped and outlives any screen, so a view model collecting it
     * gets the current answer immediately and every later one for free.
     */
    val players: StateFlow<List<MaPlayer>> = _players
    val queues: StateFlow<List<MaQueue>> = _queues

    /**
     * Ask for a read now — for a screen that has just become visible, or an action
     * whose result the user is waiting to see.
     *
     * Safe to call from anywhere and as often as you like: [refresh] coalesces, so a
     * burst of callers costs one round trip and a repeat pass, not one each.
     */
    fun refreshNow() = refresh()

    init {
        scope.launch { settings.targetPlayer.collect { _target.value = it } }
        // Say so — loudly — when the persisted target names a player this server does
        // not have.
        //
        // The target is stored as a concrete player id (blank meaning "this phone"), so
        // a selection that has since gone leaves every play command aimed at something
        // Music Assistant either refuses outright ("player is not available") or queues
        // to a stale entry that makes no sound. Nothing surfaced that before: the app
        // went on showing a player playing, because it was asking about the same dead
        // id it was playing to. Deliberately a log and not an automatic reset — a
        // legitimate speaker that is merely asleep drops out of `players/all` too, and
        // silently discarding the user's choice for that would be its own bug. Fixed by
        // re-picking the player in Speakers, which now writes a live id (see
        // SpeakersViewModel.myPlayerId).
        scope.launch {
            combine(_players, _target) { players, target -> players to target }
                .distinctUntilChanged()
                .collect { (players, target) ->
                    if (target.isBlank() || players.isEmpty()) return@collect
                    if (players.any { it.playerId == target }) return@collect
                    android.util.Log.w(
                        "MaNowPlaying",
                        "target player '$target' is not on this server " +
                            "(this phone is '$myPlayerId') - playback will not reach it",
                    )
                }
        }
        scope.launch { settings.backend.collect { _backend.value = it } }
        scope.launch {
            api.state.collect { if (it == MaApiClient.State.CONNECTED) refresh() }
        }
        // Sampled events for promptness, a 5 s poll as the floor. `queue_time_updated`
        // alone arrives about once a second per active queue, so sampling is what keeps
        // this bounded.
        //
        // 300 ms rather than the 500 ms this used to run at: the Now Playing screen had
        // its own 300 ms collector until it started reading from here, and this is now
        // the only one, so it inherits the tighter of the two rather than making the
        // screen a step slower than it was.
        scope.launch {
            api.events
                .mapNotNull { MaParse.event(it) }
                .filter { it.isPlayerOrQueue }
                .sample(300)
                .collect { refresh() }
        }
        scope.launch {
            while (true) {
                delay(POLL_MS)
                // Pointless on the Navidrome backend, where MA may not even be
                // configured — and waking the radio for it would be worse than
                // pointless.
                if (_backend.value != "subsonic" && api.state.value == MaApiClient.State.CONNECTED) {
                    // Skip the poll when the app is backgrounded and no remote
                    // player is actively playing. The poll drives WebSocket traffic
                    // and JSON parsing on the main dispatcher every 5 seconds —
                    // fine while the user is looking at the app, but pure background
                    // cost when nobody is. A remote player that *is* playing still
                    // needs the poll so the notification's seek bar stays live; a
                    // backgrounded app with nothing playing does not.
                    val remoteActive = now.value?.isPlaying == true
                    val backgrounded = !(AppLifecycleObserver.get()?.foreground?.value ?: true)
                    if (!backgrounded || remoteActive) {
                        refresh()
                    }
                }
            }
        }
        // Anchor the playhead off whatever the last read said, then let the tracker
        // project between reads so the notification's seek bar moves smoothly rather
        // than stepping once per poll.
        scope.launch {
            combine(_players, _queues, _target) { players, queues, target ->
                val id = target.ifBlank { myPlayerId }
                val p = players.firstOrNull { it.playerId == id }
                Triple(id, p, queues.firstOrNull { it.queueId == streamId(p) })
            }.collect { (id, player, queue) -> anchor(id, player, queue) }
        }
        scope.launch {
            now.map { it?.playerId }.distinctUntilChanged().collectLatest { id ->
                if (id == null) { _positionMs.value = 0L; return@collectLatest }
                positions.observe(id).collect { _positionMs.value = it }
            }
        }
        // When this phone is the player, audio starting to flow on it is the strongest
        // confirmation available that a seek or skip actually landed — better than any
        // inference from polled state, and it arrives sooner.
        scope.launch {
            SendpinApp.instance.playback.isPlaying.collect { playing ->
                val id = targetId()
                if (playing && id == myPlayerId && positions.isFrozen(id)) releaseFreeze(id)
            }
        }
    }

    // ── Optimistic freeze bookkeeping ────────────────────────────────────────
    //
    // The same shape as `NowPlayingViewModel`'s, and for the same reason: a freeze
    // that nothing releases is a bar that never moves again, so every one is paired
    // with a condition that releases it and a watchdog that releases it anyway.

    private var freezeWatchdog: kotlinx.coroutines.Job? = null
    @Volatile private var pendingSeekMs: Long? = null
    /**
     * A skip is waiting to be confirmed. Its own flag rather than
     * `pendingSkipFromTrack != null`, because that id is genuinely unknown when the
     * user skips before anything has been polled — and "unknown" must not read as
     * "nothing pending", which would confirm the freeze on the spot.
     */
    @Volatile private var pendingSkip = false
    @Volatile private var pendingSkipFromTrack: String? = null
    /** Identity of the track last seen, for spotting a skip landing. */
    @Volatile private var lastTrackId: String? = null
    /** Last `elapsed_time_last_updated` accepted, per queue — the staleness gate. */
    private val lastStamp = mutableMapOf<String, Double>()

    private fun freezeForSeek(playerId: String, target: Long) {
        pendingSeekMs = target
        pendingSkip = false
        pendingSkipFromTrack = null
        positions.setOptimisticSeek(playerId, target, now.value?.durationMs)
        armFreezeWatchdog(playerId)
    }

    private fun freezeForTrackChange(playerId: String) {
        pendingSeekMs = null
        pendingSkip = true
        pendingSkipFromTrack = lastTrackId
        positions.setOptimisticTrackChange(playerId)
        armFreezeWatchdog(playerId)
    }

    private fun armFreezeWatchdog(playerId: String) {
        freezeWatchdog?.cancel()
        freezeWatchdog = scope.launch {
            delay(FREEZE_TIMEOUT_MS)
            releaseFreeze(playerId)
        }
    }

    private fun releaseFreeze(playerId: String) {
        pendingSeekMs = null
        pendingSkip = false
        pendingSkipFromTrack = null
        freezeWatchdog?.cancel(); freezeWatchdog = null
        positions.confirmPlaying(playerId)
    }

    /** What the server is calling the current track, for detecting a skip landing. */
    private fun trackIdOf(player: MaPlayer?, queue: MaQueue?): String? {
        queue?.currentQueueItemId?.let { return it }
        queue?.currentItem?.uri?.let { return it }
        val np = player?.nowPlaying ?: return null
        if (np.title.isBlank()) return null
        return "${np.title}|${np.artist}|${np.durationMs}"
    }

    /**
     * Feed the position tracker what the server last said.
     *
     * Keyed on the *player* rather than the queue, because that is what [observe] is
     * watching and what the notification is about — a member and its leader share a
     * queue but each get their own row in the shade's history.
     */
    private fun anchor(playerId: String, player: MaPlayer?, queue: MaQueue?) {
        if (player == null) return
        val np = player.nowPlaying

        // Track identity is read *before* the playhead, and the playhead is allowed to
        // be missing. A poll that names the track but carries no `elapsed_time` is
        // still the news that a skip landed, and bailing out above this — which is
        // what an `?: return` on the elapsed reading did — meant [lastTrackId] could
        // stay behind, leaving a skip with nothing to confirm it but the watchdog.
        val trackId = trackIdOf(player, queue)
        val trackChanged = trackId != null && trackId != lastTrackId
        if (trackId != null) lastTrackId = trackId

        val elapsed = queue?.elapsedMs ?: np?.elapsedMs

        // Release an optimistic freeze once — and only once — the server corroborates
        // it. A skip is confirmed by the server naming a different track; a seek by its
        // clock landing near where the user dropped the scrubber.
        if (positions.isFrozen(playerId)) {
            val seekTarget = pendingSeekMs
            val confirmed = when {
                // `pendingSkipFromTrack` may be null — we can freeze for a skip before
                // ever having seen what was playing. Any named track is progress then.
                pendingSkip -> trackId != null && trackId != pendingSkipFromTrack
                seekTarget != null ->
                    elapsed != null && kotlin.math.abs(elapsed - seekTarget) < SEEK_CONFIRM_MS
                else -> true
            }
            if (!confirmed) return
            releaseFreeze(playerId)
        }

        if (elapsed == null) return

        if (trackChanged) {
            lastStamp.remove(playerId)
            queue?.elapsedTimeLastUpdated?.let { lastStamp[playerId] = it }
            positions.setAnchor(
                queueId = playerId,
                elapsedMs = 0L,
                isPlaying = player.isPlaying,
                durationMs = np?.durationMs,
                speed = queue?.playbackSpeed,
            )
            return
        }

        // Staleness gate. A repeated `elapsed_time_last_updated` means the server has
        // not recomputed the playhead — for a remote speaker that is most polls — so
        // re-anchoring on it would keep re-applying however stale that reading was,
        // dragging the shade's bar backwards every time. Let the projection carry on;
        // a play/pause is still news, and [setPlaying] snapshots the projection rather
        // than trusting the stale number.
        val stamp = queue?.elapsedTimeLastUpdated
        if (stamp != null && lastStamp[playerId]?.let { stamp <= it } == true) {
            positions.setPlaying(playerId, player.isPlaying)
            return
        }
        if (stamp != null) lastStamp[playerId] = stamp

        positions.setAnchor(
            queueId = playerId,
            elapsedMs = elapsed,
            isPlaying = player.isPlaying,
            durationMs = np?.durationMs,
            speed = queue?.playbackSpeed,
        )
        positions.setPlaying(playerId, player.isPlaying)
    }

    // --- refresh ----------------------------------------------------------

    private val refreshing = AtomicBoolean(false)
    private val refreshQueued = AtomicBoolean(false)

    /**
     * Re-read players and queues, one pass at a time.
     *
     * Serialised the same way the Now Playing screen's refresh is, and for the same
     * reason: overlapping responses used to land out of order and pin the shade to the
     * previous track. An empty result is not adopted while the socket is down —
     * [MaApiClient] completes pending requests with null on a drop, which parses to an
     * empty list, and taking that would blank the notification mid-track.
     */
    private fun refresh() {
        if (!refreshing.compareAndSet(false, true)) { refreshQueued.set(true); return }
        scope.launch {
            try {
                do {
                    refreshQueued.set(false)
                    val players = runCatching { repo.players() }.getOrNull()
                    val queues = runCatching { repo.queues() }.getOrNull()
                    val connected = api.state.value == MaApiClient.State.CONNECTED
                    if (players != null && (players.isNotEmpty() || connected)) _players.value = players
                    if (queues != null && (queues.isNotEmpty() || connected)) _queues.value = queues
                } while (refreshQueued.get())
            } finally {
                refreshing.set(false)
            }
        }
    }

    // --- transport (acts on the selected player) --------------------------

    /**
     * Every command addresses [targetId], not this phone.
     *
     * That is the point of this class existing: the media session used to send these
     * to `Playback.playerId`, so a headset button pressed while a speaker was playing
     * paused the phone instead of the speaker.
     */
    fun playPause() = command {
        if (now.value?.isPlaying == true) repo.pause(targetId()) else repo.play(targetId())
    }

    fun next() = command {
        freezeForTrackChange(targetId())
        repo.next(targetId())
    }

    fun previous() = command {
        freezeForTrackChange(targetId())
        repo.previous(targetId())
    }

    fun stop() = command { repo.stop(targetId()) }

    fun seekTo(positionMs: Long) = command {
        val id = targetId()
        // Hold the bar where the user dropped it: MA keeps reporting the old position
        // for a beat after a seek, and rendering that makes the bar jump back.
        //
        // The freeze is released by [anchor] once the server corroborates it, never
        // here. Confirming immediately after issuing the command — which is what this
        // used to do — released the hold before the server had even processed the
        // seek, so the very next poll reported the old position and the notification's
        // bar snapped back. That is the whole reason the freeze exists.
        freezeForSeek(id, positionMs)
        repo.seek(id, (positionMs / 1000).toInt())
    }

    /** [level01] is 0..1, as the media session reports it. */
    fun setVolume(level01: Float) = command {
        repo.setVolume(targetId(), (level01.coerceIn(0f, 1f) * 100).toInt())
    }

    private fun command(block: suspend () -> Unit) {
        scope.launch {
            runCatching { block() }
            // Give the server a beat to act, then take its word for the result rather
            // than assuming the command landed.
            delay(300)
            refresh()
        }
    }

    private companion object {
        const val POLL_MS = 5_000L

        /** How near the server's clock has to land for a seek to count as landed. */
        const val SEEK_CONFIRM_MS = 3_000L

        /**
         * How long a freeze may hold out for a confirmation that never comes. A server
         * that goes quiet should cost a stuck second, not a stuck bar.
         */
        const val FREEZE_TIMEOUT_MS = 6_000L
    }
}
