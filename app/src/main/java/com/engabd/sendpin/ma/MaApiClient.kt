package com.engabd.sendpin.ma

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import com.engabd.sendpin.data.Http
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * @param isTransport the request never reached a verdict — it timed out, or the
 *   socket went away under it. Those are worth retrying; a server that answered
 *   with an error is not, since it will answer the same way again.
 */
class MaApiException(
    message: String,
    val code: Int,
    val isTransport: Boolean = false,
) : Exception(message)

/**
 * Music Assistant **main API** client (the `/ws` endpoint — distinct from the
 * Sendspin player socket). Handles the `server_id` handshake, auth (token or
 * username/password → token), request/response by `message_id` with partial-chunk
 * accumulation, and a raw events flow. Trimmed from the massdroid MaWebSocketClient
 * (MIT). Used for on-device library browse / search / queue / transport.
 */
class MaApiClient(private val json: Json = Json { ignoreUnknownKeys = true }) {

    enum class State { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    private val http = Http.socket(pingSeconds = 30)
    private var ws: WebSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(State.DISCONNECTED)
    val state: StateFlow<State> = _state.asStateFlow()
    private val _events = MutableSharedFlow<JsonObject>(extraBufferCapacity = 64)
    val events: SharedFlow<JsonObject> = _events.asSharedFlow()

    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonElement?>>()
    private val partials = ConcurrentHashMap<String, MutableList<JsonElement>>()

    private var token: String? = null
    private var login: Pair<String, String>? = null
    @Volatile var authToken: String? = null
        private set
    var serverUrl: String? = null
        private set

    @Volatile private var userClosed = false
    private var wsUrl = ""
    private var attempt = 0
    private var reconnectJob: kotlinx.coroutines.Job? = null

    /** When a socket was last opened, for [reconnectNow]'s rate limit. */
    @Volatile private var lastDialAtMs = 0L

    fun connect(url: String, token: String? = null, username: String? = null, password: String? = null) {
        serverUrl = url
        this.token = token
        this.login = if (!username.isNullOrBlank() && !password.isNullOrBlank()) username to password else null
        _state.value = State.CONNECTING
        userClosed = false; attempt = 0; reconnectJob?.cancel()
        wsUrl = url.trimEnd('/').replace("http://", "ws://").replace("https://", "wss://") + "/ws"
        dial()
    }

    /** Open a socket to [wsUrl], recording when — see [reconnectNow]'s rate limit. */
    private fun dial() {
        lastDialAtMs = android.os.SystemClock.elapsedRealtime()
        ws = http.newWebSocket(Request.Builder().url(wsUrl).build(), listener)
    }

    fun disconnect() {
        userClosed = true; reconnectJob?.cancel()
        ws?.close(1000, "bye"); ws = null
        _state.value = State.DISCONNECTED
        failPending("Disconnected")
        partials.clear()
    }

    /**
     * Fail everything still waiting, rather than answering it with null.
     *
     * Completing with null made a dropped socket indistinguishable from a server
     * that legitimately had nothing to return, so a library that failed to load
     * rendered as a library that is empty — and callers that retry on failure had
     * nothing to retry on.
     */
    private fun failPending(why: String) {
        val waiting = pending.values.toList()
        pending.clear()
        waiting.forEach { it.completeExceptionally(MaApiException(why, -1, isTransport = true)) }
    }

    /** Reconnect with backoff after an unexpected drop — keeps browse/volume/grouping working. */
    private fun scheduleReconnect() {
        if (userClosed || wsUrl.isBlank()) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val delayMs = (500L * (1 shl attempt.coerceAtMost(5))).coerceAtMost(15_000L)
            attempt++
            kotlinx.coroutines.delay(delayMs)
            if (!userClosed) {
                _state.value = State.CONNECTING
                dial()
            }
        }
    }

    /**
     * Reconnect **now**, abandoning whatever the backoff had planned.
     *
     * The backoff is right for a server that is down: doubling up to fifteen
     * seconds is what stops a phone hammering a LAN address nobody is answering.
     * It is wrong for the case that actually happens, which is a socket dropped
     * while the app sat in the background — the phone dozes, the WebSocket dies
     * unnoticed, the backoff walks itself up to its ceiling against a network that
     * is asleep, and then the user opens the app and taps a song. That tap used to
     * wait out the remainder of a fifteen-second timer before anything was even
     * sent, which is the "sometimes it takes five seconds" half of playing from the
     * library.
     *
     * A user action is new information — someone is holding the phone and the
     * radio is up — so the schedule is torn up and a socket opened immediately.
     * Rate-limited only against itself, so a screen that fires several commands at
     * once opens one socket rather than one each, and a no-op when a connection is
     * already up or already on its way.
     */
    fun reconnectNow() {
        if (userClosed || wsUrl.isBlank()) return
        if (_state.value == State.CONNECTED || _state.value == State.CONNECTING) return
        if (android.os.SystemClock.elapsedRealtime() - lastDialAtMs < RECONNECT_MIN_GAP_MS) return
        reconnectJob?.cancel()
        attempt = 0
        _state.value = State.CONNECTING
        dial()
    }

    private val listener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            try { handle(text) } catch (e: Exception) { Log.e("MaApi", "handle: ${e.message}") }
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            _state.value = State.ERROR
            failPending(t.message ?: "Connection failed")
            scheduleReconnect()
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (_state.value != State.ERROR) _state.value = State.DISCONNECTED
            failPending("Connection closed")
            scheduleReconnect()
        }
    }

    private fun handle(text: String) {
        val obj = json.parseToJsonElement(text).jsonObject
        when {
            "server_id" in obj -> scope.launch { authenticate() }
            "message_id" in obj -> {
                val id = obj["message_id"]!!.jsonPrimitive.content
                if ("error" in obj || "error_code" in obj) {
                    val code = obj["error_code"]?.jsonPrimitive?.intOrNull ?: -1
                    val msg = obj["error"]?.jsonPrimitive?.contentOrNull
                        ?: obj["details"]?.jsonPrimitive?.contentOrNull ?: "error"
                    pending.remove(id)?.completeExceptionally(MaApiException(msg, code))
                    return
                }
                val result = obj["result"]
                val partial = obj["partial"]?.jsonPrimitive?.booleanOrNull ?: false
                if (partial) {
                    if (!pending.containsKey(id) && !partials.containsKey(id)) return
                    val list = partials.getOrPut(id) { mutableListOf() }
                    if (result is JsonArray) list.addAll(result) else if (result != null) list.add(result)
                } else {
                    val d = pending.remove(id)
                    val acc = partials.remove(id)
                    if (acc != null) {
                        if (result is JsonArray) acc.addAll(result) else if (result != null) acc.add(result)
                        d?.complete(JsonArray(acc))
                    } else {
                        d?.complete(result)
                    }
                }
            }
            "event" in obj -> _events.tryEmit(obj)
        }
    }

    private suspend fun authenticate() {
        try {
            val l = login
            if (l != null) {
                val res = sendRaw("auth/login", buildJsonObject {
                    put("username", l.first); put("password", l.second); put("device_name", "Sendpin")
                })?.jsonObject
                val tok = res?.get("access_token")?.jsonPrimitive?.contentOrNull
                val ok = res?.get("success")?.jsonPrimitive?.booleanOrNull ?: false
                if (!ok || tok.isNullOrBlank()) {
                    throw MaApiException(res?.get("error")?.jsonPrimitive?.contentOrNull ?: "Login failed", -1)
                }
                authToken = tok
                sendRaw("auth", buildJsonObject { put("token", tok); put("device_name", "Sendpin") })
            } else if (!token.isNullOrBlank()) {
                authToken = token
                sendRaw("auth", buildJsonObject { put("token", token!!); put("device_name", "Sendpin") })
            }
            // else: no credentials — assume an open LAN server. Commands that turn
            // out to need auth will fail individually rather than blocking connect.
            attempt = 0   // good handshake → reset reconnect backoff
            _state.value = State.CONNECTED
        } catch (e: Exception) {
            Log.e("MaApi", "auth: ${e.message}")
            _state.value = State.ERROR
        }
    }

    suspend fun sendCommand(
        command: String,
        args: JsonObject? = null,
        awaitResponse: Boolean = true,
        timeoutMs: Long = 30_000,
        /**
         * Extra attempts on a timeout or a dropped socket. Zero for control commands —
         * re-sending a "next track" that did land would skip twice — but worth having
         * for reads, where a retry is free of consequence and a single unlucky request
         * otherwise leaves the user staring at an error.
         */
        retries: Int = 0,
    ): JsonElement? {
        var attempt = 0
        while (true) {
            if (!isAuth(command) && _state.value != State.CONNECTED) {
                // Don't sit out the backoff — this command is a person waiting.
                reconnectNow()
                // Wait longer between retries: a reconnect in progress is the most
                // likely reason the state isn't CONNECTED yet.
                val wait = if (attempt == 0) 5_000L else 15_000L
                try { withTimeout(wait) { state.first { it == State.CONNECTED } } } catch (_: Exception) {
                    if (attempt++ >= retries) return null
                    continue
                }
            }
            try {
                return sendRaw(command, args, awaitResponse, timeoutMs)
            } catch (e: MaApiException) {
                // Only transport failures are worth another go. A server-side
                // rejection — a bad argument, a missing permission — answers the same
                // way however many times it is asked, and retrying it just multiplies
                // the delay before the user sees why.
                if (!e.isTransport || attempt++ >= retries) {
                    // The one place every Music Assistant failure passes through, and
                    // until now none of them left a trace: the whole `ma/` package had
                    // two Log.e calls, both about the socket, so a failed browse or play
                    // existed only as snackbar text that was gone before anyone could
                    // read it. A logcat line with the command name turns that class of
                    // bug from a hunt into a read.
                    Log.w("MaApi", "command '$command' failed: ${e.message} (code=${e.code})")
                    throw e
                }
                delay(500L * attempt)
            }
        }
    }

    private suspend fun sendRaw(
        command: String,
        args: JsonObject?,
        awaitResponse: Boolean = true,
        timeoutMs: Long = 30_000,
    ): JsonElement? {
        val id = UUID.randomUUID().toString().replace("-", "").take(12)
        val d = if (awaitResponse) CompletableDeferred<JsonElement?>().also { pending[id] = it } else null
        val msg = buildJsonObject {
            put("command", command); put("message_id", id); if (args != null) put("args", args)
        }
        if (ws?.send(msg.toString()) != true) {
            pending.remove(id); throw MaApiException("Not connected", -1, isTransport = true)
        }
        if (!awaitResponse) return null
        return try {
            withTimeout(timeoutMs) { d!!.await() }
        } catch (e: TimeoutCancellationException) {
            pending.remove(id); partials.remove(id)
            throw MaApiException("Request timed out", -1, isTransport = true)
        }
    }

    private fun isAuth(c: String) = c == "auth" || c == "auth/login"

    private companion object {
        /**
         * Nothing reopens a socket faster than this. Long enough that a screen
         * firing off five reads at once dials once, short enough to be invisible to
         * whoever is waiting on the first of them.
         */
        const val RECONNECT_MIN_GAP_MS = 750L
    }
}
