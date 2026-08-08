package com.engabd.sendpin.ma

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * "For you" is the one shelf built from more than one answer — `music/recommendations`
 * returns folders ("recently played", "favourite artists", "random albums") that the
 * app flattens into a single row. The folders overlap by design, so the shelf listed
 * the same album two or three times.
 */
class MaDistinctItemsTest {

    private fun item(id: String, type: String = "album", provider: String = "library") =
        MaItem(id, provider, "Name", "uri", type, null, null, null)

    @Test
    fun `a repeat across folders is dropped`() {
        val flattened = listOf(item("5"), item("9"), item("5"))
        assertEquals(listOf("5", "9"), MaRepository.distinctItems(flattened).map { it.itemId })
    }

    @Test
    fun `the first copy is the one kept, so folder order still leads`() {
        val first = item("5").copy(name = "from recently played")
        val second = item("5").copy(name = "from random albums")
        assertEquals("from recently played", MaRepository.distinctItems(listOf(first, second)).single().name)
    }

    @Test
    fun `an album and a track sharing a library id are both kept`() {
        val both = listOf(item("5", type = "album"), item("5", type = "track"))
        assertEquals(2, MaRepository.distinctItems(both).size)
    }

    @Test
    fun `the same id from two providers is two items`() {
        val both = listOf(item("5", provider = "library"), item("5", provider = "spotify"))
        assertEquals(2, MaRepository.distinctItems(both).size)
    }
}
