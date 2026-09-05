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
import com.engabd.sendpin.ma.maxSeekPositionMs
import com.engabd.sendpin.ma.seekableDurationMs
import com.engabd.sendpin.ui.viewmodel.FreezeDeadlines
import com.engabd.sendpin.ui.viewmodel.PlayerPositionTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
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
     *
     * Asked as [PlaybackOwner.State.sessionOwner] rather than `localPlayer.active`,
     * and the distinction is the whole reason that type exists. This is a *session*
     * question — `LocalPlaybackService` posts off the local player's session, so a
     * merely paused local queue still owns the shade and this must still stand down
     * for it. The neighbouring question, which tap Light Sync should read, is a
     * *playing* question and gets the other answer. Both used to be spelled out by
     * hand, in different files, and the pair went out of step twice.
     */
    private val owner get() = SendpinApp.instance.playbackOwner

    val now: StateFlow<Now?> =
        combine(_players, _queues, _target, owner.state) { players, queues, target, own ->
            if (own.sessionOwner == PlaybackOwner.Who.LOCAL) return@combine null
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
                // The queue's item duration, not the player's `current_media` — see
                // [seekableDurationMs]. The shade's bar is drawn to this and the
                // media session declares it as the track length, so a seek arriving
                // from the notification, Android Auto or a head unit is bounded by
                // the same number Music Assistant will check it against.
                durationMs = seekableDurationMs(queues.firstOrNull { it.queueId == streamId(p) }, p),
                isPlaying = p.isPlaying,
                isSelf = p.isSelfOrActiveOutput(myPlayerId),
                volumeLevel = p.volumeLevel,
                // MA does not surface a separate mute flag on the player; volume 0 is
                // what the session needs to render a muted icon, and unmuting is a
                // volume change either way.
                muted = p.volumeLevel <= 0,
            )
        }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, null)

    /** Whether the active queue has shuffle on — the driving bar's shuffle button state. */
    val shuffleActive: StateFlow<Boolean> =
        combine(_players, _queues, _target) { players, queues, target ->
            val id = target.ifBlank { myPlayerId }
            val p = players.firstOrNull { it.playerId == id }
            queues.firstOrNull { it.queueId == streamId(p) }?.shuffleEnabled == true
        }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, false)

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
                var lastEndPoll = 0L
                positions.observe(id).collect { ms ->
                    _positionMs.value = ms
                    // The projection has run out the track and no fresh anchor arrived,
                    // so the server has almost certainly moved on. Ask, rather than
                    // leaving the shade pinned at the duration until the 5 s poll floor
                    // comes round. Rate-limited: the ticker keeps emitting while pinned.
                    // Mirrors `NowPlayingViewModel.followPosition`.
                    if (positions.isAtEnd(id)) {
                        val t = android.os.SystemClock.elapsedRealtime()
                        if (t - lastEndPoll > END_REPOLL_MIN_MS) { lastEndPoll = t; refresh() }
                    }
                }
            }
        }
        // When this phone is the player, audio starting to flow on it is the strongest
        // confirmation available that a seek or skip actually landed — better than any
        // inference from polled state, and it arrives sooner.
        //
        // The *edge*, not the level: `isPlaying` is already true when a skip is asked
        // for, because the outgoing track keeps coming out of the speaker for over a
        // second afterwards. Releasing on that hands the bar straight back to the old
        // track's playhead. Mirrors `NowPlayingViewModel`, which drives the on-screen
        // bar from the same signal.
        scope.launch {
            SendpinApp.instance.playback.audibleSeq.collect { seq ->
                val armedAt = freezeAtAudibleSeq
                if (armedAt < 0 || seq <= armedAt) return@collect
                if (!targetIsThisPhone()) return@collect
                val id = targetId()
                if (positions.isFrozen(id)) releaseFreeze(id)
            }
        }
        // The other end of the same story: something in the app replaced the queue.
        // A skip freezes on its way out through [next]/[previous], but a play started
        // from the library never came through here, so its first anchor adopted MA's
        // `elapsed_time` — already a second or two in, because the server begins the
        // stream job well before this phone makes a sound. Freezing on the request and
        // releasing on the audible edge is exactly what a skip already does; this just
        // arms it for the route that was missing.
        //
        // `drop(1)` because a StateFlow replays its current value to a new collector,
        // and the count standing at whatever it was is not a fresh request.
        scope.launch {
            SendpinApp.instance.playback.playStartSeq.drop(1).collect {
                if (targetIsThisPhone()) freezeForTrackChange(targetId())
            }
        }
        // And the boundary itself, which is what covers a track ending on its own —
        // see [Playback.streamStartSeq]. Neither of the two above fires for that: one
        // is a queue replacement the app asked for, the other a skip.
        scope.launch {
            SendpinApp.instance.playback.streamStartSeq.drop(1).collect {
                if (targetIsThisPhone()) freezeForTrackChange(targetId())
            }
        }
    }

    /**
     * Is the selected player this phone?
     *
     * Not a plain id comparison since Music Assistant 2.10: the target is the
     * `universal_player` wrapper (`up…`) and [myPlayerId] is the protocol client it
     * renders through, so the two are never equal. See [MaPlayer.isSelfOrActiveOutput].
     */
    private fun targetIsThisPhone(): Boolean {
        val id = targetId()
        if (id == myPlayerId) return true
        return _players.value.firstOrNull { it.playerId == id }
            ?.isSelfOrActiveOutput(myPlayerId) == true
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
    /**
     * Identity of the track last seen, and the queue it belongs to.
     *
     * The pair, not the id alone. massdroid's `hasCurrentItemChanged` answers **false**
     * when there is no previous item to compare against (`previous?.currentItem ?:
     * return false`), and it gets a fresh start on a player switch because deselecting
     * clears the queue snapshot. Keying the id by queue buys both here: no previous
     * reading for this queue means no track change, so the first poll after a cold
     * start - or after switching to another speaker - anchors the server's real
     * position instead of slamming the bar to 0:00 and letting the next poll drag it
     * back up. "I have never seen this queue" is not "the track just changed".
     *
     * Also what a pending skip is measured against - see [freezeForTrackChange].
     */
    @Volatile private var lastTrackId: String? = null
    @Volatile private var lastTrackKey: String? = null

    /**
     * [Playback.audibleSeq] as it was when the current freeze was armed.
     *
     * The freeze may only be released by a stream that became audible *after* it —
     * see the collector in `init` for why a level cannot express that.
     */
    @Volatile private var freezeAtAudibleSeq: Long = -1L

    private fun freezeForSeek(playerId: String, target: Long) {
        pendingSeekMs = target
        pendingSkip = false
        pendingSkipFromTrack = null
        freezeAtAudibleSeq = SendpinApp.instance.playback.audibleSeq.value
        positions.setOptimisticSeek(playerId, target, now.value?.durationMs?.takeIf { it > 0 })
        // Same split as `NowPlayingViewModel.freezeForSeek`, and for the same reason:
        // the short deadline is right for a remote player, where the poll is the only
        // confirmation, and wrong for this phone, where the confirmation is the audible
        // edge and arrives about 2.9 s after the request. Holding for 2.5 s released
        // the bar before the audio existed and left it running two seconds ahead of it.
        armFreezeWatchdog(playerId, FreezeDeadlines.forSeek(targetIsThisPhone()))
    }

    private fun freezeForTrackChange(playerId: String) {
        pendingSeekMs = null
        pendingSkip = true
        pendingSkipFromTrack = lastTrackId
        freezeAtAudibleSeq = SendpinApp.instance.playback.audibleSeq.value
        positions.setOptimisticTrackChange(playerId)
        armFreezeWatchdog(playerId)
    }

    private fun armFreezeWatchdog(playerId: String, timeoutMs: Long = FreezeDeadlines.DEFAULT_MS) {
        freezeWatchdog?.cancel()
        freezeWatchdog = scope.launch {
            delay(timeoutMs)
            releaseFreeze(playerId)
        }
    }

    private fun releaseFreeze(playerId: String) {
        pendingSeekMs = null
        pendingSkip = false
        pendingSkipFromTrack = null
        freezeAtAudibleSeq = -1L
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
        val knownTrack = lastTrackId.takeIf { lastTrackKey == playerId }
        val trackChanged = trackId != null && knownTrack != null && trackId != knownTrack
        if (trackId != null) { lastTrackId = trackId; lastTrackKey = playerId }

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

        // The server's own capture timestamp (`elapsed_time_last_updated`, a Unix
        // epoch in seconds) as local wall-clock ms, handed over raw: the tracker
        // decides whether it is fresh enough to project from, and null means the
        // server said nothing. Only taken when `elapsed` came from the queue too —
        // the stamp describes the queue's reading, so pairing it with the player's
        // fallback would anchor one number on another's timestamp.
        val capturedAtMs = queue?.elapsedTimeLastUpdated
            ?.takeIf { queue.elapsedMs != null }
            ?.let { (it * 1000).toLong() }

        if (trackChanged) {
            positions.setAnchor(
                queueId = playerId,
                elapsedMs = 0L,
                capturedAtMs = null,
                isPlaying = player.isPlaying,
                durationMs = seekableDurationMs(queue, player).takeIf { it > 0 },
                speed = queue?.playbackSpeed,
            )
            return
        }

        positions.setAnchor(
            queueId = playerId,
            elapsedMs = elapsed,
            capturedAtMs = capturedAtMs,
            isPlaying = player.isPlaying,
            durationMs = seekableDurationMs(queue, player).takeIf { it > 0 },
            speed = queue?.playbackSpeed,
        )
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

    /** Explicit, not [playPause]'s toggle — for callers that must never accidentally resume. */
    fun pause() = command { repo.pause(targetId()) }

    /** Shuffle is a queue property, not a player one — resolved via [streamId] like the rest. */
    fun toggleShuffle() = command {
        val id = targetId()
        val p = players.value.firstOrNull { it.playerId == id }
        val queue = queues.value.firstOrNull { it.queueId == streamId(p) } ?: return@command
        repo.setShuffle(queue.queueId, !queue.shuffleEnabled)
    }

    fun seekTo(positionMs: Long) = command {
        val id = targetId()
        // Clamped here rather than trusted from the caller: a media session hands
        // over a position measured against the duration *it* was told, and Music
        // Assistant rejects anything past `current_item.duration` outright — a
        // rejection [command]'s `runCatching` would swallow, leaving the shade's bar
        // frozen on a position playback never reached.
        val target = positionMs.coerceIn(0L, maxSeekPositionMs(now.value?.durationMs ?: 0L))
        // Hold the bar where the user dropped it: MA keeps reporting the old position
        // for a beat after a seek, and rendering that makes the bar jump back.
        //
        // The freeze is released by [anchor] once the server corroborates it, never
        // here. Confirming immediately after issuing the command — which is what this
        // used to do — released the hold before the server had even processed the
        // seek, so the very next poll reported the old position and the notification's
        // bar snapped back. That is the whole reason the freeze exists.
        freezeForSeek(id, target)
        try {
            repo.seek(id, (target / 1000).toInt())
        } catch (e: Exception) {
            // The seek did not happen, so let the bar say where playback really is
            // instead of holding a target for six seconds and then jumping.
            releaseFreeze(id)
            throw e
        }
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

        /** Floor between end-of-track re-polls, so a pinned bar cannot spin the socket. */
        const val END_REPOLL_MIN_MS = 1_000L

        /** How near the server's clock has to land for a seek to count as landed. */
        const val SEEK_CONFIRM_MS = 3_000L

    }
}
