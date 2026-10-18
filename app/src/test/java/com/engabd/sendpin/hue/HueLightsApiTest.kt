package com.engabd.sendpin.hue

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The CLIP v2 room and grouped light calls, against a fake bridge on plain HTTP. */
class HueLightsApiTest {
    private val server = MockWebServer()

    @AfterTest fun tearDown() = server.close()

    private fun api(): HueLightsApi {
        server.start()
        return HueLightsApi({ OkHttpClient() }, scheme = "http")
    }

    private fun host() = "${server.hostName}:${server.port}"

    private val rooms = """
        {"errors":[],"data":[
          {"id":"room-1","type":"room","metadata":{"name":"Bedroom","archetype":"bedroom"},
           "services":[{"rid":"gl-1","rtype":"grouped_light"}],
           "children":[{"rid":"dev-1","rtype":"device"}]},
          {"id":"room-2","type":"room","metadata":{"name":"No group"},"services":[]}
        ]}
    """.trimIndent()

    private val zones = """
        {"errors":[],"data":[
          {"id":"zone-1","type":"zone","metadata":{"name":"Upstairs"},
           "services":[{"rid":"gl-9","rtype":"grouped_light"}]}
        ]}
    """.trimIndent()

    @Test
    fun `rooms then zones, each with the grouped light that addresses it`() = runBlocking {
        val api = api()
        server.enqueue(MockResponse.Builder().body(rooms).build())
        server.enqueue(MockResponse.Builder().body(zones).build())
        val groups = api.listGroups(host(), "key-123")
        assertEquals(
            listOf(
                HueGroup("room-1", "Bedroom", HueGroup.Kind.ROOM, "gl-1"),
                HueGroup("zone-1", "Upstairs", HueGroup.Kind.ZONE, "gl-9"),
            ),
            groups,
        )
        val r1 = server.takeRequest()
        assertEquals("/clip/v2/resource/room", r1.url.encodedPath)
        assertEquals("key-123", r1.headers["hue-application-key"])
        assertEquals("/clip/v2/resource/zone", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `a bridge that rejects the key is an error, not an empty list`() {
        val api = api()
        server.enqueue(MockResponse.Builder().code(403).build())
        assertFailsWith<HueBridgeException> { runBlocking { api.listGroups(host(), "bad") } }
    }

    @Test
    fun `group state reads on and brightness`() = runBlocking {
        val api = api()
        server.enqueue(
            MockResponse.Builder()
                .body("""{"data":[{"id":"gl-1","on":{"on":true},"dimming":{"brightness":42.5}}]}""").build(),
        )
        assertEquals(HueGroupState(true, 42.5), api.groupState(host(), "k", "gl-1"))
        assertEquals("/clip/v2/resource/grouped_light/gl-1", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `an empty answer has no state`() = runBlocking {
        val api = api()
        server.enqueue(MockResponse.Builder().body("""{"data":[]}""").build())
        assertNull(api.groupState(host(), "k", "gl-1"))
    }

    @Test
    fun `putting a body sends exactly that body to the grouped light`() = runBlocking {
        val api = api()
        server.enqueue(MockResponse.Builder().body("""{"data":[],"errors":[]}""").build())
        api.putGroup(host(), "k", "gl-1", HueLightsApi.bodyDim(1.0, 600_000))
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/clip/v2/resource/grouped_light/gl-1", req.url.encodedPath)
        assertEquals("""{"on":{"on":true},"dimming":{"brightness":1.0},"dynamics":{"duration":600000}}""", req.body!!.utf8())
    }

    @Test
    fun `a failed put throws so the caller can say the lights were unreachable`() {
        val api = api()
        server.enqueue(MockResponse.Builder().code(500).body("boom").build())
        assertFailsWith<HueBridgeException> { runBlocking { api.putGroup(host(), "k", "gl-1", "{}") } }
    }

    @Test
    fun `request bodies are exact`() {
        assertEquals(
            """{"on":{"on":true},"dimming":{"brightness":1.0},"color":{"xy":{"x":0.6,"y":0.36}},"dynamics":{"duration":0}}""",
            HueLightsApi.bodyColour(0.6, 0.36, 1.0, 0),
        )
        assertEquals(
            """{"on":{"on":true},"dimming":{"brightness":60.0},"color_temperature":{"mirek":370},"dynamics":{"duration":1260000}}""",
            HueLightsApi.bodyWhite(370, 60.0, 1_260_000),
        )
        assertEquals("""{"on":{"on":false},"dynamics":{"duration":2000}}""", HueLightsApi.bodyOff(2_000))
    }

    @Test
    fun `kelvin converts to mirek within the lamp range`() {
        assertEquals(370, kelvinToMirek(2700))
        assertEquals(250, kelvinToMirek(4000))
        assertEquals(454, kelvinToMirek(2200))
        assertEquals(500, kelvinToMirek(1000))
        assertEquals(153, kelvinToMirek(10000))
    }
}
