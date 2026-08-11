package com.engabd.sendpin.protocol

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.engabd.sendpin.data.LanOnlyCleartext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
        encodeDefaults = true   // `type` fields carry defaults - must be emitted
        explicitNulls = false   // omit null device_info / codec_header / etc.
    },
) {
    enum class State { DISCONNECTED, CONNECTING, AUTHENTICATING, CONNECTED, ERROR }

    /** Clock shared with the audio scheduler. */
    val clock = ClockSync()

    private val httpClient = OkHttpClient.Builder()
        .addInterceptor(LanOnlyCleartext)
        .readTimeout(0, TimeUnit.MILLISECONDS)   // stream: no read timeout
        .pingInterval(5, TimeUnit.SECONDS)       // keepalive
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var webSocket: WebSocket? = null
    private var timeJob: Job? = null
    private var stateJob: Job? = null
    private var reconnectJob: Job? = null

    @Volatile private var userClosed = false
    private var builtUrl = ""
    private var attempt = 0

    /**
     * Whether this client is in **idle mode** — no audio is flowing, so the timer
     * loops relax to [IDLE_TIME_SYNC_MS] / [IDLE_STATE_REPORT_MS] to reduce
     * background CPU and network traffic. The clock filter and sync gate still
     * run; they just sample less often. A `stream/start` flips this back to fast
     * cadence via [setIdleMode].
     *
     * Set by [Playback] from the connection service's idle/active transition.
     */
    @Volatile private var idleMode = false

    /** When a socket was last opened, for [reconnectNow]'s rate limit. */
    @Volatile private var lastDialAtMs = 0L

    private var clientId = ""
    private var clientName = ""
    private var deviceInfo: DeviceInfo? = null
    private var supportedFormats: List<AudioFormatSpec> = emptyList()
    private var token: String? = null

    @Volatile private var lastVolume = 100
    @Volatile private var lastMuted = false

    /**
     * The per-player latency trim, in milliseconds, and the single source of truth
     * for it — the settings layer and the server both write here, and the audio
     * engine reads only from here, so the two can't fight over it.
     *
     * Sign follows the spec, not Music Assistant's own UI: a **positive** value
     * means this output path adds that much latency, so the engine subtracts it and
     * plays *earlier*. MA's `sendspin_sync_delay` control is signed and reads as
     * "delay this speaker", which is the opposite sense; the mapping happens at the
     * settings boundary, and only 0..5000 goes on the wire.
     */
    private val _staticDelayMs = MutableStateFlow(0)
    val staticDelay: StateFlow<Int> = _staticDelayMs.asStateFlow()

    /** True while the clock is too green to be trusted — output should be muted. */
    private val _syncMuted = MutableStateFlow(false)
    val syncMuted: StateFlow<Boolean> = _syncMuted.asStateFlow()

    /** Roles the server actually activated, from `server/hello`. */
    private val _activeRoles = MutableStateFlow<List<String>>(emptyList())
    val activeRoles: StateFlow<List<String>> = _activeRoles.asStateFlow()

    private val _state = MutableStateFlow(State.DISCONNECTED)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _statusText = MutableStateFlow("Disconnected")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    // On-screen handshake trace (also mirrored to logcat) — the last ~40 events.
    private val _events = MutableStateFlow<List<String>>(emptyList())
    val events: StateFlow<List<String>> = _events.asStateFlow()
    private var connectStartMs = 0L

    private fun dbg(msg: String) {
        Log.i(TAG, msg)
        val t = if (connectStartMs == 0L) 0 else System.currentTimeMillis() - connectStartMs
        _events.value = (_events.value + "+${t}ms  $msg").takeLast(40)
    }

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private val _streamFormat = MutableStateFlow<StreamStartPlayerInfo?>(null)
    val streamFormat: StateFlow<StreamStartPlayerInfo?> = _streamFormat.asStateFlow()

    private val _serverCommands = MutableSharedFlow<PlayerCommandPayload>(extraBufferCapacity = 32)
    val serverCommands: SharedFlow<PlayerCommandPayload> = _serverCommands.asSharedFlow()

    /**
     * Where stream lifecycle and audio are delivered, in wire order.
     *
     * Deliberately an interface and not a `SharedFlow`: two collectors on two flows
     * have no ordering relative to each other, and a buffered flow drops silently on
     * overflow. Direct calls from the single ingest consumer have neither problem.
     * The remaining flows below are UI-facing and order-insensitive.
     */
    interface PlaybackSink {
        fun onStreamStart(format: StreamStartPlayerInfo)
        fun onStreamEnd()
        fun onStreamClear()
        fun onAudio(frame: ByteArray)
        fun onDisconnected()
    }

    /**
     * Set before [connect]. Calls arrive on the ingest coroutine, so implementations
     * must not block: anything slow (starting a foreground service, audio focus)
     * belongs on its own coroutine, or it stalls the socket.
     */
    @Volatile
    var sink: PlaybackSink? = null

    /** Group playback state changes pushed by the server — instant, no 5s poll. */
    private val _groupUpdates = MutableSharedFlow<SendspinIncoming.GroupUpdate>(extraBufferCapacity = 16)
    val groupUpdates: SharedFlow<SendspinIncoming.GroupUpdate> = _groupUpdates.asSharedFlow()

    /**
     * The single ordered queue every socket callback feeds. Unbounded, because
     * dropping to make room would be exactly the reordering this exists to prevent;
     * [Inbox.accept] is the memory guard instead.
     */
    private val inbox = Channel<Inbound>(Channel.UNLIMITED)
    private val inboxDepth = AtomicInteger(0)
    private var ingestJob: Job? = null

    /**
     * Wakes the `client/time` loop out of its wait. Conflated: several requests
     * before the loop gets to look are one request, and one that arrives while it
     * is already sending simply shortens the next wait instead of being lost.
     */
    private val timeKick = Channel<Unit>(Channel.CONFLATED)

    /** Frames dropped because the consumer stalled — surfaced for the debug trace. */
    private val _droppedFrames = MutableStateFlow(0L)
    val droppedFrames: StateFlow<Long> = _droppedFrames.asStateFlow()

    /** Enqueue from a socket callback. Non-blocking; ordering is the whole point. */
    private fun offer(item: Inbound) {
        if (!Inbox.accept(inboxDepth.get(), item is Inbound.Audio)) {
            _droppedFrames.value++
            return
        }
        inboxDepth.incrementAndGet()
        // UNLIMITED never rejects, but the channel is closed after close().
        if (inbox.trySend(item).isFailure) inboxDepth.decrementAndGet()
    }

    /**
     * Drains [inbox] on one coroutine, so wire order survives all the way to the
     * audio engine. Parsing happens here rather than on the socket's reader thread:
     * it is the only per-message cost that grows with payload size, and the T4
     * timestamp it needs was already taken at arrival.
     */
    private fun startIngest() {
        if (ingestJob?.isActive == true) return
        ingestJob = scope.launch {
            for (item in inbox) {
                inboxDepth.decrementAndGet()
                when (item) {
                    is Inbound.Audio -> sink?.onAudio(item.frame)
                    is Inbound.Disconnected -> sink?.onDisconnected()
                    is Inbound.Text -> {
                        val parsed = try {
                            SendspinIncoming.parse(item.text, json)
                        } catch (_: Exception) {
                            dbg("rx PARSE-ERROR: ${item.text.take(160)}")
                            SendspinIncoming.Unknown("parse_error")
                        }
                        // server/time is chatty once syncing → keep it out of the trace.
                        if (parsed is SendspinIncoming.ServerTime) Log.d(TAG, "rx server/time")
                        else dbg("rx ${item.text.take(200)}")
                        handleIncoming(
                            if (parsed is SendspinIncoming.ServerTime) {
                                parsed.copy(clientReceivedUs = item.rxUs)
                            } else parsed
                        )
                    }
                }
            }
        }
    }

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
        connectStartMs = System.currentTimeMillis()
        _events.value = emptyList()
        userClosed = false
        attempt = 0
        reconnectJob?.cancel()
        startIngest()
        builtUrl = buildUrl(serverUrl)
        dbg("connect → $builtUrl (token=${if (token.isNullOrBlank()) "none" else "set"})")
        openSocket()
    }

    private fun openSocket() {
        lastDialAtMs = android.os.SystemClock.elapsedRealtime()
        webSocket = httpClient.newWebSocket(Request.Builder().url(builtUrl).build(), listener)
    }

    /** Reconnect with capped backoff after an unexpected drop (keeps the MA player available). */
    private fun scheduleReconnect() {
        if (userClosed) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val delayMs = (500L * (1 shl attempt.coerceAtMost(5))).coerceAtMost(15_000L)
            attempt++
            dbg("reconnecting in ${delayMs}ms (attempt $attempt)")
            delay(delayMs)
            if (!userClosed) {
                _state.value = State.CONNECTING
                _statusText.value = "Reconnecting…"
                openSocket()
            }
        }
    }

    /**
     * Reconnect now instead of at the end of the backoff.
     *
     * Same argument as [com.engabd.sendpin.ma.MaApiClient.reconnectNow], and the
     * same moment: the user has asked for music. This socket is the only way Music
     * Assistant can reach this phone with audio, so a player still counting down a
     * fifteen-second retry is a player MA cannot start a stream on — the command
     * lands and nothing plays until the timer happens to run out.
     *
     * Does nothing while a socket is up or on its way, and no more often than
     * [RECONNECT_MIN_GAP_MS].
     */
    fun reconnectNow() {
        if (userClosed || builtUrl.isBlank()) return
        when (_state.value) {
            State.CONNECTED, State.CONNECTING, State.AUTHENTICATING -> return
            else -> {}
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastDialAtMs < RECONNECT_MIN_GAP_MS) return
        lastDialAtMs = now
        reconnectJob?.cancel()
        attempt = 0
        _state.value = State.CONNECTING
        _statusText.value = "Reconnecting…"
        dbg("reconnecting now (user asked for playback)")
        openSocket()
    }

    /**
     * Close the socket, telling the server *why*. The reason reaches MA in the
     * `client/goodbye` payload: `"user_request"` drops the player from the
     * speaker list immediately, `"restart"` asks MA to hold the slot open for a
     * ~30 s resume grace so a process restart doesn't make the player vanish.
     */
    fun disconnect(reason: String = "user_request") {
        userClosed = true
        reconnectJob?.cancel(); reconnectJob = null
        timeJob?.cancel(); timeJob = null
        stateJob?.cancel(); stateJob = null
        val ws = webSocket
        webSocket = null
        try { ws?.send(json.encodeToString(SendspinGoodbye(payload = GoodbyePayload(reason)))) } catch (_: Throwable) {}
        ws?.close(1000, reason)
        _state.value = State.DISCONNECTED
        _statusText.value = "Disconnected"
    }

    fun close(reason: String = "user_request") {
        disconnect(reason)
        // Only here, not in disconnect(): disconnect() is followed by reconnects, and
        // the ingest loop has to survive those to keep serving the same inbox.
        ingestJob?.cancel(); ingestJob = null
        inbox.close()
        scope.cancel()
    }

    /**
     * Report player state. [state] is `"synchronized"` or `"error"` per the spec —
     * see [SyncGate] for when each is right.
     */
    fun sendClientState(
        volume: Int = lastVolume,
        muted: Boolean = lastMuted,
        staticDelayMs: Int = _staticDelayMs.value,
        state: String = "synchronized",
    ) {
        lastVolume = volume; lastMuted = muted
        _staticDelayMs.value = staticDelayMs
        val msg = SendspinClientState(
            payload = ClientStatePayload(
                state = state,
                // The spec's wire range is 0..5000; a negative trim is meaningful
                // locally (play later) but has nothing to say to the server.
                player = PlayerStateInfo(volume, muted, staticDelayMs.coerceIn(0, MAX_STATIC_DELAY_MS)),
            )
        )
        webSocket?.send(json.encodeToString(msg))
    }

    /** Set the latency trim locally (from settings). Pushed to the server too. */
    fun setStaticDelayMs(ms: Int) {
        if (_staticDelayMs.value == ms) return
        sendClientState(staticDelayMs = ms)
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
            // Bind the field to the just-opened socket: onOpen can race ahead of the
            // `webSocket = newWebSocket(...)` assignment in connect(), which would make
            // the field-based send() a silent no-op. Send the first frame via the param.
            this@SendspinClient.webSocket = webSocket
            val t = token
            dbg("ws OPEN (http ${response.code}) → ${if (t.isNullOrBlank()) "no token, sending hello" else "sending auth"}")
            if (t.isNullOrBlank()) {
                sendHello(webSocket)
            } else {
                _state.value = State.AUTHENTICATING
                _statusText.value = "Authenticating…"
                val ok = webSocket.send(json.encodeToString(SendspinAuthMessage(token = t, clientId = clientId)))
                dbg("sent auth (queued=$ok)")
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // Stamp T4 here and nowhere later: this is the earliest point the frame
            // exists locally, and measuring it after a parse or a coroutine hop
            // biases the clock offset low, which makes the player play late.
            offer(Inbound.Text(text, MonotonicClock.nowUs()))
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            offer(Inbound.Audio(bytes.toByteArray()))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            dbg("ws CLOSING $code '$reason'")
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            dbg("ws CLOSED $code '$reason'")
            timeJob?.cancel(); stateJob?.cancel()
            // Queued rather than applied here, so the engine is torn down *after* any
            // frames still ahead of it in the inbox rather than in the middle of them.
            offer(Inbound.Disconnected)
            if (_state.value != State.ERROR) {
                _state.value = State.DISCONNECTED
                _statusText.value = "Disconnected"
            }
            // 4001 = "First message must be auth" → a token problem, retrying won't help.
            if (code != 4001) scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            dbg("ws FAILURE (http ${response?.code}): ${t.message}")
            timeJob?.cancel(); stateJob?.cancel()
            offer(Inbound.Disconnected)
            _state.value = State.ERROR
            _statusText.value = "Error: ${t.message}"
            if (response?.code != 404) scheduleReconnect()
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
                // The payload used to be parsed and thrown away, which meant a server
                // that never activated `player@v1` looked identical to one that did —
                // the client would sit there advertising a role nobody asked it to
                // play, with no way to tell from the trace.
                val p = msg.raw["payload"] as? JsonObject
                val roles = (p?.get("active_roles") as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .orEmpty()
                _activeRoles.value = roles
                val reason = (p?.get("connection_reason") as? JsonPrimitive)?.contentOrNull
                val serverName = (p?.get("name") as? JsonPrimitive)?.contentOrNull
                dbg("server/hello → CONNECTED ✓ (${serverName ?: "?"}, roles=$roles, reason=${reason ?: "?"})")
                if (roles.isNotEmpty() && roles.none { it.startsWith("player@") }) {
                    // Not fatal — metadata-only connections are legitimate — but the
                    // silence that follows is otherwise unexplainable.
                    Log.w(TAG, "server did not activate a player role; no audio will arrive")
                }
                attempt = 0   // reset backoff on a good handshake
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
                    progressAtServerUs = m.timestamp,
                    speedMilli = m.progress?.speedMilli ?: 1000L,
                )
            }
            is SendspinIncoming.GroupUpdate -> _groupUpdates.tryEmit(msg)
            is SendspinIncoming.StreamStart -> {
                // The head of this stream is about to be scheduled against the clock,
                // so take a fresh reading of it now rather than at the next tick.
                resyncClock()
                _streamFormat.value = msg.payload.player
                sink?.onStreamStart(msg.payload.player)
            }
            is SendspinIncoming.StreamEnd -> sink?.onStreamEnd()
            is SendspinIncoming.StreamClear -> sink?.onStreamClear()
            is SendspinIncoming.ServerCommand -> msg.payload.player?.let { p ->
                // Latched here rather than only downstream, so the value the client
                // reports back stays right whoever set it.
                if (p.command == "set_static_delay") {
                    p.staticDelayMs?.let { ms ->
                        val clamped = ms.coerceIn(0, MAX_STATIC_DELAY_MS)
                        dbg("server/command set_static_delay=${clamped}ms")
                        sendClientState(staticDelayMs = clamped)
                    }
                }
                _serverCommands.tryEmit(p)
            }
            else -> {}
        }
    }

    private fun sendHello(ws: WebSocket? = webSocket) {
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
        val text = json.encodeToString(hello)
        val queued = ws?.send(text) ?: false
        dbg("sent client/hello (queued=$queued)")
    }

    /**
     * Tell the client whether audio is flowing, so its timer loops can adapt.
     *
     * Idle mode (no audio) relaxes the time-sync and state-reporting cadence to
     * reduce background CPU and network traffic — the clock filter doesn't need
     * a sample every two seconds when nothing is being scheduled against it.
     * Active mode (audio flowing, including TTS) drops back to the fast cadence
     * so the clock converges quickly for the stream that just started.
     *
     * Called by [Playback] from the connection service's idle/active transition.
     * The time-sync and state-reporting loops read [idleMode] on every iteration,
     * so the change takes effect on the next tick — no restart needed.
     */
    fun setIdleMode(idle: Boolean) {
        idleMode = idle
        // A stream starting (idle → active) is exactly when a fresh clock sample
        // is most valuable — the head of the stream is scheduled against it.
        if (!idle) resyncClock()
    }

    private fun startTimeSync() {
        timeJob?.cancel()
        timeJob = scope.launch {
            while (isActive) {
                val t1 = MonotonicClock.nowUs()
                webSocket?.send(json.encodeToString(SendspinClientTime(payload = ClientTimePayload(t1))))
                // Fast while the filter cannot be scheduled against, relaxed once it
                // can. Keyed on **readiness**, not only on the sample count: a
                // connection that has been up for hours has thousands of samples and
                // can still be knocked out of convergence — by a step the filter has
                // just spotted, or by a link whose jitter widened — and at the
                // relaxed cadence it would then take a sample every two seconds to
                // come back, which is dead air at the start of whatever the user
                // just tapped. The two conditions together mean "not fit to play
                // against", which is exactly when samples are worth paying for.
                //
                // Idle mode (no audio flowing) relaxes the settled cadence further
                // — the clock doesn't need maintenance when nothing schedules
                // against it, and the 30s cadence still keeps the filter warm
                // enough to converge quickly when a stream starts.
                val settled = clock.filter.sampleCount >= 50 && clock.isReadyForPlaybackStart()
                val cadence = when {
                    !settled -> FAST_TIME_SYNC_MS
                    idleMode -> IDLE_TIME_SYNC_MS
                    else -> SLOW_TIME_SYNC_MS
                }
                // A resync request cuts the wait short — see [resyncClock].
                withTimeoutOrNull(cadence) { timeKick.receive() }
            }
        }
    }

    /**
     * Ask for a `client/time` round-trip now rather than at the next tick.
     *
     * Called when a stream is about to start, which is the one moment the offset is
     * about to be *used* — the head of the stream is scheduled against it, so a
     * sample taken here is worth more than several taken while nothing is playing.
     * It is also what turns the step detector around quickly: the first suspect
     * residual stands the clock down from ready, which drops the loop to the fast
     * cadence, and the confirming sample lands a few hundred milliseconds later.
     */
    fun resyncClock() {
        timeKick.trySend(Unit)
    }

    /**
     * Report `client/state` on a loop, telling the truth about whether this player
     * can actually be placed on the shared timeline yet — see [SyncGate].
     *
     * Faster while unconverged: the report is what the server acts on, so a stale
     * "still error" costs group join latency for no reason.
     */
    private fun startStateReporting() {
        stateJob?.cancel()
        stateJob = scope.launch {
            var unreadySinceMs = System.currentTimeMillis()
            while (isActive) {
                val ready = clock.isReadyForPlaybackStart()
                if (ready) unreadySinceMs = System.currentTimeMillis()
                val d = SyncGate.decide(
                    clockReady = ready,
                    unreadyMs = if (ready) 0L else System.currentTimeMillis() - unreadySinceMs,
                )
                if (_syncMuted.value != d.muted) {
                    dbg(if (d.muted) "clock unconverged → muting" else "clock ready → unmuting")
                }
                _syncMuted.value = d.muted
                sendClientState(
                    state = if (d.report == SyncGate.Report.SYNCHRONIZED) "synchronized" else "error"
                )
                delay(when {
                    !ready -> 300L
                    idleMode -> IDLE_STATE_REPORT_MS
                    else -> 5_000L
                })
            }
        }
    }

    private fun buildUrl(serverUrl: String): String {
        var url = serverUrl.trim()
        if (url.startsWith("http://")) url = "ws://" + url.removePrefix("http://")
        if (url.startsWith("https://")) url = "wss://" + url.removePrefix("https://")
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) url = "ws://$url"
        val noSlash = url.trimEnd('/')
        return when {
            noSlash.endsWith("/sendspin") -> noSlash
            // A pasted main-API URL (…/ws) → swap the path, don't nest /ws/sendspin.
            noSlash.endsWith("/ws") -> noSlash.removeSuffix("/ws") + "/sendspin"
            else -> "$noSlash/sendspin"
        }
    }

    companion object {
        private const val TAG = "SendspinClient"

        /** The spec's range for `static_delay_ms` in `client/state`. */
        const val MAX_STATIC_DELAY_MS = 5_000

        /** Nothing reopens the player socket faster than this. */
        const val RECONNECT_MIN_GAP_MS = 750L

        /** `client/time` cadence while the clock is not fit to schedule against. */
        const val FAST_TIME_SYNC_MS = 300L

        /** …and once it is: enough to hold a converged filter, and no more. */
        const val SLOW_TIME_SYNC_MS = 2_000L

        /**
         * …and when idle (no audio flowing): the clock filter doesn't need frequent
         * samples when nothing schedules against it. 30s keeps it warm enough to
         * converge within one or two fast samples when a stream starts.
         */
        const val IDLE_TIME_SYNC_MS = 30_000L

        /** State reporting cadence when idle — the server doesn't need updates when nothing is playing. */
        const val IDLE_STATE_REPORT_MS = 30_000L
    }
}
