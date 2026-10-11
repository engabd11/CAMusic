package com.engabd.sendpin.car

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What the car says about a folder: its items, or why there are none. */
class CarLoadTest {

    @Test
    fun `items need no message`() {
        val one = com.engabd.sendpin.ma.MaItem(itemId = "1", provider = "subsonic", name = "Song", uri = null, mediaType = "track", subtitle = null, image = null, duration = null)
        assertNull(CarLoad.message(CarLoad.Items(listOf(one)), "Navidrome", "Albums"))
    }

    @Test
    fun `a truly empty folder says so`() {
        assertEquals("Nothing here yet" to "Albums is empty", CarLoad.message(CarLoad.Items(emptyList()), "Navidrome", "Albums"))
    }

    @Test
    fun `an unreachable library is not called empty`() {
        val (title, subtitle) = CarLoad.message(CarLoad.Unreachable, "Navidrome", "Albums")!!
        assertEquals("Couldn't reach Navidrome", title)
        assertEquals("Check the connection, then go back and try again", subtitle)
    }

    @Test
    fun `a slow library says it was slow`() {
        assertEquals("Navidrome took too long", CarLoad.message(CarLoad.TimedOut, "Navidrome", null)!!.first)
        assertEquals("Couldn't reach your library", CarLoad.message(CarLoad.Unreachable, null, null)!!.first)
    }

    @Test
    fun `one server plus downloads flattens to the server`() {
        val downloads = "__downloads__"
        assertEquals("nav", CarBrowseOptions.flattenTarget(listOf("nav", downloads), { it }, downloads))
        assertEquals("nav", CarBrowseOptions.flattenTarget(listOf("nav"), { it }, downloads))
        assertEquals(downloads, CarBrowseOptions.flattenTarget(listOf(downloads), { it }, downloads))
        assertNull(CarBrowseOptions.flattenTarget(listOf("nav", "jf", downloads), { it }, downloads))
        assertNull(CarBrowseOptions.flattenTarget(listOf("nav", "jf"), { it }, downloads))
    }
}
