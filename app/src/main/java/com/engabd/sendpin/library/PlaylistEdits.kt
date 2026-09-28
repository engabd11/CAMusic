package com.engabd.sendpin.library

/**
 * The two list edits a playlist has, as pure functions — shared by the servers that
 * rewrite a whole playlist to reorder it, the stores the app keeps, and the screen's
 * own immediate update, so all of them agree on what "move 3 to 0" means.
 */
object PlaylistEdits {

    /** [list] with the element at [from] moved to [to]; out-of-range is a no-op. */
    fun <T> moved(list: List<T>, from: Int, to: Int): List<T> {
        if (from !in list.indices || to !in list.indices || from == to) return list
        val out = list.toMutableList()
        out.add(to, out.removeAt(from))
        return out
    }

    /** [list] without the elements at [positions]; out-of-range positions are ignored. */
    fun <T> removed(list: List<T>, positions: Collection<Int>): List<T> {
        val drop = positions.toSet()
        return list.filterIndexed { i, _ -> i !in drop }
    }
}
