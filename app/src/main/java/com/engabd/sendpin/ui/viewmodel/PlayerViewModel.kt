package com.engabd.sendpin.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.engabd.sendpin.audio.FormatNegotiator
import com.engabd.sendpin.audio.SendspinAudioEngine
import com.engabd.sendpin.discovery.MaDiscovery
import com.engabd.sendpin.discovery.PlayerIdentity
import com.engabd.sendpin.protocol.SendspinClient
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val context: Context get() = getApplication()

    // Discovery
    private val discovery = MaDiscovery(context)
    val discoveredServers = discovery.discoveredServers
    val isDiscovering = discovery.isDiscovering

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

    private var client: SendspinClient? = null
    private var engine: SendspinAudioEngine? = null

    fun startDiscovery() = discovery.startDiscovery()
    fun stopDiscovery() = discovery.stopDiscovery()

    fun connectToServer(url: String) {
        disconnect()
        _serverUrl.value = url
        val c = SendspinClient(); client = c
        val eng = SendspinAudioEngine(c.clock); engine = eng

        viewModelScope.launch { c.state.collect { _connected.value = it == SendspinClient.State.CONNECTED } }
        viewModelScope.launch { c.statusText.collect { _connectionStatus.value = it } }
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

        c.connect(url, playerId, playerName, deviceInfo, FormatNegotiator.supportedFormats)
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
