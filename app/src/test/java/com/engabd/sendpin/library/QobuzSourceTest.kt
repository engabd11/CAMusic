package com.engabd.sendpin.library

import com.engabd.sendpin.qobuz.QobuzClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The source is a thin mapping onto [QobuzClient] (whose parsers carry the real
 * fixture tests), so the pins here are the contract with the rest of the app: stream
 * urls are *scheme uris* the player resolves at open time — never a real file url,
 * which would be expired before the track started — and the capability set is honest
 * about what Qobuz's API can actually answer.
 */
class QobuzSourceTest {

    @Test
    fun `stream urls are scheme uris, not file urls`() {
        val url = QobuzSource(QobuzClient()).streamUrl("4812793")
        assertEquals("qobuz://track/4812793", url)
        assertFalse(url.startsWith("http"), "a signed https url in the queue dies before playback")
    }

    @Test
    fun `the capability set is honest`() {
        val caps = QobuzSource(QobuzClient()).capabilities
        assertTrue(Capability.SEARCH in caps)
        assertTrue(Capability.FAVORITES in caps)
        assertTrue(Capability.PLAYLIST_READ in caps)
        assertTrue(Capability.METADATA in caps)
        // Qobuz serves what it serves: there is no original file to download, no
        // play history to show, no lyrics on the wire. Offering them would render
        // shelves a user can never fill.
        assertFalse(Capability.DOWNLOAD in caps)
        assertFalse(Capability.HISTORY in caps)
        assertFalse(Capability.LYRICS in caps)
    }

    @Test
    fun `the provider tag matches the client's`() {
        assertEquals(QobuzClient.PROVIDER, QobuzSource(QobuzClient()).providerId)
    }
}
