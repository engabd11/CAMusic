package com.engabd.sendpin.hue

import android.content.Context
import android.util.Log
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.ma.MaPlayer
import com.engabd.sendpin.ma.MaRepository
import com.engabd.sendpin.service.MaNowPlaying
import com.engabd.sendpin.service.Playback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/** What the light listener should be doing. See [LightListenerRules.plan]. */
sealed interface ListenerPlan {
    /**
     * Not listening. [afterGrace]: the speaker has only stopped, so a resume within
     * [LightListener]'s grace keeps the phone's place rather than rejoining.
     */
    data class Idle(val afterGrace: Boolean = false) : ListenerPlan

    /** Sit silently in [leaderId]'s group. */
    data class Join(val leaderId: String, val leaderName: String) : ListenerPlan

    /** Wanted, but not possible here; the show stays on the scan, and the status line says why. */
    data class Fallback(val reason: String) : ListenerPlan
}

/**
 * The listener's decisions, pure so they can be tested without a server.
 */
object LightListenerRules {

    /**
     * Whether to join, and whose group.
     *
     * Conservative by design: a join changes a group someone is listening to, so
     * anything this cannot show to be safe falls back to the scan-driven show rather
     * than trying. Above all, only a Sendspin speaker: the phone can share a Sendspin
     * timeline, and has no business in an AirPlay, Cast or Sonos group, which it could
     * not keep time with and which Music Assistant may restart to add a member to.
     *
     * [phoneIds] is every id this phone goes by (its Sendspin client id and, from Music
     * Assistant 2.10, the `up…` wrapper around it); [myPlayerId] is the client id alone.
     * [failed] is a leader this already tried and gave up on, with why.
     */
    fun plan(
        wanted: Boolean,
        playerConnected: Boolean,
        phonePlayingAloud: Boolean,
        targetId: String?,
        targetPlaying: Boolean,
        targetIsPhone: Boolean,
        players: List<MaPlayer>,
        phoneIds: Set<String>,
        myPlayerId: String,
        failed: Pair<String, String>?,
    ): ListenerPlan {
        // The phone audible in its own right already has the real feed; never touch that.
        if (!wanted || targetId == null || targetIsPhone || phonePlayingAloud) return ListenerPlan.Idle()
        if (!targetPlaying) return ListenerPlan.Idle(afterGrace = true)
        val target = players.firstOrNull { it.playerId == targetId } ?: return ListenerPlan.Idle()
        // A member plays the leader's stream, so the leader's group is the one to be in.
        val leader = target.syncedTo?.let { id -> players.firstOrNull { it.playerId == id } } ?: target
        if (leader.playerId in phoneIds) return ListenerPlan.Idle()
        failed?.takeIf { it.first == leader.playerId }?.let { return ListenerPlan.Fallback(it.second) }
        // Adding a member to a saved group player would change the user's group for good.
        if (leader.type == "group") {
            return ListenerPlan.Fallback("${leader.name} is a group player, and the phone only joins a single speaker")
        }
        if (!isSendspin(leader, players, myPlayerId)) {
            return ListenerPlan.Fallback("${leader.name} is not a Sendspin speaker, so the phone cannot keep time with it")
        }
        if (!leader.canSetMembers) return ListenerPlan.Fallback("${leader.name} does not accept group members")
        if (!playerConnected) return ListenerPlan.Fallback("this phone is not connected to Music Assistant as a player")
        return ListenerPlan.Join(leader.playerId, leader.name)
    }

    /**
     * Whether [leader] renders through Sendspin, judged by its provider, or by the
     * provider of the protocol player it is playing through (Music Assistant 2.10 wraps
     * each speaker in an `up…` player). The phone's own client names the provider when
     * Music Assistant lists it; `sendspin` otherwise. Unknown is no.
     */
    fun isSendspin(leader: MaPlayer, players: List<MaPlayer>, myPlayerId: String): Boolean {
        val sendspin = setOfNotNull(players.firstOrNull { it.playerId == myPlayerId }?.provider?.domain(), "sendspin")
        val protocol = leader.activeOutputProtocol?.let { id -> players.firstOrNull { it.playerId == id } } ?: leader
        return protocol.provider?.domain() in sendspin
    }

    /** Is the phone in [leaderId]'s group, by either side's account of it? */
    fun phoneInGroup(players: List<MaPlayer>, leaderId: String, phoneIds: Set<String>): Boolean =
        players.any { it.playerId in phoneIds && it.syncedTo == leaderId } ||
            players.firstOrNull { it.playerId == leaderId }?.groupChilds?.any { it in phoneIds } == true

    /**
     * The volume the silent phone reports: the mean of the group's real speakers.
     *
     * Music Assistant's group volume is the members' average, and a group volume
     * change moves every member by the same step from it. A member sitting at that
     * very average adds nothing to it, so the group reads and moves exactly as it
     * would without the phone. Null when there is nothing to average.
     */
    fun groupVolume(players: List<MaPlayer>, leaderId: String, phoneIds: Set<String>): Int? {
        val childs = players.firstOrNull { it.playerId == leaderId }?.groupChilds.orEmpty()
        val levels = players
            .filter { it.playerId !in phoneIds && (it.playerId == leaderId || it.syncedTo == leaderId || it.playerId in childs) }
            .map { it.volumeLevel }
        return if (levels.isEmpty()) null else levels.average().roundToInt()
    }

    private fun String.domain() = substringBefore("--")
}

/**
 * The light listener: Light Sync straight to the bridge for a Music Assistant speaker
 * in another room.
 *
 * ## The problem
 *
 * The bridge path needs audio, and a remote speaker's audio never reaches this phone.
 * The show fell back to a schedule synthesised from an offline scan (see
 * [ScanFrameSource]) — beats and sections, no spectrum, nothing at all for a track no
 * library here could find — or the user ran syncoV2 through Home Assistant.
 *
 * ## What this does
 *
 * Music Assistant will stream any Sendspin speaker's audio to every member of its
 * group, each told when to play it on one shared timeline. So the phone joins the
 * group as a member and plays nothing: [Playback.setListenOnly] decodes the stream
 * into the analysis tap with no output (see
 * [com.engabd.sendpin.audio.SendspinNativeEngine.analysisOnly]), and the tap's lead is
 * the time until the group actually sounds it. The room gets the full live show, in
 * step with the speaker.
 *
 * ## The rules it keeps
 *
 * - It joins only a Sendspin speaker that is playing, and only with the setting on,
 *   direct Light Sync on and a bridge paired. Anything else is [ListenerPlan.Fallback],
 *   said in the status line, and the scan show carries on as before.
 * - Silent before joining and until it has seen itself leave: a stream racing either
 *   edge is decoded for the tap and never played.
 * - It leaves when any of that stops being true, after a grace for a pause, and on the
 *   next start if the process died while it was in a group — the one exit it cannot
 *   see coming.
 * - It reports the group's own mean volume, so group volume is not skewed by it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LightListener(
    context: Context,
    private val settings: AppSettings,
    private val playback: Playback,
    private val nowPlaying: MaNowPlaying,
    private val repo: MaRepository,
    private val myPlayerId: () -> String,
) {
    private data class Joined(val phoneId: String, val leaderId: String, val leaderName: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Survives the process, so a group the phone was left in can be left on the next start. */
    private val prefs = context.getSharedPreferences("light_listener", Context.MODE_PRIVATE)

    private val failed = MutableStateFlow<Pair<String, String>?>(null)
    private val joined = MutableStateFlow<Joined?>(null)

    private val _listeningAs = MutableStateFlow<String?>(null)

    /** This phone's Music Assistant id while it sits in a group as the listener, else null. */
    val listeningAs: StateFlow<String?> = _listeningAs.asStateFlow()

    private val _note = MutableStateFlow<String?>(null)

    /** A few words for the Light Sync status line: listening, or why not. Null when there is nothing to say. */
    val note: StateFlow<String?> = _note.asStateFlow()

    private var audioWatch: Job? = null
    private var volumeJob: Job? = null
    private var retryJob: Job? = null
    private var started = false

    /** One join or leave at a time: the plan collector and a background leave retry both make them. */
    private val lock = Mutex()

    fun start() {
        if (started) return
        started = true
        // Silent first, before the player socket can deliver that group's next stream.
        val stale = stored()
        if (stale != null) {
            playback.setListenOnly(true)
            joined.value = stale
        }
        scope.launch {
            stale?.let { leave(it) }
            val wanted = combine(
                settings.lightSyncListenMa, settings.lightSyncMode,
                settings.lightSyncEnabled, settings.hueBridgePaired,
            ) { on, mode, enabled, paired -> on && mode == AppSettings.MODE_DIRECT && enabled && paired }
                .distinctUntilChanged()
            wanted.flatMapLatest { on ->
                if (!on) {
                    failed.value = null
                    flowOf(ListenerPlan.Idle())
                } else {
                    combine(
                        nowPlaying.now, nowPlaying.players, playback.connected, playback.isPlaying, failed,
                    ) { now, players, connected, aloud, failedAt ->
                        val me = myPlayerId()
                        LightListenerRules.plan(
                            wanted = true,
                            playerConnected = connected,
                            phonePlayingAloud = aloud,
                            targetId = now?.playerId,
                            targetPlaying = now?.isPlaying == true,
                            targetIsPhone = now?.isSelf == true,
                            players = players,
                            phoneIds = setOfNotNull(me, joined.value?.phoneId),
                            myPlayerId = me,
                            failed = failedAt,
                        )
                    }
                }
            }
                .distinctUntilChanged()
                .collect { plan ->
                    // Not collectLatest: a join or leave is never abandoned half way, and
                    // the pause grace is the one wait, held in its own job. Joined, so a
                    // leave the grace already began has finished before this looks.
                    graceJob?.cancelAndJoin()
                    graceJob = null
                    act(plan)
                }
        }
    }

    private var graceJob: Job? = null

    private suspend fun act(plan: ListenerPlan) {
        val current = joined.value
        when (plan) {
            is ListenerPlan.Join -> {
                // Joined this session, not merely left over from the last one.
                if (current?.leaderId == plan.leaderId && volumeJob != null) return
                if (current != null) leave(current)
                join(plan)
            }
            is ListenerPlan.Fallback -> {
                if (current != null) leave(current)
                _note.value = "not listening silently: ${plan.reason}"
            }
            is ListenerPlan.Idle -> {
                failed.value = null
                if (current == null) {
                    _note.value = null
                    return
                }
                if (plan.afterGrace) {
                    graceJob = scope.launch {
                        delay(PAUSE_GRACE_MS)
                        if (joined.value == current) leave(current)
                    }
                } else {
                    leave(current)
                }
            }
        }
    }

    private suspend fun join(plan: ListenerPlan.Join) = withContext(NonCancellable) {
        val refused = lock.withLock { joinLocked(plan) } ?: return@withContext
        // A refused command added nothing, but a timed-out one may have; leave properly
        // either way, then say why.
        leave(refused)
        failed.value = plan.leaderId to "Music Assistant would not add the phone to ${plan.leaderName}"
    }

    /** The join itself; returns the attempt when Music Assistant would not take it. */
    private suspend fun joinLocked(plan: ListenerPlan.Join): Joined? {
        // The id Music Assistant lists this phone under — the `up…` wrapper on 2.10 —
        // resolved the way Playback's group poll does.
        val phoneId = runCatching { repo.getPlayer(myPlayerId())?.playerId }.getOrNull() ?: myPlayerId()
        val j = Joined(phoneId, plan.leaderId, plan.leaderName)
        playback.setListenOnly(true)
        LightListenerRules.groupVolume(nowPlaying.players.value, plan.leaderId, setOf(phoneId, myPlayerId()))
            ?.let(playback::setListenerVolume)
        // Recorded before asking, so a process that dies mid-command still leaves next start.
        store(j)
        joined.value = j
        val added = runCatching { repo.setMembers(plan.leaderId, add = listOf(phoneId)) }
        if (added.isFailure) {
            Log.w(TAG, "Could not join ${plan.leaderName}: ${added.exceptionOrNull()?.message}")
            return j
        }
        Log.i(TAG, "Listening silently in ${plan.leaderName}'s group as $phoneId")
        _listeningAs.value = phoneId
        _note.value = "listening silently to ${plan.leaderName}"
        nowPlaying.refreshNow()
        volumeJob = scope.launch {
            nowPlaying.players.collect { players ->
                LightListenerRules.groupVolume(players, plan.leaderId, setOf(phoneId, myPlayerId()))
                    ?.let(playback::setListenerVolume)
            }
        }
        // Joined is not hearing. A group Music Assistant does not stream to this member
        // would otherwise leave the room on an idle glow with a status saying it listens.
        audioWatch = scope.launch {
            val heard = withTimeoutOrNull(NO_AUDIO_MS) { playback.listenerAudio.first { it } } != null
            if (!heard && joined.value == j) {
                Log.w(TAG, "No audio from ${plan.leaderName}'s group in ${NO_AUDIO_MS / 1000} s; falling back")
                failed.value = plan.leaderId to "no audio reached the phone from ${plan.leaderName}"
            }
        }
        return null
    }

    /**
     * Leave the group, and stay silent until that is certain: Music Assistant lists the
     * phone outside it, or took the command and has stopped sending audio. Otherwise it
     * stays a silent member and tries again in the background, so a phone left silent
     * by a server that was not answering does not stay that way.
     */
    private suspend fun leave(j: Joined): Unit = withContext(NonCancellable) {
        lock.withLock { leaveLocked(j) }
    }

    private suspend fun leaveLocked(j: Joined) {
        // Already left, by the retry or by whoever got the lock first.
        if (joined.value != j) return
        audioWatch?.cancel(); audioWatch = null
        volumeJob?.cancel(); volumeJob = null
        val ids = setOf(j.phoneId, myPlayerId())
        repeat(LEAVE_ATTEMPTS) {
            val sent = runCatching { repo.ungroup(j.phoneId) }.isSuccess
            nowPlaying.refreshNow()
            // A list, not an empty one: before Music Assistant answers at all the list
            // is empty, and "not in the group" would be read off nothing.
            val out = withTimeoutOrNull(LEAVE_CONFIRM_MS) {
                nowPlaying.players.first { it.isNotEmpty() && !LightListenerRules.phoneInGroup(it, j.leaderId, ids) }
            } != null
            if (out || (sent && !playback.listenerAudio.value)) {
                Log.i(TAG, "Left ${j.leaderName}'s group")
                joined.value = null
                _listeningAs.value = null
                _note.value = null
                store(null)
                playback.setListenOnly(false)
                return
            }
        }
        Log.w(TAG, "Could not confirm leaving ${j.leaderName}'s group; staying silent, trying again")
        if (retryJob?.isActive != true) {
            retryJob = scope.launch {
                while (joined.value == j) {
                    delay(LEAVE_RETRY_MS)
                    leave(j)
                }
            }
        }
    }

    private fun stored(): Joined? {
        val v = prefs.getString(KEY_JOINED, null) ?: return null
        val parts = v.split('\n')
        return if (parts.size == 3) Joined(parts[0], parts[1], parts[2]) else null
    }

    private fun store(j: Joined?) {
        prefs.edit().apply {
            if (j == null) remove(KEY_JOINED) else putString(KEY_JOINED, "${j.phoneId}\n${j.leaderId}\n${j.leaderName}")
        }.apply()
    }

    private companion object {
        const val TAG = "LightListener"
        const val KEY_JOINED = "joined"

        /** A pause this short keeps the phone's place, so play/pause does not regroup. */
        const val PAUSE_GRACE_MS = 30_000L

        /** How long a join may go without audio before it is called a failure. */
        const val NO_AUDIO_MS = 8_000L

        const val LEAVE_ATTEMPTS = 3
        const val LEAVE_CONFIRM_MS = 6_000L
        const val LEAVE_RETRY_MS = 30_000L
    }
}
