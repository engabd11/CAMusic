package com.engabd.sendpin.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.discovery.PlayerIdentity
import com.engabd.sendpin.ma.MaApiClient
import com.engabd.sendpin.ma.MaPlayer
import com.engabd.sendpin.ma.MaRepository
import com.engabd.sendpin.ma.SyncDelay
import kotlinx.coroutines.Job
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

    private val api = (app as SendpinApp).maApi
    private val repo = MaRepository(api)

    /** The process-scoped poller this screen reads its player list from. */
    private val maNowPlaying = (app as SendpinApp).maNowPlaying

    /** What MA last told us. Never written to optimistically — see [Intent]. */
    private val _players = MutableStateFlow<List<MaPlayer>>(emptyList())
    private val _syncDelays = MutableStateFlow<Map<String, SyncDelay>>(emptyMap())

    /**
     * Players already asked for a sync delay this membership, answer or not.
     *
     * Separate from [_syncDelays] because "asked and got nothing" and "never asked"
     * need to be told apart — keying the skip on the answer meant a player with no
     * sync-delay key was re-queried on every poll for the life of the session.
     * Pruned when a player leaves the group, so a rejoin asks again.
     */
    private val askedSyncDelay = mutableSetOf<String>()
    private val _target = MutableStateFlow("")       // selected play-to player ("" = this phone)
    private val _error = MutableStateFlow<String?>(null); val error: StateFlow<String?> = _error
    private val _refreshing = MutableStateFlow(false); val refreshing: StateFlow<Boolean> = _refreshing
    private var refreshJob: Job? = null

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

    /**
     * Whether the active player has something going. Gates "move music here" —
     * there is no queue to hand over when nothing is playing, and the button
     * would silently do nothing.
     */
    val leaderIsPlaying: StateFlow<Boolean> = combine(_players, _target) { players, target ->
        players.firstOrNull { it.playerId == leaderIdOf(players, target) }?.isPlaying ?: false
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
        // The player list comes from the process-scoped MaNowPlaying, which already
        // polls `players/all` every 5 s and refreshes on sampled player/queue events.
        // This screen used to run its own identical loop — and so did Now Playing — so
        // one server answered three copies of the same command on the same schedule.
        // Collecting its result costs nothing and is never staler than the shared poll.
        viewModelScope.launch {
            maNowPlaying.players.collect { players ->
                _players.value = players
                retireSettledIntents(players)
                fetchMissingSyncDelays(players)
                _error.value = null
            }
        }
        // Ask for a read now rather than waiting up to 5 s for the shared poll: opening
        // this screen is exactly the moment the list needs to be right.
        maNowPlaying.refreshNow()
        // The Sendspin player socket pushes `group/update` on a *different* connection
        // to MA's API, so it can beat the shared refresh. Still worth listening for.
        viewModelScope.launch {
            (app as SendpinApp).playback.groupUpdates.collect { maNowPlaying.refreshNow() }
        }
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch { doRefresh() }
    }

    /**
     * The same fetch as [refresh], but drives [refreshing] so the Speakers screen's
     * refresh button can show a spinner. Kept separate from [refresh] because that one
     * also runs silently every 5s and on every MA/group push — spinning the button on
     * every one of those would read as constant, meaningless activity.
     */
    fun manualRefresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            _refreshing.value = true
            try {
                doRefresh()
            } finally {
                _refreshing.value = false
            }
        }
    }

    private suspend fun doRefresh() {
        try {
            val players = repo.players()
            _players.value = players
            retireSettledIntents(players)
            fetchMissingSyncDelays(players)
            _error.value = null
        } catch (e: Exception) {
            _error.value = e.message
        }
    }

    /** Sync delays for currently-joined players, best effort, asked once each. */
    private suspend fun fetchMissingSyncDelays(players: List<MaPlayer>) {
        val leader = leaderIdOf(players, _target.value)
        val members = joinedIds(players, emptyMap(), leader)
        val delays = _syncDelays.value.toMutableMap()
        for (p in players) {
            // This phone is included: it is a Sendspin player, so Music Assistant holds
            // a sync delay for it like any other member, and skipping it was why its own
            // row reported a hard-coded zero.
            if (p.playerId !in members) continue
            // [askedSyncDelay] is checked, not `delays` — see its docs. Keying the skip
            // on the *result* meant a player with no sync-delay key was re-queried on
            // every 5-second poll for ever.
            if (p.playerId in askedSyncDelay) continue
            askedSyncDelay += p.playerId
            runCatching { repo.getSyncDelay(p.playerId) }.getOrNull()?.let { delays[p.playerId] = it }
        }
        // A player that has left the group gets a clean slate, so rejoining asks the
        // server again rather than trusting a cached "no".
        askedSyncDelay.retainAll(members)
        delays.keys.retainAll(members)
        _syncDelays.value = delays
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

    /**
     * Transfer the entire queue (items, position, shuffle/repeat state) from
     * the current leader to [targetPlayerId] — the "tap a speaker, music moves
     * there" feature. Auto-starts playback on the target.
     */
    fun transferQueueTo(targetPlayerId: String) {
        val source = leaderId
        if (targetPlayerId == source) return
        act {
            val sourceQueue = repo.activeQueueId(source)
            val targetQueue = repo.activeQueueId(targetPlayerId)
            repo.transferQueue(sourceQueue, targetQueue, autoPlay = true)
            // Follow the music — switch the target to where it went.
            selectPlayer(targetPlayerId)
        }
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

    /**
     * The hero slider. `players/cmd/group_volume` is documented to take "a group
     * player or syncleader" — a lone player is neither, so MA ignores the command
     * and the slider appears dead. Fall back to that player's own volume whenever
     * there is nothing grouped to fan out to.
     */
    fun setGroupVolume(level01: Float) {
        val lvl = (level01 * 100).toInt().coerceIn(0, 100)
        val leader = leaderId
        val isGroup = joinedIds(_players.value, _joinIntent.value, leader).size > 1
        _groupVolumeIntent.value = Intent(lvl)
        groupVolJob?.cancel()
        groupVolJob = viewModelScope.launch {
            delay(180)
            try {
                if (isGroup) repo.setGroupVolume(leader, lvl) else repo.setVolume(leader, lvl)
            } catch (e: Exception) { _error.value = e.message }
        }
    }

    fun changeOffset(playerId: String, deltaMs: Int) {
        val cur = _syncDelays.value[playerId]
        act {
            // Bailing silently here is what made the −/+ buttons look broken: a player
            // Music Assistant exposes no sync-delay key for accepted the taps and did
            // nothing, with no message. The screen now hides the row in that case, so
            // this is the belt-and-braces path — but it says so rather than shrugging.
            val sd = cur ?: repo.getSyncDelay(playerId)
            if (sd == null) {
                val name = _players.value.firstOrNull { it.playerId == playerId }?.name ?: "That speaker"
                _error.value = "$name doesn't expose a sync offset through Music Assistant"
                return@act
            }
            val next = (sd.ms + deltaMs).coerceIn(-2000, 2000)
            // Optimistic, so the number moves under the finger rather than on the next
            // poll — and rolled back if the save is refused.
            _syncDelays.update { it + (playerId to sd.copy(ms = next)) }
            try {
                repo.setSyncDelay(playerId, sd.key, next)
            } catch (e: Exception) {
                _syncDelays.update { it + (playerId to sd) }
                _error.value = e.message ?: "Couldn't change the sync offset"
            }
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
            // Purely "did the server give us one". This used to be `self || …`, which
            // forced the phone's own row to claim a known offset of 0 while never
            // fetching one — so the one speaker whose delay is definitely adjustable
            // showed a value that was not its own.
            offsetKnown = delay != null,
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
        // Shared MaApiClient — don't disconnect it when one ViewModel is destroyed.
    }
}

/** Ungroup several players via the batch command (MA ≥ 2.9), with a per-id fallback. */
private suspend fun MaRepository.ungroup(playerIds: List<String>) {
    try {
        ungroupMany(playerIds)
    } catch (_: Exception) {
        // Fallback for older servers without ungroup_many.
        for (id in playerIds) ungroup(id)
    }
}
