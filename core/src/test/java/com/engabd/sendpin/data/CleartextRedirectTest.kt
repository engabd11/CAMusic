package com.engabd.sendpin.data

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [LanOnlyCleartext] has to see redirect hops, not only the request that was made.
 *
 * OkHttp follows redirects below the application-interceptor layer, so a guard
 * registered only there approved the first (local or https) request and never saw the
 * cleartext hop the server sent it on to. The fake server is on loopback, which is
 * local; `music.example.com` is made to resolve to it too, so the hop *looks* public
 * to the guard without the test needing the internet.
 */
class CleartextRedirectTest {

    private val server = MockWebServer()

    @AfterTest fun tearDown() = server.close()

    private val publicName = "music.example.com"

    private fun client() = Http.base.newBuilder()
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                if (hostname == publicName) listOf(InetAddress.getLoopbackAddress()) else Dns.SYSTEM.lookup(hostname)
        })
        .build()

    @Test
    fun `a redirect to cleartext on a public host is refused`() {
        server.start()
        server.enqueue(
            MockResponse.Builder().code(302)
                .setHeader("Location", "http://$publicName:${server.port}/stream.flac?api_key=secret")
                .build(),
        )
        server.enqueue(MockResponse.Builder().body("audio").build())
        val call = client().newCall(Request.Builder().url(server.url("/Audio/1/universal")).build())
        val e = assertFailsWith<IOException> { call.execute().close() }
        assertTrue(e.message!!.contains(publicName), "refused for the wrong reason: ${e.message}")
        assertEquals(1, server.requestCount, "the cleartext hop reached the server")
    }

    @Test
    fun `a redirect that stays on the local network is followed`() {
        server.start()
        server.enqueue(MockResponse.Builder().code(302).setHeader("Location", server.url("/static.flac").toString()).build())
        server.enqueue(MockResponse.Builder().body("audio").build())
        client().newCall(Request.Builder().url(server.url("/Audio/1/universal")).build()).execute().use {
            assertEquals("audio", it.body.string())
        }
    }
}
