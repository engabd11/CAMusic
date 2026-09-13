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
import com.engabd.sendpin.ma.resolveTargetPlayer
import com.engabd.sendpin.ma.seekableDurationMs
import kotlinx.serialization.json.JsonObject
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

    /**
     * The one playhead for the selected player — the screen's `NowPlayingViewModel`
     * reads [positionMs] and routes its seeks and skips through here, so the bar on
     * screen and the bar in the shade are the same bar.
     */
    private val playhead = MaPlayhead(scope)

    private fun targetId() = resolveTargetPlayer(_players.value, _target.value, myPlayerId)

    /** The selected player as [playhead] needs it, resolved against the current lists. */
    private fun target(): MaPlayhead.Target {
        val id = targetId()
        val p = _players.value.firstOrNull { it.playerId == id }
        return MaPlayhead.Target(key = id, queueId = streamId(p), isSelf = targetIsThisPhone())
    }

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
            val id = resolveTargetPlayer(players, target, myPlayerId)
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
                isSelf = isThisPhone(p),
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
            val id = resolveTargetPlayer(players, target, myPlayerId)
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
        // The playhead reads the events themselves, unsampled: a `queue_updated`
        // names the new track with its elapsed and stamp, a `queue_time_updated`
        // carries a seek's landing, a `player_updated` a pause — each is an anchor
        // the sampled re-read above would only deliver a poll later. This is the
        // official app's whole position pipeline; the poll is the floor under it.
        scope.launch {
            api.events
                .mapNotNull { MaParse.event(it) }
                .collect { e ->
                    val t = target()
                    when {
                        e.isQueueTime -> MaParse.queueTimeMs(e)?.let { ms ->
                            playhead.onQueueTime(t, e.objectId ?: return@collect, ms)
                        }
                        e.isQueueUpdated -> (e.data as? JsonObject)
                            ?.let { MaParse.queue(it, api.serverUrl) }
                            ?.let { q -> playhead.onQueueUpdated(t, q, _players.value.firstOrNull { it.playerId == t.key }) }
                        e.isPlayerUpdated -> (e.data as? JsonObject)
                            ?.let { MaParse.player(it, api.serverUrl) }
                            ?.let { playhead.onPlayerUpdated(t, it) }
                    }
                }
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
            combine(_players, _queues, _target) { players, queues, _ -> players to queues }
                .collect { (players, queues) ->
                    val t = target()
                    val p = players.firstOrNull { it.playerId == t.key }
                    playhead.onPoll(t, p, queues.firstOrNull { it.queueId == t.queueId })
                }
        }
        scope.launch {
            combine(_players, _target) { _, _ -> targetId() }
                .distinctUntilChanged()
                .collectLatest { id ->
                    var lastEndPoll = 0L
                    playhead.observe(id).collect { ms ->
                        _positionMs.value = ms
                        // The projection has run out the track and no fresh anchor
                        // arrived, so the server has almost certainly moved on. Ask,
                        // rather than leaving the shade pinned at the duration until
                        // the 5 s poll floor comes round. Rate-limited: the ticker
                        // keeps emitting while pinned.
                        if (playhead.isAtEnd(id)) {
                            val t = android.os.SystemClock.elapsedRealtime()
                            if (t - lastEndPoll > END_REPOLL_MIN_MS) { lastEndPoll = t; refresh() }
                        }
                    }
                }
        }
        // This phone's stream (re)starting is what lifts a hold placed on its own bar.
        scope.launch {
            SendpinApp.instance.playback.streamChunkSeq.drop(1).collect { playhead.onStreamChunk(it) }
        }
        // A seek or skip that reached the player through the media session rather
        // than through here still holds the bar the same way.
        SendpinApp.instance.playback.onSelfSeekRequested = { ms ->
            playhead.armSeek(target(), ms, now.value?.durationMs?.takeIf { it > 0 })
        }
        SendpinApp.instance.playback.onSelfSkipRequested = { playhead.armTrackChange(target()) }
        scope.launch {
            api.state.collect { if (it == MaApiClient.State.DISCONNECTED) playhead.clear() }
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
        return _players.value.firstOrNull { it.playerId == id }?.let { isThisPhone(it) } == true
    }

    /**
     * The protocol client each wrapper was last seen rendering through.
     *
     * `active_output_protocol` is only set while the wrapper is actually playing:
     * Music Assistant clears it on pause and between tracks. Without a memory of it,
     * this phone stopped being "this phone" the moment it paused — a seek while
     * paused got no hold, and the shade routed the next command to a "remote"
     * player that was the phone itself. So a wrapper that last rendered through us,
     * and has not since named anything else, is still us.
     */
    private val lastOutputProtocol = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun isThisPhone(p: MaPlayer): Boolean {
        p.activeOutputProtocol?.let { lastOutputProtocol[p.playerId] = it }
        return p.isSelfOrActiveOutput(myPlayerId) ||
            (p.activeOutputProtocol == null && lastOutputProtocol[p.playerId] == myPlayerId)
    }

    // --- refresh ----------------------------------------------------------

    private val refreshing = AtomicBoolean(false)
    private val refreshQueued = AtomicBoolean(false)

    /**
     * Why the last read failed, or null when it succeeded. The Speakers screen shows
     * it: a server that refuses `players/all` without a login used to leave that
     * screen at "0 of 0 players" with the reason logged and nowhere else.
     */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

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
                    val playersResult = runCatching { repo.players() }
                    val players = playersResult.getOrNull()
                    val queues = runCatching { repo.queues() }.getOrNull()
                    _lastError.value = playersResult.exceptionOrNull()?.message
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

    fun next() = command { skipNext() }

    fun previous() = command { skipPrevious() }

    /**
     * Skip, holding this phone's own bar at zero until its new stream arrives — see
     * [MaPlayhead.holdForTrackChange]. Throws what the server throws; [next] and
     * [previous] swallow that, the screen's callers decide for themselves.
     */
    suspend fun skipNext() = playhead.holdForTrackChange(target()) { repo.next(targetId()) }

    suspend fun skipPrevious() = playhead.holdForTrackChange(target()) { repo.previous(targetId()) }

    /** Run [block] — a queue jump, a play — under the same hold as a skip. */
    suspend fun <T> holdForTrackChange(block: suspend () -> T): T =
        playhead.holdForTrackChange(target(), block)

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

    fun seekTo(positionMs: Long) = command { seek(positionMs) }

    /**
     * Seek the selected player, holding this phone's own bar at the target until its
     * new stream arrives — see [MaPlayhead.holdForSeek]. Throws what the server
     * throws, so a caller with a toast can say why.
     *
     * Clamped here rather than trusted from the caller: a media session hands over a
     * position measured against the duration *it* was told, and Music Assistant
     * rejects anything past `current_item.duration` outright.
     */
    suspend fun seek(positionMs: Long) {
        val duration = now.value?.durationMs ?: 0L
        // Whole seconds: that is what `players/cmd/seek` takes, so the bar is held
        // exactly where the server will land rather than up to a second past it.
        val targetSec = (positionMs.coerceIn(0L, maxSeekPositionMs(duration)) / 1000).toInt()
        playhead.holdForSeek(target(), targetSec * 1000L, duration.takeIf { it > 0 }) {
            repo.seek(targetId(), targetSec)
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
    }
}
