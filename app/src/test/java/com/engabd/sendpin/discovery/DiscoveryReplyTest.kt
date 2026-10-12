package com.engabd.sendpin.discovery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The reply parsers behind the add-a-server picker, held to what servers send:
 * the Jellyfin beacon reply exactly as a live Jellyfin 10.x server answered it
 * (2026-10-12), and Plex's GDM reply as its protocol documents it.
 */
class DiscoveryReplyTest {

    @Test
    fun `a live Jellyfin beacon reply becomes a named server under the fork it answered`() {
        val reply = """{"Address":"http://192.168.0.156:8096","Id":"18149d384a8b45ad9d74260a55e9a5f1","Name":"Hogwarts","EndpointAddress":null}"""
        val found = MediaServerDiscovery.parseReply(reply, MediaServerDiscovery.Fork.JELLYFIN)
        assertEquals(MediaServerDiscovery.Found(MediaServerDiscovery.Fork.JELLYFIN, "Hogwarts", "http://192.168.0.156:8096"), found)
    }

    @Test
    fun `a beacon reply with no name falls back to its address, and a trailing slash is dropped`() {
        val found = MediaServerDiscovery.parseReply("""{"Address":"http://10.0.0.2:8096/","Name":""}""", MediaServerDiscovery.Fork.EMBY)
        assertEquals("http://10.0.0.2:8096", found?.url)
        assertEquals("http://10.0.0.2:8096", found?.name)
    }

    @Test
    fun `a beacon reply without a usable address is not offered`() {
        val fork = MediaServerDiscovery.Fork.JELLYFIN
        assertNull(MediaServerDiscovery.parseReply("""{"Name":"x"}""", fork))
        assertNull(MediaServerDiscovery.parseReply("""{"Address":"","Name":"x"}""", fork))
        assertNull(MediaServerDiscovery.parseReply("""{"Address":"192.168.0.2:8096","Name":"x"}""", fork))
        assertNull(MediaServerDiscovery.parseReply("who is JellyfinServer?", fork))
    }

    @Test
    fun `a GDM reply names a Plex server at the address it came from`() {
        val reply = "HTTP/1.0 200 OK\r\nContent-Type: plex/media-server\r\nResource-Identifier: abc123\r\n" +
            "Name: Living Room\r\nPort: 32400\r\nUpdated-At: 1700000000\r\nVersion: 1.41.0.8992\r\n\r\n"
        assertEquals(
            PlexGdmDiscovery.Found("Living Room", "http://192.168.0.20:32400"),
            PlexGdmDiscovery.parseReply(reply, "192.168.0.20"),
        )
    }

    @Test
    fun `GDM header names are matched without regard to case, and a nameless server shows its address`() {
        val reply = "HTTP/1.0 200 OK\ncontent-type: plex/media-server\nport: 32401\n"
        assertEquals(
            PlexGdmDiscovery.Found("http://10.1.1.1:32401", "http://10.1.1.1:32401"),
            PlexGdmDiscovery.parseReply(reply, "10.1.1.1"),
        )
    }

    @Test
    fun `GDM replies from players, errors and malformed bodies are not offered`() {
        assertNull(PlexGdmDiscovery.parseReply("HTTP/1.0 200 OK\r\nContent-Type: plex/media-player\r\nPort: 32500\r\n", "1.2.3.4"))
        assertNull(PlexGdmDiscovery.parseReply("HTTP/1.0 404 Not Found\r\nContent-Type: plex/media-server\r\nPort: 32400\r\n", "1.2.3.4"))
        assertNull(PlexGdmDiscovery.parseReply("HTTP/1.0 200 OK\r\nContent-Type: plex/media-server\r\nPort: x\r\n", "1.2.3.4"))
        assertNull(PlexGdmDiscovery.parseReply("HTTP/1.0 200 OK\r\nContent-Type: plex/media-server\r\n", "1.2.3.4"))
        assertNull(PlexGdmDiscovery.parseReply("", "1.2.3.4"))
    }
}
