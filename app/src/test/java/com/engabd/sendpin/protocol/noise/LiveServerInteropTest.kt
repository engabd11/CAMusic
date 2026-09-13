package com.engabd.sendpin.protocol.noise

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Kotlin Noise layer against a **real** Sendspin server — Music Assistant 2.10 —
 * over a plain OkHttp socket: `client/init` → `server/init` → handshake → encrypted
 * `server/hello` → `client/hello` → `server/activate` → `client/time`/`server/time` →
 * `client/state` → `client/goodbye`.
 *
 * Skipped unless `SENDSPIN_SERVER` names an endpoint, e.g.
 * `SENDSPIN_SERVER=ws://192.168.0.48:8927/sendspin ./gradlew :app:testMobileDebugUnitTest --tests '*LiveServer*'`.
 * The identity is derived from a fixed seed, so every run is the same throwaway
 * player ("CAMusic interop test") on the server rather than a new one each time.
 */
class LiveServerInteropTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    private sealed class Frame {
        class Text(val text: String) : Frame()
        class Binary(val bytes: ByteArray) : Frame()
        class Closed(val reason: String) : Frame()
    }

    @Test
    fun `handshake, hello, activate, time and state against a live server`() {
        val url = System.getenv("SENDSPIN_SERVER") ?: run {
            println("SENDSPIN_SERVER not set; skipping live interop test")
            return
        }
        val identity = SendspinIdentity(NoiseHash.sha256("camusic-interop-test-identity".toByteArray()))
        val suite = NoiseCipherSuite.AESGCM
        val frames = LinkedBlockingQueue<Frame>()
        val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        val ws = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) { frames.put(Frame.Text(text)) }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) { frames.put(Frame.Binary(bytes.toByteArray())) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { frames.put(Frame.Closed("$code $reason")) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { frames.put(Frame.Closed("failure ${t.message}")) }
            },
        )
        fun text(): String = when (val f = frames.poll(10, TimeUnit.SECONDS)) {
            is Frame.Text -> f.text
            is Frame.Closed -> error("socket closed: ${f.reason}")
            is Frame.Binary -> error("unexpected binary frame")
            null -> error("timed out waiting for a text frame")
        }

        // --- cleartext init + Noise handshake ---
        val clientInit = """{"type":"client/init","payload":{"client_id":"${identity.peerId}","version":1,"suite":"${suite.wireName}"}}"""
        ws.send(clientInit)
        val serverInitText = text()
        val serverInit = json.parseToJsonElement(serverInitText).jsonObject
        assertEquals("server/init", serverInit["type"]?.jsonPrimitive?.content)
        val serverId = serverInit["payload"]!!.jsonObject["server_id"]!!.jsonPrimitive.content
        val serverPub = assertNotNull(SendspinIdentity.decodePeerId(serverId))
        val hs = NoiseHandshake(suite, identity.privateKey, serverPub, clientInit.toByteArray() + serverInitText.toByteArray())

        val msg1 = json.parseToJsonElement(text()).jsonObject
        assertEquals("noise/handshake", msg1["type"]?.jsonPrimitive?.content)
        val payload1 = hs.readMessage1(B64Url.decode(msg1["payload"]!!.jsonObject["data"]!!.jsonPrimitive.content))
        val pskId = json.parseToJsonElement(String(payload1)).jsonObject["psk_id"]!!.jsonPrimitive.content
        assertEquals(SendspinPsk.SENTINEL_ID, pskId, "an unknown client is offered the Sentinel PSK")
        val (msg2, result) = hs.writeMessage2(SendspinPsk.SENTINEL, "{}".toByteArray())
        ws.send("""{"type":"noise/handshake","payload":{"data":"${B64Url.encode(msg2)}"}}""")
        val transport = result.transport
        val reassembler = NoiseFraming.Reassembler()

        fun sendJson(s: String) {
            for (frame in NoiseFraming.fragment(NoiseFraming.json(s))) ws.send(transport.encrypt(frame).toByteString())
        }
        fun recvJson(): JsonObject {
            while (true) {
                when (val f = frames.poll(10, TimeUnit.SECONDS)) {
                    is Frame.Binary -> {
                        val pt = reassembler.accept(transport.decrypt(f.bytes)) ?: continue
                        if (pt[0].toInt() == NoiseFraming.TYPE_JSON) return json.parseToJsonElement(String(pt, 1, pt.size - 1)).jsonObject
                    }
                    is Frame.Text -> error("cleartext after handshake: ${f.text.take(80)}")
                    is Frame.Closed -> error("socket closed: ${f.reason}")
                    null -> error("timed out waiting for an encrypted frame")
                }
            }
        }

        // --- hello ---
        val hello = recvJson()
        assertEquals("server/hello", hello["type"]?.jsonPrimitive?.content)
        println("server/hello from ${hello["payload"]!!.jsonObject["name"]}")
        sendJson(
            """{"type":"client/hello","payload":{"name":"CAMusic interop test","supported_roles":["player@v1"],""" +
                """"device_info":{"product_name":"CAMusic","manufacturer":"CAMusic","software_version":"test"},""" +
                """"player@v1_support":{"supported_formats":[{"codec":"pcm","channels":2,"sample_rate":48000,"bit_depth":16}],"buffer_capacity":1000000,"supported_commands":["volume","mute"]},""" +
                """"trust_level":"none","supported_pair_methods":[{"method":"pairing_psk","locations":["device"]}],"unpaired_access":{"enabled":true}}}""",
        )

        // --- activation: the first may carry no roles; MA approves guest access and re-activates ---
        var roles: List<String> = emptyList()
        var activations = 0
        val deadline = System.currentTimeMillis() + 10_000
        while (roles.isEmpty() && System.currentTimeMillis() < deadline) {
            val m = recvJson()
            val type = m["type"]?.jsonPrimitive?.content
            println("<< $type ${m["payload"].toString().take(120)}")
            if (type == "server/activate") {
                activations++
                roles = (m["payload"]!!.jsonObject["active_roles"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
            }
        }
        assertTrue(activations >= 1, "server/activate never arrived")
        assertTrue("player@v1" in roles, "player role was not activated (roles=$roles)")

        // --- time sync round trip, then the initial state ---
        sendJson("""{"type":"client/time","payload":{"client_transmitted":${System.nanoTime() / 1000}}}""")
        var gotTime = false
        repeat(5) {
            if (gotTime) return@repeat
            val m = recvJson()
            if (m["type"]?.jsonPrimitive?.content == "server/time") {
                val p = m["payload"]!!.jsonObject
                assertTrue(p["server_received"]!!.jsonPrimitive.content.toLong() <= p["server_transmitted"]!!.jsonPrimitive.content.toLong())
                gotTime = true
            }
        }
        assertTrue(gotTime, "no server/time reply")
        sendJson("""{"type":"client/state","payload":{"available":true,"player":{"volume":50,"muted":false,"static_delay_ms":0,"required_lead_time_ms":400,"min_buffer_ms":250,"supported_commands":["set_static_delay"]}}}""")
        // The server does not acknowledge state; a clean second round-trip proves the transport is still in step.
        sendJson("""{"type":"client/time","payload":{"client_transmitted":${System.nanoTime() / 1000}}}""")
        var second = false
        repeat(5) { if (!second && recvJson()["type"]?.jsonPrimitive?.content == "server/time") second = true }
        assertTrue(second, "transport fell out of step after client/state")

        sendJson("""{"type":"client/goodbye","payload":{"reason":"user_request"}}""")
        ws.close(1000, "done")
        client.dispatcher.executorService.shutdown()
    }
}
