package com.engabd.sendpin.ma

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class MaApiException(message: String, val code: Int) : Exception(message)

/**
 * Music Assistant **main API** client (the `/ws` endpoint — distinct from the
 * Sendspin player socket). Handles the `server_id` handshake, auth (token or
 * username/password → token), request/response by `message_id` with partial-chunk
 * accumulation, and a raw events flow. Trimmed from the massdroid MaWebSocketClient
 * (MIT). Used for on-device library browse / search / queue / transport.
 */
class MaApiClient(private val json: Json = Json { ignoreUnknownKeys = true }) {

    enum class State { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
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

    fun connect(url: String, token: String? = null, username: String? = null, password: String? = null) {
        serverUrl = url
        this.token = token
        this.login = if (!username.isNullOrBlank() && !password.isNullOrBlank()) username to password else null
        _state.value = State.CONNECTING
        userClosed = false; attempt = 0; reconnectJob?.cancel()
        wsUrl = url.trimEnd('/').replace("http://", "ws://").replace("https://", "wss://") + "/ws"
        ws = http.newWebSocket(Request.Builder().url(wsUrl).build(), listener)
    }

    fun disconnect() {
        userClosed = true; reconnectJob?.cancel()
        ws?.close(1000, "bye"); ws = null
        _state.value = State.DISCONNECTED
        pending.values.forEach { it.complete(null) }; pending.clear(); partials.clear()
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
                ws = http.newWebSocket(Request.Builder().url(wsUrl).build(), listener)
            }
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            try { handle(text) } catch (e: Exception) { Log.e("MaApi", "handle: ${e.message}") }
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            _state.value = State.ERROR
            pending.values.forEach { it.complete(null) }; pending.clear()
            scheduleReconnect()
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (_state.value != State.ERROR) _state.value = State.DISCONNECTED
            pending.values.forEach { it.complete(null) }; pending.clear()
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
    ): JsonElement? {
        if (!isAuth(command) && _state.value != State.CONNECTED) {
            try { withTimeout(5_000) { state.first { it == State.CONNECTED } } } catch (_: Exception) { return null }
        }
        return sendRaw(command, args, awaitResponse, timeoutMs)
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
        if (ws?.send(msg.toString()) != true) { pending.remove(id); throw MaApiException("Not connected", -1) }
        if (!awaitResponse) return null
        return try {
            withTimeout(timeoutMs) { d!!.await() }
        } catch (e: TimeoutCancellationException) {
            pending.remove(id); partials.remove(id); throw MaApiException("Request timed out", -1)
        }
    }

    private fun isAuth(c: String) = c == "auth" || c == "auth/login"
}
