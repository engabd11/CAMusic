package com.engabd.sendpin.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.engabd.sendpin.audio.FormatNegotiator
import com.engabd.sendpin.audio.SendspinAudioEngine
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.discovery.MaDiscovery
import com.engabd.sendpin.discovery.PlayerIdentity
import com.engabd.sendpin.ma.MaApiClient
import com.engabd.sendpin.protocol.SendspinClient
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val context: Context get() = getApplication()

    // Discovery
    private val discovery = MaDiscovery(context)
    val discoveredServers = discovery.discoveredServers
    val isDiscovering = discovery.isDiscovering

    private val settings = AppSettings(context)

    // Player identity
    val playerId = PlayerIdentity.getPlayerId(context)
    val playerName = PlayerIdentity.getDefaultPlayerName()
    private val deviceInfo = PlayerIdentity.getDeviceInfo()

    // Connection state
    private val _connected = MutableStateFlow(false); val connected: StateFlow<Boolean> = _connected
    private val _connectionStatus = MutableStateFlow("Disconnected"); val connectionStatus: StateFlow<String> = _connectionStatus

    // Playback state
    private val _trackTitle = MutableStateFlow(""); val trackTitle: StateFlow<String> = _trackTitle
    private val _artist = MutableStateFlow(""); val artist: StateFlow<String> = _artist
    private val _album = MutableStateFlow(""); val album: StateFlow<String> = _album
    private val _artworkUrl = MutableStateFlow<String?>(null); val artworkUrl: StateFlow<String?> = _artworkUrl
    private val _isPlaying = MutableStateFlow(false); val isPlaying: StateFlow<Boolean> = _isPlaying
    private val _volume = MutableStateFlow(1.0f); val volume: StateFlow<Float> = _volume
    private val _currentFormat = MutableStateFlow("—"); val currentFormat: StateFlow<String> = _currentFormat
    private val _serverUrl = MutableStateFlow(""); val serverUrl: StateFlow<String> = _serverUrl

    // On-screen handshake trace (for diagnosing connection issues without adb).
    private val _connectionLog = MutableStateFlow<List<String>>(emptyList()); val connectionLog: StateFlow<List<String>> = _connectionLog

    // Saved MA credentials, for pre-filling the sign-in fields.
    val savedUsername: Flow<String> get() = settings.maUsername
    val savedPassword: Flow<String> get() = settings.maPassword

    private var client: SendspinClient? = null
    private var engine: SendspinAudioEngine? = null

    fun startDiscovery() = discovery.startDiscovery()
    fun stopDiscovery() = discovery.stopDiscovery()

    /**
     * Connect the Sendspin player. When MA serves `/sendspin` on its main API port
     * ("proxy mode") it demands an `auth` frame first ("First message must be auth"),
     * so we log into the MA API to get an access token and hand it to the handshake.
     * Blank credentials fall back to the saved ones (Settings / a previous connect).
     */
    fun connectToServer(url: String, username: String = "", password: String = "") {
        disconnect()
        _serverUrl.value = url
        _connectionStatus.value = "Signing in…"
        viewModelScope.launch {
            val user = username.ifBlank { settings.maUsername.first() }
            val pass = password.ifBlank { settings.maPassword.first() }
            val base = httpBase(url)
            // Persist for reconnects (Settings / discovered-server tap).
            settings.setMa(base, user, pass)
            val hasCreds = user.isNotBlank() && pass.isNotBlank()
            val token = if (hasCreds) fetchMaToken(base, user, pass) else null
            if (hasCreds && token == null) {
                _connectionStatus.value = "Sign-in failed — check username / password"
                return@launch
            }
            startSendspin(url, token)
        }
    }

    /** Log into the MA main API and return the access token (null on failure). */
    private suspend fun fetchMaToken(base: String, user: String, pass: String): String? {
        val api = MaApiClient()
        return try {
            api.connect(base, token = null, username = user, password = pass)
            val end = withTimeoutOrNull(12_000) {
                api.state.first { it == MaApiClient.State.CONNECTED || it == MaApiClient.State.ERROR }
            }
            if (end == MaApiClient.State.CONNECTED) api.authToken else null
        } catch (_: Exception) {
            null
        } finally {
            api.disconnect()
        }
    }

    private fun startSendspin(url: String, token: String?) {
        val c = SendspinClient(); client = c
        val eng = SendspinAudioEngine(c.clock); engine = eng

        viewModelScope.launch { c.state.collect { _connected.value = it == SendspinClient.State.CONNECTED } }
        viewModelScope.launch { c.statusText.collect { _connectionStatus.value = it } }
        viewModelScope.launch { c.events.collect { _connectionLog.value = it } }
        viewModelScope.launch {
            c.nowPlaying.collect { np ->
                _trackTitle.value = np?.title ?: ""
                _artist.value = np?.artist ?: ""
                _album.value = np?.album ?: ""
                _artworkUrl.value = np?.artworkUrl
            }
        }
        viewModelScope.launch {
            c.streamFormat.collect { f ->
                _currentFormat.value = f?.let {
                    "${it.sampleRate / 1000}kHz / ${it.bitDepth}-bit / ${it.codec.uppercase()}"
                } ?: "—"
            }
        }
        viewModelScope.launch {
            c.serverCommands.collect { cmd ->
                when (cmd.command) {
                    "volume" -> cmd.volume?.let { v -> _volume.value = v / 100f; eng.setVolume(v / 100f) }
                    "mute" -> cmd.mute?.let { m -> eng.setVolume(if (m) 0f else _volume.value) }
                }
            }
        }
        viewModelScope.launch {
            c.streamEvents.collect { ev ->
                when (ev) {
                    is SendspinClient.StreamEvent.Start -> { eng.start(ev.format); _isPlaying.value = true }
                    SendspinClient.StreamEvent.End -> { eng.stop(); _isPlaying.value = false }
                    SendspinClient.StreamEvent.Clear -> eng.flush()
                }
            }
        }
        viewModelScope.launch { c.audioFrames.collect { bytes -> eng.submit(bytes) } }

        c.connect(url, playerId, playerName, deviceInfo, FormatNegotiator.supportedFormats, token)
    }

    /** Derive the MA API base (`http[s]://host:port`) from a Sendspin ws URL. */
    private fun httpBase(url: String): String {
        var u = url.trim()
        if (u.startsWith("wss://")) u = "https://" + u.removePrefix("wss://")
        else if (u.startsWith("ws://")) u = "http://" + u.removePrefix("ws://")
        else if (!u.startsWith("http")) u = "http://$u"
        // Strip any path (/sendspin, /ws, …) — keep scheme://host:port.
        val schemeEnd = u.indexOf("://") + 3
        val pathStart = u.indexOf('/', schemeEnd)
        return if (pathStart >= 0) u.substring(0, pathStart) else u
    }

    /** Play/pause is server-driven for a Sendspin player@v1; kept for UI compatibility. */
    fun onPlayPause() { /* no-op */ }

    fun onVolumeChange(vol: Float) {
        _volume.value = vol
        engine?.setVolume(vol)
        client?.sendClientState(volume = (vol * 100).toInt())
    }

    fun disconnect() {
        engine?.stop(); engine = null
        client?.close(); client = null
        _connected.value = false
        _connectionStatus.value = "Disconnected"
        _currentFormat.value = "—"
        _isPlaying.value = false
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
        stopDiscovery()
    }
}
