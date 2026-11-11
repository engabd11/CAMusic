package com.engabd.sendpin.plex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The heart on Plex is the `userRating` at 10. See [PlexClient.favouriteRating]. */
class PlexFavouriteTest {

    @Test
    fun `loving sets ten, whatever was there`() {
        assertEquals("10", PlexClient.favouriteRating(null, starred = true))
        assertEquals("10", PlexClient.favouriteRating(6.0, starred = true))
        assertNull(PlexClient.favouriteRating(10.0, starred = true), "already loved: nothing to send")
    }

    @Test
    fun `un-loving clears only a ten and leaves a hand-set rating alone`() {
        assertEquals("-1", PlexClient.favouriteRating(10.0, starred = false))
        assertNull(PlexClient.favouriteRating(6.0, starred = false), "three stars are not a favourite to clear")
        assertNull(PlexClient.favouriteRating(9.0, starred = false))
        assertNull(PlexClient.favouriteRating(null, starred = false))
    }

    @Test
    fun `a ten reads as a favourite and a six does not`() {
        val client = PlexClient("http://nas.local:32400", token = "tok", librarySectionKey = "1")
        fun track(rating: String) = client.item(
            Json.parseToJsonElement("""{"ratingKey":"1","type":"track","title":"T","userRating":$rating}""").jsonObject,
        )!!
        assertTrue(track("10").favorite)
        assertFalse(track("6").favorite)
        assertEquals(3, track("6").userRating)
    }
}
