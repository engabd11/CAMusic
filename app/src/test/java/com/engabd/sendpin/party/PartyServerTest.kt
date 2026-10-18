package com.engabd.sendpin.party

import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A party backed by plain lists, so the rules and the HTTP skin can be driven end to end. */
private class FakeBackend : PartyBackend {
    val queue = mutableListOf<PartyUpNext>()
    var skipped = 0
    var playing: PartyNowPlaying? = PartyNowPlaying("k", "Playing Song", "Band", "Record", 200_000, 50_000, true, "http://secret-server:4533/art?token=SECRET")
    val library = listOf(
        PartyCandidate("ref-1", "Alpha", "Artist", "Album", 100_000, "http://secret-server/art/1"),
        PartyCandidate("ref-2", "Alpine", "Artist", "Album", 100_000, null),
    )

    override fun nowPlaying() = playing
    override suspend fun upNext(limit: Int) = queue.take(limit)
    override suspend fun search(query: String, limit: Int) = library.filter { it.title.contains(query, true) }
    override suspend fun enqueue(ref: String, position: Int): String? {
        val c = library.firstOrNull { it.ref == ref } ?: return null
        queue.add(position.coerceAtMost(queue.size), PartyUpNext(ref, c.title, c.artist, c.artRef))
        return ref
    }
    override suspend fun artwork(artRef: String): ByteArray? = byteArrayOf(1, 2, 3)
    override fun skip() { skipped++ }
}

class PartyServerTest {
    private lateinit var backend: FakeBackend
    private lateinit var engine: PartyEngine
    private lateinit var server: PartyServer
    private lateinit var base: String
    private var now = 1_000_000L

    @BeforeTest fun setUp() {
        backend = FakeBackend()
        engine = PartyEngine("0123456789abcdef0123456789abcdef", backend, clock = { now })
        server = PartyServer("127.0.0.1", 0, engine) { name ->
            if (name == "index.html") "<html>hi</html>".toByteArray() else null
        }
        server.begin()
        base = "http://127.0.0.1:${server.listeningPort}/p/${engine.token}/"
    }

    @AfterTest fun tearDown() { server.stop() }

    private class Reply(val code: Int, val text: String, val headers: Map<String, String>) {
        val json: JsonObject get() = Json.parseToJsonElement(text).jsonObject
    }

    private fun call(url: String, method: String = "GET", guest: String? = null, body: String? = null): Reply {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        guest?.let { c.setRequestProperty("X-Guest-Id", it) }
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setFixedLengthStreamingMode(body.toByteArray().size)
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val stream = if (code < 400) c.inputStream else c.errorStream
        val text = stream?.use { String(it.readBytes()) }.orEmpty()
        val headers = c.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() }.mapValues { it.value.first() }
        return Reply(code, text, headers)
    }

    private fun join(name: String = "Sam"): String =
        call(base + "api/join", "POST", body = """{"name":"$name"}""").json["guestId"]!!.jsonPrimitive.content

    private fun add(guest: String, id: String) = call(base + "api/add", "POST", guest, """{"id":"$id"}""")

    private fun firstResultId(guest: String): String =
        call(base + "api/search?q=alp", guest = guest).json["results"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content

    @Test fun servesThePageWithSecurityHeaders() {
        val r = call(base)
        assertEquals(200, r.code)
        assertEquals("<html>hi</html>", r.text)
        assertEquals("default-src 'self'; img-src 'self' data:", r.headers["content-security-policy"])
        assertEquals("nosniff", r.headers["x-content-type-options"])
        assertEquals("no-referrer", r.headers["referrer-policy"])
        assertNull(r.headers["access-control-allow-origin"])
    }

    @Test fun anythingOffTheTokenPathIsNotFound() {
        val root = "http://127.0.0.1:${server.listeningPort}"
        listOf("/", "/p/", "/p/wrong/", "/p/wrong/api/state", "/p/${engine.token}/app.js", "/p/${engine.token}/../x").forEach {
            assertEquals(404, call(root + it).code, it)
        }
    }

    @Test fun apiNeedsAJoinedGuest() {
        assertEquals(401, call(base + "api/state").code)
        assertEquals(401, call(base + "api/state", guest = "nope").code)
        assertEquals(405, call(base + "api/join").code)
    }

    @Test fun stateShowsNowPlayingAndWhoAddedWhat() {
        val g = join("Sam")
        assertEquals(200, add(g, firstResultId(g)).code)
        val s = call(base + "api/state", guest = g).json
        assertEquals("Playing Song", s["nowPlaying"]!!.jsonObject["title"]!!.jsonPrimitive.content)
        val next = s["upNext"]!!.jsonArray[0].jsonObject
        assertEquals("Alpha", next["title"]!!.jsonPrimitive.content)
        assertEquals("Sam", next["addedBy"]!!.jsonPrimitive.content)
    }

    @Test fun responsesNeverCarryServerDetails() {
        val g = join()
        val id = firstResultId(g)
        add(g, id)
        val all = listOf(
            call(base + "api/state", guest = g).text,
            call(base + "api/search?q=alp", guest = g).text,
            call(base + "api/join", "POST", body = """{"name":"x"}""").text,
        ).joinToString()
        listOf("secret-server", "SECRET", "http://", "ref-1", "ref-2", "4533", "token=").forEach {
            assertFalse(all.contains(it), "leaked $it")
        }
        val keys = call(base + "api/search?q=alp", guest = g).json["results"]!!.jsonArray[0].jsonObject.keys
        assertEquals(setOf("id", "title", "artist", "album", "durationSec", "art"), keys)
    }

    @Test fun artIsProxiedAsBytesBehindAnOpaqueId() {
        val g = join()
        val state = call(base + "api/state", guest = g).json
        val art = state["nowPlaying"]!!.jsonObject["art"]!!.jsonPrimitive.content
        assertTrue(PartyPaths.isId(art))
        val r = call(base + "api/art/$art")
        assertEquals(200, r.code)
        assertEquals("image/jpeg", r.headers["content-type"])
        assertEquals(404, call(base + "api/art/deadbeefdeadbeef").code)
    }

    @Test fun addsAreRateLimitedPerGuest() {
        val g = join()
        val id = firstResultId(g)
        val codes = (1..12).map { add(g, id).code }
        assertEquals(10, codes.count { it == 200 })
        assertEquals(2, codes.count { it == 429 })
        // A different guest is unaffected.
        val other = join("Kim")
        assertEquals(200, add(other, firstResultId(other)).code)
    }

    @Test fun addRejectsUnknownAndMalformedIds() {
        val g = join()
        assertEquals(400, add(g, "not an id").code)
        assertEquals(410, add(g, "abcdef0123456789").code)
        assertEquals(400, call(base + "api/add", "POST", g, "not json").code)
    }

    @Test fun oversizedBodiesAreRefused() {
        val g = join()
        assertEquals(413, call(base + "api/add", "POST", g, """{"id":"${"a".repeat(5000)}"}""").code)
    }

    @Test fun guestsAreRoundRobinedInTheQueue() {
        val a = join("A"); val b = join("B")
        val id = firstResultId(a)
        repeat(3) { add(a, id) }
        add(b, firstResultId(b))
        // A's first, then B's, then A's remaining two.
        assertEquals(listOf("ref-1", "ref-1", "ref-1", "ref-1"), backend.queue.map { it.key })
        val s = call(base + "api/state", guest = a).json
        val by = s["upNext"]!!.jsonArray.map { it.jsonObject["addedBy"]!!.jsonPrimitive.content }
        assertEquals(listOf("A", "B", "A", "A"), by)
    }

    @Test fun approvalHoldsSongsUntilTheHostSaysYes() = runBlocking {
        engine.updateSettings { it.copy(approveFirst = true) }
        val g = join("Sam")
        val r = add(g, firstResultId(g))
        assertEquals("pending", r.json["status"]!!.jsonPrimitive.content)
        assertTrue(backend.queue.isEmpty())
        val req = engine.host.value.requests.single()
        assertEquals("Alpha", req.title)
        assertTrue(engine.approve(req.id))
        assertEquals(1, backend.queue.size)
        assertTrue(engine.host.value.requests.isEmpty())
    }

    @Test fun declinedRequestsNeverReachTheQueue() {
        engine.updateSettings { it.copy(approveFirst = true) }
        val g = join()
        add(g, firstResultId(g))
        engine.decline(engine.host.value.requests.single().id)
        assertTrue(backend.queue.isEmpty() && engine.host.value.requests.isEmpty())
    }

    @Test fun votesToSkipReachTheThreshold() {
        engine.updateSettings { it.copy(skipMode = SkipMode.TWO) }
        val a = join("A"); val b = join("B"); join("C")
        assertEquals(false, call(base + "api/skip", "POST", a, "{}").json["skipped"]!!.jsonPrimitive.content.toBoolean())
        // The same guest voting twice counts once.
        call(base + "api/skip", "POST", a, "{}")
        assertEquals(0, backend.skipped)
        val r = call(base + "api/skip", "POST", b, "{}")
        assertEquals("true", r.json["skipped"]!!.jsonPrimitive.content)
        assertEquals(1, backend.skipped)
    }

    @Test fun skipVotingOffRefusesVotes() {
        val g = join()
        assertEquals(403, call(base + "api/skip", "POST", g, "{}").code)
    }

    @Test fun partyFillsUp() {
        val full = PartyEngine("0123456789abcdef0123456789abcdef", backend, maxGuests = 2, clock = { now })
        runBlocking {
            suspend fun joinNew(i: Int) = full.handle(PartyRoute.Api("join"), "POST", null, "10.0.0.$i", emptyMap(),
                Json.parseToJsonElement("""{"name":"g$i"}""").jsonObject).status
            assertEquals(200, joinNew(1)); assertEquals(200, joinNew(2)); assertEquals(403, joinNew(3))
        }
    }

    @Test fun searchIsRateLimited() {
        val g = join()
        val codes = (1..62).map { call(base + "api/search?q=alp", guest = g).code }
        assertTrue(codes.count { it == 429 } >= 1)
        assertNotNull(codes.firstOrNull { it == 200 })
    }
}
