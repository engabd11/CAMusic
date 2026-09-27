package com.engabd.sendpin.ha

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Home Assistant's WebSocket handshake, played by a fake HA. */
class HaClientTest {

    private val server = MockWebServer()
    private val seen = Collections.synchronizedList(mutableListOf<String>())

    @AfterTest fun tearDown() = server.close()

    /** A fake HA: asks for auth, answers it after [authDelayMs], then runs [onCommand]. */
    private fun fakeHa(authDelayMs: Long = 300, onCommand: (WebSocket, Int, String) -> Unit) {
        server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"type":"auth_required"}""")
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = Json.parseToJsonElement(text).jsonObject
                val type = msg["type"]!!.jsonPrimitive.content
                seen += type
                if (type == "auth") {
                    Thread.sleep(authDelayMs)
                    webSocket.send("""{"type":"auth_ok"}""")
                } else {
                    onCommand(webSocket, msg["id"]!!.jsonPrimitive.int, type)
                }
            }
        }).build())
        server.start()
    }

    private fun baseUrl() = "http://${server.hostName}:${server.port}"

    @Test
    fun `a command sent straight after connect waits for auth instead of being lost`() = runBlocking {
        fakeHa { ws, id, _ -> ws.send("""{"id":$id,"type":"result","success":true,"result":[{"entity_id":"light.a"}]}""") }
        val client = HaClient()
        client.connect(baseUrl(), "token")
        // No waiting for CONNECTED — the one-shot "switch every area off" path did this.
        val states = client.getStates()
        assertEquals("light.a", states.jsonArray.single().jsonObject["entity_id"]!!.jsonPrimitive.content)
        assertEquals(listOf("auth", "get_states"), seen.toList(), "HA must hear the auth before any command")
        client.disconnect()
    }

    @Test
    fun `a request in flight fails at once when HA closes the socket`() = runBlocking {
        fakeHa { ws, _, _ -> ws.close(1001, "restarting") }
        val client = HaClient()
        client.connect(baseUrl(), "token")
        val started = System.nanoTime()
        assertFailsWith<HaException> { client.sendCommand("get_states", timeoutMs = 15_000) }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(tookMs < 5_000, "failed after ${tookMs} ms — it waited out the timeout")
        client.disconnect()
    }

    @Test
    fun `a rejected token fails the command rather than hanging`() = runBlocking {
        server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"type":"auth_required"}""")
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                webSocket.send("""{"type":"auth_invalid","message":"bad"}""")
            }
        }).build())
        server.start()
        val client = HaClient()
        client.connect(baseUrl(), "wrong")
        assertFailsWith<HaException> { client.sendCommand("get_states", timeoutMs = 5_000) }
        assertEquals(HaClient.State.ERROR, client.state.value)
        client.disconnect()
    }
}
