package com.engabd.sendpin.hue

import com.engabd.sendpin.util.runCatchingCancellable
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.engabd.sendpin.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Brings Light Sync back after an outage, without the user touching anything.
 *
 * [DirectLightSync] rides out short faults itself — its reconnect backs off for about
 * two minutes, which covers a Wi-Fi roam. What it cannot ride out is anything longer:
 * a router that takes five minutes to reboot, a bridge switched off at the wall, the
 * phone leaving the house and coming back, a bridge DHCP moved to a new address.
 * Every one of those left the room dark until a Light Sync setting was changed.
 *
 * While [DirectLightSync.outage] holds, this retries on the events that make success
 * likely, rather than on a fast blind timer:
 *
 * - **a network appearing** — the phone rejoined Wi-Fi;
 * - **the bridge announcing itself** over mDNS (`_hue._tcp`) under the paired bridge
 *   id — it came back, and if it did so at a new address, that address is saved first,
 *   because a bridge that moved can never be reached at the old one;
 * - **the app coming to the foreground** — the user is looking, and probably wondering;
 * - and a slow timer as the floor (30 s, doubling to five minutes), for the case none
 *   of the above notices.
 *
 * Nothing runs outside an outage: discovery, the network callback and the timer all
 * live inside it, so a healthy session costs nothing extra.
 */
class LightSyncHealer(
    private val context: Context,
    private val sync: DirectLightSync,
    private val foreground: StateFlow<Boolean>?,
) {
    private val settings = AppSettings(context)
    private var job: Job? = null

    /** Watch for outages. Called when Light Sync is switched on; idempotent. */
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            // collectLatest: the outage ending (a heal worked, or the user switched
            // off) cancels the whole attempt, triggers and all.
            sync.outage.collectLatest { down -> if (down) heal() }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun heal() = coroutineScope {
        Log.i(TAG, "Light Sync is down; watching for a way back")
        val kicks = Channel<String>(Channel.CONFLATED)
        val schedule = HealSchedule()

        // A network appearing. `onAvailable` fires for the current network on
        // registration too, which is a welcome first attempt rather than a problem.
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { kicks.trySend("a network appeared") }
        }
        runCatchingCancellable { cm?.registerDefaultNetworkCallback(callback) }

        // The bridge announcing itself, possibly somewhere new.
        val discovery = HueBridgeClient(context)
        launch {
            val pairedId = settings.hueBridgeId.first()
            if (pairedId.isBlank()) return@launch   // paired by address only: nothing to match on
            runCatchingCancellable { discovery.startDiscovery() }
            discovery.discovered.collect { bridges ->
                val known = settings.hueBridgeIp.first()
                when (val move = bridgeMove(pairedId, known, bridges.map { it.bridgeId to it.host })) {
                    null -> Unit
                    known -> kicks.trySend("the bridge announced itself")
                    else -> {
                        Log.i(TAG, "The bridge moved to a new address")
                        settings.setHueBridgeIp(move)
                        kicks.trySend("the bridge announced a new address")
                    }
                }
            }
        }

        // The app coming forward.
        foreground?.let { fg ->
            launch { fg.drop(1).filter { it }.collect { kicks.trySend("the app opened") } }
        }

        // The floor.
        launch {
            while (true) {
                delay(schedule.nextTimerMs())
                kicks.trySend("the retry timer")
            }
        }

        try {
            for (reason in kicks) {
                val wait = schedule.waitBeforeAttemptMs(System.currentTimeMillis())
                if (wait > 0) delay(wait)
                schedule.attempted(System.currentTimeMillis())
                Log.i(TAG, "Retrying Light Sync: $reason")
                sync.start()
                // Success flips `outage`, and collectLatest cancels this scope.
            }
        } finally {
            runCatching { cm?.unregisterNetworkCallback(callback) }
            discovery.stopDiscovery()
        }
    }

    companion object {
        private const val TAG = "LightSyncHealer"

        /**
         * Where the paired bridge is announcing itself, from `(bridgeId, host)` pairs
         * seen on mDNS: its host, or null when it is not among them. Ids compare
         * case-blind — the TXT record and the stored id have disagreed on case before.
         */
        internal fun bridgeMove(pairedId: String, knownHost: String, seen: List<Pair<String, String>>): String? {
            if (pairedId.isBlank()) return null
            val hosts = seen.filter { it.first.equals(pairedId, ignoreCase = true) }.map { it.second }
            if (hosts.isEmpty()) return null
            // Unchanged beats changed: a bridge answering on both (IPv4 and v6, or a
            // lease mid-handover) has not moved.
            return hosts.firstOrNull { it == knownHost } ?: hosts.first()
        }
    }
}

/**
 * The healer's pacing. Attempts are at least [MIN_GAP_MS] apart however many events
 * arrive — one reboot of a router fires a network, a discovery and a timer in the same
 * few seconds — and the timer floor backs off from 30 s to five minutes, so a bridge
 * that is simply switched off costs one HTTPS request every five minutes.
 */
internal class HealSchedule {
    private var lastAttemptAt = Long.MIN_VALUE / 2
    private var timerMs = FIRST_TIMER_MS

    fun waitBeforeAttemptMs(now: Long): Long = (lastAttemptAt + MIN_GAP_MS - now).coerceAtLeast(0)

    fun attempted(now: Long) { lastAttemptAt = now }

    fun nextTimerMs(): Long = timerMs.also { timerMs = (timerMs * 2).coerceAtMost(MAX_TIMER_MS) }

    companion object {
        const val MIN_GAP_MS = 10_000L
        const val FIRST_TIMER_MS = 30_000L
        const val MAX_TIMER_MS = 5 * 60_000L
    }
}
