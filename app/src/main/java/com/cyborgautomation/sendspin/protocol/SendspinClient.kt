package com.cyborgautomation.sendspin.protocol

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import okhttp3.*
import java.util.concurrent.TimeUnit

class SendspinClient(
    private val serverUrl: String,
    private val playerId: String,
    private val playerName: String,
    private val deviceInfo: DeviceInfo,
    private val playerV1Support: PlayerV1Support,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)  // No read timeout for streaming
        .build()

    private var webSocket: WebSocket? = null
    private var connected = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _messages = MutableSharedFlow<Message>(replay = 0, extraBufferCapacity = 64)
    val messages: SharedFlow<Message> = _messages

    private val _audioFrames = MutableSharedFlow<AudioFrame>(replay = 0, extraBufferCapacity = 256)
    val audioFrames: SharedFlow<AudioFrame> = _audioFrames

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val audioFrameParser = AudioFrameParser()

    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    suspend fun connect() {
        _connectionState.value = ConnectionState.CONNECTING

        val request = Request.Builder()
            .url(serverUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _connectionState.value = ConnectionState.CONNECTED
                connected = true

                // Send client/hello immediately
                val hello = Message.ClientHello(
                    clientId = playerId,
                    name = playerName,
                    deviceInfo = deviceInfo,
                    playerV1Support = playerV1Support,
                )
                send(hello)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val msg = json.decodeFromString<Message>(text)
                    scope.launch { _messages.emit(msg) }
                } catch (e: Exception) {
                    // Unknown message, ignore
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                try {
                    val frame = audioFrameParser.parse(bytes.toByteArray())
                    scope.launch { _audioFrames.emit(frame) }
                } catch (e: Exception) {
                    // Invalid frame, ignore
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                _connectionState.value = ConnectionState.ERROR
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        })
    }

    fun send(message: Message) {
        val text = json.encodeToString(Message.serializer(), message)
        webSocket?.send(text)
    }

    fun send(bytes: ByteArray) {
        webSocket?.send(okio.ByteString.of(*bytes))
    }

    fun disconnect(reason: String = "user_disconnect") {
        send(Message.ClientGoodbye(reason))
        webSocket?.close(1000, reason)
        connected = false
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    fun isConnected(): Boolean = connected

    fun close() {
        scope.cancel()
        webSocket?.close(1000, "shutdown")
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
