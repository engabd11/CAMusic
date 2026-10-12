package com.engabd.sendpin.library

import com.engabd.sendpin.ma.MaItem

/**
 * Folders built from where each file sits, for a library that has files but no
 * folder listing of its own (this phone's music, which MediaStore lists flat).
 *
 * Paths are relative and slash-separated, like MediaStore's `RELATIVE_PATH`
 * ("Music/Albums/Coastal Drive/"); the trailing slash and any leading one are
 * ignored. Pure, so the shape of a library's folders is tested rather than guessed.
 */
object FolderTree {

    /** A path without leading or trailing slashes: "Music/Albums". "" is the top. */
    fun clean(path: String?): String = path.orEmpty().trim().trim('/')

    /**
     * The folders and tracks directly inside [path], from [entries] of (folder, track).
     * Folders come back as their full paths, sorted by name; tracks in disc, track
     * number and name order, the way an album folder is meant to play.
     */
    fun level(path: String, entries: List<Pair<String, MaItem>>): Pair<List<String>, List<MaItem>> {
        val here = clean(path)
        val prefix = if (here.isEmpty()) "" else "$here/"
        val folders = sortedSetOf<String>(String.CASE_INSENSITIVE_ORDER)
        val tracks = mutableListOf<MaItem>()
        for ((rawFolder, track) in entries) {
            val folder = clean(rawFolder)
            when {
                folder == here -> tracks += track
                here.isEmpty() || folder.startsWith(prefix) -> {
                    val rest = folder.removePrefix(prefix)
                    if (rest.isNotEmpty()) folders += prefix + rest.substringBefore('/')
                }
            }
        }
        return folders.toList() to tracks.sortedWith(PLAY_ORDER)
    }

    /**
     * Where browsing should start: the top, stepped down past any folder that holds
     * nothing but one other folder ("Music", then "Music/Albums"), so the first screen
     * is the first one with a choice on it.
     */
    fun start(entries: List<Pair<String, MaItem>>): String {
        var at = ""
        while (true) {
            val (folders, tracks) = level(at, entries)
            if (tracks.isNotEmpty() || folders.size != 1) return at
            at = folders.single()
        }
    }

    /** Every track under [path], however deep, folder by folder in name order. */
    fun under(path: String, entries: List<Pair<String, MaItem>>): List<MaItem> {
        val here = clean(path)
        val prefix = if (here.isEmpty()) "" else "$here/"
        return entries
            .filter { (folder, _) -> clean(folder).let { it == here || here.isEmpty() || it.startsWith(prefix) } }
            .groupBy({ clean(it.first) }, { it.second })
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
            .values
            .flatMap { it.sortedWith(PLAY_ORDER) }
    }

    /** The last part of a path, for a folder's name. */
    fun name(path: String): String = clean(path).substringAfterLast('/').ifEmpty { "Folders" }

    private val PLAY_ORDER = compareBy<MaItem>({ it.discNumber ?: 0 }, { it.trackNumber ?: Int.MAX_VALUE }, { it.name.lowercase() })
}
