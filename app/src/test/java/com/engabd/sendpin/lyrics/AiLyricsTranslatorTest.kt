package com.engabd.sendpin.lyrics

import com.engabd.sendpin.crash.LogRedactor
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient

class AiLyricsTranslatorTest {
    private val server = MockWebServer()
    private val dir = File.createTempFile("ai-lyrics", "").let { it.delete(); it.mkdirs(); it }

    @AfterTest fun tearDown() {
        server.close()
        dir.deleteRecursively()
    }

    private fun translator(readMs: Long = 5_000) = AiLyricsTranslator(OkHttpClient(), readMs)

    private fun config(path: String = "/v1", key: String = "", model: String = "gemma3:12b") =
        AiLyricsTranslator.Config(server.url(path).toString(), model, key)

    private fun reply(content: String) = MockResponse.Builder().body(
        buildString {
            append("""{"choices":[{"message":{"role":"assistant","content":""")
            append(Json.encodeToString(kotlinx.serialization.serializer<String>(), content))
            append("}}]}")
        },
    ).build()

    private val song = listOf("Hola", "", "Adios")

    private fun run(
        t: AiLyricsTranslator = translator(),
        c: AiLyricsTranslator.Config = config(),
        lines: List<String> = song,
        key: String = "k1",
    ) = runBlocking { t.translate(c, lines, "English", dir, key) }

    // ── Request shape ─────────────────────────────────────────────────────

    @Test
    fun `the request goes to chat completions with the model, numbered lines and the language`() {
        server.start()
        server.enqueue(reply("""{"lines":["Hello","Goodbye"],"about":"A farewell."}"""))
        val r = run() as AiLyricsTranslator.Result.Done
        assertEquals(listOf("Hello", null, "Goodbye"), r.lines)
        assertEquals("A farewell.", r.about)

        val req = server.takeRequest()
        assertEquals("/v1/chat/completions", req.url.encodedPath)
        assertEquals("POST", req.method)
        val body = Json.parseToJsonElement(req.body!!.utf8()) as JsonObject
        assertEquals("gemma3:12b", body["model"]!!.jsonPrimitive.content)
        assertEquals("json_object", ((body["response_format"] as JsonObject)["type"])!!.jsonPrimitive.content)
        val msgs = body["messages"] as JsonArray
        assertEquals("system", (msgs[0] as JsonObject)["role"]!!.jsonPrimitive.content)
        assertTrue((msgs[0] as JsonObject)["content"]!!.jsonPrimitive.content.contains("English"))
        // Blank lines are not sent; the others are numbered from 1.
        assertEquals("1: Hola\n2: Adios", (msgs[1] as JsonObject)["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a trailing slash on the address makes no difference`() {
        server.start()
        server.enqueue(reply("""{"lines":["a","b"]}"""))
        run(c = config("/v1/"))
        assertEquals("/v1/chat/completions", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `the Authorization header is sent only when there is a key`() {
        server.start()
        server.enqueue(reply("""{"lines":["a","b"]}"""))
        server.enqueue(reply("""{"lines":["a","b"]}"""))
        run(c = config(key = "sekret"), key = "one")
        assertEquals("Bearer sekret", server.takeRequest().headers["Authorization"])
        run(c = config(key = ""), key = "two")
        assertNull(server.takeRequest().headers["Authorization"])
    }

    // ── Parsing ───────────────────────────────────────────────────────────

    @Test
    fun `fenced JSON is read`() {
        val p = AiLyricsTranslator.parse("```json\n{\"lines\":[\"x\",\"y\"],\"about\":\"Hi\"}\n```")!!
        assertEquals(listOf("x", "y"), p.lines)
        assertEquals("Hi", p.about)
    }

    @Test
    fun `prose around the JSON is ignored`() {
        val p = AiLyricsTranslator.parse("Sure! Here you go:\n{\"lines\":[\"x\"]}\nHope that helps {really}")
        // The trailing brace pair widens the slice and breaks it: the parser says so, not guesses.
        assertNull(p)
        val ok = AiLyricsTranslator.parse("Sure! Here you go:\n{\"lines\":[\"x\"]}\nHope that helps.")!!
        assertEquals(listOf("x"), ok.lines)
    }

    @Test
    fun `same true means the song is already in the target language`() {
        server.start()
        server.enqueue(reply("""{"same": true}"""))
        assertEquals(AiLyricsTranslator.Result.SameLanguage, run())
    }

    @Test
    fun `garbage is a failure with words, not a crash`() {
        server.start()
        server.enqueue(reply("I cannot help with that."))
        val r = run() as AiLyricsTranslator.Result.Failed
        assertTrue(r.reason.contains("not in a form"))
    }

    @Test
    fun `a line count a little off is padded or trimmed and not cached`() {
        server.start()
        server.enqueue(reply("""{"lines":["Hello"]}"""))
        val r = run() as AiLyricsTranslator.Result.Done
        assertEquals(listOf("Hello", null, null), r.lines)
        assertTrue(dir.list()!!.isEmpty())
    }

    @Test
    fun `a line count badly off fails`() {
        server.start()
        val many = (1..40).map { "line $it" }
        server.enqueue(reply("""{"lines":["only","two"]}"""))
        val r = run(lines = many) as AiLyricsTranslator.Result.Failed
        assertTrue(r.reason.contains("2 lines for a song of 40"))
    }

    @Test
    fun `an all blank song asks nothing`() {
        // The server is never started: any request would fail the test.
        val r = run(c = AiLyricsTranslator.Config("http://127.0.0.1:1/v1", "m"), lines = listOf("", " ")) as AiLyricsTranslator.Result.Done
        assertEquals(listOf(null, null), r.lines)
    }

    // ── Retry, errors ─────────────────────────────────────────────────────

    @Test
    fun `a 400 retries once without response_format`() {
        server.start()
        server.enqueue(MockResponse.Builder().code(400).body("""{"error":{"message":"unsupported"}}""").build())
        server.enqueue(reply("""{"lines":["a","b"]}"""))
        assertTrue(run() is AiLyricsTranslator.Result.Done)
        val first = Json.parseToJsonElement(server.takeRequest().body!!.utf8()) as JsonObject
        val second = Json.parseToJsonElement(server.takeRequest().body!!.utf8()) as JsonObject
        assertTrue("response_format" in first)
        assertFalse("response_format" in second)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a refused key, a missing model and a busy service each say so`() {
        server.start()
        server.enqueue(MockResponse.Builder().code(401).build())
        server.enqueue(MockResponse.Builder().code(404).build())
        server.enqueue(MockResponse.Builder().code(429).build())
        server.enqueue(MockResponse.Builder().code(503).build())
        assertTrue((run(key = "a") as AiLyricsTranslator.Result.Failed).reason.contains("refused the key"))
        assertTrue((run(key = "b") as AiLyricsTranslator.Result.Failed).reason.contains("wasn't found"))
        assertTrue((run(key = "c") as AiLyricsTranslator.Result.Failed).reason.contains("Try again later"))
        assertTrue((run(key = "d") as AiLyricsTranslator.Result.Failed).reason.contains("503"))
    }

    @Test
    fun `a server that never answers times out with a plain message`() {
        server.start()
        server.enqueue(MockResponse.Builder().headersDelay(5, TimeUnit.SECONDS).build())
        val r = run(t = translator(readMs = 300)) as AiLyricsTranslator.Result.Failed
        assertTrue(r.reason.contains("took too long"))
    }

    @Test
    fun `an address nothing listens on says it could not be reached, without the key`() {
        server.start()
        val c = config(key = "topsecret")
        server.close()
        val r = run(c = c) as AiLyricsTranslator.Result.Failed
        assertTrue(r.reason.contains("Couldn't reach"))
        assertFalse(r.reason.contains("topsecret"))
    }

    @Test
    fun `Test reports success and failure`() {
        server.start()
        server.enqueue(reply("ready"))
        server.enqueue(MockResponse.Builder().code(401).build())
        val ok = runBlocking { translator().test(config()) }
        assertTrue(ok.ok)
        assertTrue(ok.message.startsWith("Connected"))
        val bad = runBlocking { translator().test(config()) }
        assertFalse(bad.ok)
        assertTrue(bad.message.contains("refused the key"))
    }

    // ── Cache ─────────────────────────────────────────────────────────────

    @Test
    fun `a finished translation is cached and not asked for twice`() {
        server.start()
        server.enqueue(reply("""{"lines":["Hello","Goodbye"],"about":"A farewell."}"""))
        run()
        val again = run() as AiLyricsTranslator.Result.Done
        assertEquals("A farewell.", again.about)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `the cache key includes the model, the language and the address`() {
        val t = translator()
        val base = t.cacheFile(dir, "song", "English", AiLyricsTranslator.Config("http://a/v1", "m1"), song)
        assertNotEquals(base, t.cacheFile(dir, "song", "English", AiLyricsTranslator.Config("http://a/v1", "m2"), song))
        assertNotEquals(base, t.cacheFile(dir, "song", "French", AiLyricsTranslator.Config("http://a/v1", "m1"), song))
        assertNotEquals(base, t.cacheFile(dir, "song", "English", AiLyricsTranslator.Config("http://b/v1", "m1"), song))
        assertNotEquals(base, t.cacheFile(dir, "song", "English", AiLyricsTranslator.Config("http://a/v1", "m1"), song + "x"))
        assertEquals(base, t.cacheFile(dir, "song", "English", AiLyricsTranslator.Config("http://a/v1", "m1"), song))
    }

    // ── Words ─────────────────────────────────────────────────────────────

    @Test
    fun `privacy words follow the address`() {
        assertTrue(AiLyricsTranslator.privacyNote("http://192.168.1.10:11434/v1").contains("stay at home"))
        assertTrue(AiLyricsTranslator.privacyNote("https://openrouter.ai/api/v1").contains("openrouter.ai"))
    }

    @Test
    fun `an address with no scheme is read as http, and blank is unusable`() {
        assertTrue(AiLyricsTranslator.Config("192.168.1.10:11434/v1", "m").usable)
        assertFalse(AiLyricsTranslator.Config("", "m").usable)
        assertFalse(AiLyricsTranslator.Config("http://x/v1", " ").usable)
    }

    @Test
    fun `the log redactor masks a bearer key`() {
        assertEquals("Authorization: Bearer ***", LogRedactor.scrub("Authorization: Bearer sk-abc123XYZ"))
    }
}
