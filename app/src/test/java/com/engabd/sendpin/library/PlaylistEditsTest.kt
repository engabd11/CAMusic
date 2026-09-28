package com.engabd.sendpin.library

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaylistEditsTest {

    private val list = listOf("a", "b", "c", "d")

    @Test
    fun `moving down and up lands where asked`() {
        assertEquals(listOf("b", "c", "a", "d"), PlaylistEdits.moved(list, 0, 2))
        assertEquals(listOf("d", "a", "b", "c"), PlaylistEdits.moved(list, 3, 0))
        assertEquals(listOf("a", "c", "b", "d"), PlaylistEdits.moved(list, 1, 2))
    }

    @Test
    fun `out-of-range or same-place moves change nothing`() {
        assertEquals(list, PlaylistEdits.moved(list, 1, 1))
        assertEquals(list, PlaylistEdits.moved(list, -1, 2))
        assertEquals(list, PlaylistEdits.moved(list, 0, 9))
    }

    @Test
    fun `removing drops exactly those positions, duplicates included`() {
        assertEquals(listOf("a", "d"), PlaylistEdits.removed(list, listOf(1, 2)))
        assertEquals(listOf("a", "b", "a"), PlaylistEdits.removed(listOf("a", "a", "b", "a"), listOf(0)))
        assertEquals(list, PlaylistEdits.removed(list, listOf(7)))
    }
}
