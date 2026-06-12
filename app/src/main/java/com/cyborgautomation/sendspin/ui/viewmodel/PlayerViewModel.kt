package com.cyborgautomation.sendspin.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cyborgautomation.sendspin.audio.*
import com.cyborgautomation.sendspin.discovery.MaDiscovery
import com.cyborgautomation.sendspin.discovery.PlayerIdentity
import com.cyborgautomation.sendspin.protocol.*
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
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val _connectionStatus = MutableStateFlow("Disconnected")
    val connectionStatus: StateFlow<String> = _connectionStatus

    // Playback state
    private val _trackTitle = MutableStateFlow("")
    val trackTitle: StateFlow<String> = _trackTitle

    private val _artist = MutableStateFlow("")
    val artist: StateFlow<String> = _artist

    private val _album = MutableStateFlow("")
    val album: StateFlow<String> = _album

    private val _artworkUrl = MutableStateFlow<String?>(null)
    val artworkUrl: StateFlow<String?> = _artworkUrl

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _volume = MutableStateFlow(1.0f)
    val volume: StateFlow<Float> = _volume

    private val _currentFormat = MutableStateFlow("—")
    val currentFormat: StateFlow<String> = _currentFormat

    // Audio pipeline
    private var client: SendspinClient? = null
    private var clockSync: ClockSync? = null
    private var audioOutput: AudioOutput? = null
    private var flacDecoder: NativeFlacDecoder? = null
    private var scheduler: PlaybackScheduler? = null
    private var negotiatedFormat: NegotiatedFormat? = null

    // Server URL
    private val _serverUrl = MutableStateFlow("")
    val serverUrl: StateFlow<String> = _serverUrl

    fun startDiscovery() = discovery.startDiscovery()
    fun stopDiscovery() = discovery.stopDiscovery()

    fun connectToServer(url: String) {
        _serverUrl.value = url
        viewModelScope.launch {
            try {
                _connectionStatus.value = "Connecting..."

                client = SendspinClient(
                    serverUrl = url,
                    playerId = playerId,
                    playerName = playerName,
                    deviceInfo = deviceInfo,
                    playerV1Support = PlayerV1Support(
                        supportedFormats = FormatNegotiator.supportedFormats
                    ),
                )

                client!!.connect()

                // Wait for connection
                client!!.connectionState.first { it == SendspinClient.ConnectionState.CONNECTED }
                _connected.value = true
                _connectionStatus.value = "Connected"

                // Initialize clock sync
                clockSync = ClockSync(client!!)
                clockSync!!.initialSync()

                // Listen for messages
                viewModelScope.launch {
                    client!!.messages.collect { msg ->
                        handleMessage(msg)
                    }
                }

                // Listen for audio frames
                viewModelScope.launch {
                    client!!.audioFrames.collect { frame ->
                        scheduler?.enqueue(frame)
                    }
                }
            } catch (e: Exception) {
                _connectionStatus.value = "Error: ${e.message}"
                disconnect()
            }
        }
    }

    fun disconnect() {
        scheduler?.stop()
        scheduler = null
        flacDecoder?.close()
        flacDecoder = null
        audioOutput?.close()
        audioOutput = null
        clockSync = null
        client?.disconnect()
        client?.close()
        client = null
        _connected.value = false
        _connectionStatus.value = "Disconnected"
        _currentFormat.value = "—"
    }

    fun onPlayPause() {
        client?.send(Message.ClientState())
        // Toggle via server/command expectation; server sends play/pause commands
        // In a real player, we'd send the appropriate command
    }

    fun onVolumeChange(vol: Float) {
        _volume.value = vol
        audioOutput?.setVolume(vol)
        client?.send(Message.ClientState(volume = vol))
    }

    private fun handleMessage(msg: Message) {
        when (msg) {
            is Message.ServerHello -> {
                _connectionStatus.value = "Connected to ${msg.serverName}"
            }
            is Message.ServerState -> {
                _trackTitle.value = msg.trackTitle
                _artist.value = msg.artist
                _album.value = msg.album
                _artworkUrl.value = msg.artworkUrl
                _isPlaying.value = msg.playing
                msg.volume.let { _volume.value = it }
                msg.streamFormat?.let {
                    _currentFormat.value = "${it.sampleRate/1000}kHz / ${it.bitDepth}-bit / ${it.codec.uppercase()}"
                }
            }
            is Message.StreamStart -> {
                handleStreamStart(msg)
            }
            is Message.StreamEnd -> {
                scheduler?.stop()
                _isPlaying.value = false
            }
            is Message.ServerCommand -> {
                when (msg.command) {
                    "play" -> _isPlaying.value = true
                    "pause" -> _isPlaying.value = false
                    "stop" -> { _isPlaying.value = false; scheduler?.stop() }
                }
            }
            else -> {}
        }
    }

    private fun handleStreamStart(msg: Message.StreamStart) {
        val format = FormatNegotiator.resolve(msg.format) ?: return
        negotiatedFormat = format

        _currentFormat.value = "${format.sampleRate/1000}kHz / ${format.bitDepth}-bit / ${format.codec.uppercase()}"

        // Set up codec
        when (format.codec) {
            "flac" -> {
                flacDecoder = NativeFlacDecoder(
                    sampleRate = format.sampleRate,
                    channels = format.channels,
                    bitDepth = format.bitDepth,
                )
            }
            "pcm" -> {
                flacDecoder?.close()
                flacDecoder = null
            }
        }

        // Start audio output
        audioOutput = AudioOutput(
            sampleRate = format.sampleRate,
            channels = format.channels,
            bitDepth = format.bitDepth,
        )

        // Start scheduler
        scheduler = PlaybackScheduler(
            clockSync = clockSync!!,
            audioOutput = audioOutput!!,
            flacDecoder = flacDecoder,
            negotiatedFormat = format,
        )
        scheduler!!.start()
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
        stopDiscovery()
    }
}
