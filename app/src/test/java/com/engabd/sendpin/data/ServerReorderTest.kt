package com.engabd.sendpin.data

import com.engabd.sendpin.data.AppSettings.Companion.reorderServers
import com.engabd.sendpin.library.ServerConfig
import com.engabd.sendpin.library.ServerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * The order of the library list is a setting, so moving something in it has to behave
 * like one.
 *
 * The list is rendered twice — the Providers page in Settings and the switcher in the
 * Library tab both take it as they find it — so this is the one write that decides
 * both, and a move that quietly dropped or duplicated an entry would take a configured
 * server off the switcher.
 */
class ServerReorderTest {

    private fun list(vararg names: String) = names.map {
        ServerConfig(id = it, kind = ServerKind.NAVIDROME, label = it)
    }

    private fun ids(list: List<ServerConfig>) = list.map { it.id }

    @Test
    fun `moving up swaps with the entry above`() {
        assertEquals(
            listOf("a", "c", "b", "d"),
            ids(reorderServers(list("a", "b", "c", "d"), "c", up = true)),
        )
    }

    @Test
    fun `moving down swaps with the entry below`() {
        assertEquals(
            listOf("b", "a", "c", "d"),
            ids(reorderServers(list("a", "b", "c", "d"), "a", up = false)),
        )
    }

    /**
     * Both ends are a no-op rather than a wrap. The buttons are disabled there, but a
     * second tap can still be in flight when the first one lands.
     */
    @Test
    fun `the ends of the list have nowhere to go`() {
        val servers = list("a", "b", "c")
        assertSame(servers, reorderServers(servers, "a", up = true))
        assertSame(servers, reorderServers(servers, "c", up = false))
    }

    /** A server removed on another screen while this one was composed. */
    @Test
    fun `an unknown id leaves the list alone`() {
        val servers = list("a", "b")
        assertSame(servers, reorderServers(servers, "gone", up = true))
    }

    /** Nothing may be dropped, duplicated, or reordered except the one thing moved. */
    @Test
    fun `a move keeps every other entry in the same relative order`() {
        val moved = reorderServers(list("a", "b", "c", "d", "e"), "e", up = true)
        assertEquals(listOf("a", "b", "c", "e", "d"), ids(moved))
        assertEquals(5, moved.map { it.id }.toSet().size)
    }

    /** Walking one entry to the front takes exactly as many moves as it has neighbours. */
    @Test
    fun `repeated moves walk an entry to the front`() {
        var servers = list("a", "b", "c", "d")
        repeat(3) { servers = reorderServers(servers, "d", up = true) }
        assertEquals(listOf("d", "a", "b", "c"), ids(servers))
        assertSame(servers, reorderServers(servers, "d", up = true))
    }
}
