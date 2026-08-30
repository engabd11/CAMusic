package com.engabd.sendpin.ma

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "Continue listening" is fed by two Music Assistant commands that overlap, and the
 * whole point of [ContinueListening] is that the overlap is resolved once, for both
 * shelves, rather than twice and differently.
 */
class ContinueListeningTest {

    private fun item(id: String, type: String, provider: String = "library") =
        MaItem(id, provider, "Name $id", "uri://$id", type, null, null, null)

    @Test
    fun `songs join the podcasts instead of only appearing under Recently played`() {
        // The report this exists for: a library of music showed a "Continue listening"
        // shelf with nothing but podcasts in it.
        val split = ContinueListening.split(
            inProgress = listOf(item("ep1", "podcast_episode")),
            recentlyPlayed = listOf(item("t1", "track"), item("al1", "album")),
        )
        assertEquals(listOf("ep1", "t1"), split.continueListening.map { it.itemId })
    }

    @Test
    fun `unfinished items lead, however recently a song played`() {
        val split = ContinueListening.split(
            inProgress = listOf(item("ch1", "chapter")),
            recentlyPlayed = listOf(item("t1", "track")),
        )
        assertEquals("ch1", split.continueListening.first().itemId)
    }

    @Test
    fun `a song promoted to Continue listening is not repeated under Recently played`() {
        val split = ContinueListening.split(
            inProgress = emptyList(),
            recentlyPlayed = listOf(item("t1", "track"), item("al1", "album"), item("t2", "track")),
        )
        assertEquals(listOf("t1", "t2"), split.continueListening.map { it.itemId })
        assertEquals(listOf("al1"), split.recentlyPlayed.map { it.itemId })
    }

    @Test
    fun `the places you listened stay under Recently played`() {
        // The second shelf is only worth keeping if it is a different question, so
        // everything that is a container rather than a song has to survive the split.
        val containers = listOf(
            item("al1", "album"), item("ar1", "artist"),
            item("pl1", "playlist"), item("r1", "radio"),
        )
        val split = ContinueListening.split(emptyList(), containers)
        assertEquals(containers.map { it.itemId }, split.recentlyPlayed.map { it.itemId })
        assertTrue(split.continueListening.isEmpty())
    }

    @Test
    fun `an episode that is both in progress and last played is listed once`() {
        val split = ContinueListening.split(
            inProgress = listOf(item("ep1", "podcast_episode")),
            recentlyPlayed = listOf(item("ep1", "podcast_episode"), item("t1", "track")),
        )
        assertEquals(listOf("ep1", "t1"), split.continueListening.map { it.itemId })
    }

    @Test
    fun `a finished episode is not promoted just for having played recently`() {
        // Only `in_progress_items` knows whether an episode is unfinished. Pulling
        // episodes out of the recently-played list as well would put a *finished* one
        // at the top of the page under a heading that says otherwise.
        val split = ContinueListening.split(
            inProgress = emptyList(),
            recentlyPlayed = listOf(item("ep9", "podcast_episode")),
        )
        assertTrue(split.continueListening.isEmpty())
        assertEquals(listOf("ep9"), split.recentlyPlayed.map { it.itemId })
    }

    @Test
    fun `the same id from two providers is two items`() {
        val split = ContinueListening.split(
            inProgress = listOf(item("1", "podcast_episode", provider = "spotify")),
            recentlyPlayed = listOf(item("1", "track", provider = "library")),
        )
        assertEquals(2, split.continueListening.size)
    }
}
