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

/** How long a local intent outranks the server's answer. */
private const val SETTLE_MS = 6_000L

/**
 * Something the user just asked for that MA has not confirmed yet.
 *
 * Music Assistant applies grouping and volume asynchronously: a `players/all`
 * poll landing in the moments after a command still reports the *old* state.
 * Without this the UI would snap back to that stale answer, and every tap would
 * look like it did nothing — so you'd tap again. An intent wins over the server
 * until the server agrees with it, or until it expires.
 */
private class Intent<T>(val value: T, private val expiresAt: Long = System.currentTimeMillis() + SETTLE_MS) {
    val live: Boolean get() = System.currentTimeMillis() < expiresAt
}

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
        /** This player can lead a sync group, so it's a valid "Play here" target. */
        val canLead: Boolean,
    )

    private val settings = AppSettings(app)
    val myPlayerId: String = PlayerIdentity.getPlayerId(app)

    private val api = MaApiClient()
    private val repo = MaRepository(api)

    /** What MA last told us. Never written to optimistically — see [Intent]. */
    private val _players = MutableStateFlow<List<MaPlayer>>(emptyList())
    private val _syncDelays = MutableStateFlow<Map<String, SyncDelay>>(emptyMap())
    private val _target = MutableStateFlow("")       // selected play-to player ("" = this phone)
    private val _error = MutableStateFlow<String?>(null); val error: StateFlow<String?> = _error

    // Un-acknowledged local intents, laid over MA's answer.
    private val _volumeIntent = MutableStateFlow<Map<String, Intent<Int>>>(emptyMap())
    private val _groupVolumeIntent = MutableStateFlow<Intent<Int>?>(null)
    private val _joinIntent = MutableStateFlow<Map<String, Intent<Boolean>>>(emptyMap())

    val connected: StateFlow<Boolean> = api.state
        .map { it == MaApiClient.State.CONNECTED }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Group leader = the active play-to player (the Speakers picker) when MA knows it,
     * else the phone, else any existing group leader.
     */
    private val leaderId: String get() = leaderIdOf(_players.value, _target.value)

    val joined: StateFlow<List<SpeakerUi>> =
        combine(_players, _syncDelays, _target, _volumeIntent, _joinIntent) { players, delays, target, vol, join ->
            val leader = leaderIdOf(players, target)
            val members = joinedIds(players, join, leader)
            players.filter { it.playerId in members }
                .sortedByDescending { it.playerId == leader }
                .map { it.withVolume(vol).toUi(delays, leader) }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val available: StateFlow<List<SpeakerUi>> =
        combine(_players, _target, _volumeIntent, _joinIntent) { players, target, vol, join ->
            val leader = leaderIdOf(players, target)
            val members = joinedIds(players, join, leader)
            players.filter { it.available && it.playerId !in members }
                .map { it.withVolume(vol).toUi(emptyMap(), leader) }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Group volume for the slider (leader's group volume, else its own). */
    val groupVolume: StateFlow<Float> =
        combine(_players, _target, _groupVolumeIntent) { players, target, intent ->
            if (intent != null && intent.live) return@combine (intent.value / 100f).coerceIn(0f, 1f)
            val leader = players.firstOrNull { it.playerId == leaderIdOf(players, target) }
            ((leader?.groupVolume ?: leader?.volumeLevel ?: 0) / 100f).coerceIn(0f, 1f)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, 0f)

    /**
     * Whether the active player can lead a group. Sendspin players (incl. this phone)
     * can't — MA replies "does not support group commands" — so the screen greys Join
     * out and says why, rather than letting a tap fail.
     */
    val leaderCanGroup: StateFlow<Boolean> = combine(_players, _target) { players, target ->
        players.firstOrNull { it.playerId == leaderIdOf(players, target) }?.canSetMembers ?: false
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

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
        // MA pushes player events the moment grouping or volume actually changes —
        // far sooner than the poll, so a confirmed tap settles in ~100ms.
        viewModelScope.launch {
            api.events.collect {
                if ("player" in it["event"]?.toString().orEmpty()) refresh()
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                val players = repo.players()
                _players.value = players
                retireSettledIntents(players)
                // Fetch sync-delays for currently-joined non-self players (best effort).
                val leader = leaderIdOf(players, _target.value)
                val members = joinedIds(players, emptyMap(), leader)
                val delays = _syncDelays.value.toMutableMap()
                for (p in players) {
                    if (p.playerId == myPlayerId || p.playerId !in members) continue
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

    fun join(playerId: String) {
        val leader = _players.value.firstOrNull { it.playerId == leaderId }
        if (leader?.canSetMembers != true) {
            _error.value = "\"${leader?.name ?: "This player"}\" can't lead a group. Pick a speaker that supports grouping (tap its name to Play here), then Join others to it."
            return
        }
        _joinIntent.update { it + (playerId to Intent(true)) }
        act { repo.setMembers(leaderId, add = listOf(playerId)) }
    }

    fun unjoin(playerId: String) {
        if (playerId == leaderId) return
        _joinIntent.update { it + (playerId to Intent(false)) }
        act { repo.ungroup(playerId) }
    }

    fun groupAll() {
        val others = _players.value.filter { it.available && it.playerId != leaderId }.map { it.playerId }
        if (others.isEmpty()) return
        _joinIntent.update { m -> m + others.associateWith { Intent(true) } }
        act { repo.setMembers(leaderId, add = others) }
    }

    fun ungroupAll() {
        val leader = leaderId
        val members = joinedIds(_players.value, _joinIntent.value, leader).filter { it != leader }
        if (members.isEmpty()) return
        _joinIntent.update { m -> m + members.associateWith { Intent(false) } }
        act { repo.ungroup(members) }
    }

    /** Make [playerId] the active play-to player (and the group leader). */
    fun selectPlayer(playerId: String) {
        val id = if (playerId == myPlayerId) "" else playerId   // "" = this phone
        _target.value = id
        viewModelScope.launch { settings.setTargetPlayer(id) }
    }

    /** Play on the phone only: switch the target back to the phone and drop members. */
    fun playHereOnly() {
        ungroupAll()
        selectPlayer(myPlayerId)
    }

    // Volume dragging fires every frame; debounce so we don't flood MA (which then
    // logs "Ignoring command cmd_volume_set …" and lags). The intent holds the UI
    // at the dragged value until MA reports it back.
    private val volumeJobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    private var groupVolJob: kotlinx.coroutines.Job? = null

    fun setPlayerVolume(playerId: String, level01: Float) {
        val lvl = (level01 * 100).toInt().coerceIn(0, 100)
        _volumeIntent.update { it + (playerId to Intent(lvl)) }
        volumeJobs[playerId]?.cancel()
        volumeJobs[playerId] = viewModelScope.launch {
            delay(180)
            try { repo.setVolume(playerId, lvl) } catch (e: Exception) { _error.value = e.message }
        }
    }

    fun setGroupVolume(level01: Float) {
        val lvl = (level01 * 100).toInt().coerceIn(0, 100)
        _groupVolumeIntent.value = Intent(lvl)
        groupVolJob?.cancel()
        groupVolJob = viewModelScope.launch {
            delay(180)
            try { repo.setGroupVolume(leaderId, lvl) } catch (e: Exception) { _error.value = e.message }
        }
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

    private fun leaderIdOf(players: List<MaPlayer>, target: String): String {
        if (target.isNotBlank() && players.any { it.playerId == target }) return target
        return players.firstOrNull { it.playerId == myPlayerId }?.playerId
            ?: players.firstOrNull { it.isLeader }?.playerId
            ?: myPlayerId
    }

    /** Who is in the group: MA's answer, with any live join/unjoin intent on top. */
    private fun joinedIds(
        players: List<MaPlayer>,
        intents: Map<String, Intent<Boolean>>,
        leader: String,
    ): Set<String> {
        val childs = players.firstOrNull { it.playerId == leader }?.groupChilds.orEmpty()
        val ids = players
            .filter { it.playerId == leader || it.syncedTo == leader || it.playerId in childs }
            .mapTo(mutableSetOf()) { it.playerId }
        for ((id, intent) in intents) {
            if (!intent.live || id == leader) continue
            if (intent.value) ids += id else ids -= id
        }
        return ids
    }

    /** Drop intents MA has now caught up with, and any that have timed out. */
    private fun retireSettledIntents(fresh: List<MaPlayer>) {
        val leader = leaderIdOf(fresh, _target.value)
        val serverJoined = joinedIds(fresh, emptyMap(), leader)
        _joinIntent.update { m ->
            m.filterValues { it.live }.filterNot { (id, i) -> (id in serverJoined) == i.value }
        }
        _volumeIntent.update { m ->
            m.filterValues { it.live }
                .filterNot { (id, i) -> fresh.firstOrNull { it.playerId == id }?.volumeLevel == i.value }
        }
        _groupVolumeIntent.update { i ->
            if (i == null || !i.live) return@update null
            val p = fresh.firstOrNull { it.playerId == leader }
            if ((p?.groupVolume ?: p?.volumeLevel) == i.value) null else i
        }
    }

    private fun MaPlayer.withVolume(intents: Map<String, Intent<Int>>): MaPlayer {
        val i = intents[playerId] ?: return this
        return if (i.live) copy(volumeLevel = i.value) else this
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
            canLead = canSetMembers,
        )
    }

    private fun metaFor(p: MaPlayer): String = when {
        p.type == "group" -> "Group"
        p.isLeader -> "Group leader"
        !p.available -> "Unavailable"
        !p.powered -> "Off"
        else -> "Ready"
    }

    /**
     * Run a command, then chase MA's asynchronous settle. The intents keep the UI
     * showing what was asked for meanwhile, so these refreshes only ever *confirm* —
     * they can no longer snap a control back to a stale value.
     */
    private inline fun act(crossinline block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                _error.value = null
            } catch (e: Exception) {
                _error.value = e.message ?: "Command failed"
            }
            for (wait in longArrayOf(250, 550, 1_000, 2_000)) {
                delay(wait)
                refresh()
            }
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
