package com.engabd.sendpin.ha

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import com.engabd.sendpin.data.Http
import com.engabd.sendpin.protocol.ReconnectBackoff
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class HaException(message: String) : Exception(message)

/**
 * Home Assistant **WebSocket API** client (`/api/websocket`). Auth is a long-lived
 * access token. After `auth_ok` we send `{id, type, …}` commands with a
 * per-connection increasing integer id and await the `{id, type:"result"}` reply.
 * Used by the light-sync backend to drive the Hue Synco entities via `call_service`.
 *
 * A dropped socket is dialled again on [ReconnectBackoff]'s schedule, as [com.engabd.sendpin.ma.MaApiClient]
 * does, and the new socket authenticates itself from `auth_required` like the first.
 * Nothing is subscribed, so nothing else needs restoring. No redial after [disconnect]
 * or a rejected token, which would fail the same way for ever.
 */
class HaClient(private val json: Json = Json { ignoreUnknownKeys = true }) {

    companion object {
        /** Whether a socket that just dropped should be dialled again. */
        internal fun shouldReconnect(userClosed: Boolean, authRejected: Boolean, wsUrl: String): Boolean =
            !userClosed && !authRejected && wsUrl.isNotBlank()

        /** [reconnectNow]'s limit against itself, as `MaApiClient.RECONNECT_MIN_GAP_MS`. */
        private const val RECONNECT_MIN_GAP_MS = 750L
        /** How long a command waits for a redial it set off, before failing. */
        private const val REDIAL_WAIT_MS = 5_000L
    }

    enum class State { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    private val http = Http.socket(pingSeconds = 30)
    // Written by connect/disconnect on the caller's thread, read on OkHttp's.
    @Volatile private var ws: WebSocket? = null
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonElement?>>()

    private val _state = MutableStateFlow(State.DISCONNECTED)
    val state: StateFlow<State> = _state.asStateFlow()

    private var token: String = ""
    private var wsUrl = ""
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var userClosed = false
    @Volatile private var authRejected = false
    private var attempt = 0
    private var reconnectJob: Job? = null
    /** When a socket was last opened, for [reconnectNow]'s rate limit. */
    @Volatile private var lastDialAtMs = 0L

    /**
     * [baseUrl] like `http://host:8123` (ws/wss derived). A no-op when already connected,
     * or connecting, to the same place with the same token.
     */
    fun connect(baseUrl: String, accessToken: String) {
        val nextUrl = baseUrl.trim().trimEnd('/')
            .replace("https://", "wss://").replace("http://", "ws://")
            .let { if (it.startsWith("ws")) it else "ws://$it" } + "/api/websocket"
        if (nextUrl == wsUrl && accessToken == token && !userClosed &&
            (_state.value == State.CONNECTED || _state.value == State.CONNECTING)
        ) return
        token = accessToken
        wsUrl = nextUrl
        userClosed = false; authRejected = false; attempt = 0; reconnectJob?.cancel()
        _state.value = State.CONNECTING
        nextId.set(1); pending.clear()
        dial()
    }

    // Same rule as MaApiClient.dial: a socket nobody references is not a socket
    // that closes. Whatever was here is shut before its replacement is opened.
    private fun dial() {
        lastDialAtMs = System.currentTimeMillis()
        ws?.close(1000, "redial")
        ws = http.newWebSocket(Request.Builder().url(wsUrl).build(), listener)
    }

    fun disconnect() {
        userClosed = true; reconnectJob?.cancel()
        ws?.close(1000, "bye"); ws = null
        _state.value = State.DISCONNECTED
        failPending("Disconnected")
    }

    /** Dial again after an unexpected drop, backing off while HA stays away. */
    private fun scheduleReconnect() {
        if (!shouldReconnect(userClosed, authRejected, wsUrl)) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(ReconnectBackoff.delayMs(attempt++))
            if (!shouldReconnect(userClosed, authRejected, wsUrl)) return@launch
            _state.value = State.CONNECTING
            dial()
        }
    }

    /**
     * Dial at once instead of waiting out the backoff, as `MaApiClient.reconnectNow`:
     * a command is someone waiting. A no-op when a socket is already up or on its
     * way, when no redial is allowed, or when one was opened moments ago.
     */
    private fun reconnectNow() {
        if (!shouldReconnect(userClosed, authRejected, wsUrl)) return
        if (_state.value == State.CONNECTED || _state.value == State.CONNECTING) return
        if (System.currentTimeMillis() - lastDialAtMs < RECONNECT_MIN_GAP_MS) return
        reconnectJob?.cancel()
        attempt = 0
        _state.value = State.CONNECTING
        dial()
    }

    /**
     * Fail every request still waiting for an answer. With an exception, not a null:
     * a null reads as "Home Assistant has no lights", which is exactly the lie a
     * dropped connection must not tell.
     */
    private fun failPending(why: String) {
        val waiting = pending.values.toList()
        pending.clear()
        waiting.forEach { it.completeExceptionally(HaException(why)) }
    }

    private val listener = object : WebSocketListener() {
        // A socket this client has already replaced still reports its own closing;
        // that must not touch the state of the one that replaced it.
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== ws) return
            try { handle(text) } catch (e: Exception) { Log.e("HaClient", "handle: ${e.message}") }
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket !== ws) return
            _state.value = State.ERROR
            failPending("Connection to Home Assistant lost: ${t.message}")
            scheduleReconnect()
        }
        // HA restarting sends a close frame; OkHttp then waits for this side to answer
        // before it will call onClosed. Nothing answered, so the socket sat half-closed
        // and every request on it waited out its timeout.
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            if (webSocket !== ws) return
            if (_state.value != State.ERROR) _state.value = State.DISCONNECTED
            failPending("Home Assistant closed the connection")
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket !== ws) return
            if (_state.value != State.ERROR) _state.value = State.DISCONNECTED
            // Until this, a request in flight when HA closed the socket waited out its
            // whole fifteen-second timeout for an answer that could no longer come.
            failPending("Home Assistant closed the connection")
            scheduleReconnect()
        }
    }

    private fun handle(text: String) {
        val obj = json.parseToJsonElement(text).jsonObject
        when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "auth_required" -> ws?.send(buildJsonObject {
                put("type", "auth"); put("access_token", token)
            }.toString())
            "auth_ok" -> { attempt = 0; _state.value = State.CONNECTED }
            "auth_invalid" -> {
                authRejected = true
                _state.value = State.ERROR
                failPending("Home Assistant rejected the token")
            }
            "result" -> {
                val id = obj["id"]?.jsonPrimitive?.intOrNull ?: return
                val d = pending.remove(id) ?: return
                val success = obj["success"]?.jsonPrimitive?.booleanOrNull ?: false
                if (success) d.complete(obj["result"])
                else d.completeExceptionally(
                    HaException((obj["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: "HA error")
                )
            }
            // "event" frames are ignored for now (no subscriptions used yet).
        }
    }

    suspend fun sendCommand(type: String, extra: JsonObject = JsonObject(emptyMap()), timeoutMs: Long = 15_000): JsonElement? {
        // HA ignores anything sent before `auth_ok` and then drops the socket, so a
        // command issued straight after connect() — which is how the one-shot "switch
        // every area off" path used it — never reached HA at all. Wait for the
        // handshake to settle; fail fast if it did not succeed. A command arriving while
        // the socket is down (a redial waiting out its backoff) dials now and waits
        // briefly for it, through a failed attempt or two, rather than failing at once.
        val down = _state.value == State.ERROR || _state.value == State.DISCONNECTED
        if (down) reconnectNow()
        val settled = try {
            withTimeout(if (down) minOf(timeoutMs, REDIAL_WAIT_MS) else timeoutMs) {
                state.first {
                    it == State.CONNECTED ||
                        (it != State.CONNECTING && (!down || !shouldReconnect(userClosed, authRejected, wsUrl)))
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw HaException(if (down) "Not connected" else "Home Assistant did not answer")
        }
        if (settled != State.CONNECTED) throw HaException("Not connected")
        val socket = ws ?: throw HaException("Not connected")
        val id = nextId.getAndIncrement()
        val d = CompletableDeferred<JsonElement?>().also { pending[id] = it }
        val msg = buildJsonObject {
            put("id", id); put("type", type)
            extra.forEach { (k, v) -> put(k, v) }
        }
        if (!socket.send(msg.toString())) { pending.remove(id); throw HaException("Send failed") }
        return try {
            withTimeout(timeoutMs) { d.await() }
        } catch (e: TimeoutCancellationException) {
            pending.remove(id); throw HaException("HA request timed out")
        }
    }

    suspend fun getStates(): JsonArray =
        sendCommand("get_states") as? JsonArray ?: JsonArray(emptyList())

    suspend fun entityRegistryList(): JsonArray =
        sendCommand("config/entity_registry/list") as? JsonArray ?: JsonArray(emptyList())

    suspend fun deviceRegistryList(): JsonArray =
        sendCommand("config/device_registry/list") as? JsonArray ?: JsonArray(emptyList())

    suspend fun callService(
        domain: String,
        service: String,
        entityId: String,
        serviceData: JsonObject = JsonObject(emptyMap()),
    ): JsonElement? = sendCommand("call_service", buildJsonObject {
        put("domain", domain)
        put("service", service)
        if (serviceData.isNotEmpty()) put("service_data", serviceData)
        put("target", buildJsonObject { put("entity_id", entityId) })
    })
}
