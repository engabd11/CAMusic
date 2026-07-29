package com.engabd.sendpin.service

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.engabd.sendpin.audio.FormatNegotiator
import com.engabd.sendpin.audio.SendspinAudioEngine
import com.engabd.sendpin.audio.StreamQuality
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.discovery.MaDiscovery
import com.engabd.sendpin.discovery.PlayerIdentity
import com.engabd.sendpin.ma.MaApiClient
import com.engabd.sendpin.protocol.SendspinClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The player connection, held at **process scope** (by [com.engabd.sendpin.SendpinApp])
 * so it survives the Activity/ViewModel being destroyed. The UI observes it through
 * [com.engabd.sendpin.ui.viewmodel.PlayerViewModel]; [SendspinService] keeps the
 * process alive and shows the media notification while connected.
 */
class Playback(private val app: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val settings = AppSettings(app)
    private val discovery = MaDiscovery(app)

    val playerId: String = PlayerIdentity.getPlayerId(app)
    val playerName: String = PlayerIdentity.getDefaultPlayerName()
    private val deviceInfo = PlayerIdentity.getDeviceInfo()

    val discoveredServers = discovery.discoveredServers
    val isDiscovering = discovery.isDiscovering

    private val _connected = MutableStateFlow(false); val connected: StateFlow<Boolean> = _connected
    private val _connectionStatus = MutableStateFlow("Disconnected"); val connectionStatus: StateFlow<String> = _connectionStatus

    private val _trackTitle = MutableStateFlow(""); val trackTitle: StateFlow<String> = _trackTitle
    private val _artist = MutableStateFlow(""); val artist: StateFlow<String> = _artist
    private val _album = MutableStateFlow(""); val album: StateFlow<String> = _album
    private val _artworkUrl = MutableStateFlow<String?>(null); val artworkUrl: StateFlow<String?> = _artworkUrl
    private val _isPlaying = MutableStateFlow(false); val isPlaying: StateFlow<Boolean> = _isPlaying
    private val _volume = MutableStateFlow(1.0f); val volume: StateFlow<Float> = _volume
    private val _currentFormat = MutableStateFlow("—"); val currentFormat: StateFlow<String> = _currentFormat
    private val _streamQuality = MutableStateFlow<StreamQuality?>(null); val streamQuality: StateFlow<StreamQuality?> = _streamQuality
    private val _serverUrl = MutableStateFlow(""); val serverUrl: StateFlow<String> = _serverUrl
    private val _connectionLog = MutableStateFlow<List<String>>(emptyList()); val connectionLog: StateFlow<List<String>> = _connectionLog
    private val _positionMs = MutableStateFlow(0L); val playbackPositionMs: Long get() = _positionMs.value
    private val _durationMs = MutableStateFlow(0L); val playbackDurationMs: Long get() = _durationMs.value

    val savedUsername: Flow<String> get() = settings.maUsername
    val savedPassword: Flow<String> get() = settings.maPassword
    val savedPlayerName: Flow<String> get() = settings.playerName

    val hasSavedServer: StateFlow<Boolean> =
        settings.maBaseUrl.map { it.isNotBlank() }.stateIn(scope, SharingStarted.Eagerly, false)
    private val _bootChecked = MutableStateFlow(false); val bootChecked: StateFlow<Boolean> = _bootChecked

    private var client: SendspinClient? = null
    private var engine: SendspinAudioEngine? = null
    private var discoveryStop: Job? = null
    private var volumeJob: Job? = null

    // --- audio focus -------------------------------------------------------

    private val audioManager by lazy { app.getSystemService(AUDIO_SERVICE) as AudioManager }
    private var audioFocusRequest: AudioFocusRequest? = null
    @Volatile private var wasPlayingBeforeFocusLoss = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Duck: lower volume but keep playing.
                engine?.setVolume(volume.value * 0.3f)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Temporary loss (phone call) — pause.
                wasPlayingBeforeFocusLoss = _isPlaying.value
                if (_isPlaying.value) engine?.stop()
                _isPlaying.value = false
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Full loss — stop playback and abandon focus.
                wasPlayingBeforeFocusLoss = false
                engine?.stop()
                _isPlaying.value = false
                abandonAudioFocus()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                // Regained — restore volume and resume if we were playing.
                engine?.setVolume(volume.value)
                if (wasPlayingBeforeFocusLoss) {
                    wasPlayingBeforeFocusLoss = false
                    // Can't truly resume a Sendspin stream (server-driven), but reset state.
                    _isPlaying.value = true
                }
            }
        }
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            audioManager.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusListener)
        }
    }

    init {
        scope.launch {
            val base = settings.maBaseUrl.first()
            val user = settings.maUsername.first()
            if (base.isNotBlank() && user.isNotBlank()) connectToServer(sendspinUrlFrom(base))
            _bootChecked.value = true
        }
    }

    // --- discovery --------------------------------------------------------

    fun startDiscovery() {
        discovery.startDiscovery()
        discoveryStop?.cancel()
        discoveryStop = scope.launch { delay(8_000); discovery.stopDiscovery() }
    }

    fun stopDiscovery() {
        discoveryStop?.cancel()
        discovery.stopDiscovery()
    }

    // --- connection -------------------------------------------------------

    fun connectToServer(url: String, username: String = "", password: String = "", name: String = "") {
        disconnect(stopService = false)
        _serverUrl.value = url
        _connectionStatus.value = "Signing in…"
        scope.launch {
            val user = username.ifBlank { settings.maUsername.first() }
            val pass = password.ifBlank { settings.maPassword.first() }
            val playerName = name.ifBlank { settings.playerName.first() }.ifBlank { PlayerIdentity.getDefaultPlayerName() }
            val base = httpBase(url)
            settings.setMa(base, user, pass)
            settings.setPlayerName(playerName)
            val hasCreds = user.isNotBlank() && pass.isNotBlank()
            val token = if (hasCreds) fetchMaToken(base, user, pass) else null
            if (hasCreds && token == null) {
                _connectionStatus.value = "Sign-in failed — check username / password"
                return@launch
            }
            startSendspin(url, token, playerName)
        }
    }

    private suspend fun fetchMaToken(base: String, user: String, pass: String): String? {
        val api = MaApiClient()
        return try {
            api.connect(base, token = null, username = user, password = pass)
            val end = withTimeoutOrNull(12_000) {
                api.state.first { it == MaApiClient.State.CONNECTED || it == MaApiClient.State.ERROR }
            }
            if (end == MaApiClient.State.CONNECTED) api.authToken else null
        } catch (_: Exception) { null } finally { api.disconnect() }
    }

    private fun startSendspin(url: String, token: String?, name: String) {
        val c = SendspinClient(); client = c
        val eng = SendspinAudioEngine(c.clock); engine = eng

        scope.launch { c.state.collect { _connected.value = it == SendspinClient.State.CONNECTED } }
        scope.launch { c.statusText.collect { _connectionStatus.value = it } }
        scope.launch { c.events.collect { _connectionLog.value = it } }
        scope.launch {
            c.nowPlaying.collect { np ->
                _trackTitle.value = np?.title ?: ""
                _artist.value = np?.artist ?: ""
                _album.value = np?.album ?: ""
                _artworkUrl.value = np?.artworkUrl
                _durationMs.value = np?.durationMs ?: 0
                _positionMs.value = np?.progressMs ?: 0
            }
        }
        scope.launch {
            c.streamFormat.collect { f ->
                _currentFormat.value = f?.let { "${it.sampleRate / 1000}kHz / ${it.bitDepth}-bit / ${it.codec.uppercase()}" } ?: "—"
                _streamQuality.value = f?.let { StreamQuality(it.codec, it.sampleRate, it.bitDepth) }
            }
        }
        scope.launch {
            c.serverCommands.collect { cmd ->
                when (cmd.command) {
                    "volume" -> cmd.volume?.let { v -> _volume.value = v / 100f; eng.setVolume(v / 100f) }
                    "mute" -> cmd.mute?.let { m -> eng.setVolume(if (m) 0f else _volume.value) }
                }
            }
        }
        scope.launch {
            c.streamEvents.collect { ev ->
                when (ev) {
                    is SendspinClient.StreamEvent.Start -> {
                        requestAudioFocus()
                        eng.start(ev.format); _isPlaying.value = true
                    }
                    SendspinClient.StreamEvent.End -> { eng.stop(); _isPlaying.value = false }
                    SendspinClient.StreamEvent.Clear -> eng.flush()
                }
            }
        }
        scope.launch { c.audioFrames.collect { bytes -> eng.submit(bytes) } }

        // Foreground service keeps the process (and this connection) alive in the background.
        ContextCompat.startForegroundService(app, Intent(app, SendspinService::class.java).apply {
            action = SendspinService.ACTION_CONNECT
        })

        c.connect(url, playerId, name, deviceInfo, FormatNegotiator.supportedFormats, token)
    }

    /** Play/pause is server-driven for a Sendspin player@v1; kept for UI compatibility. */
    fun onPlayPause() { /* no-op */ }

    /** Media button: skip next — server-driven, but allow the UI to stay in sync. */
    fun onMediaNext() { /* no-op — Sendspin player@v1 has no transport; server pushes tracks */ }

    /** Media button: skip previous. */
    fun onMediaPrevious() { /* no-op */ }

    /** Media button: seek — convert seconds for the server. */
    fun onMediaSeek(positionSec: Int) {
        volumeJob?.cancel()
        volumeJob = scope.launch { delay(100); client?.sendClientState() }
    }

    fun onVolumeChange(vol: Float) {
        _volume.value = vol
        engine?.setVolume(vol)
        volumeJob?.cancel()
        volumeJob = scope.launch { delay(180); client?.sendClientState(volume = (vol * 100).toInt()) }
    }

    fun enablePlayer() = scope.launch {
        val base = settings.maBaseUrl.first()
        if (base.isNotBlank()) connectToServer(sendspinUrlFrom(base))
    }

    fun disablePlayer() = disconnect()

    fun logout() {
        disconnect()
        scope.launch { settings.setMa("", "", "") }
    }

    fun disconnect(stopService: Boolean = true) {
        abandonAudioFocus()
        engine?.stop(); engine = null
        client?.close(); client = null
        _connected.value = false
        _connectionStatus.value = "Disconnected"
        _currentFormat.value = "—"
        _streamQuality.value = null
        _isPlaying.value = false
        _positionMs.value = 0
        _durationMs.value = 0
        if (stopService) app.stopService(Intent(app, SendspinService::class.java))
    }

    private fun sendspinUrlFrom(base: String): String {
        val ws = base.trim().replace("https://", "wss://").replace("http://", "ws://")
            .let { if (it.startsWith("ws")) it else "ws://$it" }.trimEnd('/')
        return "$ws/sendspin"
    }

    private fun httpBase(url: String): String {
        var u = url.trim()
        if (u.startsWith("wss://")) u = "https://" + u.removePrefix("wss://")
        else if (u.startsWith("ws://")) u = "http://" + u.removePrefix("ws://")
        else if (!u.startsWith("http")) u = "http://$u"
        val schemeEnd = u.indexOf("://") + 3
        val pathStart = u.indexOf('/', schemeEnd)
        return if (pathStart >= 0) u.substring(0, pathStart) else u
    }
}
