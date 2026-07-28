package com.engabd.sendpin.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.discovery.PlayerIdentity
import com.engabd.sendpin.ma.MaApiClient
import com.engabd.sendpin.ma.MaPlayer
import com.engabd.sendpin.ma.MaRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Now-Playing as a **controller**: reflects and controls the currently *selected*
 * player (the Speakers "Play here" target, "" = this phone) via the Music Assistant
 * API, so the screen shows whatever that player is playing — title/artist/art,
 * playing/paused, position — not just this phone's own Sendspin stream.
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
        val hasTrack: Boolean = false,
    )

    private val settings = AppSettings(app)
    private val myPlayerId = PlayerIdentity.getPlayerId(app)
    private val api = MaApiClient()
    private val repo = MaRepository(api)

    private val _target = MutableStateFlow("")
    private val _players = MutableStateFlow<List<MaPlayer>>(emptyList())

    private fun targetId() = _target.value.ifBlank { myPlayerId }

    val state: StateFlow<State> = combine(_players, _target) { players, target ->
        val id = target.ifBlank { myPlayerId }
        val p = players.firstOrNull { it.playerId == id }
        val np = p?.nowPlaying
        State(
            playerName = p?.name ?: "This phone",
            isSelf = id == myPlayerId,
            title = np?.title.orEmpty(),
            artist = np?.artist.orEmpty(),
            album = np?.album.orEmpty(),
            artworkUrl = np?.imageUrl,
            isPlaying = p?.isPlaying == true,
            volume = ((p?.volumeLevel ?: 100) / 100f).coerceIn(0f, 1f),
            positionMs = np?.elapsedMs ?: 0,
            durationMs = np?.durationMs ?: 0,
            hasTrack = np?.title?.isNotBlank() == true,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, State())

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
        }
    }

    // --- transport (act on the selected player) ---------------------------

    fun playPause() = act {
        if (state.value.isPlaying) repo.pause(targetId()) else repo.play(targetId())
    }
    fun next() = act { repo.next(targetId()) }
    fun previous() = act { repo.previous(targetId()) }
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

    private inline fun act(crossinline block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block() } catch (_: Exception) {}
            delay(300); refresh()
        }
    }

    override fun onCleared() {
        super.onCleared()
        api.disconnect()
    }
}
