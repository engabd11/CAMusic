package com.engabd.sendpin.ma

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The wrong-song bug, pinned.
 *
 * Between PR #97 and the fix these tests guard, tapping any track on an album screen
 * played the *first* one. The app sent every track as `media` and named the tapped one
 * as `start_item` — but Music Assistant's `MediaResolver._resolve_media_items`
 * dispatches `start_item` on the item's `media_type` and only threads it into the
 * container branches (PLAYLIST, ALBUM, GENRE, AUDIOBOOK). A TRACK falls straight
 * through and returns as a one-item list, so the parameter was never read and the queue
 * always began at index 0.
 *
 * [queueFrom] does the slicing on this side instead, where it cannot be ignored.
 */
class QueueSliceTest {

    private fun track(id: String, uri: String? = "library://track/$id") =
        MaItem(id, "library", "Track $id", uri, "track", null, null, null)

    private val album = listOf(track("1"), track("2"), track("3"), track("4"))

    @Test
    fun `tapping the third track starts the queue at the third track`() {
        val q = queueFrom(album, album[2])
        assertEquals("library://track/3", q.first())
    }

    @Test
    fun `the tracks after the tapped one come with it, in order`() {
        assertEquals(
            listOf("library://track/3", "library://track/4"),
            queueFrom(album, album[2]),
        )
    }

    @Test
    fun `earlier tracks are dropped, matching MA's own start_item semantics`() {
        // test_start_item_rotation.py: playing an album from a chosen track "normally
        // drops everything before that track".
        val q = queueFrom(album, album[2])
        assertTrue(q.none { it.endsWith("/1") || it.endsWith("/2") })
    }

    @Test
    fun `tapping the first track queues the whole album`() {
        assertEquals(4, queueFrom(album, album[0]).size)
    }

    @Test
    fun `tapping the last track queues only it`() {
        assertEquals(listOf("library://track/4"), queueFrom(album, album[3]))
    }

    @Test
    fun `duplicates are collapsed before the index is taken`() {
        // MA's album_tracks concatenates per provider mapping, so a two-provider album
        // arrives with every track twice. Slicing the raw list would start inside the
        // first copy and then replay the whole album.
        val doubled = album + album
        assertEquals(
            listOf("library://track/3", "library://track/4"),
            queueFrom(doubled, album[2]),
        )
    }

    @Test
    fun `a track with no uri is left out rather than breaking the slice`() {
        val withGap = listOf(track("1"), track("2", uri = null), track("3"))
        assertEquals(listOf("library://track/3"), queueFrom(withGap, withGap[2]))
    }

    @Test
    fun `no tapped track means the whole list, from the top`() {
        assertEquals(4, queueFrom(album, null).size)
    }

    @Test
    fun `a tapped track that is not in the list starts at the top rather than playing nothing`() {
        val stranger = track("99")
        assertEquals(4, queueFrom(album, stranger).size)
    }

    @Test
    fun `an empty list stays empty`() {
        assertTrue(queueFrom(emptyList(), null).isEmpty())
    }

    @Test
    fun `a list with no playable uris at all stays empty`() {
        assertTrue(queueFrom(listOf(track("1", uri = null)), null).isEmpty())
    }
}
