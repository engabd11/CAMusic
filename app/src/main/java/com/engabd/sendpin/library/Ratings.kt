package com.engabd.sendpin.library

import com.engabd.sendpin.ma.MaItem
import java.util.concurrent.ConcurrentHashMap

/**
 * Star ratings, for whichever library the item belongs to.
 *
 * Asked from the long-press sheet, which every list in the app shares — so it works
 * off the process-wide active source rather than any one screen's ViewModel, and a
 * rating is offered wherever the item came from the library that can take it.
 *
 * Ratings made this session are remembered here and answered first. A list row
 * carries the rating it was loaded with, and an album re-opened after being rated
 * would otherwise show the old number until the list reloaded.
 */
object Ratings {

    private val made = ConcurrentHashMap<String, Int>()

    private fun key(item: MaItem) = "${item.provider}|${item.mediaType}|${item.itemId}"

    /** [source] if it can rate [item], else null. */
    fun rater(source: MusicSource?, item: MaItem): MusicSource? = source?.takeIf {
        item.itemId.isNotBlank() &&
            it.providerId == item.provider &&
            Capability.RATING in it.capabilities &&
            it.ratable(item)
    }

    /** 0 = unrated. Never throws: an unreadable rating is shown as none. */
    suspend fun current(source: MusicSource, item: MaItem): Int =
        made[key(item)] ?: (runCatching { source.rating(item) }.getOrNull() ?: 0)

    /** Throws what the server said, so the caller can show it. */
    suspend fun set(source: MusicSource, item: MaItem, stars: Int) {
        val s = stars.coerceIn(0, 5)
        source.setRating(item, s)
        made[key(item)] = s
    }
}
