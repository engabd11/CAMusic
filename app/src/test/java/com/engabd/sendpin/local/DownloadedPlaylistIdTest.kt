package com.engabd.sendpin.local

import com.engabd.sendpin.library.MusicSources
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The pure half of downloaded playlists: how one is identified, and how it reads.
 *
 * The DAO itself is not covered — it needs a real database and there is no
 * Robolectric in this project (see `DownloadMappersTest`'s own note). What is here is
 * the part that would be wrong silently: an id that collides puts one library's
 * playlist on top of another's, and nothing about that looks like a bug until two
 * servers are configured.
 */
class DownloadedPlaylistIdTest {

    @Test
    fun `the same playlist on the same server is the same id`() {
        assertEquals(
            DownloadsIndex.playlistId("navidrome", "42"),
            DownloadsIndex.playlistId("navidrome", "42"),
        )
    }

    @Test
    fun `two servers handing out the same playlist id do not collide`() {
        // The reason ids are namespaced at all. Subsonic and Jellyfin both number
        // playlists from their own sequences, so "3" on one is not "3" on the other —
        // and a shared key would silently merge two unrelated playlists.
        assertNotEquals(
            DownloadsIndex.playlistId("navidrome", "3"),
            DownloadsIndex.playlistId("jellyfin", "3"),
        )
    }

    @Test
    fun `a missing provider still yields a stable id`() {
        // Not expected, but it must not throw or collapse every unknown-provider
        // playlist into one another.
        assertEquals(
            DownloadsIndex.playlistId(null, "7"),
            DownloadsIndex.playlistId(null, "7"),
        )
        assertNotEquals(
            DownloadsIndex.playlistId(null, "7"),
            DownloadsIndex.playlistId(null, "8"),
        )
    }

    @Test
    fun `a playlist id cannot be mistaken for an album or artist id`() {
        // All three live in the same local id space and are told apart by prefix, so a
        // playlist named like an album must not produce an album's id.
        val playlist = DownloadsIndex.playlistId("navidrome", "abc")
        assertTrue(playlist.startsWith("dlplaylist:"))
        assertTrue(!playlist.startsWith("dlalbum:"))
        assertTrue(!playlist.startsWith("dlartist:"))
    }

    @Test
    fun `provider case does not fork the id`() {
        // A source that reports its provider with different casing between builds
        // would otherwise strand every playlist recorded under the old spelling.
        assertEquals(
            DownloadsIndex.playlistId("Navidrome", "9"),
            DownloadsIndex.playlistId("navidrome", "9"),
        )
    }

    @Test
    fun `a playlist item reads as a downloads-provider playlist`() {
        val item = DownloadsIndex.playlistItem("dlplaylist:navidrome|1", "Road trip", 12)
        assertEquals("playlist", item.mediaType)
        assertEquals(MusicSources.DOWNLOAD_PROVIDER, item.provider)
        assertEquals("Road trip", item.name)
        // Browsable *and* playable, which is what lets a car or a tile both open it
        // and play it. `playable` needs a non-null uri, so the id doubles as one.
        assertTrue(item.browsable)
        assertTrue(item.playable)
    }

    @Test
    fun `the subtitle counts songs and gets the plural right`() {
        // It reports what is on the phone, not what the server said the playlist held
        // — a half-finished download should read as half.
        assertEquals("12 songs", DownloadsIndex.playlistItem("p", "n", 12).subtitle)
        assertEquals("1 song", DownloadsIndex.playlistItem("p", "n", 1).subtitle)
        assertEquals("0 songs", DownloadsIndex.playlistItem("p", "n", 0).subtitle)
    }
}
