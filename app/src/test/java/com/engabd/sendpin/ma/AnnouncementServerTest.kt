package com.engabd.sendpin.ma

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnnouncementServerTest {
    private val token = "0123456789abcdef0123456789abcdef"
    private val bytes = ByteArray(4096) { it.toByte() }
    private lateinit var file: File
    private var now = 1_000_000L
    private lateinit var server: AnnouncementServer

    @BeforeTest fun setUp() {
        file = File(createTempDirectory().toFile(), "announce1.aac").apply { writeBytes(bytes) }
        server = AnnouncementServer("127.0.0.1", file, "audio/aac", lifetimeMs = 60_000, token = token, clock = { now })
        server.begin()
    }

    @AfterTest fun tearDown() { server.stop() }

    private fun get(path: String): Pair<Int, ByteArray> {
        val c = URL("http://127.0.0.1:${server.listeningPort}$path").openConnection() as HttpURLConnection
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
        if (code == 200) assertEquals("audio/aac", c.contentType)
        return code to body
    }

    @Test fun theRightTokenServesTheClipAsOftenAsAsked() {
        assertEquals("http://127.0.0.1:${server.listeningPort}/$token/announce1.aac", server.url)
        repeat(2) {
            val (code, body) = get("/$token/announce1.aac")
            assertEquals(200, code)
            assertContentEquals(bytes, body)
        }
    }

    @Test fun anythingElseIsNotFound() {
        listOf(
            "/", "/$token", "/$token/", "/$token/other.aac", "/wrong/announce1.aac",
            "/${token.uppercase()}/announce1.aac", "/$token/../announce1.aac", "/announce1.aac",
        ).forEach { assertEquals(404, get(it).first, it) }
    }

    @Test fun expiredClipIsNotFound() {
        now += 60_000
        assertEquals(404, get("/$token/announce1.aac").first)
    }

    @Test fun stopsItselfAndDeletesTheClip() {
        val short = File(file.parentFile, "announce2.wav").apply { writeBytes(bytes) }
        val s = AnnouncementServer("127.0.0.1", short, "audio/wav", lifetimeMs = 200, token = token)
        s.begin()
        assertTrue(s.isAlive)
        val deadline = System.currentTimeMillis() + 5_000
        while ((s.isAlive || short.exists()) && System.currentTimeMillis() < deadline) Thread.sleep(25)
        assertFalse(s.isAlive)
        assertFalse(short.exists())
    }

    @Test fun summaryNamesWhoRefused() {
        assertEquals(Announcer.Status("Announced on Kitchen, Lounge."), Announcer.summary(listOf("Kitchen" to null, "Lounge" to null)))
        val mixed = Announcer.summary(listOf("Kitchen" to null, "Lounge" to IllegalStateException("Player is off.")))
        assertTrue(mixed.failed)
        assertEquals("Couldn't announce on Lounge: Player is off. Played on Kitchen.", mixed.text)
    }

    @Test fun addressTowardALoopbackServerIsLoopback() {
        assertEquals("127.0.0.1", Announcer.addressToward("http://127.0.0.1:8095"))
        assertEquals(null, Announcer.addressToward("not a url"))
    }
}
