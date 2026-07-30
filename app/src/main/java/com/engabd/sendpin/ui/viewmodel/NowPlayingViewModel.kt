package com.engabd.sendpin.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.audio.FormatNegotiator
import com.engabd.sendpin.audio.LocalTrack
import com.engabd.sendpin.audio.StreamQuality
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.discovery.PlayerIdentity
import com.engabd.sendpin.ma.MaApiClient
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ma.MaLyrics
import com.engabd.sendpin.ma.MaNowPlaying
import com.engabd.sendpin.ma.MaPlayer
import com.engabd.sendpin.ma.MaQueue
import com.engabd.sendpin.ma.MaQueueItem
import com.engabd.sendpin.ma.MaRepository
import com.engabd.sendpin.ma.MaSimilarTrack
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Now-Playing as a **controller**: reflects and controls the currently *selected*
 * player (the Speakers "Play here" target, "" = this phone) via the Music Assistant
 * API, so the screen shows whatever that player is playing — title/artist/art,
 * playing/paused, position — not just this phone's own Sendspin stream.
 *
 * It also owns the three panels hung off that track — queue, lyrics and sonically
 * similar tracks — which load on demand rather than on every poll.
 */
class NowPlayingViewModel(app: Application) : AndroidViewModel(app) {

    data class State(
        val playerName: String = "",
        val isSelf: Boolean = true,
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val artworkUrl: String? = null,
        val isPlaying: Boolean = false,
        val volume: Float = 1f,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        /** The shown track is loaded on the player right now (vs. carried over). */
        val hasTrack: Boolean = false,
        /** The player has nothing loaded — show the screen, but say so. */
        val idle: Boolean = true,
        /** Nothing has been seen playing yet, so there is not even a stale track. */
        val blank: Boolean = true,
        /** What is actually coming out of the pipe right now — the negotiated/decoded format. */
        val quality: StreamQuality? = null,
        /** The original source format from the library (before any transcoding). */
        val sourceQuality: StreamQuality? = null,
        /**
         * Where the track came from: "MA", "Navidrome", "Offline", or the streaming
         * provider MA pulled it from. Never blank — the badge tells the user which
         * backend owns playback, and that is knowable even before a queue exists.
         */
        val source: String = "MA",
        /** Players sharing this stream, when the target leads a sync group. */
        val groupSize: Int = 1,
        val shuffle: Boolean = false,
        val repeatMode: String = "off",   // off | one | all
        val queueSize: Int = 0,
        /** Which queue row is playing. Matched by id, since indexes shift on edits. */
        val currentQueueItemId: String? = null,
        val playbackSpeed: Float = 1f,
        val dontStopTheMusic: Boolean = false,
        val powered: Boolean = true,
        /** The player exposes a power switch, so the control is worth showing. */
        val canPower: Boolean = false,
        /**
         * This phone is playing on its own (Navidrome-direct or offline) rather than
         * reflecting a Music Assistant player. The MA connection being down is then
         * not worth complaining about — nothing on screen depends on it.
         */
        val isLocalSession: Boolean = false,
    )

    /** A panel's load state — the UI has to tell "empty" from "not fetched yet". */
    sealed interface Load<out T> {
        data object Idle : Load<Nothing>
        data object Loading : Load<Nothing>
        data class Ready<T>(val value: T) : Load<T>
        data class Failed(val message: String) : Load<Nothing>
    }

    private val settings = AppSettings(app)
    private val myPlayerId = PlayerIdentity.getPlayerId(app)
    private val api = (app as SendpinApp).maApi
    private val repo = MaRepository(api)
    /**
     * The standalone queue player (Navidrome-direct / offline). While it has a
     * session loaded it *is* what's playing on this phone, so it takes the screen
     * over from the Music Assistant view — otherwise starting a Navidrome album
     * left Now Playing showing whatever MA last had, with transport buttons that
     * controlled something the user couldn't hear.
     */
    private val local = (app as SendpinApp).localPlayer
    /** This phone's own Sendspin stream — the authoritative format when we're the player. */
    private val localQuality = (app as SendpinApp).playback.streamQuality
    /** What this phone can put out on its own, for locally-decoded playback. */
    private val deviceQuality = FormatNegotiator.deviceOutputQuality()

    /** Everything the local player exposes, folded into one value to combine with. */
    private data class LocalSnap(
        val active: Boolean = false,
        val track: LocalTrack? = null,
        val playing: Boolean = false,
        val durationMs: Long = 0,
        val queueSize: Int = 0,
        val index: Int = -1,
        val shuffle: Boolean = false,
        val repeat: String = "off",
        val speed: Float = 1f,
    )

    private val localSnap: StateFlow<LocalSnap> = combine(
        local.active, local.current, local.playing, local.durationMs,
        combine(local.queue, local.index, local.shuffle, local.repeatMode, local.speed) { q, i, s, r, sp ->
            LocalSnap(queueSize = q.size, index = i, shuffle = s, repeat = r, speed = sp)
        },
    ) { active, track, playing, dur, queue ->
        queue.copy(active = active, track = track, playing = playing, durationMs = dur)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LocalSnap())

    /** True while the phone is playing on its own rather than through MA. */
    private val isLocal get() = localSnap.value.active

    private val _target = MutableStateFlow("")
    private val _players = MutableStateFlow<List<MaPlayer>>(emptyList())
    private val _queues = MutableStateFlow<List<MaQueue>>(emptyList())

    /**
     * The last track we actually saw playing. Polls can come back empty between
     * tracks, on reconnect, or while the server is still waking up; without this
     * the screen would blank out and then repopulate every time.
     */
    private val _lastTrack = MutableStateFlow<MaNowPlaying?>(null)

    // ── Server-anchored position engine ──────────────────────────────────────
    // Instead of polling every 2s and snapping the bar to the server value, the
    // position is driven by a local 500ms ticker that forward-projects from the
    // last server anchor (positionBaseTime @ positionBaseTimestamp). The server
    // only needs to nudge the anchor periodically — the bar is always smooth.
    // Mirrors the approach used by massdroid_native + MA's own web UI.
    @Volatile private var posBaseTime = 0L           // server-reported elapsed_ms
    @Volatile private var posBaseTimestamp = 0L       // local wall-clock when captured
    @Volatile private var posIsPlaying = false
    @Volatile private var posDuration = 0L
    @Volatile private var posSpeed = 1f               // playback speed (1.0 = normal)
    @Volatile private var lastTrackId: String? = null  // detect track changes for immediate reset

    private val _positionMs = MutableStateFlow(0L)

    /**
     * Where the scrubber sits. The local player knows its own position exactly, so
     * when it is the one playing there is nothing to project forward from a server
     * anchor — the anchored engine below only applies to the MA-side player.
     */
    val positionMs: StateFlow<Long> =
        combine(localSnap, _positionMs, local.positionMs) { l, server, here ->
            if (l.active) here else server
        }.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    private var tickerJob: Job? = null

    private fun startPositionTicker() {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (isActive) {
                delay(500)
                _positionMs.value = interpolatedPosition()
            }
        }
    }

    private fun stopPositionTicker() {
        tickerJob?.cancel(); tickerJob = null
    }

    private fun interpolatedPosition(): Long {
        if (!posIsPlaying) return posBaseTime.coerceIn(0L, posDuration)
        val deltaMs = ((System.currentTimeMillis() - posBaseTimestamp) * posSpeed).toLong()
        return (posBaseTime + deltaMs).coerceIn(0L, posDuration)
    }

    /**
     * Re-anchor the position engine from a server poll or event.
     * If the track has changed, position is reset to 0 immediately — before
     * the new queue state even publishes — so the UI never flashes a stale
     * "9:54 / 4:11" frame.
     */
    private fun reanchorPosition(
        serverElapsedMs: Long,
        isPlaying: Boolean,
        duration: Long,
        speed: Float,
        trackId: String?,
    ) {
        val trackChanged = trackId != null && trackId != lastTrackId
        if (trackChanged) {
            posBaseTime = 0L
            _positionMs.value = 0L
            lastTrackId = trackId
        } else {
            posBaseTime = serverElapsedMs
        }
        posBaseTimestamp = System.currentTimeMillis()
        val wasPlaying = posIsPlaying
        posIsPlaying = isPlaying
        posDuration = duration
        posSpeed = speed
        if (isPlaying && (trackChanged || !wasPlaying)) startPositionTicker()
        else if (!isPlaying) stopPositionTicker()
        if (!trackChanged) _positionMs.value = interpolatedPosition()
    }

    private fun targetId() = _target.value.ifBlank { myPlayerId }

    private val maState: StateFlow<State> = combine(_players, _target, _queues, localQuality, _lastTrack) { players, target, queues, local, last ->
        val id = target.ifBlank { myPlayerId }
        val p = players.firstOrNull { it.playerId == id }
        val isSelf = id == myPlayerId
        // A synced member plays the leader's stream, so read quality off the leader's queue.
        val streamId = p?.syncedTo ?: id
        val queue = queues.firstOrNull { it.queueId == streamId }
        val queueQuality = queue?.quality

        val live = p?.nowPlaying?.takeIf { it.title.isNotBlank() }
        // Fall back to the last track so the screen keeps its shape when the
        // player goes quiet, rather than collapsing to an empty state.
        val np = live ?: last
        State(
            playerName = p?.name ?: "This phone",
            isSelf = isSelf,
            title = np?.title.orEmpty(),
            artist = np?.artist.orEmpty(),
            album = np?.album.orEmpty(),
            artworkUrl = np?.imageUrl,
            isPlaying = live != null && p?.isPlaying == true,
            volume = ((p?.volumeLevel ?: 100) / 100f).coerceIn(0f, 1f),
            positionMs = if (live != null) live.elapsedMs ?: 0 else 0,
            durationMs = if (live != null) live.durationMs ?: 0 else 0,
            hasTrack = live != null,
            idle = live == null,
            blank = np == null,
            // `quality` is what's actually coming out of the pipe right now.
            //
            // When this phone is the player, the negotiated Sendspin stream format
            // *is* the output — the same 48/16 the Settings screen reports — so it
            // wins outright. The queue's streamdetails describes the file MA opened
            // at its end, which is the source, not the output, and reading playing
            // quality off it made the badge claim 96/24 while the phone was being
            // handed 48/16. For a remote speaker we have no such handle, so the
            // queue's details are the best available answer.
            quality = when {
                !isSelf -> queueQuality
                local != null -> local
                queueQuality != null -> queueQuality
                // Nothing negotiated and no queue details, but something *is*
                // playing: the file is being decoded here (a Navidrome stream
                // played direct), so the phone's own ceiling is the answer.
                live != null -> deviceQuality
                else -> null
            },
            // `sourceQuality` is the original library file's format — derived from
            // the current item's provider_mappings audio_format, NOT the stream
            // details (which reflect what the server is actually sending, after
            // any transcoding). These genuinely differ when MA converts a 96/24
            // FLAC to 48/16 for a player that can't handle hi-res.
            sourceQuality = queue?.currentItem?.audioFormat?.let {
                StreamQuality(it.codec, it.sampleRate, it.bitDepth)
            },
            // Source: where the bytes are actually coming from. See [sourceOf]. With
            // no queue yet there is nothing to read a provider off, but the backend
            // in charge is still MA — so the badge names it rather than vanishing.
            source = queue?.let { sourceOf(it) }?.ifBlank { null } ?: "MA",
            groupSize = 1 + (p?.groupChilds?.size ?: 0),
            shuffle = queue?.shuffleEnabled == true,
            repeatMode = queue?.repeatMode ?: "off",
            queueSize = queue?.itemCount ?: 0,
            currentQueueItemId = queue?.currentQueueItemId,
            playbackSpeed = queue?.playbackSpeed ?: 1f,
            dontStopTheMusic = queue?.dontStopTheMusic == true,
            powered = p?.powered ?: true,
            canPower = p?.let { "power" in it.supportedFeatures } ?: false,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, State())

    /**
     * What the screen shows: the local player when it has a session, the Music
     * Assistant view otherwise. There is no third state — the phone is either
     * playing something itself or reflecting a player MA owns.
     */
    val state: StateFlow<State> = combine(maState, localSnap) { ma, l ->
        if (!l.active) return@combine ma
        val t = l.track
        State(
            playerName = "This phone",
            isSelf = true,
            title = t?.title.orEmpty(),
            artist = t?.artist.orEmpty(),
            album = t?.album.orEmpty(),
            artworkUrl = t?.artUrl,
            isPlaying = l.playing,
            // The local player runs at the device volume; MA's per-player level
            // means nothing here, so the slider stays where the system has it.
            volume = ma.volume,
            positionMs = 0,
            durationMs = l.durationMs,
            hasTrack = t != null,
            idle = t == null,
            blank = t == null,
            // Decoded on this phone, so the phone's own output ceiling is the
            // honest answer to "what is coming out of the pipe".
            quality = deviceQuality,
            sourceQuality = null,
            source = if (t?.offline == true) "Offline" else "Navidrome",
            groupSize = 1,
            shuffle = l.shuffle,
            repeatMode = l.repeat,
            queueSize = l.queueSize,
            currentQueueItemId = localQueueItemId(l.index, t?.id),
            playbackSpeed = l.speed,
            dontStopTheMusic = false,
            powered = true,
            canPower = false,
            isLocalSession = true,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, State())

    /** Track-id for [reanchorPosition] — resolved from the queue's current item. */
    private fun currentTrackId(): String? {
        val id = targetId()
        val streamId = _players.value.firstOrNull { it.playerId == id }?.syncedTo ?: id
        val q = _queues.value.firstOrNull { it.queueId == streamId }
        return q?.currentQueueItemId ?: q?.currentItem?.uri
    }

    // Drive the position engine from the combined state. Each poll/event that
    // changes players/queues flows through [state], which re-anchors the engine.
    init {
        viewModelScope.launch {
            state.collect { st ->
                // The local player reports its own position; projecting a server
                // anchor forward on top of it would fight it.
                if (isLocal) return@collect
                reanchorPosition(
                    serverElapsedMs = st.positionMs,
                    isPlaying = st.isPlaying,
                    duration = st.durationMs,
                    speed = st.playbackSpeed,
                    trackId = if (st.hasTrack) currentTrackId() else lastTrackId,
                )
            }
        }
    }

    /**
     * The library item behind what's playing, taken from the queue's `current_item`.
     * Everything track-specific — the heart, lyrics, "more like this" — needs a real
     * `item_id` and provider, which the player's `current_media` doesn't carry.
     */
    val currentItem: StateFlow<MaItem?> = combine(_queues, _players, _target, localSnap) { queues, players, target, l ->
        // Nothing MA-side is playing while the local player holds the session, so
        // the track-scoped controls (heart, lyrics, similar) have no handle to act
        // on and correctly show as unavailable.
        if (l.active) return@combine null
        val id = target.ifBlank { myPlayerId }
        val streamId = players.firstOrNull { it.playerId == id }?.syncedTo ?: id
        queues.firstOrNull { it.queueId == streamId }?.currentItem
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Un-acknowledged favourite flip: item id → wanted state. */
    private val _favoriteOverride = MutableStateFlow<Pair<String, Boolean>?>(null)

    val favorite: StateFlow<Boolean> = combine(currentItem, _favoriteOverride) { item, override ->
        if (item == null) false
        else if (override != null && override.first == item.itemId) override.second
        else item.favorite
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // --- panels (loaded on demand) ----------------------------------------

    private val _queueItems = MutableStateFlow<Load<List<MaQueueItem>>>(Load.Idle)
    val queueItems: StateFlow<Load<List<MaQueueItem>>> = _queueItems

    private val _lyrics = MutableStateFlow<Load<MaLyrics?>>(Load.Idle)
    val lyrics: StateFlow<Load<MaLyrics?>> = _lyrics

    private val _similar = MutableStateFlow<Load<List<MaSimilarTrack>>>(Load.Idle)
    val similar: StateFlow<Load<List<MaSimilarTrack>>> = _similar

    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toast: SharedFlow<String> = _toast.asSharedFlow()

    val connected: StateFlow<Boolean> = api.state
        .map { it == MaApiClient.State.CONNECTED }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        viewModelScope.launch {
            val url = settings.maBaseUrl.first()
            val user = settings.maUsername.first()
            val pass = settings.maPassword.first()
            if (url.isNotBlank()) api.connect(url, token = null, username = user.ifBlank { null }, password = pass.ifBlank { null })
        }
        viewModelScope.launch { settings.targetPlayer.collect { _target.value = it } }
        // Remember whatever the selected player last had loaded.
        viewModelScope.launch {
            combine(_players, _target) { players, target ->
                val id = target.ifBlank { myPlayerId }
                players.firstOrNull { it.playerId == id }?.nowPlaying
            }.collect { np -> if (np != null && np.title.isNotBlank()) _lastTrack.value = np }
        }
        // A new track invalidates whatever the panels were showing.
        viewModelScope.launch {
            currentItem.map { it?.itemId }.distinctUntilChanged().collect {
                _lyrics.value = Load.Idle
                _similar.value = Load.Idle
                _favoriteOverride.value = null
                if (_queueItems.value !is Load.Idle) loadQueue(silent = true)
            }
        }
        // The local queue lives in memory and can change under an open panel — a
        // track finishing, a "play next", a drag — so the panel follows it.
        viewModelScope.launch {
            local.queue.collect {
                if (isLocal && _queueItems.value !is Load.Idle) _queueItems.value = Load.Ready(localQueueItems())
            }
        }
        viewModelScope.launch {
            local.current.collect {
                if (!isLocal) return@collect
                _lyrics.value = Load.Idle
                _similar.value = Load.Idle
            }
        }
        viewModelScope.launch { api.state.collect { if (it == MaApiClient.State.CONNECTED) refresh() } }
        // Poll for metadata (track info, volume, queue state). Position is driven
        // by the server-anchored ticker, so this can be relaxed — 5s is enough for
        // metadata + as a fallback anchor for the position engine.
        viewModelScope.launch {
            while (true) {
                delay(5_000)
                if (api.state.value == MaApiClient.State.CONNECTED) refresh()
            }
        }
        // Refresh promptly on MA player/queue events — but debounce bursts so
        // rapid skips (which emit multiple events) only trigger one refresh.
        viewModelScope.launch {
            api.events
                .filter { it["event"]?.toString()?.let { e -> "player" in e || "queue" in e } ?: false }
                .debounce(150)   // coalesce event bursts from rapid skips/seek
                .collect { refresh() }
        }
    }

    private fun refresh() {
        viewModelScope.launch {
            try { _players.value = repo.players() } catch (_: Exception) {}
            try { _queues.value = repo.queues() } catch (_: Exception) {}
        }
    }

    // --- transport (act on the selected player) ---------------------------

    fun playPause() {
        if (isLocal) { local.toggle(); return }
        act { if (state.value.isPlaying) repo.pause(targetId()) else repo.play(targetId()) }
    }

    /**
     * Stop playback entirely — not just pause, but stop and clear the session.
     * Works for both the local player (Navidrome/offline) and the MA player.
     */
    fun stop() {
        if (isLocal) { local.stop(); return }
        act { repo.stop(targetId()) }
    }

    // In-flight guards prevent button-mash spam: rapid taps fire only one MA
    // command, the rest are dropped. A short cooldown after the command lands
    // prevents overlapping refresh coroutines.
    private val nextInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    private val prevInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    fun next() {
        if (isLocal) { local.next(); return }
        if (!nextInFlight.compareAndSet(false, true)) return
        // Optimistic: reset position immediately so the UI doesn't show the old
        // track's position while waiting for the server to confirm the skip.
        _positionMs.value = 0L
        posBaseTime = 0L
        posBaseTimestamp = System.currentTimeMillis()
        viewModelScope.launch {
            try { repo.next(targetId()) } catch (_: Exception) {}
            finally {
                delay(250)   // small cooldown for server to settle
                nextInFlight.set(false)
            }
        }
    }

    fun previous() {
        if (isLocal) { local.previous(); return }
        if (!prevInFlight.compareAndSet(false, true)) return
        _positionMs.value = 0L
        posBaseTime = 0L
        posBaseTimestamp = System.currentTimeMillis()
        viewModelScope.launch {
            try { repo.previous(targetId()) } catch (_: Exception) {}
            finally {
                delay(400)   // 400ms cooldown — matches massdroid_native's prev cooldown
                prevInFlight.set(false)
            }
        }
    }

    fun toggleShuffle() {
        if (isLocal) { local.setShuffle(!state.value.shuffle); return }
        act { repo.setShuffle(streamQueueId(), !state.value.shuffle) }
    }

    /** Cycles the way players conventionally do: off → all → one → off. */
    fun cycleRepeat() {
        if (isLocal) { local.cycleRepeat(); return }
        act {
            val next = when (state.value.repeatMode) {
                "off" -> "all"
                "all" -> "one"
                else -> "off"
            }
            repo.setRepeat(streamQueueId(), next)
        }
    }

    /** Queue commands go to the group leader, since members share its queue. */
    private fun streamQueueId(): String {
        val id = targetId()
        return _players.value.firstOrNull { it.playerId == id }?.syncedTo ?: id
    }
    fun seekTo(fraction: Float) {
        if (isLocal) {
            val dur = state.value.durationMs
            if (dur > 0) local.seekTo((fraction.coerceIn(0f, 1f) * dur).toLong())
            return
        }
        seekOnServer(fraction)
    }

    private fun seekOnServer(fraction: Float) = act {
        val dur = state.value.durationMs
        if (dur > 0) {
            // Clamp to [0, duration - 1s] so MA doesn't interpret a seek-to-end
            // as "skip to next track". 1 second of headroom is enough.
            val maxFraction = ((dur - 1000L).coerceAtLeast(0L).toFloat() / dur)
            val clamped = fraction.coerceIn(0f, maxFraction)
            repo.seek(targetId(), ((clamped * dur) / 1000).toInt())
        }
    }

    private var volJob: kotlinx.coroutines.Job? = null
    fun setVolume(level01: Float) {
        if (isLocal) { local.setVolume(level01.coerceIn(0f, 1f)); return }
        val lvl = (level01 * 100).toInt().coerceIn(0, 100)
        _players.update { list -> list.map { if (it.playerId == targetId()) it.copy(volumeLevel = lvl) else it } }
        volJob?.cancel()
        volJob = viewModelScope.launch { delay(180); try { repo.setVolume(targetId(), lvl) } catch (_: Exception) {} }
    }

    // --- player + queue options -------------------------------------------

    fun setPower(on: Boolean) = act(toastOnError = "Couldn't switch the player") {
        repo.setPower(targetId(), on)
    }

    private var speedJob: kotlinx.coroutines.Job? = null
    fun setPlaybackSpeed(speed: Float) {
        if (isLocal) { local.setSpeed(speed); return }
        val q = streamQueueId()
        _queues.update { list -> list.map { if (it.queueId == q) it.copy(playbackSpeed = speed) else it } }
        speedJob?.cancel()
        speedJob = viewModelScope.launch {
            delay(200)
            try { repo.setPlaybackSpeed(q, speed) } catch (e: Exception) { _toast.tryEmit(e.message ?: "Couldn't set speed") }
        }
    }

    fun toggleDontStopTheMusic() {
        if (isLocal) { _toast.tryEmit("Don't stop the music needs Music Assistant"); return }
        val q = streamQueueId()
        val next = !state.value.dontStopTheMusic
        _queues.update { list -> list.map { if (it.queueId == q) it.copy(dontStopTheMusic = next) else it } }
        act(toastOnError = "Couldn't change Don't stop the music") { repo.setDontStopTheMusic(q, next) }
    }

    // --- favourite ---------------------------------------------------------

    fun toggleFavorite() {
        val item = currentItem.value ?: return
        val wanted = !favorite.value
        _favoriteOverride.value = item.itemId to wanted
        viewModelScope.launch {
            try {
                if (wanted) repo.addFavorite(item) else repo.removeFavorite(item)
                _toast.tryEmit(if (wanted) "Added to favourites" else "Removed from favourites")
                // Let MA write it through before we trust `favorite` off the queue again.
                delay(1_200); refresh(); delay(1_500)
                _favoriteOverride.value = null
            } catch (e: Exception) {
                _favoriteOverride.value = null
                _toast.tryEmit(e.message ?: "Couldn't change favourite")
            }
        }
    }

    // --- queue -------------------------------------------------------------

    /**
     * Fetch the queue's items.
     *
     * [silent] keeps whatever is already on screen while the new list is in flight.
     * Dropping to [Load.Loading] tore the list out from under the user on every
     * refresh — the panel flashed a spinner, lost its scroll position and came back
     * re-collapsed, which is what made playing a track from the queue feel like it
     * scrambled the list. There is nothing to show a spinner *for* when the list is
     * already there and only its ordering might have moved.
     */
    fun loadQueue(silent: Boolean = false) {
        // The local queue is already in memory — there is nothing to fetch, and a
        // spinner in front of a list we are holding would be theatre.
        if (isLocal) { _queueItems.value = Load.Ready(localQueueItems()); return }
        val q = streamQueueId()
        if (!silent || _queueItems.value !is Load.Ready) _queueItems.value = Load.Loading
        viewModelScope.launch {
            val next = try {
                Load.Ready(repo.queueItems(q))
            } catch (e: Exception) {
                Load.Failed(e.message ?: "Couldn't load the queue")
            }
            // A silent refresh that failed leaves the good list up rather than
            // replacing it with an error for a blip we can retry on the next poll.
            if (silent && next is Load.Failed && _queueItems.value is Load.Ready) return@launch
            _queueItems.value = next
        }
    }

    /**
     * Jump to a queue position. The queue's *contents* don't change, only which row
     * is current — which the ordinary player poll already reports — so this
     * deliberately does not re-fetch the items.
     */
    fun playQueueIndex(index: Int) {
        if (isLocal) { local.playAt(index); return }
        val q = streamQueueId()
        viewModelScope.launch {
            try {
                repo.playIndex(q, index)
            } catch (e: Exception) {
                _toast.tryEmit(e.message ?: "Couldn't play that")
                return@launch
            }
            delay(350); refresh()
        }
    }

    fun removeQueueItem(item: MaQueueItem) {
        if (isLocal) { local.removeAt(item.index); loadQueue(); return }
        queueAction { repo.deleteQueueItem(it, item.queueItemId) }
    }

    /** [shift] is relative: -1 moves the item one place earlier, +1 one later. */
    fun moveQueueItem(item: MaQueueItem, shift: Int) {
        if (isLocal) { local.move(item.index, shift); loadQueue(); return }
        queueAction { repo.moveQueueItem(it, item.queueItemId, shift) }
    }

    fun clearQueue() {
        if (isLocal) { local.clear(); loadQueue(); return }
        queueAction { repo.clearQueue(it) }
    }

    /** The local queue as queue rows, so one panel renders either session. */
    private fun localQueueItems(): List<MaQueueItem> =
        local.queue.value.mapIndexed { i, t ->
            MaQueueItem(
                queueItemId = localQueueItemId(i, t.id) ?: "$i",
                name = t.title,
                duration = (t.durationMs / 1000).toInt().takeIf { it > 0 },
                sortIndex = i,
                mediaItem = null,
                streamDetails = null,
                index = i,
                artist = t.artist,
                image = t.artUrl,
            )
        }

    /**
     * A stable row key. The track id alone isn't enough — the same track can sit in
     * the queue twice, and two rows sharing a key breaks the drag-reorder list.
     */
    private fun localQueueItemId(index: Int, id: String?): String? = id?.let { "$index-$it" }

    fun saveQueueAsPlaylist(name: String) {
        if (isLocal) { _toast.tryEmit("Saving a playlist needs Music Assistant"); return }
        if (name.isBlank()) return
        val q = streamQueueId()
        viewModelScope.launch {
            try {
                repo.saveQueueAsPlaylist(q, name.trim())
                _toast.tryEmit("Saved \"${name.trim()}\"")
            } catch (e: Exception) {
                _toast.tryEmit(e.message ?: "Couldn't save the playlist")
            }
        }
    }

    private inline fun queueAction(crossinline block: suspend (String) -> Unit) {
        val q = streamQueueId()
        viewModelScope.launch {
            try {
                block(q)
            } catch (e: Exception) {
                _toast.tryEmit(e.message ?: "Queue command failed")
                return@launch
            }
            // MA rebuilds the queue asynchronously, so re-read rather than guess —
            // in place, so the panel doesn't blink out and back. Read twice: the
            // first can land before the server has finished applying a move, and a
            // stale read would visibly undo the row the user just dragged. The
            // second is free when nothing changed, since an equal list is not
            // re-emitted.
            delay(350); refresh(); loadQueue(silent = true)
            delay(900); loadQueue(silent = true)
        }
    }

    // --- lyrics ------------------------------------------------------------

    fun loadLyrics() {
        val item = currentItem.value ?: run {
            _lyrics.value = Load.Failed("Nothing playing")
            return
        }
        _lyrics.value = Load.Loading
        viewModelScope.launch {
            _lyrics.value = try {
                Load.Ready(repo.getLyrics(item))
            } catch (e: Exception) {
                Load.Failed(e.message ?: "Couldn't fetch lyrics")
            }
        }
    }

    // --- sonic similarity ---------------------------------------------------

    /** Acoustically similar tracks to what's playing. */
    fun loadSimilar() {
        val item = currentItem.value ?: run {
            _similar.value = Load.Failed("Nothing playing")
            return
        }
        _similar.value = Load.Loading
        viewModelScope.launch {
            _similar.value = try {
                Load.Ready(repo.similarTracks(item.itemId, item.provider))
            } catch (e: Exception) {
                Load.Failed(e.message ?: "Couldn't find similar tracks")
            }
        }
    }

    /** Natural-language search over the sonic embeddings ("rainy sunday piano"). */
    fun sonicSearch(query: String) {
        if (query.isBlank()) return loadSimilar()
        _similar.value = Load.Loading
        viewModelScope.launch {
            _similar.value = try {
                Load.Ready(repo.sonicTextSearch(query.trim()))
            } catch (e: Exception) {
                Load.Failed(e.message ?: "Search failed")
            }
        }
    }

    /** [option] is MA's QueueOption: `next` to play after this, `add` to append. */
    fun enqueue(track: MaSimilarTrack, option: String) {
        val uri = track.uri ?: "track://${track.provider}/${track.itemId}"
        val q = streamQueueId()
        viewModelScope.launch {
            try {
                repo.playMedia(q, listOf(uri), option)
                _toast.tryEmit(if (option == "next") "Playing next" else "Added to queue")
                delay(350); refresh()
                if (_queueItems.value !is Load.Idle) loadQueue(silent = true)
            } catch (e: Exception) {
                _toast.tryEmit(e.message ?: "Couldn't queue that")
            }
        }
    }

    /** A short preview of a track, without disturbing what's playing. */
    suspend fun previewUrl(itemId: String, provider: String): String? =
        try { repo.trackPreview(itemId, provider) } catch (_: Exception) { null }

    // --- sleep timer -------------------------------------------------------

    private var sleepTimerJob: kotlinx.coroutines.Job? = null
    private val _sleepTimerMin = MutableStateFlow(0); val sleepTimerMin: StateFlow<Int> = _sleepTimerMin

    /**
     * Milliseconds left on the running timer, ticking once a second — the chip had
     * no readout at all before, so a timer that was quietly counting down looked
     * exactly like a button that did nothing.
     */
    private val _sleepTimerRemainingMs = MutableStateFlow(0L)
    val sleepTimerRemainingMs: StateFlow<Long> = _sleepTimerRemainingMs

    /**
     * Start a sleep timer that fades playback to silence over the last 10 seconds,
     * then pauses. [minutes] = 0 cancels an existing timer.
     *
     * The countdown is driven off a wall-clock deadline rather than accumulated
     * delays, so a timer stays honest across a doze or a long GC pause.
     */
    fun setSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        if (minutes <= 0) {
            _sleepTimerMin.value = 0
            _sleepTimerRemainingMs.value = 0
            return
        }
        _sleepTimerMin.value = minutes
        val totalMs = minutes * 60_000L
        _sleepTimerRemainingMs.value = totalMs
        _toast.tryEmit("Sleeping in ${minutes}m")

        sleepTimerJob = viewModelScope.launch {
            val deadline = System.currentTimeMillis() + totalMs
            while (true) {
                val left = deadline - System.currentTimeMillis()
                _sleepTimerRemainingMs.value = left.coerceAtLeast(0)
                if (left <= FADE_MS) break
                delay(minOf(1_000L, left - FADE_MS))
            }
            // Fade out over the last stretch by stepping the volume down.
            val player = targetId()
            val startVol = state.value.volume
            val steps = 20
            for (i in 1..steps) {
                setVolume(startVol * (1f - i.toFloat() / steps))
                _sleepTimerRemainingMs.value = (deadline - System.currentTimeMillis()).coerceAtLeast(0)
                delay(FADE_MS / steps)
            }
            if (isLocal) local.pause() else try { repo.pause(player) } catch (_: Exception) {}
            setVolume(startVol)   // restore so the user's volume isn't stuck at zero
            _sleepTimerMin.value = 0
            _sleepTimerRemainingMs.value = 0
            _toast.tryEmit("Sleep timer ended")
        }
    }

    fun cancelSleepTimer() {
        val wasRunning = _sleepTimerMin.value > 0
        setSleepTimer(0)
        if (wasRunning) _toast.tryEmit("Sleep timer cancelled")
    }

    private inline fun act(toastOnError: String? = null, crossinline block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                if (toastOnError != null) _toast.tryEmit(e.message ?: toastOnError)
            }
            // No delay(300) — the MA WebSocket pushes player/queue events which
            // trigger refresh() and re-anchor the position engine. The 300ms wait
            // was adding visible latency to every transport action.
            refresh()
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Shared MaApiClient — don't disconnect it when one ViewModel is destroyed.
    }

    private companion object {
        /** How long the sleep timer spends fading out before it pauses. */
        const val FADE_MS = 10_000L
    }

    /**
     * Where the queue's current track is really streaming from.
     *
     * A library item's own `provider` is always `library` — that says Music
     * Assistant catalogued it, not who holds the file — so reading the badge off it
     * labelled every Navidrome track "MA". The honest answers, in order:
     *
     *  1. `streamdetails.provider` — the backend MA actually opened for this play;
     *  2. the item's `provider_mappings` — who *could* supply it, which for a
     *     single-backend library is the same thing;
     *  3. the item's own provider, for a non-library (direct provider) item.
     */
    private fun sourceOf(queue: MaQueue): String {
        val item = queue.currentItem
        val provider = queue.streamProvider?.takeIf { it.isNotBlank() }
            ?: item?.providerDomains?.firstOrNull { !it.isLibrary() }
            ?: item?.provider
            ?: return ""
        return sourceLabel(provider)
    }

    private fun String.isLibrary(): Boolean =
        substringBefore("--").lowercase() in setOf("library", "builtin")

    /**
     * The badge text for a provider.
     *
     * Music Assistant identifies a provider either by its domain ("spotify") or by
     * an instance id carrying a `--<hash>` suffix ("spotify--AbC123"), so match on
     * the part in front of the suffix. Only MA's own library counts as "MA" — an
     * unrecognised provider is named after itself rather than silently claimed,
     * which is what made every track look like it came from MA.
     */
    private fun sourceLabel(provider: String): String {
        val domain = provider.substringBefore("--").lowercase()
        return when (domain) {
            "subsonic", "opensubsonic" -> "Navidrome"
            "library", "builtin" -> "MA"
            "spotify" -> "Spotify"
            "ytmusic", "youtube" -> "YouTube"
            "tidal" -> "Tidal"
            "qobuz" -> "Qobuz"
            "deezer" -> "Deezer"
            "apple_music" -> "Apple Music"
            "soundcloud" -> "SoundCloud"
            "radiobrowser" -> "Radio"
            "filesystem_local", "filesystem_smb" -> "Files"
            // "some_provider" → "Some Provider": better a rough name than a wrong one.
            else -> domain.split('_').filter { it.isNotEmpty() }
                .joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
                .ifBlank { "MA" }
        }
    }
}
