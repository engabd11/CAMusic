package com.engabd.sendpin.mpd

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [MpdClient.idleEvents] against a stand-in MPD on localhost speaking the real
 * protocol: greeting, `idle` parked until something changes, `changed:` lines, `OK`.
 */
class MpdIdleTest {

    /** Serves one client: answers each `idle` with the next batch of [changes]. */
    private fun fakeMpd(changes: List<List<String>>, seenCommands: MutableList<String>): Int {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            server.use { srv ->
                srv.accept().use { s ->
                    val input = BufferedReader(InputStreamReader(s.getInputStream()))
                    val out = s.getOutputStream()
                    out.write("OK MPD 0.24.0\n".toByteArray()); out.flush()
                    for (batch in changes) {
                        val cmd = input.readLine() ?: return@use
                        synchronized(seenCommands) { seenCommands += cmd }
                        val reply = batch.joinToString("") { "changed: $it\n" } + "OK\n"
                        out.write(reply.toByteArray()); out.flush()
                    }
                    // Park the next idle until the client goes away.
                    input.readLine()
                }
            }
        }
        return server.localPort
    }

    @Test
    fun `each idle answer becomes one set of changed subsystems`() = runBlocking {
        val seen = mutableListOf<String>()
        val port = fakeMpd(listOf(listOf("player"), listOf("mixer", "options")), seen)
        val events = withTimeout(5_000) {
            MpdClient("127.0.0.1:$port").idleEvents().take(2).toList()
        }
        assertEquals(listOf(setOf("player"), setOf("mixer", "options")), events)
        synchronized(seen) {
            assertTrue(seen.all { it == "idle ${MpdClient.IDLE_SUBSYSTEMS}" }, "sent: $seen")
        }
    }

    @Test
    fun `a server that hangs up ends the flow with an error`() = runBlocking {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            server.use { srv -> srv.accept().use { s -> s.getOutputStream().write("OK MPD 0.24.0\n".toByteArray()) } }
        }
        val result = runCatching {
            withTimeout(5_000) { MpdClient("127.0.0.1:${server.localPort}").idleEvents().toList() }
        }
        assertTrue(result.exceptionOrNull() is MpdException, "got ${result.exceptionOrNull()}")
    }
}
