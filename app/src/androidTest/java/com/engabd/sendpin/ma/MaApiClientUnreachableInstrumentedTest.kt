package com.engabd.sendpin.ma

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An unreachable Music Assistant is an error, not an empty answer.
 *
 * `sendCommand` used to return null when the socket never came up, and every caller
 * parses null as "nothing there" — so a server that was down looked like a library
 * with nothing in it. Instrumented because the client logs and reads SystemClock,
 * which the plain JVM tests here have no stubs for.
 */
@RunWith(AndroidJUnit4::class)
class MaApiClientUnreachableInstrumentedTest {

    @Test
    fun a_command_to_an_unreachable_server_throws_a_transport_error() = runBlocking {
        val client = MaApiClient()
        // Port 9 (discard) on loopback: nothing listens, the connect is refused.
        client.connect("http://127.0.0.1:9")
        try {
            client.sendCommand("players/all")
            fail("an unreachable server answered as if it had nothing")
        } catch (e: MaApiException) {
            assertTrue("not flagged as transport: ${e.message}", e.isTransport)
        } finally {
            client.disconnect()
        }
    }
}
