package com.engabd.sendpin.mpd

import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MPD playlist writes, and artist art, against a fake MPD on a real socket.
 *
 * The fake speaks just enough of the protocol: a greeting, then one command (or one
 * command list) per connection, answered by [reply] and closed — which is how
 * [MpdClient] talks, one socket per command.
 */
class MpdPlaylistWriteTest {

    private val server = ServerSocket(0)
    /** Every command received, command lists expanded line by line. */
    private val received = Collections.synchronizedList(mutableListOf<String>())
    @Volatile private var reply: (String) -> List<String> = { emptyList() }

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                socket.use { s ->
                    val out = PrintWriter(s.getOutputStream(), true)
                    val input = BufferedReader(InputStreamReader(s.getInputStream()))
                    out.println("OK MPD 0.24.0")
                    val first = input.readLine() ?: return@use
                    val lines = mutableListOf(first)
                    if (first == "command_list_begin") {
                        while (true) {
                            val l = input.readLine() ?: break
                            lines += l
                            if (l == "command_list_end") break
                        }
                    }
                    received += lines.filter { it != "command_list_begin" && it != "command_list_end" }
                    reply(first).forEach(out::println)
                    out.println("OK")
                }
            }
        }
    }

    @AfterTest fun tearDown() = server.close()

    private fun client() = MpdClient("127.0.0.1:${server.localPort}")

    @Test
    fun `a playlist created with songs is filled in order, in one command list`() = runBlocking {
        client().createPlaylist("Road trip", listOf("a/1.flac", "b/2.flac"))
        assertEquals(
            listOf("playlistadd \"Road trip\" \"a/1.flac\"", "playlistadd \"Road trip\" \"b/2.flac\""),
            received.toList(),
        )
    }

    @Test
    fun `an empty playlist is made by adding a song and removing it again`() = runBlocking {
        reply = { cmd -> if (cmd.startsWith("lsinfo")) listOf("directory: Artist", "file: Artist/song.flac") else emptyList() }
        client().createPlaylist("Later", emptyList())
        assertEquals(
            listOf("lsinfo \"\"", "playlistadd \"Later\" \"Artist/song.flac\"", "playlistdelete \"Later\" 0"),
            received.toList(),
        )
    }

    @Test
    fun `adding and deleting address the playlist by name`() = runBlocking {
        client().addToPlaylist("Mix", listOf("x.flac"))
        client().deletePlaylist("Mix")
        assertEquals(listOf("playlistadd \"Mix\" \"x.flac\"", "rm \"Mix\""), received.toList())
    }

    @Test
    fun `an artist resolves to one of their songs for its picture`() = runBlocking {
        reply = { cmd -> if (cmd.startsWith("find artist")) listOf("file: Adele/21/01.flac") else emptyList() }
        assertEquals("Adele/21/01.flac", client().anySongBy("Adele"))
        assertTrue(received.first().endsWith("window 0:1"), "asked for everything: ${received.first()}")
    }

    @Test
    fun `artist art ids round-trip and are never mistaken for a file or an album`() {
        val url = MpdArt.artistUrl("AC/DC")!!
        val id = MpdArt.idFrom(url)!!
        assertEquals("AC/DC", MpdArt.artistFrom(id))
        assertTrue(!MpdArt.isAlbumId(id))
        assertNull(MpdArt.artistFrom("AC_DC/Back In Black/01.flac"))
        assertNull(MpdArt.artistFrom("Back In Black\u0000AC/DC"))
    }
}
