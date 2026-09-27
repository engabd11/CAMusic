package com.engabd.sendpin.service

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [RTreeReader] against trees laid out exactly as SQLite's `rtree.c` writes them.
 * The real file is checked on a device by `SpeedLimitLookupInstrumentedTest`.
 */
class RTreeReaderTest {

    private data class Cell(val id: Long, val x0: Float, val x1: Float, val y0: Float, val y1: Float)

    private fun node(depth: Int, cells: List<Cell>): ByteArray {
        val buf = ByteBuffer.allocate(4 + cells.size * RTreeReader.CELL).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(depth.toShort()).putShort(cells.size.toShort())
        for (c in cells) buf.putLong(c.id).putFloat(c.x0).putFloat(c.x1).putFloat(c.y0).putFloat(c.y1)
        return buf.array()
    }

    /**
     * Root (node 1, depth 1) over two leaves: node 2 holds zones 10 and 11 around
     * (145.0, -37.8); node 3 holds zone 20 far away.
     */
    private val tree = mapOf(
        1L to node(1, listOf(Cell(2, 144.9f, 145.1f, -37.9f, -37.7f), Cell(3, 146.0f, 146.2f, -38.2f, -38.0f))),
        2L to node(0, listOf(Cell(10, 144.95f, 145.0f, -37.85f, -37.8f), Cell(11, 145.05f, 145.1f, -37.75f, -37.7f))),
        3L to node(0, listOf(Cell(20, 146.05f, 146.1f, -38.1f, -38.05f))),
    )

    @Test
    fun `finds the leaf entries a box meets, and only those`() {
        val reads = mutableListOf<Long>()
        val reader = RTreeReader { n -> reads += n; tree[n] }
        assertEquals(listOf(10L), reader.search(144.97, 144.98, -37.83, -37.82))
        assertTrue(3L !in reads, "descended into a branch the box does not meet")
        assertEquals(setOf(10L, 11L), reader.search(144.9, 145.1, -37.9, -37.7).toSet())
        assertEquals(listOf(20L), reader.search(146.06, 146.07, -38.08, -38.07))
        assertEquals(emptyList(), reader.search(0.0, 1.0, 0.0, 1.0))
    }

    @Test
    fun `nodes are read once and then served from memory`() {
        val reads = mutableListOf<Long>()
        val reader = RTreeReader { n -> reads += n; tree[n] }
        repeat(5) { reader.search(144.97, 144.98, -37.83, -37.82) }
        assertEquals(listOf(1L, 2L), reads)
    }

    @Test
    fun `a damaged tree yields nothing rather than throwing`() {
        assertEquals(emptyList(), RTreeReader { null }.search(0.0, 1.0, 0.0, 1.0))
        assertEquals(emptyList(), RTreeReader { byteArrayOf(0, 1) }.search(0.0, 1.0, 0.0, 1.0))
        // A cell count larger than the blob holds stops at the last whole cell.
        val truncated = node(0, listOf(Cell(7, 0f, 1f, 0f, 1f))).also { it[3] = 9 }
        assertEquals(listOf(7L), RTreeReader { truncated }.search(0.0, 1.0, 0.0, 1.0))
    }

    @Test
    fun `a segment along the direction of travel costs nothing, one across it costs the most`() {
        // A north-south segment at Melbourne's latitude.
        fun penalty(h: Float) = SpeedLimitDatabase.headingPenaltyMeters(h, -37.80, 145.0, -37.79, 145.0)
        assertEquals(0.0, penalty(0f))
        assertEquals(0.0, penalty(180f), "roads are two-way: southbound is along it too")
        assertEquals(0.0, penalty(10f))
        assertEquals(SpeedLimitDatabase.HEADING_PENALTY_M, penalty(90f))
        assertEquals(SpeedLimitDatabase.HEADING_PENALTY_M, penalty(270f))
        val partial = penalty(30f)
        assertTrue(partial > 0.0 && partial < SpeedLimitDatabase.HEADING_PENALTY_M, "30 degrees off gave $partial")
        assertEquals(0.0, SpeedLimitDatabase.headingPenaltyMeters(null, -37.80, 145.0, -37.79, 145.0))
    }
}
