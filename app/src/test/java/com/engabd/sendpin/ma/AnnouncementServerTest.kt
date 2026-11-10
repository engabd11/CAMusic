package com.engabd.sendpin.ma

import com.engabd.sendpin.ma.Announcer.LocalAddress
import java.io.File
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
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
        assertFalse(server.fetched.isCompleted)
        assertEquals("http://127.0.0.1:${server.listeningPort}/$token/announce1.aac", server.url)
        repeat(2) {
            val (code, body) = get("/$token/announce1.aac")
            assertEquals(200, code)
            assertContentEquals(bytes, body)
        }
        assertTrue(server.fetched.isCompleted)
    }

    @Test fun anythingElseIsNotFound() {
        listOf(
            "/", "/$token", "/$token/", "/$token/other.aac", "/wrong/announce1.aac",
            "/${token.uppercase()}/announce1.aac", "/$token/../announce1.aac", "/announce1.aac",
        ).forEach { assertEquals(404, get(it).first, it) }
        // A wrong path is not MA fetching the clip.
        assertFalse(server.fetched.isCompleted)
    }

    @Test fun stoppingToTryAnotherAddressKeepsTheClip() {
        server.stopKeepingClip()
        assertTrue(file.exists())
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

    @Test fun theRouteToALoopbackServerIsLoopback() {
        assertEquals("127.0.0.1", Announcer.routeToward(InetAddress.getByName("127.0.0.1") as Inet4Address)?.ip)
        assertEquals("127.0.0.1", Announcer.maAddress("http://127.0.0.1:8095")?.hostAddress)
        assertEquals(null, Announcer.maAddress("not a url"))
    }

    private val wifi = LocalAddress("192.168.0.179", 24)
    private val vpn = LocalAddress("100.67.113.26", 32)

    @Test fun aVpnDefaultRouteDoesNotHideWifiFromALanServer() {
        assertEquals(listOf("192.168.0.179", "100.67.113.26"), Announcer.orderCandidates(listOf(wifi), vpn, "192.168.0.20"))
    }

    @Test fun aServerReachedOverTheVpnWithNoSharedSubnetStillGetsBoth() {
        assertEquals(listOf("192.168.0.179", "100.67.113.26"), Announcer.orderCandidates(listOf(wifi), vpn, "100.101.5.9"))
    }

    @Test fun theAddressInTheServersSubnetGoesFirst() {
        val wideVpn = LocalAddress("100.67.113.26", 10)
        assertEquals(listOf("100.67.113.26", "192.168.0.179"), Announcer.orderCandidates(listOf(wifi), wideVpn, "100.101.5.9"))
    }

    @Test fun oneAddressIsOfferedOnce() {
        assertEquals(listOf("192.168.0.179"), Announcer.orderCandidates(listOf(wifi), wifi, "192.168.0.20"))
        assertEquals(listOf("100.67.113.26"), Announcer.orderCandidates(emptyList(), vpn, null))
    }

    @Test fun noAddressIsNoCandidates() {
        assertEquals(emptyList(), Announcer.orderCandidates(emptyList(), null, "192.168.0.20"))
    }
}
