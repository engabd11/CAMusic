package com.engabd.sendpin.local

import android.content.Context
import com.engabd.sendpin.ma.MaItem

/**
 * Favourites in the Downloads library, kept on this phone.
 *
 * Every other library asks its server what is favourited. Downloads has no server, so
 * it had no heart and no Favourites category, and it was the one library without them.
 * This is the record those need: a set of `mediaType:itemId` keys, using the same ids
 * [DownloadsIndex] hands out, so a favourite still matches its item every time the
 * index is rebuilt.
 *
 * SharedPreferences rather than a Room table: it is one small set, read whole, and a
 * table would cost a schema migration for nothing a set cannot do. Not cached here
 * either, so [DownloadManager] and [DownloadsSource] can each hold an instance and
 * still agree; SharedPreferences already keeps the set in memory.
 *
 * Nothing is pruned when a file is deleted. Reads only ever mark items that exist, so
 * a stale key is invisible, and a track downloaded again comes back still favourited.
 */
class DownloadedFavourites(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun keys(): Set<String> = prefs.getStringSet(KEY, null).orEmpty()

    fun isFavourite(item: MaItem): Boolean = key(item.mediaType, item.itemId) in keys()

    fun set(item: MaItem, favourite: Boolean) = set(item.mediaType, item.itemId, favourite)

    fun set(mediaType: String, itemId: String, favourite: Boolean) {
        // A copy: the set SharedPreferences hands back must not be changed in place.
        val next = keys().toMutableSet()
        val changed = if (favourite) next.add(key(mediaType, itemId)) else next.remove(key(mediaType, itemId))
        if (changed) prefs.edit().putStringSet(KEY, next).apply()
    }

    /** [item] with its heart filled in from this store. */
    fun mark(item: MaItem): MaItem {
        val fav = isFavourite(item)
        return if (fav == item.favorite) item else item.copy(favorite = fav)
    }

    fun mark(items: List<MaItem>): List<MaItem> {
        val all = keys()
        if (all.isEmpty() && items.none { it.favorite }) return items
        return items.map { item ->
            val fav = key(item.mediaType, item.itemId) in all
            if (fav == item.favorite) item else item.copy(favorite = fav)
        }
    }

    fun clear() = prefs.edit().remove(KEY).apply()

    companion object {
        private const val PREFS = "downloaded_favourites"
        private const val KEY = "keys"

        fun key(mediaType: String, itemId: String) = "$mediaType:$itemId"
    }
}
