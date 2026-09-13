package com.engabd.sendpin.protocol

import android.util.Log
import com.engabd.sendpin.data.Http
import com.engabd.sendpin.protocol.noise.B64Url
import com.engabd.sendpin.protocol.noise.NoiseCipherSuite
import com.engabd.sendpin.protocol.noise.NoiseFraming
import com.engabd.sendpin.protocol.noise.NoiseHandshake
import com.engabd.sendpin.protocol.noise.NoiseHandshakeException
import com.engabd.sendpin.protocol.noise.NoiseTransport
import com.engabd.sendpin.protocol.noise.PairingStore
import com.engabd.sendpin.protocol.noise.PskCategory
import com.engabd.sendpin.protocol.noise.ResolvedPsk
import com.engabd.sendpin.protocol.noise.SendspinIdentity
import com.engabd.sendpin.protocol.noise.SendspinPsk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sendspin player client, per the published specification.
 *
 * The session it runs, once the WebSocket is open:
 *
 * ```
 *   client/init ──▶ server/init ──▶ noise/handshake (1) ──▶ noise/handshake (2)
 *   ── everything from here is a Noise-encrypted binary frame ──
 *   server/hello ──▶ client/hello ──▶ server/activate
 *   client/time ⇄ server/time (until the clock filter converges)
 *   client/state { available: true, player: {…} }
 *   stream/start ──▶ audio chunks ──▶ stream/clear / stream/end …
 * ```
 *
 * The server is the Noise initiator and picks the PSK; `client/init` names this phone
 * by its X25519 public key, and the PSK the server's first handshake message names is
 * looked up in the [PairingStore]: the published Sentinel for an unpaired ("guest")
 * session, the phone's own Pairing PSK while an operator is pairing it, or a
 * long-term record from an earlier pairing. `server/activate` then says what the
 * connection is for; [ActivationPolicy] checks that against what the matched PSK
 * permits. A server may re-run the handshake in-band at any time (to switch trust
 * after pairing), which this client handles on the socket thread so the transport keys
 * swap in wire order.
 *
 * Music Assistant servers before 2.10 (schema < 45) predate all of that and speak the
 * cleartext [Encryption.LEGACY] dialect: `client/hello` first, carrying the id and
 * version, answered by a `server/hello` that already lists the active roles. The
 * caller picks the dialect from the server's schema version, never by probing.
 *
 * Thread model: OkHttp's reader thread runs the handshake and decrypts frames (the
 * transport counters need wire order), then hands everything to one ordered inbox that
 * the ingest coroutine drains; all sends go through one lock so ciphertext order matches
 * counter order.
 */
class SendspinClient(
    private val store: PairingStore,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true   // `type` fields carry defaults - must be emitted
        explicitNulls = false   // omit null device_info / codec_header / etc.
    },
) {
    enum class State { DISCONNECTED, CONNECTING, AUTHENTICATING, CONNECTED, ERROR }

    /** Which dialect the server speaks — see the class docs. */
    enum class Encryption { ENCRYPTED, LEGACY }

    /**
     * Somewhere to reach the Sendspin server. [authToken] is only for Music Assistant's
     * `:8095/sendspin` proxy, which wants an `auth` message before it forwards anything;
     * the native `:8927/sendspin` endpoint takes no such preamble.
     */
    data class Endpoint(val url: String, val authToken: String? = null)

    /** What the current session was admitted with, for the settings screen. */
    data class Security(
        val serverId: String,
        val serverName: String?,
        val category: PskCategory,
        val encrypted: Boolean,
    )

    /** Clock shared with the audio scheduler. */
    val clock = ClockSync()

    // No read timeout (a quiet stream is not a broken one) and a 5s keepalive, tighter
    // than the control socket's: this one carries audio, so a dead peer has to be
    // noticed within a track rather than within a minute.
    private val httpClient = Http.socket(pingSeconds = 5)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var webSocket: WebSocket? = null
    private var timeJob: Job? = null
    private var gateJob: Job? = null
    private var initialStateJob: Job? = null
    private var reconnectJob: Job? = null

    @Volatile private var userClosed = false
    private var endpoints: List<Endpoint> = emptyList()
    @Volatile private var endpointIndex = 0
    private var attempt = 0
    private var handshakeFailures = 0

    /**
     * Whether this client is in **idle mode** — no audio is flowing, so the timer
     * loops relax to [IDLE_TIME_SYNC_MS] to reduce background CPU and network
     * traffic. The clock filter and sync gate still run; they just sample less often.
     * A `stream/start` flips this back to fast cadence via [setIdleMode].
     */
    @Volatile private var idleMode = false

    /** When a socket was last opened, for [reconnectNow]'s rate limit. */
    @Volatile private var lastDialAtMs = 0L

    private var encryption = Encryption.ENCRYPTED
    private var clientName = ""
    private var deviceInfo: DeviceInfo? = null
    private var supportedFormats: List<AudioFormatSpec> = emptyList()

    @Volatile private var lastVolume = 100
    @Volatile private var lastMuted = false

    /**
     * The startup lead and steady-state buffer this player asks the server for, in
     * `client/state`. Set by the engine's owner from what the engine really needs;
     * the defaults are the reference client's.
     */
    @Volatile var requiredLeadTimeMs: Int = DEFAULT_REQUIRED_LEAD_TIME_MS
        set(value) { if (field != value) { field = value; resendPlayerState() } }
    @Volatile var minBufferMs: Int = DEFAULT_MIN_BUFFER_MS
        set(value) { if (field != value) { field = value; resendPlayerState() } }

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

    /** Whether a player stream is open on this socket (`stream/start` seen, no `stream/end` yet). */
    @Volatile private var streaming = false

    /** Roles the server activated — `server/activate`, or the legacy `server/hello`. */
    private val _activeRoles = MutableStateFlow<List<String>>(emptyList())
    val activeRoles: StateFlow<List<String>> = _activeRoles.asStateFlow()

    private val _security = MutableStateFlow<Security?>(null)
    val security: StateFlow<Security?> = _security.asStateFlow()

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

        /**
         * A `stream/start` for a stream that is already open: the spec says this
         * "updates the stream configuration without clearing buffers", so nothing
         * queued may be dropped.
         */
        fun onStreamReconfigure(format: StreamStartPlayerInfo)
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
     * Callback to persist the clock offset (server-minus-wall, microseconds) so
     * it survives a reconnect. Fired every ~100 `server/time` samples once the
     * filter's error estimate is under 2 ms, so a player that was in sync before the
     * socket dropped can resume on the same timeline without waiting for cold-start
     * convergence.
     */
    @Volatile
    var onClockOffsetPersist: ((serverMinusWallUs: Long) -> Unit)? = null

    /**
     * Seed the clock filter from a persisted offset on startup, before any
     * `client/time` ↔ `server/time` round-trips have arrived. Called after the
     * client is constructed but before [connect].
     *
     * The persisted value is server-minus-wall (reboot-safe). We reconstruct
     * the BOOTTIME-domain offset the filter needs from the current
     * wall-to-boottime delta.
     */
    fun seedClockOffset(serverMinusWallUs: Long) {
        if (serverMinusWallUs == 0L) return
        val bootUs = MonotonicClock.nowUs()
        val wallUs = System.currentTimeMillis() * 1000L
        clock.filter.seedOffset(serverMinusWallUs - bootUs + wallUs)
    }

    // --- Session state (one socket) --------------------------------------------

    /** Where the bring-up has got to on the current socket. */
    private enum class Phase {
        /** Waiting for the `:8095` proxy's `auth_ok`. */
        AUTH,
        /** `client/init` sent; waiting for `server/init`. */
        INIT,
        /** Waiting for Noise message 1. */
        NOISE_MSG1,
        /** Encrypted (or legacy-hello sent); waiting for `server/hello`. */
        HELLO,
        /** `client/hello` sent; waiting for `server/activate`. */
        ACTIVATE,
        /** Steady state. */
        READY,
    }

    @Volatile private var phase = Phase.HELLO

    // Noise session state — written on the socket thread only.
    private val suite = NoiseCipherSuite.AESGCM
    private var clientInitText = ""
    private var serverId: String? = null
    private var serverStaticPublic: ByteArray? = null
    private var handshake: NoiseHandshake? = null
    private var handshakeHash: ByteArray? = null
    @Volatile private var transport: NoiseTransport? = null
    @Volatile private var matched: ResolvedPsk? = null
    private val reassembler = NoiseFraming.Reassembler()

    /** Serialises every send: ciphertext order must equal nonce order. */
    private val sendLock = Any()

    // Activation state — written on the ingest coroutine.
    @Volatile private var activities: Set<String> = emptySet()
    private var serverName: String? = null
    @Volatile private var initialStateSent = false
    private var pendingPairing: PendingPairing? = null
    private val management = ManagementHandler(store)

    private class PendingPairing(val psk: ByteArray, val serverId: String, val timeout: Job)

    /** True while `'pairing'` is declared: playback traffic is held (spec §Entering and leaving pairing). */
    private val pairing: Boolean get() = ActivationPolicy.PAIRING in activities
    private val playerActive: Boolean get() = _activeRoles.value.any { it.startsWith("player@") }

    /** Steady-state messages (time, state, format requests) may flow. */
    private val steady: Boolean get() = phase == Phase.READY && !pairing

    // --- Inbox ---------------------------------------------------------------

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
                    is Inbound.Audio -> if (streaming) sink?.onAudio(item.frame)
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
                        try {
                            handleIncoming(
                                if (parsed is SendspinIncoming.ServerTime) {
                                    parsed.copy(clientReceivedUs = item.rxUs)
                                } else parsed,
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "handling ${item.text.take(60)} failed", e)
                        }
                    }
                }
            }
        }
    }

    // --- Connection lifecycle ----------------------------------------------------

    /**
     * Open the session. [endpoints] are tried in order when a dial fails outright
     * (the native `:8927` port first, the authenticated `:8095` proxy as the fallback
     * for networks where only the web port is reachable).
     */
    fun connect(
        endpoints: List<Endpoint>,
        encryption: Encryption,
        clientName: String,
        deviceInfo: DeviceInfo,
        supportedFormats: List<AudioFormatSpec>,
    ) {
        require(endpoints.isNotEmpty())
        this.endpoints = endpoints.map { it.copy(url = normaliseUrl(it.url)) }
        this.encryption = encryption
        this.clientName = clientName
        this.deviceInfo = deviceInfo
        this.supportedFormats = supportedFormats

        _state.value = State.CONNECTING
        _statusText.value = "Connecting…"
        connectStartMs = System.currentTimeMillis()
        _events.value = emptyList()
        userClosed = false
        attempt = 0
        handshakeFailures = 0
        endpointIndex = 0
        reconnectJob?.cancel()
        startIngest()
        dbg("connect → ${this.endpoints.joinToString { it.url }} as ${store.identity.peerId} ($encryption)")
        openSocket()
    }

    private fun openSocket() {
        lastDialAtMs = android.os.SystemClock.elapsedRealtime()
        val ep = endpoints[endpointIndex.coerceIn(0, endpoints.size - 1)]
        webSocket = httpClient.newWebSocket(Request.Builder().url(ep.url).build(), listener)
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
     * Reconnect now instead of at the end of the backoff — the user has asked for
     * music, and this socket is the only way Music Assistant can reach this phone
     * with audio. Does nothing while a socket is up or on its way, and no more often
     * than [RECONNECT_MIN_GAP_MS]. Also the way out of a stopped retry loop.
     */
    fun reconnectNow() {
        if (userClosed || endpoints.isEmpty()) return
        when (_state.value) {
            State.CONNECTED, State.CONNECTING, State.AUTHENTICATING -> return
            else -> {}
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastDialAtMs < RECONNECT_MIN_GAP_MS) return
        lastDialAtMs = now
        reconnectJob?.cancel()
        attempt = 0
        handshakeFailures = 0
        _state.value = State.CONNECTING
        _statusText.value = "Reconnecting…"
        dbg("reconnecting now (user asked for playback)")
        openSocket()
    }

    /**
     * Close the socket, telling the server *why* in `client/goodbye`:
     * `"user_request"` drops the player from the speaker list immediately,
     * `"restart"` asks the server to hold the slot open for a resume.
     */
    fun disconnect(reason: String = "user_request") {
        userClosed = true
        reconnectJob?.cancel(); reconnectJob = null
        stopSessionJobs()
        val ws = webSocket
        webSocket = null
        try { sendGoodbye(reason) } catch (_: Throwable) {}
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

    private fun stopSessionJobs() {
        timeJob?.cancel(); timeJob = null
        gateJob?.cancel(); gateJob = null
        initialStateJob?.cancel(); initialStateJob = null
        pendingPairing?.timeout?.cancel(); pendingPairing = null
    }

    /** Reset per-socket state before a (re)dial. */
    private fun resetSession() {
        streaming = false
        transport = null
        matched = null
        handshake = null
        handshakeHash = null
        serverId = null
        serverStaticPublic = null
        serverName = null
        activities = emptySet()
        _activeRoles.value = emptyList()
        _security.value = null
        initialStateSent = false
        pendingPairing?.timeout?.cancel(); pendingPairing = null
    }

    // --- Sending -------------------------------------------------------------------

    /** Send one JSON message: a text frame on a legacy socket, encrypted frames otherwise. */
    private fun sendJson(text: String): Boolean = synchronized(sendLock) { sendJsonLocked(text) }

    private fun sendJsonLocked(text: String): Boolean {
        val ws = webSocket ?: return false
        if (encryption == Encryption.LEGACY) return ws.send(text)
        val t = transport ?: run {
            Log.w(TAG, "dropping ${text.take(40)}: transport not up")
            return false
        }
        var ok = true
        for (frame in NoiseFraming.fragment(NoiseFraming.json(text))) {
            ok = ws.send(t.encrypt(frame).toByteString()) && ok
        }
        return ok
    }

    /** Sends only in steady state; a message queued mid-handshake or mid-pairing is dropped. */
    private fun sendSteady(text: String): Boolean = if (steady) sendJson(text) else false

    private fun sendGoodbye(reason: String) {
        val text = json.encodeToString(SendspinGoodbye(payload = GoodbyePayload(reason)))
        synchronized(sendLock) {
            if (encryption == Encryption.LEGACY || transport != null) sendJsonLocked(text)
        }
    }

    /** `client/goodbye` with [reason], then close — the spec's rejection path. No reconnect. */
    private fun rejectWithGoodbye(reason: String, status: String) {
        dbg("rejecting session: $reason")
        userClosed = true
        stopSessionJobs()
        val ws = webSocket
        webSocket = null
        sendGoodbye(reason)
        ws?.close(1000, reason)
        _state.value = State.ERROR
        _statusText.value = status
    }

    // --- Player state --------------------------------------------------------------

    /**
     * Report player state — volume, mute, latency trim, timing needs. The full player
     * object goes out every time (the spec lets a client resend unchanged fields),
     * once the initial report has been made; before that the values are only latched,
     * and the initial report carries them.
     */
    fun sendClientState(
        volume: Int = lastVolume,
        muted: Boolean = lastMuted,
        staticDelayMs: Int = _staticDelayMs.value,
    ) {
        lastVolume = volume; lastMuted = muted
        _staticDelayMs.value = staticDelayMs
        resendPlayerState()
    }

    private fun playerState() = PlayerStateInfo(
        volume = lastVolume,
        muted = lastMuted,
        // The spec's wire range is 0..5000; a negative trim is meaningful locally
        // (play later) but has nothing to say to the server. The signed value is kept
        // in [_staticDelayMs], which is what the engine reads — see the echo guard in
        // `handleIncoming`.
        staticDelayMs = _staticDelayMs.value.coerceIn(0, MAX_STATIC_DELAY_MS),
        requiredLeadTimeMs = requiredLeadTimeMs,
        minBufferMs = minBufferMs,
        supportedCommands = listOf("set_static_delay"),
    )

    private fun resendPlayerState() {
        if (!initialStateSent || !playerActive) return
        sendSteady(json.encodeToString(SendspinClientState(payload = ClientStatePayload(available = true, player = playerState()))))
    }

    /**
     * The initial `client/state`, which unlocks the server's streams. It says
     * `available: true`, and the spec forbids that until the clock filter can place
     * samples — so it waits for readiness, at most [INITIAL_STATE_DEADLINE_MS] (the
     * server gives up after five seconds). Past the deadline a filter with *any*
     * offset is good enough to report: the local [SyncGate] still mutes until the
     * estimate tightens, and staying silent would mean no music at all.
     */
    private fun scheduleInitialState() {
        initialStateJob?.cancel()
        initialStateSent = false
        initialStateJob = scope.launch {
            val started = System.currentTimeMillis()
            resyncClock()
            while (isActive) {
                val ready = clock.isReadyForPlaybackStart()
                val overdue = System.currentTimeMillis() - started >= INITIAL_STATE_DEADLINE_MS
                if (ready || (overdue && clock.isSynced())) {
                    if (!ready) Log.w(TAG, "initial state past deadline with err=${clock.errorUs()}us — reporting available anyway")
                    if (!steady || !playerActive) return@launch
                    initialStateSent = true
                    dbg("clock ready (err=${clock.errorUs()}us, n=${clock.sampleCount()}) → client/state available")
                    sendSteady(json.encodeToString(SendspinClientState(payload = ClientStatePayload(available = true, player = playerState()))))
                    return@launch
                }
                delay(100)
            }
        }
    }

    /** Set the latency trim locally (from settings). Pushed to the server too. */
    fun setStaticDelayMs(ms: Int) {
        if (_staticDelayMs.value == ms) return
        sendClientState(staticDelayMs = ms)
    }

    fun sendRequestFormat(codec: String, sampleRate: Int = 48000, bitDepth: Int = 16, channels: Int = 2) {
        val msg = SendspinRequestFormat(
            payload = RequestFormatPayload(RequestFormatPlayerPayload(codec, sampleRate, bitDepth, channels)),
        )
        sendSteady(json.encodeToString(msg))
    }

    // --- Socket callbacks ----------------------------------------------------------

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            // Bind the field to the just-opened socket: onOpen can race ahead of the
            // `webSocket = newWebSocket(...)` assignment in connect(), which would make
            // the field-based send() a silent no-op. Send the first frame via the param.
            this@SendspinClient.webSocket = webSocket
            resetSession()
            val ep = endpoints[endpointIndex.coerceIn(0, endpoints.size - 1)]
            dbg("ws OPEN (http ${response.code}) ${ep.url}")
            if (!ep.authToken.isNullOrBlank()) {
                phase = Phase.AUTH
                _state.value = State.AUTHENTICATING
                _statusText.value = "Authenticating…"
                webSocket.send(json.encodeToString(SendspinAuthMessage(token = ep.authToken, clientId = store.identity.peerId)))
                dbg("sent auth (proxy endpoint)")
            } else {
                beginProtocol(webSocket)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // Stamp T4 here and nowhere later: this is the earliest point the frame
            // exists locally, and measuring it after a parse or a coroutine hop
            // biases the clock offset low, which makes the player play late.
            val rxUs = MonotonicClock.nowUs()
            when (phase) {
                Phase.AUTH -> onAuthReply(webSocket, text)
                Phase.INIT -> handshakeStep { onServerInit(webSocket, text) }
                Phase.NOISE_MSG1 -> handshakeStep { onNoiseMessage1(webSocket, text) }
                else -> if (encryption == Encryption.LEGACY) {
                    offer(Inbound.Text(text, rxUs))
                } else {
                    protocolError(webSocket, "text frame after the Noise handshake")
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val rxUs = MonotonicClock.nowUs()
            if (encryption == Encryption.LEGACY) {
                offer(Inbound.Audio(bytes.toByteArray()))
                return
            }
            val t = transport ?: run { protocolError(webSocket, "binary frame before the Noise handshake"); return }
            val plaintext = try {
                reassembler.accept(t.decrypt(bytes.toByteArray())) ?: return
            } catch (e: Exception) {
                protocolError(webSocket, "frame failed: ${e.message}")
                return
            }
            when (plaintext[0].toInt()) {
                NoiseFraming.TYPE_JSON -> {
                    val text = String(plaintext, 1, plaintext.size - 1, Charsets.UTF_8)
                    if (isRehandshake(text)) handshakeStep { onRehandshake(text) } else offer(Inbound.Text(text, rxUs))
                }
                NoiseFraming.TYPE_AUDIO -> offer(Inbound.Audio(plaintext))
                else -> Log.d(TAG, "ignoring binary type ${plaintext[0]}")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            dbg("ws CLOSING $code '$reason'")
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            dbg("ws CLOSED $code '$reason'")
            stopSessionJobs()
            // Queued rather than applied here, so the engine is torn down *after* any
            // frames still ahead of it in the inbox rather than in the middle of them.
            offer(Inbound.Disconnected)
            if (_state.value != State.ERROR) {
                _state.value = State.DISCONNECTED
                _statusText.value = "Disconnected"
            }
            // 4001 = the proxy's "First message must be auth" → a token problem, retrying won't help.
            if (code != 4001) scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            dbg("ws FAILURE (http ${response?.code}): ${t.message}")
            stopSessionJobs()
            offer(Inbound.Disconnected)
            _state.value = State.ERROR
            _statusText.value = "Error: ${t.message}"
            // A dial that never got a socket up tries the next endpoint straight away;
            // only once every endpoint has failed does the backoff start.
            if (transport == null && phase != Phase.HELLO && endpointIndex < endpoints.size - 1) {
                endpointIndex++
                dbg("trying fallback endpoint ${endpoints[endpointIndex].url}")
                openSocket()
                return
            }
            if (response?.code != 404) scheduleReconnect()
        }
    }

    private fun onAuthReply(webSocket: WebSocket, text: String) {
        val type = try { json.parseToJsonElement(text).jsonObject["type"]?.jsonPrimitive?.contentOrNull } catch (_: Exception) { null }
        when (type) {
            "auth_ok" -> beginProtocol(webSocket)
            "auth_error" -> {
                _state.value = State.ERROR
                _statusText.value = "Auth failed"
                dbg("auth_error: ${text.take(160)}")
            }
            else -> dbg("unexpected during auth: ${text.take(120)}")
        }
    }

    /** The first Sendspin message on a socket: `client/init`, or the legacy `client/hello`. */
    private fun beginProtocol(webSocket: WebSocket) {
        _state.value = State.AUTHENTICATING
        _statusText.value = "Handshaking…"
        if (encryption == Encryption.LEGACY) {
            phase = Phase.HELLO
            sendLegacyHello(webSocket)
            return
        }
        phase = Phase.INIT
        clientInitText = json.encodeToString(
            SendspinClientInit(payload = ClientInitPayload(clientId = store.identity.peerId, suite = suite.wireName)),
        )
        val queued = webSocket.send(clientInitText)
        dbg("sent client/init (queued=$queued)")
    }

    /** Runs one handshake step on the socket thread; any failure closes the socket silently, per the spec. */
    private inline fun handshakeStep(step: () -> Unit) {
        try {
            step()
        } catch (e: Exception) {
            val ws = webSocket
            handshakeFailures++
            dbg("handshake failed: ${e.message}")
            // A PSK the server names and we do not hold will not appear by retrying.
            val terminal = e is NoiseHandshakeException && e.message?.startsWith("no PSK") == true && handshakeFailures >= 3
            _statusText.value = if (terminal) "Server expects a pairing this phone no longer has — unpair it in Music Assistant" else "Handshake failed"
            if (terminal) userClosed = true
            _state.value = State.ERROR
            webSocket = null
            ws?.close(1002, null)
        }
    }

    private fun onServerInit(webSocket: WebSocket, text: String) {
        val obj = json.parseToJsonElement(text).jsonObject
        if (obj["type"]?.jsonPrimitive?.contentOrNull != "server/init") {
            throw NoiseHandshakeException("expected server/init, got ${obj["type"]}")
        }
        val init = json.decodeFromJsonElement(ServerInitPayload.serializer(), obj["payload"]!!)
        if (init.version != 1) throw NoiseHandshakeException("unsupported protocol version ${init.version}")
        val pub = SendspinIdentity.decodePeerId(init.serverId) ?: throw NoiseHandshakeException("invalid server_id")
        serverId = init.serverId
        serverStaticPublic = pub
        // The prologue is the exact bytes of both init messages as they crossed the wire.
        val prologue = clientInitText.toByteArray(Charsets.UTF_8) + text.toByteArray(Charsets.UTF_8)
        handshake = NoiseHandshake(suite, store.identity.privateKey, pub, prologue)
        phase = Phase.NOISE_MSG1
        dbg("server/init ${init.serverId.take(8)}…")
    }

    private fun onNoiseMessage1(webSocket: WebSocket, text: String) {
        val hs = handshake ?: throw NoiseHandshakeException("no handshake in progress")
        val (msg2, result, resolved) = completeHandshake(hs, text)
        val sent = webSocket.send(json.encodeToString(SendspinNoiseHandshake(payload = NoiseHandshakePayload(B64Url.encode(msg2)))))
        installTransport(result, resolved)
        handshake = null
        phase = Phase.HELLO
        dbg("noise handshake complete (psk=${resolved.category}, msg2 queued=$sent)")
    }

    /** Message 1 in, message 2 out: shared by the first handshake and every re-handshake. */
    private fun completeHandshake(hs: NoiseHandshake, text: String): Triple<ByteArray, NoiseHandshake.Result, ResolvedPsk> {
        val obj = json.parseToJsonElement(text).jsonObject
        if (obj["type"]?.jsonPrimitive?.contentOrNull != "noise/handshake") {
            throw NoiseHandshakeException("expected noise/handshake, got ${obj["type"]}")
        }
        val data = (obj["payload"] as? JsonObject)?.get("data")?.jsonPrimitive?.contentOrNull
            ?: throw NoiseHandshakeException("noise/handshake without data")
        val msg1 = try { B64Url.decode(data) } catch (e: IllegalArgumentException) { throw NoiseHandshakeException("malformed handshake encoding", e) }
        val payload = hs.readMessage1(msg1)
        val pskId = try {
            json.decodeFromString(NoiseMsg1Payload.serializer(), String(payload, Charsets.UTF_8)).pskId
        } catch (e: Exception) {
            throw NoiseHandshakeException("malformed Noise message 1 payload", e)
        }
        val resolved = store.resolve(pskId) ?: throw NoiseHandshakeException("no PSK matches psk_id=${pskId.take(8)}…")
        // Stored-pubkey record: the server it is bound to must be the one we reached.
        if (resolved.serverId != null && resolved.serverId != serverId) {
            throw NoiseHandshakeException("PSK bound to another server")
        }
        val (msg2, result) = hs.writeMessage2(resolved.psk, "{}".toByteArray(Charsets.UTF_8))
        return Triple(msg2, result, resolved)
    }

    private fun installTransport(result: NoiseHandshake.Result, resolved: ResolvedPsk) {
        transport = result.transport
        handshakeHash = result.handshakeHash
        matched = resolved
        handshakeFailures = 0
        if (resolved.category == PskCategory.LONG_TERM) store.markUsed(resolved.pskId)
    }

    private fun isRehandshake(text: String): Boolean =
        text.length < 4096 && text.contains("noise/handshake") &&
            runCatching { json.parseToJsonElement(text).jsonObject["type"]?.jsonPrimitive?.contentOrNull == "noise/handshake" }.getOrDefault(false)

    /**
     * A server-initiated re-handshake inside the encrypted channel (spec §Re-handshake):
     * same pattern, prologue = the previous handshake hash, message 2 still under the
     * old keys, the next frame each way under the new ones. Held under the send lock so
     * nothing of ours slips out under the old keys after message 2.
     */
    private fun onRehandshake(text: String) {
        val ws = webSocket ?: return
        val pub = serverStaticPublic ?: throw NoiseHandshakeException("re-handshake without a server key")
        val prologue = handshakeHash ?: throw NoiseHandshakeException("re-handshake without a prior hash")
        synchronized(sendLock) {
            val hs = NoiseHandshake(suite, store.identity.privateKey, pub, prologue)
            val (msg2, result, resolved) = completeHandshake(hs, text)
            val reply = json.encodeToString(SendspinNoiseHandshake(payload = NoiseHandshakePayload(B64Url.encode(msg2))))
            val old = transport ?: throw NoiseHandshakeException("re-handshake before transport")
            for (frame in NoiseFraming.fragment(NoiseFraming.json(reply))) ws.send(old.encrypt(frame).toByteString())
            installTransport(result, resolved)
            phase = Phase.HELLO
            dbg("re-handshake complete (psk=${resolved.category})")
        }
    }

    private fun protocolError(webSocket: WebSocket, what: String) {
        dbg("protocol error: $what")
        this.webSocket = null
        webSocket.close(1002, null)
    }

    // --- Hello -------------------------------------------------------------------------

    /** The pre-spec hello: id and version inside, none of the trust and pairing fields. */
    private fun sendLegacyHello(ws: WebSocket) {
        val hello = SendspinClientHello(
            payload = ClientHelloPayload(
                name = clientName,
                deviceInfo = deviceInfo,
                playerV1Support = PlayerV1Support(supportedFormats = supportedFormats),
                clientId = store.identity.peerId,
                version = 1,
            ),
        )
        val queued = ws.send(json.encodeToString(hello))
        dbg("sent legacy client/hello (queued=$queued)")
    }

    private fun sendEncryptedHello() {
        val trust = if (matched?.category == PskCategory.LONG_TERM) "user" else "none"
        val hello = SendspinClientHello(
            payload = ClientHelloPayload(
                name = clientName,
                deviceInfo = deviceInfo,
                playerV1Support = PlayerV1Support(supportedFormats = supportedFormats),
                trustLevel = trust,
                supportedPairMethods = if (store.pairingPskEnabled) {
                    listOf(PairMethodDescriptor(method = ActivationPolicy.PAIR_METHOD_PSK, locations = listOf("device")))
                } else emptyList(),
                unpairedAccess = UnpairedAccess(store.unpairedAccessEnabled),
            ),
        )
        val queued = sendJson(json.encodeToString(hello))
        phase = Phase.ACTIVATE
        dbg("sent client/hello (trust=$trust, queued=$queued)")
    }

    // --- Incoming ----------------------------------------------------------------------

    private fun handleIncoming(msg: SendspinIncoming) {
        when (msg) {
            is SendspinIncoming.AuthOk, is SendspinIncoming.AuthError -> {} // handled on the socket thread
            is SendspinIncoming.ServerHello -> onServerHello(msg.raw)
            is SendspinIncoming.ServerActivate -> onServerActivate(msg.payload)
            is SendspinIncoming.ServerTime -> onServerTime(msg)
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
                val format = msg.payload.player ?: return
                _streamFormat.value = format
                if (streaming) {
                    // In-place configuration update: nothing buffered may be dropped.
                    dbg("stream/start on the open stream → reconfigure")
                    sink?.onStreamReconfigure(format)
                } else {
                    // The head of this stream is about to be scheduled against the clock,
                    // so take a fresh reading of it now rather than at the next tick.
                    resyncClock()
                    streaming = true
                    sink?.onStreamStart(format)
                }
            }
            is SendspinIncoming.StreamEnd -> if (msg.roles == null || "player" in msg.roles) {
                streaming = false
                sink?.onStreamEnd()
            }
            is SendspinIncoming.StreamClear -> if (msg.roles == null || "player" in msg.roles) sink?.onStreamClear()
            is SendspinIncoming.ServerCommand -> msg.payload.player?.let { onPlayerCommand(it) }
            is SendspinIncoming.ServerPairFinalize -> onPairFinalize()
            is SendspinIncoming.PairAbort -> {
                dbg("pair/abort ${msg.reason}")
                pendingPairing?.timeout?.cancel(); pendingPairing = null
            }
            is SendspinIncoming.ServerUnpair -> onServerUnpair()
            is SendspinIncoming.Management -> {
                val session = matched
                val outcome = management.handle(
                    request = msg.request,
                    payload = msg.payload,
                    managementActive = ActivationPolicy.MANAGEMENT in activities && session?.category == PskCategory.LONG_TERM,
                    sessionPskId = session?.pskId ?: "",
                )
                sendJson(json.encodeToString(SendspinManagementResult.serializer(), outcome.result))
                if (outcome.closeUnauthorizedAfter) rejectWithGoodbye("unauthorized", "Pairing removed by the server")
            }
            is SendspinIncoming.Unknown -> {}
        }
    }

    private fun onServerHello(raw: JsonObject) {
        val p = raw["payload"] as? JsonObject
        serverName = (p?.get("name") as? JsonPrimitive)?.contentOrNull
        if (encryption == Encryption.ENCRYPTED) {
            if (phase != Phase.HELLO) {
                dbg("unexpected server/hello in phase $phase")
                return
            }
            sendEncryptedHello()
            return
        }
        // Legacy: the hello doubles as the activation and carries the roles.
        val roles = (p?.get("active_roles") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        val legacyServerId = (p?.get("server_id") as? JsonPrimitive)?.contentOrNull ?: "?"
        serverId = legacyServerId
        _security.value = Security(legacyServerId, serverName, PskCategory.SENTINEL, encrypted = false)
        dbg("legacy server/hello → CONNECTED ✓ (${serverName ?: "?"}, roles=$roles)")
        applyRoles(roles)
        becomeReady()
        // The legacy server wants the initial state just the same, within its 5 s.
        if (playerActive) scheduleInitialState()
    }

    private fun onServerActivate(payload: ServerActivatePayload) {
        val session = matched ?: run { dbg("server/activate without a session"); return }
        // Any activation ends an in-flight pairing attempt, including one it rejects.
        pendingPairing?.timeout?.cancel(); pendingPairing = null

        when (val d = ActivationPolicy.decide(
            category = session.category,
            activities = payload.activities,
            explicitRoles = payload.activeRoles,
            persistedRoles = _activeRoles.value,
            unpairedAccess = store.unpairedAccessEnabled,
            pairingMethod = payload.pairing?.method,
            pairingPskOffered = store.pairingPskEnabled,
        )) {
            is ActivationPolicy.Decision.Reject -> {
                rejectWithGoodbye(
                    d.reason,
                    if (d.reason == "pairing_required") "Server requires pairing — enable unpaired access or pair this phone"
                    else "Server activation not authorised",
                )
                return
            }
            is ActivationPolicy.Decision.AbortPairing -> {
                dbg("pairing activation refused: method=${payload.pairing?.method} psk=${session.category}")
                sendJson(json.encodeToString(SendspinPairAbort(payload = PairAbortPayload("method_not_supported"))))
                return
            }
            is ActivationPolicy.Decision.Accept -> {
                val wasPlayer = playerActive
                activities = d.activities
                applyRoles(d.activeRoles)
                _security.value = Security(serverId ?: "?", serverName, session.category, encrypted = true)
                // Also true after an in-band re-handshake, whose hello exchange runs
                // the phase back through HELLO/ACTIVATE: the session is re-established.
                val first = phase != Phase.READY
                val wasConnected = _state.value == State.CONNECTED
                dbg(
                    "server/activate activities=${d.activities} roles=${d.activeRoles}" +
                        when { first && !wasConnected -> " → CONNECTED ✓"; first -> " (session re-established)"; else -> "" },
                )
                if (first) becomeReady()
                if (pairing) {
                    startPairingAttempt(session)
                    return
                }
                // A (re)activated player role gets its full state; after a re-handshake the
                // server has a fresh session and needs it again even if the role persisted.
                if (playerActive && (!wasPlayer || first)) scheduleInitialState()
            }
        }
    }

    private fun applyRoles(roles: List<String>) {
        val hadPlayer = playerActive
        _activeRoles.value = roles
        if (hadPlayer && !playerActive && streaming) {
            // The server ends the role's stream first; belt and braces.
            streaming = false
            sink?.onStreamEnd()
        }
        if (roles.isNotEmpty() && roles.none { it.startsWith("player@") }) {
            Log.w(TAG, "server did not activate a player role; no audio will arrive")
        }
    }

    /** First activation (or legacy hello): the session is up. */
    private fun becomeReady() {
        phase = Phase.READY
        attempt = 0
        _state.value = State.CONNECTED
        _statusText.value = "Connected"
        startTimeSync()
        startSyncGate()
    }

    private fun onServerTime(msg: SendspinIncoming.ServerTime) {
        val p = msg.payload
        val rttUs = (msg.clientReceivedUs - p.clientTransmitted) - (p.serverTransmitted - p.serverReceived)
        // Reject an absurdly slow round-trip, but only during cold-start convergence:
        // past a few good samples the filter's own variance handles ordinary jitter.
        if (rttUs > STARTUP_RTT_REJECT_US && clock.filter.sampleCount < STARTUP_REJECT_SAMPLE_CEILING) {
            clock.markStartupRejected()
            return
        }
        clock.onServerTime(p.clientTransmitted, p.serverReceived, p.serverTransmitted, msg.clientReceivedUs)
        // Persist the clock offset every ~100 samples when error < 2 ms, so a
        // reconnect can seed the filter and skip cold-start convergence. Stored as
        // server-minus-wall (reboot-safe).
        val sc = clock.filter.sampleCount
        if (sc > 0 && sc % 100 == 0 && clock.errorUs() < 2_000L) {
            val bootUs = MonotonicClock.nowUs()
            val wallUs = System.currentTimeMillis() * 1000L
            onClockOffsetPersist?.invoke(clock.currentOffsetUs() + bootUs - wallUs)
        }
    }

    private fun onPlayerCommand(p: PlayerCommandPayload) {
        // Latched here rather than only downstream, so the value the client
        // reports back stays right whoever set it.
        if (p.command == "set_static_delay") {
            p.staticDelayMs?.let { ms ->
                val clamped = ms.coerceIn(0, MAX_STATIC_DELAY_MS)
                // An echo of our own clamp is not an instruction: the wire field is
                // unsigned, so a local -300 goes out as 0, MA stores that and echoes
                // `set_static_delay: 0` back. Taking the echo at face value erased the
                // signed local value within one round trip of it being set.
                val ours = _staticDelayMs.value
                if (ours < 0 && clamped == 0) {
                    dbg("server/command set_static_delay=0 ignored (echo of local ${ours}ms)")
                } else {
                    dbg("server/command set_static_delay=${clamped}ms")
                    sendClientState(staticDelayMs = clamped)
                    _serverCommands.tryEmit(p)
                }
                return
            }
        }
        _serverCommands.tryEmit(p)
    }

    // --- Pairing (Pairing PSK flow) ----------------------------------------------------

    /**
     * The server has moved this connection into pairing with our Pairing PSK
     * (spec §Pairing PSK Flow): mint a long-term PSK, hand it over in
     * `client/pair-finalize`, and persist it only on `server/pair-finalize`. The
     * server then re-handshakes onto it.
     */
    private fun startPairingAttempt(session: ResolvedPsk) {
        val sid = serverId ?: return
        if (session.category != PskCategory.PAIRING) {
            // The policy already refused a mismatched method; this is the receiving-side
            // check the spec asks for before finalize.
            sendJson(json.encodeToString(SendspinPairAbort(payload = PairAbortPayload("method_not_supported"))))
            return
        }
        val psk = store.newLongTermPsk()
        val timeout = scope.launch {
            delay(PAIRING_ATTEMPT_TIMEOUT_MS)
            if (pendingPairing?.psk === psk) {
                dbg("pairing attempt timed out")
                pendingPairing = null
                sendJson(json.encodeToString(SendspinPairAbort(payload = PairAbortPayload("attempt_timeout"))))
            }
        }
        pendingPairing = PendingPairing(psk, sid, timeout)
        sendJson(json.encodeToString(SendspinClientPairFinalize(payload = ClientPairFinalizePayload(B64Url.encode(psk)))))
        dbg("pairing: sent client/pair-finalize")
    }

    private fun onPairFinalize() {
        val pending = pendingPairing ?: run { dbg("server/pair-finalize with no attempt in progress"); return }
        pending.timeout.cancel()
        pendingPairing = null
        store.persistPairing(pending.serverId, pending.psk)
        dbg("paired with ${pending.serverId.take(8)}… — record persisted")
    }

    /** `server/unpair`: drop the record it was admitted with, say goodbye, come back unpaired. */
    private fun onServerUnpair() {
        val session = matched ?: return
        if (session.category != PskCategory.LONG_TERM) {
            dbg("server/unpair on an unpaired session ignored")
            return
        }
        val record = store.record(session.pskId)
        if (record?.serverId != null) store.removeRecord(session.pskId)
        else dbg("server/unpair on a shared record: kept")
        dbg("unpaired by the server")
        stopSessionJobs()
        val ws = webSocket
        webSocket = null
        sendGoodbye("unpaired")
        // Not a user close, so onClosed reconnects: Music Assistant expects the device
        // to "reconnect as unpaired" after an unpair, and the next handshake is offered
        // the Sentinel PSK.
        ws?.close(1000, "unpaired")
    }

    // --- Timers --------------------------------------------------------------------------

    /**
     * Tell the client whether audio is flowing, so its timer loops can adapt. Idle mode
     * relaxes the time-sync cadence; a stream starting (idle → active) is exactly when
     * a fresh clock sample is most valuable.
     */
    fun setIdleMode(idle: Boolean) {
        idleMode = idle
        if (!idle) resyncClock()
    }

    private fun startTimeSync() {
        timeJob?.cancel()
        timeJob = scope.launch {
            while (isActive) {
                if (steady) {
                    val t1 = MonotonicClock.nowUs()
                    sendJson(json.encodeToString(SendspinClientTime(payload = ClientTimePayload(t1))))
                }
                // Fast while the filter cannot be scheduled against, relaxed once it
                // can. Keyed on **readiness**, not only on the sample count: a
                // connection that has been up for hours can still be knocked out of
                // convergence, and that is exactly when samples are worth paying for.
                val settled = clock.filter.sampleCount >= 50 && clock.isReadyForPlaybackStart()
                val backoff = clock.startupBackoffMs()
                val cadence = when {
                    !settled && backoff > 0L -> backoff
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
     * Ask for a `client/time` round-trip now rather than at the next tick — when a
     * stream is about to start, which is the one moment the offset is about to be used.
     */
    fun resyncClock() {
        timeKick.trySend(Unit)
    }

    /**
     * The clock-readiness loop: decides the local mute ([SyncGate]). Local output policy
     * only — nothing about it goes on the wire; `available` is reported once, when the
     * filter is first fit to schedule against.
     */
    private fun startSyncGate() {
        gateJob?.cancel()
        gateJob = scope.launch {
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
                delay(when {
                    !ready -> 300L
                    idleMode -> IDLE_TIME_SYNC_MS
                    else -> SLOW_TIME_SYNC_MS
                })
            }
        }
    }

    private fun normaliseUrl(serverUrl: String): String {
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

        /** The reference client's defaults; the engine's owner overrides them with its own needs. */
        const val DEFAULT_REQUIRED_LEAD_TIME_MS = 250
        const val DEFAULT_MIN_BUFFER_MS = 250

        /** Nothing reopens the player socket faster than this. */
        const val RECONNECT_MIN_GAP_MS = 750L

        /** `client/time` cadence while the clock is not fit to schedule against — the reference's burst rate. */
        const val FAST_TIME_SYNC_MS = 200L

        /**
         * RTT above this during cold start is rejected rather than fed into the
         * filter — a round-trip this slow would seed (or re-seed) the offset from a
         * measurement whose own error bar is too wide to be worth much.
         */
        const val STARTUP_RTT_REJECT_US = 150_000L

        /** [STARTUP_RTT_REJECT_US] only gates cold start — past this sample count the filter's own variance handles jitter. */
        const val STARTUP_REJECT_SAMPLE_CEILING = 5

        /** …and once it is: enough to hold a converged filter, and no more. */
        const val SLOW_TIME_SYNC_MS = 2_000L

        /**
         * …and when idle (no audio flowing): the clock filter doesn't need frequent
         * samples when nothing schedules against it. 30s keeps it warm enough to
         * converge within one or two fast samples when a stream starts.
         */
        const val IDLE_TIME_SYNC_MS = 30_000L

        /** How long the initial `client/state` waits for the clock; the server's own limit is 5 s. */
        const val INITIAL_STATE_DEADLINE_MS = 4_000L

        /** The spec's recommended pairing attempt timeout. */
        const val PAIRING_ATTEMPT_TIMEOUT_MS = 120_000L
    }
}
