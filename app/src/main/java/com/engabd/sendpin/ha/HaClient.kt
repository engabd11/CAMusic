package com.engabd.sendpin.ha

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import com.engabd.sendpin.data.Http
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
 */
class HaClient(private val json: Json = Json { ignoreUnknownKeys = true }) {

    enum class State { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    private val http = Http.socket(pingSeconds = 30)
    private var ws: WebSocket? = null
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonElement?>>()

    private val _state = MutableStateFlow(State.DISCONNECTED)
    val state: StateFlow<State> = _state.asStateFlow()

    private var token: String = ""

    /** [baseUrl] like `http://host:8123` (ws/wss derived). */
    fun connect(baseUrl: String, accessToken: String) {
        token = accessToken
        _state.value = State.CONNECTING
        val wsUrl = baseUrl.trim().trimEnd('/')
            .replace("https://", "wss://").replace("http://", "ws://")
            .let { if (it.startsWith("ws")) it else "ws://$it" } + "/api/websocket"
        nextId.set(1); pending.clear()
        ws = http.newWebSocket(Request.Builder().url(wsUrl).build(), listener)
    }

    fun disconnect() {
        ws?.close(1000, "bye"); ws = null
        _state.value = State.DISCONNECTED
        pending.values.forEach { it.complete(null) }; pending.clear()
    }

    private val listener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            try { handle(text) } catch (e: Exception) { Log.e("HaClient", "handle: ${e.message}") }
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            _state.value = State.ERROR
            pending.values.forEach { it.complete(null) }; pending.clear()
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (_state.value != State.ERROR) _state.value = State.DISCONNECTED
        }
    }

    private fun handle(text: String) {
        val obj = json.parseToJsonElement(text).jsonObject
        when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "auth_required" -> ws?.send(buildJsonObject {
                put("type", "auth"); put("access_token", token)
            }.toString())
            "auth_ok" -> _state.value = State.CONNECTED
            "auth_invalid" -> _state.value = State.ERROR
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
