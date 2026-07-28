package com.engabd.sendpin.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.discovery.PlayerIdentity
import com.engabd.sendpin.ma.MaApiClient
import com.engabd.sendpin.ma.MaPlayer
import com.engabd.sendpin.ma.MaRepository
import com.engabd.sendpin.ma.SyncDelay
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Speaker grouping over the **Music Assistant** main API. The phone
 * ([myPlayerId]) is the group anchor: joining a player syncs it to us, unjoining
 * removes it, and the per-player "sync offset" is MA's Sendspin sync-delay
 * player-config value. MA-only by design — in Navidrome-direct mode there is no
 * multi-room to group.
 */
class SpeakersViewModel(app: Application) : AndroidViewModel(app) {

    /** One row in the Speakers screen. [volume]/[groupVolume] are 0f..1f. */
    data class SpeakerUi(
        val id: String,
        val name: String,
        val meta: String,
        val isSelf: Boolean,
        val isTarget: Boolean,        // the active play-to / group-leader player
        val volume: Float,
        val offsetMs: Int,
        val offsetKnown: Boolean,
    )

    private val settings = AppSettings(app)
    val myPlayerId: String = PlayerIdentity.getPlayerId(app)

    private val api = MaApiClient()
    private val repo = MaRepository(api)

    private val _players = MutableStateFlow<List<MaPlayer>>(emptyList())
    private val _syncDelays = MutableStateFlow<Map<String, SyncDelay>>(emptyMap())
    private val _target = MutableStateFlow("")       // selected play-to player ("" = this phone)
    private val _error = MutableStateFlow<String?>(null); val error: StateFlow<String?> = _error

    val connected: StateFlow<Boolean> = api.state
        .map { it == MaApiClient.State.CONNECTED }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Group leader = the active play-to player (the Speakers picker) when MA knows it,
     * else the phone, else any existing group leader.
     */
    private val leaderId: String get() = leaderIdOf(_players.value)

    val joined: StateFlow<List<SpeakerUi>> = combine(_players, _syncDelays, _target) { players, delays, _ ->
        val leader = leaderIdOf(players)
        players.filter { p -> isJoined(p, players) }
            .sortedByDescending { it.playerId == leader }
            .map { it.toUi(delays, leader) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val available: StateFlow<List<SpeakerUi>> = combine(_players, _target) { players, _ ->
        val leader = leaderIdOf(players)
        players.filter { p -> p.available && !isJoined(p, players) }
            .map { it.toUi(emptyMap(), leader) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Group volume for the slider (leader's group volume, else its own). */
    val groupVolume: StateFlow<Float> = combine(_players, _target) { players, _ ->
        val leader = players.firstOrNull { it.playerId == leaderIdOf(players) }
        ((leader?.groupVolume ?: leader?.volumeLevel ?: 0) / 100f).coerceIn(0f, 1f)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0f)

    init {
        viewModelScope.launch {
            val url = settings.maBaseUrl.first()
            val user = settings.maUsername.first()
            val pass = settings.maPassword.first()
            if (url.isNotBlank()) {
                api.connect(url, token = null, username = user.ifBlank { null }, password = pass.ifBlank { null })
            } else {
                _error.value = "Set a Music Assistant server in Settings first."
            }
        }
        viewModelScope.launch { settings.targetPlayer.collect { _target.value = it } }
        // Refresh on connect, then poll while the screen is alive.
        viewModelScope.launch {
            api.state.collect { if (it == MaApiClient.State.CONNECTED) refresh() }
        }
        viewModelScope.launch {
            while (true) {
                delay(5_000)
                if (api.state.value == MaApiClient.State.CONNECTED) refresh()
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                val players = repo.players()
                _players.value = players
                // Fetch sync-delays for currently-joined non-self players (best effort).
                val targets = players.filter { isJoined(it, players) && it.playerId != myPlayerId }
                val delays = _syncDelays.value.toMutableMap()
                for (p in targets) {
                    if (!delays.containsKey(p.playerId)) {
                        repo.getSyncDelay(p.playerId)?.let { delays[p.playerId] = it }
                    }
                }
                _syncDelays.value = delays
                _error.value = null
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    fun join(playerId: String) = act { repo.setMembers(leaderId, add = listOf(playerId)) }
    fun unjoin(playerId: String) = act {
        if (playerId == leaderId) return@act
        repo.ungroup(playerId)
    }

    fun groupAll() = act {
        val others = _players.value.filter { it.available && it.playerId != leaderId }.map { it.playerId }
        if (others.isNotEmpty()) repo.setMembers(leaderId, add = others)
    }

    fun ungroupAll() = act {
        val members = _players.value.filter { isJoined(it, _players.value) && it.playerId != leaderId }.map { it.playerId }
        if (members.isNotEmpty()) repo.ungroup(members) // ungroup each
    }

    /** Make [playerId] the active play-to player (and the group leader). */
    fun selectPlayer(playerId: String) {
        val id = if (playerId == myPlayerId) "" else playerId   // "" = this phone
        _target.value = id
        viewModelScope.launch { settings.setTargetPlayer(id) }
    }

    /** Play on the phone only: switch the target back to the phone and drop members. */
    fun playHereOnly() {
        selectPlayer(myPlayerId)
        ungroupAll()
    }

    fun setPlayerVolume(playerId: String, level01: Float) {
        val lvl = (level01 * 100).toInt().coerceIn(0, 100)
        _players.update { list -> list.map { if (it.playerId == playerId) it.copy(volumeLevel = lvl) else it } }
        act { repo.setVolume(playerId, lvl) }
    }

    fun setGroupVolume(level01: Float) {
        val lvl = (level01 * 100).toInt().coerceIn(0, 100)
        act { repo.setGroupVolume(leaderId, lvl) }
    }

    fun changeOffset(playerId: String, deltaMs: Int) {
        val cur = _syncDelays.value[playerId]
        act {
            val sd = cur ?: repo.getSyncDelay(playerId) ?: return@act
            val next = (sd.ms + deltaMs).coerceIn(-2000, 2000)
            repo.setSyncDelay(playerId, sd.key, next)
            _syncDelays.update { it + (playerId to sd.copy(ms = next)) }
        }
    }

    // --- helpers ----------------------------------------------------------

    private fun leaderIdOf(players: List<MaPlayer>): String {
        val t = _target.value
        if (t.isNotBlank() && players.any { it.playerId == t }) return t
        return players.firstOrNull { it.playerId == myPlayerId }?.playerId
            ?: players.firstOrNull { it.isLeader }?.playerId
            ?: myPlayerId
    }

    private fun isJoined(p: MaPlayer, players: List<MaPlayer>): Boolean {
        val leader = leaderIdOf(players)
        return p.playerId == leader || p.syncedTo == leader ||
            players.firstOrNull { it.playerId == leader }?.groupChilds?.contains(p.playerId) == true
    }

    private fun MaPlayer.toUi(delays: Map<String, SyncDelay>, leader: String): SpeakerUi {
        val self = playerId == myPlayerId
        val delay = delays[playerId]
        return SpeakerUi(
            id = playerId,
            name = if (self) "$name (this phone)" else name,
            meta = metaFor(this),
            isSelf = self,
            isTarget = playerId == leader,
            volume = (volumeLevel / 100f).coerceIn(0f, 1f),
            offsetMs = delay?.ms ?: 0,
            offsetKnown = self || delay != null,
        )
    }

    private fun metaFor(p: MaPlayer): String = when {
        p.type == "group" -> "Group"
        p.isLeader -> "Group leader"
        !p.available -> "Unavailable"
        !p.powered -> "Off"
        else -> "Ready"
    }

    private inline fun act(crossinline block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                _error.value = null
            } catch (e: Exception) {
                _error.value = e.message ?: "Command failed"
            }
            refresh()
            delay(700); refresh()   // MA updates group state asynchronously — catch the settle
        }
    }

    override fun onCleared() {
        super.onCleared()
        api.disconnect()
    }
}

/** Ungroup several players (no batch command needed — MA ungroups each by id). */
private suspend fun MaRepository.ungroup(playerIds: List<String>) {
    for (id in playerIds) ungroup(id)
}
