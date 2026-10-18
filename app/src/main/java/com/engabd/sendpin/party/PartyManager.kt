package com.engabd.sendpin.party

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.data.AppSettings
import java.net.Inet4Address
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Owns the one listening party this process can host: the server, the engine behind
 * it, the foreground service that keeps the process alive, and the three reasons it
 * ends (the host ends it, Wi-Fi goes, nobody has been near it for a while).
 */
class PartyManager(private val app: SendpinApp) {

    sealed interface State {
        data object Idle : State
        data class Running(val url: String, val startedAt: Long) : State
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val settings = AppSettings(app)
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)

    /** Why the last party ended, or why one would not start; shown once on the Party screen. */
    val message: StateFlow<String?> = _message.asStateFlow()

    private val engineFlow = MutableStateFlow<PartyEngine?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val host: StateFlow<PartyHostState> = engineFlow
        .flatMapLatest { it?.host ?: flowOf(PartyHostState()) }
        .stateIn(scope, SharingStarted.Eagerly, PartyHostState())

    private var server: PartyServer? = null
    private var watcher: Job? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun clearMessage() { _message.value = null }

    private val _openScreen = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits when something outside the navigation graph (the player options sheet) wants the Party screen. */
    val openScreen: kotlinx.coroutines.flow.SharedFlow<Unit> = _openScreen

    fun requestOpenScreen() { _openScreen.tryEmit(Unit) }

    /** The "added by" caption for the song with queue key [key], while a party is running. */
    fun addedBy(key: String): String? = engineFlow.value?.addedBy(key)

    suspend fun start(): Boolean {
        if (_state.value is State.Running) return true
        val prefs = PartyPrefs.read(settings)
        return startNow(prefs)
    }

    @Synchronized
    private fun startNow(prefs: PartyPrefs.Snapshot): Boolean {
        if (_state.value is State.Running) return true
        val found = wifiAddress()
        if (found == null) {
            _message.value = "Connect to Wi-Fi to host a party. Guests need to be on the same network."
            return false
        }
        val (network, address) = found
        val engine = PartyEngine(
            token = PartyAuth.newToken(),
            backend = AppPartyBackend(app),
            initial = PartySettings(prefs.approveFirst, prefs.skipMode),
        )
        val srv = PartyServer(address.hostAddress.orEmpty(), 0, engine) { name ->
            runCatching { app.assets.open("party/$name").use { it.readBytes() } }.getOrNull()
        }
        try {
            srv.begin()
        } catch (e: Exception) {
            Log.w(TAG, "Party server did not start: ${e.message}")
            _message.value = "Could not open the party. Try again in a moment."
            return false
        }
        server = srv
        engineFlow.value = engine
        _message.value = null
        _state.value = State.Running(
            url = "http://${address.hostAddress}:${srv.listeningPort}/p/${engine.token}/",
            startedAt = System.currentTimeMillis(),
        )
        watchNetwork(network, address.hostAddress.orEmpty())
        watchIdle(engine)
        PartyService.start(app)
        return true
    }

    fun end(reason: String? = null) {
        val srv: PartyServer?
        synchronized(this) {
            srv = server
            server = null
            watcher?.cancel(); watcher = null
            callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
            callback = null
            engineFlow.value = null
            _state.value = State.Idle
            if (reason != null) _message.value = reason
        }
        runCatching { srv?.stop() }
        PartyService.stop(app)
    }

    /** Persist and apply a settings change made on the Party screen. */
    fun updateSettings(change: (PartySettings) -> PartySettings) {
        val engine = engineFlow.value ?: return
        engine.updateSettings(change)
        val now = engine.settingsNow()
        scope.launch { PartyPrefs.write(settings, now) }
    }

    fun approve(id: String) { engineFlow.value?.let { e -> scope.launch { e.approve(id) } } }
    fun decline(id: String) { engineFlow.value?.decline(id) }

    private fun watchIdle(engine: PartyEngine) {
        watcher?.cancel()
        watcher = scope.launch {
            while (true) {
                delay(TICK_MS)
                engine.refreshHostState()
                val hours = settings.partyIdleHours.first()
                if (System.currentTimeMillis() - engine.lastActivity > hours * 3_600_000L) {
                    end("The party ended after $hours hours with no guests around.")
                    return@launch
                }
            }
        }
    }

    private fun watchNetwork(bound: Network, boundAddress: String) {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                if (network == bound) end("The party ended because Wi-Fi disconnected.")
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) {
                if (network != bound) return
                val still = linkProperties.linkAddresses.any { it.address.hostAddress == boundAddress }
                if (!still) end("The party ended because your Wi-Fi address changed.")
            }
        }
        callback = cb
        runCatching { connectivity.registerNetworkCallback(wifiRequest(), cb) }
    }

    private fun wifiRequest() = android.net.NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .build()

    /** The Wi-Fi network and its IPv4 address, if the phone is on Wi-Fi. */
    private fun wifiAddress(): Pair<Network, Inet4Address>? {
        for (network in connectivity.allNetworks) {
            val caps = connectivity.getNetworkCapabilities(network) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            val v4 = connectivity.getLinkProperties(network)?.linkAddresses
                ?.map { it.address }?.filterIsInstance<Inet4Address>()
                ?.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress } ?: continue
            return network to v4
        }
        return null
    }

    private companion object {
        const val TAG = "PartyManager"
        const val TICK_MS = 30_000L
    }
}

/** The party settings that persist between parties. */
object PartyPrefs {
    data class Snapshot(val approveFirst: Boolean = false, val skipMode: SkipMode = SkipMode.OFF)

    suspend fun read(settings: AppSettings): Snapshot = Snapshot(
        approveFirst = settings.partyApproveFirst.first(),
        skipMode = runCatching { SkipMode.valueOf(settings.partySkipMode.first()) }.getOrDefault(SkipMode.OFF),
    )

    suspend fun write(settings: AppSettings, s: PartySettings) {
        settings.setPartyApproveFirst(s.approveFirst)
        settings.setPartySkipMode(s.skipMode.name)
    }
}
