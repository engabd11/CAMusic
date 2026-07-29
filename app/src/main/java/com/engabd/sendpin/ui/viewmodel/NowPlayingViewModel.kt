package com.engabd.sendpin.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.audio.FormatNegotiator
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
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
        /** Where the track came from: "MA" (Music Assistant) or "Navidrome". */
        val source: String = "",
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
    /** This phone's own Sendspin stream — the authoritative format when we're the player. */
    private val localQuality = (app as SendpinApp).playback.streamQuality
    /** What this phone can put out on its own, for locally-decoded playback. */
    private val deviceQuality = FormatNegotiator.deviceOutputQuality()

    private val _target = MutableStateFlow("")
    private val _players = MutableStateFlow<List<MaPlayer>>(emptyList())
    private val _queues = MutableStateFlow<List<MaQueue>>(emptyList())

    /**
     * The last track we actually saw playing. Polls can come back empty between
     * tracks, on reconnect, or while the server is still waking up; without this
     * the screen would blank out and then repopulate every time.
     */
    private val _lastTrack = MutableStateFlow<MaNowPlaying?>(null)

    private fun targetId() = _target.value.ifBlank { myPlayerId }

    val state: StateFlow<State> = combine(_players, _target, _queues, localQuality, _lastTrack) { players, target, queues, local, last ->
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
            // Source: where the bytes are actually coming from. See [sourceOf].
            source = queue?.let { sourceOf(it) } ?: "",
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
     * The library item behind what's playing, taken from the queue's `current_item`.
     * Everything track-specific — the heart, lyrics, "more like this" — needs a real
     * `item_id` and provider, which the player's `current_media` doesn't carry.
     */
    val currentItem: StateFlow<MaItem?> = combine(_queues, _players, _target) { queues, players, target ->
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
        viewModelScope.launch { api.state.collect { if (it == MaApiClient.State.CONNECTED) refresh() } }
        // Poll for state (and interpolate position between polls in the UI if needed).
        viewModelScope.launch {
            while (true) {
                delay(2_000)
                if (api.state.value == MaApiClient.State.CONNECTED) refresh()
            }
        }
        // Refresh promptly on MA player/queue events.
        viewModelScope.launch {
            api.events.collect {
                val type = it["event"]?.toString().orEmpty()
                if ("player" in type || "queue" in type) refresh()
            }
        }
    }

    private fun refresh() {
        viewModelScope.launch {
            try { _players.value = repo.players() } catch (_: Exception) {}
            try { _queues.value = repo.queues() } catch (_: Exception) {}
        }
    }

    // --- transport (act on the selected player) ---------------------------

    fun playPause() = act {
        if (state.value.isPlaying) repo.pause(targetId()) else repo.play(targetId())
    }
    fun next() = act { repo.next(targetId()) }
    fun previous() = act { repo.previous(targetId()) }

    fun toggleShuffle() = act { repo.setShuffle(streamQueueId(), !state.value.shuffle) }

    /** Cycles the way players conventionally do: off → all → one → off. */
    fun cycleRepeat() = act {
        val next = when (state.value.repeatMode) {
            "off" -> "all"
            "all" -> "one"
            else -> "off"
        }
        repo.setRepeat(streamQueueId(), next)
    }

    /** Queue commands go to the group leader, since members share its queue. */
    private fun streamQueueId(): String {
        val id = targetId()
        return _players.value.firstOrNull { it.playerId == id }?.syncedTo ?: id
    }
    fun seekTo(fraction: Float) = act {
        val dur = state.value.durationMs
        if (dur > 0) repo.seek(targetId(), ((fraction.coerceIn(0f, 1f) * dur) / 1000).toInt())
    }

    private var volJob: kotlinx.coroutines.Job? = null
    fun setVolume(level01: Float) {
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
        val q = streamQueueId()
        _queues.update { list -> list.map { if (it.queueId == q) it.copy(playbackSpeed = speed) else it } }
        speedJob?.cancel()
        speedJob = viewModelScope.launch {
            delay(200)
            try { repo.setPlaybackSpeed(q, speed) } catch (e: Exception) { _toast.tryEmit(e.message ?: "Couldn't set speed") }
        }
    }

    fun toggleDontStopTheMusic() {
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

    fun removeQueueItem(item: MaQueueItem) = queueAction { repo.deleteQueueItem(it, item.queueItemId) }

    /** [shift] is relative: -1 moves the item one place earlier, +1 one later. */
    fun moveQueueItem(item: MaQueueItem, shift: Int) = queueAction { repo.moveQueueItem(it, item.queueItemId, shift) }

    fun clearQueue() = queueAction { repo.clearQueue(it) }

    fun saveQueueAsPlaylist(name: String) {
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

    /** The presets the sleep-timer sheet offers, in minutes. */
    val sleepTimerPresets = listOf(5, 15, 30, 45, 60, 90)

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
            try { repo.pause(player) } catch (_: Exception) {}
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
            delay(300); refresh()
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
