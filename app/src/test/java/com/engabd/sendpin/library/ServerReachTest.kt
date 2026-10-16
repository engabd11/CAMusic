package com.engabd.sendpin.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Where the startup reachability check knocks, for the addresses people actually type. */
class ServerReachTest {

    @Test
    fun `an explicit port is used`() {
        assertEquals("192.168.0.10" to 4533, ServerReach.hostPort("http://192.168.0.10:4533"))
        assertEquals("nas.local" to 8096, ServerReach.hostPort("nas.local:8096"))
    }

    @Test
    fun `no port means the scheme's own`() {
        assertEquals("music.example.com" to 443, ServerReach.hostPort("https://music.example.com/"))
        assertEquals("music.example.com" to 80, ServerReach.hostPort("http://music.example.com/navidrome"))
        assertEquals("ma.local" to 443, ServerReach.hostPort("wss://ma.local/ws"))
    }

    @Test
    fun `an address it cannot read is left to the sign-in`() {
        assertNull(ServerReach.hostPort(""))
        assertNull(ServerReach.hostPort("smb://nas/share"))
    }
}
