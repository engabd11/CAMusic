package com.engabd.sendpin.protocol

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

/**
 * Sendspin player client for Music Assistant over a **plain WebSocket** (text
 * frames = JSON `{type,payload}`, binary frames = audio). Orchestrates the whole
 * handshake and exposes parsed state as flows so the UI/service layers stay thin:
 *
 *   connect → (optional `auth` → `auth_ok`) → `client/hello` → `server/hello`
 *           → `client/time`/`server/time` loop (feeds the Kalman clock)
 *           → periodic `client/state` (so MA marks us available)
 *           → `stream/start` + binary audio + `server/state` metadata.
 *
 * Modeled on the working massdroid client (MIT) and MA's own mobile app (Apache-2.0).
 */
class SendspinClient(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true   // `type` fields carry defaults — must be emitted
        explicitNulls = false   // omit null device_info / codec_header / etc.
    },
) {
    enum class State { DISCONNECTED, CONNECTING, AUTHENTICATING, CONNECTED, ERROR }

    sealed class StreamEvent {
        data class Start(val format: StreamStartPlayerInfo) : StreamEvent()
        data object End : StreamEvent()
        data object Clear : StreamEvent()
    }

    /** Clock shared with the audio scheduler. */
    val clock = ClockSync()

    private val httpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)   // stream: no read timeout
        .pingInterval(5, TimeUnit.SECONDS)       // keepalive
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var webSocket: WebSocket? = null
    private var timeJob: Job? = null
    private var stateJob: Job? = null

    private var clientId = ""
    private var clientName = ""
    private var deviceInfo: DeviceInfo? = null
    private var supportedFormats: List<AudioFormatSpec> = emptyList()
    private var token: String? = null

    @Volatile private var lastVolume = 100
    @Volatile private var lastMuted = false
    @Volatile private var staticDelayMs = 0

    private val _state = MutableStateFlow(State.DISCONNECTED)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _statusText = MutableStateFlow("Disconnected")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private val _streamFormat = MutableStateFlow<StreamStartPlayerInfo?>(null)
    val streamFormat: StateFlow<StreamStartPlayerInfo?> = _streamFormat.asStateFlow()

    private val _serverCommands = MutableSharedFlow<PlayerCommandPayload>(extraBufferCapacity = 32)
    val serverCommands: SharedFlow<PlayerCommandPayload> = _serverCommands.asSharedFlow()

    private val _streamEvents = MutableSharedFlow<StreamEvent>(extraBufferCapacity = 32)
    val streamEvents: SharedFlow<StreamEvent> = _streamEvents.asSharedFlow()

    // Raw binary audio chunks; SendspinAudioEngine parses the header + decodes.
    private val _audioFrames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 512)
    val audioFrames: SharedFlow<ByteArray> = _audioFrames.asSharedFlow()

    fun connect(
        serverUrl: String,
        clientId: String,
        clientName: String,
        deviceInfo: DeviceInfo,
        supportedFormats: List<AudioFormatSpec>,
        token: String? = null,
    ) {
        this.clientId = clientId
        this.clientName = clientName
        this.deviceInfo = deviceInfo
        this.supportedFormats = supportedFormats
        this.token = token

        _state.value = State.CONNECTING
        _statusText.value = "Connecting…"
        val request = Request.Builder().url(buildUrl(serverUrl)).build()
        webSocket = httpClient.newWebSocket(request, listener)
    }

    fun disconnect(reason: String = "user_request") {
        timeJob?.cancel(); timeJob = null
        stateJob?.cancel(); stateJob = null
        val ws = webSocket
        webSocket = null
        try { ws?.send(json.encodeToString(SendspinGoodbye(payload = GoodbyePayload(reason)))) } catch (_: Throwable) {}
        ws?.close(1000, reason)
        _state.value = State.DISCONNECTED
        _statusText.value = "Disconnected"
    }

    fun close() {
        disconnect()
        scope.cancel()
    }

    fun sendClientState(volume: Int = lastVolume, muted: Boolean = lastMuted, staticDelayMs: Int = this.staticDelayMs) {
        lastVolume = volume; lastMuted = muted; this.staticDelayMs = staticDelayMs
        val msg = SendspinClientState(
            payload = ClientStatePayload(player = PlayerStateInfo(volume, muted, staticDelayMs))
        )
        webSocket?.send(json.encodeToString(msg))
    }

    fun sendRequestFormat(codec: String, sampleRate: Int = 48000, bitDepth: Int = 16, channels: Int = 2) {
        val msg = SendspinRequestFormat(
            payload = RequestFormatPayload(RequestFormatPlayerPayload(codec, sampleRate, bitDepth, channels))
        )
        webSocket?.send(json.encodeToString(msg))
    }

    // --- internals --------------------------------------------------------

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val t = token
            if (t.isNullOrBlank()) {
                sendHello()
            } else {
                _state.value = State.AUTHENTICATING
                _statusText.value = "Authenticating…"
                webSocket.send(json.encodeToString(SendspinAuthMessage(token = t, clientId = clientId)))
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // Stamp T4 before any deserialize/dispatch (only server/time needs it).
            val rxUs = System.nanoTime() / 1000
            val parsed = try {
                SendspinIncoming.parse(text, json)
            } catch (e: Exception) {
                SendspinIncoming.Unknown("parse_error")
            }
            val incoming = if (parsed is SendspinIncoming.ServerTime) parsed.copy(clientReceivedUs = rxUs) else parsed
            scope.launch { handleIncoming(incoming) }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            _audioFrames.tryEmit(bytes.toByteArray())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (_state.value != State.ERROR) {
                _state.value = State.DISCONNECTED
                _statusText.value = "Disconnected"
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            _state.value = State.ERROR
            _statusText.value = "Error: ${t.message}"
        }
    }

    private fun handleIncoming(msg: SendspinIncoming) {
        when (msg) {
            is SendspinIncoming.AuthOk -> sendHello()
            is SendspinIncoming.AuthError -> {
                _state.value = State.ERROR
                _statusText.value = "Auth failed: ${msg.message}"
            }
            is SendspinIncoming.ServerHello -> {
                _state.value = State.CONNECTED
                _statusText.value = "Connected"
                startTimeSync()
                startStateReporting()
            }
            is SendspinIncoming.ServerTime -> {
                val p = msg.payload
                clock.onServerTime(p.clientTransmitted, p.serverReceived, p.serverTransmitted, msg.clientReceivedUs)
            }
            is SendspinIncoming.ServerState -> {
                val m = msg.payload.metadata ?: return
                _nowPlaying.value = NowPlaying(
                    title = m.title.orEmpty(),
                    artist = m.artist.orEmpty(),
                    album = m.album.orEmpty(),
                    artworkUrl = m.artworkUrl,
                    durationMs = m.progress?.trackDuration,
                    progressMs = m.progress?.trackProgress,
                )
            }
            is SendspinIncoming.StreamStart -> {
                _streamFormat.value = msg.payload.player
                _streamEvents.tryEmit(StreamEvent.Start(msg.payload.player))
            }
            is SendspinIncoming.StreamEnd -> _streamEvents.tryEmit(StreamEvent.End)
            is SendspinIncoming.StreamClear -> _streamEvents.tryEmit(StreamEvent.Clear)
            is SendspinIncoming.ServerCommand -> msg.payload.player?.let { _serverCommands.tryEmit(it) }
            else -> {}
        }
    }

    private fun sendHello() {
        _state.value = State.AUTHENTICATING
        _statusText.value = "Handshaking…"
        val hello = SendspinClientHello(
            payload = ClientHelloPayload(
                clientId = clientId,
                name = clientName,
                deviceInfo = deviceInfo,
                playerV1Support = PlayerV1Support(supportedFormats = supportedFormats),
            )
        )
        webSocket?.send(json.encodeToString(hello))
    }

    private fun startTimeSync() {
        timeJob?.cancel()
        timeJob = scope.launch {
            while (isActive) {
                val t1 = System.nanoTime() / 1000
                webSocket?.send(json.encodeToString(SendspinClientTime(payload = ClientTimePayload(t1))))
                // Fast cadence until the filter has enough samples, then relax.
                delay(if (clock.filter.sampleCount < 50) 300L else 2000L)
            }
        }
    }

    private fun startStateReporting() {
        stateJob?.cancel()
        stateJob = scope.launch {
            while (isActive) {
                sendClientState()
                delay(5_000L)
            }
        }
    }

    private fun buildUrl(serverUrl: String): String {
        var url = serverUrl.trim()
        if (url.startsWith("http://")) url = "ws://" + url.removePrefix("http://")
        if (url.startsWith("https://")) url = "wss://" + url.removePrefix("https://")
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) url = "ws://$url"
        val noSlash = url.trimEnd('/')
        return if (noSlash.contains("/sendspin")) noSlash else "$noSlash/sendspin"
    }
}
