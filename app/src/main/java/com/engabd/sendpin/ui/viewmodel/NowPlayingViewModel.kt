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
import com.engabd.sendpin.subsonic.SubsonicClient
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
    /** True while this phone's Sendspin stream is actually running. */
    private val sendspinPlaying = (app as SendpinApp).playback.isPlaying
    /** What this phone can put out on its own, for locally-decoded playback. */
    private val deviceQuality = FormatNegotiator.deviceOutputQuality()

    /**
     * The Navidrome transcode currently asked for, or null when streaming the stored
     * file. Held as plain state rather than read per frame — it changes rarely and the
     * badge is rebuilt on every poll.
     */
    @Volatile private var navFormat: String? = null

    /**
     * The format a Navidrome transcode token describes, for the Source row. Rates and
     * depths are the codecs' own defaults — the token only pins codec and bitrate, and
     * claiming a sample rate the server never promised would be worse than omitting it.
     */
    private fun transcodeQuality(token: String): StreamQuality? {
        val codec = token.substringBefore('-')
        val kbps = token.substringAfter('-', "").toIntOrNull() ?: 0
        return when (codec) {
            "flac" -> StreamQuality("FLAC", 0, 16)
            "mp3" -> StreamQuality("MP3", 0, 0, kbps)
            "opus" -> StreamQuality("OPUS", 48_000, 0, kbps)
            else -> null
        }
    }

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
    // The bar is not snapped to whatever the last poll said; it is projected
    // forward from an anchor by [PlayerPositionTracker], a port of the official
    // Music Assistant app's tracker. See that file for why the shape matters —
    // in short, a seek or a skip freezes the anchor until audio is confirmed, and
    // server readings are only accepted when they are demonstrably fresher.
    private val positions = PlayerPositionTracker()

    /** Which queue the tracker is keyed on: the group leader, since members share it. */
    private fun positionKey(): String = streamQueueId()

    /** Identity of the current track, for detecting a change between polls. */
    @Volatile private var lastTrackId: String? = null

    private val _positionMs = MutableStateFlow(0L)

    /**
     * Where the scrubber sits. The local player knows its own position exactly, so
     * when it is the one playing there is nothing to project forward from a server
     * anchor — the anchored engine only applies to the MA-side player.
     */
    val positionMs: StateFlow<Long> =
        combine(localSnap, _positionMs, local.positionMs) { l, server, here ->
            if (l.active) here else server
        }.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    private var tickerJob: Job? = null

    /**
     * Republish the tracker's projection into [_positionMs] for [positionKey].
     *
     * Restarted whenever the key changes; the tracker's own [PlayerPositionTracker.observe]
     * is cold and stops ticking on its own when the queue is paused or frozen.
     */
    private fun followPosition(queueId: String) {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            var lastEndPoll = 0L
            positions.observe(queueId).collect { ms ->
                _positionMs.value = ms
                // The projection has run out the track but no fresh anchor arrived —
                // the server has almost certainly moved on. Ask, rather than sitting
                // pinned at the duration until the next 5s poll. Rate-limited: the
                // ticker keeps emitting while pinned, and one poll per second is
                // plenty to catch a boundary.
                if (positions.isAtEnd(queueId)) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastEndPoll > 1_000L) { lastEndPoll = now; refresh() }
                }
            }
        }
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
            // `quality` is Playing: what this device is actually putting out, and the
            // same reading the Settings screen prints under "Format". When this phone
            // is the player that is the negotiated Sendspin stream and nothing else —
            // falling back to the queue's stream details here is what made the badge
            // disagree with Settings on the very same track. A remote speaker gives us
            // no output handle at all, so there the queue's details are all there is.
            quality = when {
                !isSelf -> queueQuality
                local != null -> local
                // Nothing negotiated but something *is* playing: the file is being
                // decoded here (a Navidrome stream played direct), so the phone's own
                // output ceiling is the honest answer.
                live != null -> deviceQuality
                else -> null
            },
            // `sourceQuality` is Source: the library file's own format, before anything
            // touched it.
            //
            // The library item's `provider_mappings.audio_format` comes first because it
            // is the only reading that genuinely describes the *file*; the queue's
            // stream details describe what MA opened, which is a fallback for a track
            // whose mappings didn't come through. Reading both off `queueQuality` —
            // which is what the previous ordering did whenever stream details existed —
            // handed Playing and Source the same object, and the popup's
            // "hide Source when it matches Playing" rule then dropped the row on every
            // MA track. Two independent readings is what makes the row mean something.
            sourceQuality = queue?.currentItem?.audioFormat?.let {
                StreamQuality(it.codec, it.sampleRate, it.bitDepth)
            } ?: queueQuality,
            // The badge names the player, not the track's provider: anything MA is
            // driving is "MA", however MA got hold of the file. The local-player
            // branch below is the Navidrome/offline case.
            source = "MA",
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
            // Playing is what this phone is being fed and decoding.
            //
            // It used to be `deviceQuality`, which is hard-coded "PCM" — the
            // AudioTrack's own encoding — so the badge read PCM no matter what
            // Navidrome had been asked for, and choosing a format looked like it did
            // nothing. A downloaded file plays from disk untouched; a requested
            // transcode is what arrives; otherwise it is the library file itself, and
            // only when the library said nothing at all do we fall back to describing
            // the output.
            quality = when {
                t == null -> null
                t.offline -> t.sourceQuality ?: deviceQuality
                else -> navFormat?.let { transcodeQuality(it) } ?: t.sourceQuality ?: deviceQuality
            },
            // Source stays the library file, so a transcode shows as one.
            sourceQuality = t?.sourceQuality,
            // This phone is playing on its own, which only ever means the Navidrome
            // library (or a downloaded copy of it).
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

    /**
     * Identity of the queue's current track.
     *
     * `queue_item_id` is the real answer, but a server that omits it would make track
     * changes structurally undetectable — and the old engine's `trackId != null` guard
     * turned that into "the bar never resets". Title/artist/duration is a coarse but
     * always-available fallback.
     */
    private fun currentTrackId(): String? {
        val q = _queues.value.firstOrNull { it.queueId == streamQueueId() }
        q?.currentQueueItemId?.let { return it }
        q?.currentItem?.uri?.let { return it }
        val np = _players.value.firstOrNull { it.playerId == targetId() }?.nowPlaying ?: return null
        if (np.title.isBlank()) return null
        return "${np.title}|${np.artist}|${np.durationMs}"
    }

    // ── Optimistic freeze bookkeeping ────────────────────────────────────────
    // A freeze makes the tracker ignore server readings, which is exactly what we
    // want right after a seek or a skip — and exactly what must not wedge. Every
    // freeze is therefore paired with a watchdog that force-confirms, so a server
    // that never catches up costs a stuck second, not a stuck bar.
    private var freezeWatchdog: Job? = null
    @Volatile private var pendingSeekMs: Long? = null
    @Volatile private var pendingSkipFromTrack: String? = null

    private fun freezeForSeek(target: Long) {
        val key = positionKey()
        pendingSeekMs = target
        pendingSkipFromTrack = null
        positions.setOptimisticSeek(key, target, state.value.durationMs.takeIf { it > 0 })
        armFreezeWatchdog(key)
    }

    private fun freezeForTrackChange() {
        val key = positionKey()
        pendingSeekMs = null
        pendingSkipFromTrack = lastTrackId
        positions.setOptimisticTrackChange(key)
        armFreezeWatchdog(key)
    }

    private fun armFreezeWatchdog(key: String) {
        freezeWatchdog?.cancel()
        freezeWatchdog = viewModelScope.launch {
            delay(FREEZE_TIMEOUT_MS)
            releaseFreeze(key)
        }
    }

    private fun releaseFreeze(key: String) {
        pendingSeekMs = null
        pendingSkipFromTrack = null
        freezeWatchdog?.cancel(); freezeWatchdog = null
        positions.confirmPlaying(key)
    }

    // Drive the position engine off the raw player/queue state rather than the
    // derived [state], because the staleness stamp doesn't survive that projection.
    init {
        viewModelScope.launch {
            combine(_players, _queues, _target) { players, queues, _ ->
                Triple(players, queues, Unit)
            }.collect { (players, queues, _) ->
                // The local player reports its own position; projecting a server
                // anchor forward on top of it would fight it.
                if (isLocal) return@collect
                anchorFromServer(players, queues)
            }
        }
        // Keep the republishing ticker pointed at whichever queue is current.
        viewModelScope.launch {
            combine(_players, _target) { _, _ -> streamQueueId() }
                .distinctUntilChanged()
                .collect { followPosition(it) }
        }
    }

    /** Last `elapsed_time_last_updated` accepted, per queue — the staleness gate. */
    private val lastStamp = mutableMapOf<String, Double>()

    private fun anchorFromServer(players: List<MaPlayer>, queues: List<MaQueue>) {
        val id = targetId()
        val p = players.firstOrNull { it.playerId == id }
        val key = p?.syncedTo ?: id
        val q = queues.firstOrNull { it.queueId == key }

        val live = p?.nowPlaying?.takeIf { it.title.isNotBlank() }
        val isPlaying = live != null && p.isPlaying
        val duration = live?.durationMs ?: 0L
        // The queue's own playhead is the better reading — the player object can lag
        // it — but not every server fills it in.
        val elapsed = q?.elapsedMs ?: live?.elapsedMs ?: 0L

        val trackId = currentTrackId()
        val trackChanged = trackId != null && trackId != lastTrackId
        if (trackId != null) lastTrackId = trackId

        // Release an optimistic freeze once the server corroborates it.
        if (positions.isFrozen(key)) {
            val seekTarget = pendingSeekMs
            val skipFrom = pendingSkipFromTrack
            val confirmed = when {
                // A skip is confirmed by the server naming a different track.
                skipFrom != null -> trackId != null && trackId != skipFrom
                // A seek is confirmed when the server's clock lands near the target.
                // Deliberately not gated on isPlaying: a seek while paused stays
                // paused (see [seekOnServer]) and still has to release.
                seekTarget != null -> kotlin.math.abs(elapsed - seekTarget) < SEEK_CONFIRM_MS
                else -> true
            }
            if (!confirmed) return
            releaseFreeze(key)
        }

        val speed = q?.playbackSpeed ?: 1f

        // A track change is news no matter what the clock says: anchor at zero.
        if (trackChanged) {
            lastStamp.remove(key)
            q?.elapsedTimeLastUpdated?.let { lastStamp[key] = it }
            positions.setAnchor(key, 0L, isPlaying = isPlaying, durationMs = duration, speed = speed)
            return
        }

        // Staleness gate. A repeated `elapsed_time_last_updated` means the server has
        // not recomputed the playhead — for a remote speaker that is most polls — so
        // re-anchoring on it would keep re-applying however stale the reading was,
        // dragging the bar backwards on every poll. Let the projection carry on.
        val stamp = q?.elapsedTimeLastUpdated
        val staleReading = stamp != null && lastStamp[key]?.let { stamp <= it } == true
        if (staleReading) {
            // The clock is old news, but a play/pause is not — and [setPlaying]
            // snapshots the projection rather than trusting the stale number.
            positions.setPlaying(key, isPlaying)
            return
        }
        if (stamp != null) lastStamp[key] = stamp

        positions.setAnchor(key, elapsed, isPlaying = isPlaying, durationMs = duration, speed = speed)
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
        viewModelScope.launch {
            settings.navStreamFormat.collect { navFormat = it.takeIf { f -> f != "raw" } }
        }
        // When this phone *is* the Music Assistant player, the Sendspin stream starting
        // is proof that audio is flowing — which is exactly what releases an optimistic
        // freeze in the official app, rather than a guess made from polled state. A
        // remote speaker gives us no such signal and still relies on the poll
        // corroborating the skip (see [anchorFromServer]).
        viewModelScope.launch {
            sendspinPlaying.collect { playing ->
                if (playing && !isLocal) {
                    val key = positionKey()
                    if (positions.isFrozen(key)) releaseFreeze(key)
                }
            }
        }
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
        // Refresh promptly on MA player/queue events, at a bounded rate.
        //
        // Deliberately `sample`, not `debounce`: MA emits `queue_time_updated` about
        // once a second per active queue and bursts during skips, and debounce only
        // emits after a gap of silence — a busy server starved it completely, leaving
        // the 5s poll as the only refresh. Sample fires on a cadence regardless.
        viewModelScope.launch {
            api.events
                .filter { it["event"]?.toString()?.let { e -> "player" in e || "queue" in e } ?: false }
                .sample(300)
                .collect { refresh() }
        }
    }

    /** Only one refresh in flight; requests arriving during one collapse into a re-run. */
    private val refreshing = java.util.concurrent.atomic.AtomicBoolean(false)
    private val refreshQueued = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Re-read players and queues.
     *
     * Serialised on purpose. Four call sites drive this (connect, the 5s poll, the
     * event stream, and every transport command), and overlapping responses used to
     * land out of order — a slow earlier read overwriting a newer one pinned the UI
     * to the previous track until something forced another poll.
     *
     * An empty result is also not accepted while the socket is down: [MaApiClient]
     * completes pending requests with `null` on a drop, which parses to an empty
     * list, and adopting that blanks the screen mid-track.
     */
    private fun refresh() {
        // Requests that arrive mid-flight are not dropped — they set a flag and the
        // running pass loops once more, so a poll landing during a transport command's
        // refresh can't leave the UI a round behind.
        if (!refreshing.compareAndSet(false, true)) { refreshQueued.set(true); return }
        viewModelScope.launch {
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
        // Optimistic, and *held*: MA keeps reporting the outgoing track's position
        // for a beat after the skip, so simply zeroing the bar would let the next
        // poll drag it straight back. The freeze holds zero until the server names
        // a different track.
        freezeForTrackChange()
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
        freezeForTrackChange()
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

    /**
     * Seek the MA player.
     *
     * Two server behaviours have to be worked around here:
     *
     *  - `players/cmd/seek` resolves into `play_index(..., seek_position=)`, which
     *    **starts** the queue. Seeking while paused therefore begins playback, which
     *    is not what dropping the scrubber means. Re-pause afterwards when that's the
     *    state the user was in.
     *  - The seek takes a moment to land, and until it does MA reports the *old*
     *    position. [freezeForSeek] holds the bar at the target so it doesn't snap
     *    back before jumping forward again.
     */
    private fun seekOnServer(fraction: Float) = act {
        val dur = state.value.durationMs
        if (dur <= 0) return@act
        // Clamp to [0, duration - 1s] so MA doesn't interpret a seek-to-end
        // as "skip to next track". 1 second of headroom is enough.
        val maxFraction = ((dur - 1000L).coerceAtLeast(0L).toFloat() / dur)
        val clamped = fraction.coerceIn(0f, maxFraction)
        val targetMs = (clamped * dur).toLong()
        val wasPlaying = state.value.isPlaying

        freezeForSeek(targetMs)
        repo.seek(targetId(), (targetMs / 1000).toInt())
        if (!wasPlaying) {
            // The seek restarted it. Put it back where it was — after a beat, or the
            // pause races the play the seek just issued.
            delay(250)
            runCatching { repo.pause(targetId()) }
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
     * Jump to the tapped queue row. The queue's *contents* don't change, only which
     * row is current — which the ordinary player poll already reports — so this
     * deliberately does not re-fetch the items.
     *
     * Addressed by **queue item id**, not by position. `player_queues/items` is a
     * paged read and its array order is not promised to be the play order MA counts
     * positions in, so a row's place in the list is not a reliable index — sending
     * one played the wrong track, or restarted the queue from the top. MA's
     * `play_index` takes `int | str` and resolves a string as a queue_item_id, which
     * cannot be off by one. The local player is matched by the same id.
     */
    fun playQueueItem(item: MaQueueItem) {
        if (isLocal) {
            val at = localQueueItems().indexOfFirst { it.queueItemId == item.queueItemId }
            local.playAt(if (at >= 0) at else item.index)
            return
        }
        val q = streamQueueId()
        freezeForTrackChange()
        viewModelScope.launch {
            try {
                repo.playQueueItem(q, item.queueItemId)
            } catch (e: Exception) {
                // A server that types `index` as an int rather than `int | str`
                // rejects the id. Fall back to the position rather than doing
                // nothing at all.
                try {
                    repo.playQueueIndex(q, item.index)
                } catch (_: Exception) {
                    _toast.tryEmit(e.message ?: "Couldn't play that")
                    return@launch
                }
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
        // The local player has its own queue; sending this to Music Assistant while
        // Navidrome is what's actually playing put the track somewhere the user
        // couldn't hear and reported success. There is no MA URI to hand over either —
        // a local track is a Subsonic id — so this branch has to build one.
        if (isLocal) {
            viewModelScope.launch {
                val queued = localTrackFor(track)
                if (queued == null) {
                    _toast.tryEmit("Can't queue that here")
                    return@launch
                }
                if (option == "next") local.playNext(listOf(queued)) else local.addToQueue(listOf(queued))
                _toast.tryEmit(if (option == "next") "Playing next" else "Added to queue")
                if (_queueItems.value !is Load.Idle) _queueItems.value = Load.Ready(localQueueItems())
            }
            return
        }
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

    /** Built on demand for the local-queue path; null when no Navidrome is configured. */
    private var subsonic: SubsonicClient? = null

    private suspend fun subsonicClient(): SubsonicClient? {
        subsonic?.let { return it }
        val url = settings.navUrl.first().trim()
        if (url.isBlank()) return null
        return SubsonicClient(url, settings.navUsername.first(), settings.navPassword.first())
            .also { it.streamFormat = settings.navStreamFormat.first(); subsonic = it }
    }

    /**
     * A [LocalTrack] for a similar-track suggestion, when one can be made.
     *
     * Only a Subsonic-provided suggestion can be: an MA-only track has no URL this
     * phone could fetch on its own, and there is nothing honest to queue.
     */
    private suspend fun localTrackFor(track: MaSimilarTrack): LocalTrack? {
        if (track.provider != SubsonicClient.PROVIDER) return null
        val sc = subsonicClient() ?: return null
        return LocalTrack(
            id = track.itemId,
            title = track.name,
            artist = track.artist.orEmpty(),
            album = "",
            artUrl = track.image,
            streamUrl = sc.streamUrl(track.itemId),
        )
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

        /**
         * How long an optimistic seek/skip may ignore the server before it gives up
         * and accepts whatever the server says. A freeze that never releases would
         * wedge the bar permanently, so this is the liveness backstop, not a tuning
         * knob — keep it comfortably longer than a slow round trip.
         */
        const val FREEZE_TIMEOUT_MS = 6_000L

        /** How close the server's clock must land to a seek target to count as landed. */
        const val SEEK_CONFIRM_MS = 3_000L
    }
}
