package com.engabd.sendpin.ma

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MA 2.10 split `music/recommendations` in two: the listing names the rows and carries
 * no items, and `music/recommendations/items` fills one. The app still read
 * `folder["items"]`, which 2.10 does not send — so the "For you" shelf was empty on
 * every current server, and with it went the sixteen rows the built-in recommendations
 * provider offers ("Random artists", "Forgotten Albums", "Never / Rarely Played" …).
 *
 * Both shapes have to parse: the 2.10 listing, and the older inline one.
 */
class MaRecommendationRowsTest {

    private fun rows(json: String) =
        MaParse.recommendationRows(Json.parseToJsonElement(json), null)

    private val listing2_10 = """
        [
          {"item_id":"random_artists","provider":"recommendations","name":"Random artists",
           "icon":"mdi-account-music","enabled_by_default":false,
           "uri":"library://folder/random_artists","supports_provider_filter":true},
          {"item_id":"forgotten_albums","provider":"recommendations","name":"Forgotten Albums",
           "icon":"mdi-timer-sand","enabled_by_default":false},
          {"item_id":"never_played_tracks","provider":"recommendations",
           "name":"Never / Rarely Played","icon":"mdi-sleep","enabled_by_default":false}
        ]
    """.trimIndent()

    @Test
    fun `a 2_10 listing parses even though it carries no items`() {
        assertEquals(3, rows(listing2_10).size)
    }

    @Test
    fun `the row keeps the name and icon the server gave it`() {
        val row = rows(listing2_10).first()
        assertEquals("Random artists", row.name)
        assertEquals("mdi-account-music", row.icon)
        assertEquals("recommendations", row.provider)
        assertTrue(row.items.isEmpty())
    }

    @Test
    fun `enabled_by_default is carried through, false and all`() {
        assertTrue(rows(listing2_10).none { it.enabledByDefault })
    }

    @Test
    fun `a row is enabled unless the server says otherwise`() {
        val row = rows("""[{"item_id":"x","provider":"p","name":"X"}]""").single()
        assertTrue(row.enabledByDefault)
    }

    @Test
    fun `an older server's inline items are still read`() {
        val inline = """
            [{"item_id":"recently_played","provider":"recommendations","name":"Recently played",
              "items":[{"item_id":"1","uri":"library://album/1","media_type":"album","name":"A"}]}]
        """.trimIndent()
        assertEquals(listOf("1"), rows(inline).single().items.map { it.itemId })
    }

    @Test
    fun `a row with no item_id is dropped - there is nothing to fetch it with`() {
        assertTrue(rows("""[{"provider":"p","name":"Nameless"}]""").isEmpty())
    }

    @Test
    fun `a row with no name falls back to a readable form of its id`() {
        val row = rows("""[{"item_id":"forgotten_albums","provider":"p"}]""").single()
        assertEquals("forgotten albums", row.name)
    }

    @Test
    fun `a non-array answer is no rows rather than a crash`() {
        assertTrue(rows("""{"error":"nope"}""").isEmpty())
    }
}
