package com.engabd.sendpin.service

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Searches a SQLite R*Tree by reading its node table directly, without the `rtree`
 * module.
 *
 * **Android's platform SQLite is built without R*Tree.** `SELECT … FROM
 * speed_zones_rtree` fails on every phone with "no such module: rtree", and the
 * lookup that issued it caught the exception and answered "no limit here" — so
 * auto-detect never once found a speed zone on a device, and the alert fell back to
 * the manual limit everywhere. Set to highway speed, that read exactly like "it only
 * works on highways".
 *
 * The tree itself is fine: the module keeps it in an ordinary table,
 * `<name>_node(nodeno INTEGER PRIMARY KEY, data BLOB)`, which any SQLite can read.
 * The blob layout is documented in SQLite's `rtree.c` and fixed by the file format:
 *
 * - 2 bytes: the tree's depth (meaningful on the root, node 1, only);
 * - 2 bytes: the number of cells;
 * - per cell, 8 bytes of id (a row id on a leaf, a child node number above it)
 *   and then min/max per dimension as 32-bit floats — here `min_lon, max_lon,
 *   min_lat, max_lat`, the column order the table was declared with.
 *
 * All big-endian. The stored floats are rounded outward by SQLite, so a search that
 * compares against them can only return a superset of the true matches, never
 * miss one; the caller measures real distances afterwards anyway.
 *
 * @param readNode the blob for a node number, or null if there is no such node.
 */
internal class RTreeReader(private val readNode: (Long) -> ByteArray?) {

    /**
     * The upper levels are read on every lookup; keeping them avoids a query each.
     * Nodes are ~1.2 KB, so this is well under a megabyte.
     */
    private val cache = object : LinkedHashMap<Long, ByteArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>) = size > NODE_CACHE
    }

    private fun node(n: Long): ByteArray? = cache[n] ?: readNode(n)?.also { cache[n] = it }

    /** Row ids of every entry whose box intersects the query box. */
    @Synchronized
    fun search(minLon: Double, maxLon: Double, minLat: Double, maxLat: Double): List<Long> {
        val root = node(ROOT) ?: return emptyList()
        if (root.size < HEADER) return emptyList()
        val depth = ByteBuffer.wrap(root).order(ByteOrder.BIG_ENDIAN).getShort(0).toInt() and 0xFFFF
        val out = ArrayList<Long>()
        // (node, levels above the leaves). Explicit stack: the tree is shallow, but
        // a corrupt depth must not become a stack overflow.
        val stack = ArrayDeque<Pair<Long, Int>>()
        stack.addLast(ROOT to depth)
        var visited = 0
        while (stack.isNotEmpty()) {
            val (n, level) = stack.removeLast()
            if (++visited > MAX_NODES_PER_SEARCH) break
            val blob = if (n == ROOT) root else node(n) ?: continue
            val buf = ByteBuffer.wrap(blob).order(ByteOrder.BIG_ENDIAN)
            if (blob.size < HEADER) continue
            val cells = buf.getShort(2).toInt() and 0xFFFF
            for (i in 0 until cells) {
                val at = HEADER + i * CELL
                if (at + CELL > blob.size) break
                val id = buf.getLong(at)
                val x0 = buf.getFloat(at + 8)
                val x1 = buf.getFloat(at + 12)
                val y0 = buf.getFloat(at + 16)
                val y1 = buf.getFloat(at + 20)
                if (x0 <= maxLon && x1 >= minLon && y0 <= maxLat && y1 >= minLat) {
                    if (level == 0) out += id else stack.addLast(id to level - 1)
                }
            }
        }
        return out
    }

    companion object {
        private const val ROOT = 1L
        private const val HEADER = 4
        /** 8-byte id + four 4-byte floats, for a two-dimensional tree. */
        const val CELL = 24
        private const val NODE_CACHE = 512
        /** A bound on a single search's work, whatever the file says. */
        private const val MAX_NODES_PER_SEARCH = 4_096
    }
}
