package com.engabd.sendpin.library

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PagingTest {

    private fun server(total: Int): suspend (Int, Int) -> List<Int> = { offset, limit ->
        (offset until minOf(offset + limit, total)).toList()
    }

    @Test
    fun `a list longer than one page comes back whole`() = runTest {
        assertEquals((0 until 250).toList(), fetchAllPages(200, fetch = server(250)))
    }

    @Test
    fun `an exact multiple of the page size ends on the empty page`() = runTest {
        var calls = 0
        val all = fetchAllPages(100) { o, l -> calls++; server(300)(o, l) }
        assertEquals(300, all.size)
        assertEquals(4, calls)
    }

    @Test
    fun `a short first page is one request`() = runTest {
        var calls = 0
        assertEquals(12, fetchAllPages(200) { o, l -> calls++; server(12)(o, l) }.size)
        assertEquals(1, calls)
    }

    @Test
    fun `a server that ignores the offset is stopped by the cap`() = runTest {
        val all = fetchAllPages(100, cap = 1_000) { _, _ -> List(100) { it } }
        assertEquals(1_000, all.size)
    }
}
