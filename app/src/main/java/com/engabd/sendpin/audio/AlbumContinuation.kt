package com.engabd.sendpin.audio

import com.engabd.sendpin.library.MusicSource
import com.engabd.sendpin.ma.MaItem

/**
 * The three questions carrying on through a library in order needs answering.
 *
 * A narrow interface rather than the whole of [MusicSource] because the walk is the
 * part with decisions in it — where an album ends, which record follows it, when to
 * give up — and none of that needs a server to be wrong. [LibraryAlbumWalk] is the
 * real one.
 */
interface AlbumWalk {
    /** An album's own item and its tracks, in play order. */
    suspend fun album(id: String): Pair<MaItem?, List<MaItem>>

    /** Every album by this artist, in the order the library lists them. */
    suspend fun artistAlbums(artistId: String): List<MaItem>

    /** The library's albums, in its own order, a page at a time. */
    suspend fun albumsPage(offset: Int, limit: Int): List<MaItem>
}

/** [AlbumWalk] over a connected library. */
class LibraryAlbumWalk(private val source: MusicSource) : AlbumWalk {
    override suspend fun album(id: String): Pair<MaItem?, List<MaItem>> = source.albumDetail(id)

    override suspend fun artistAlbums(artistId: String): List<MaItem> = source.artistDetail(artistId).second

    override suspend fun albumsPage(offset: Int, limit: Int): List<MaItem> = source.albums(offset, limit)
}

/**
 * What plays next when the queue runs out and the listener has *not* asked for
 * shuffle: the rest of this record, then the one after it, and so on.
 *
 * The counterpart to [LocalRadio], and the reason both exist. The radio's ladder is
 * a claim about similarity — the right answer when someone has said "keep going,
 * surprise me". Unshuffled play is the opposite request: a listener working through
 * an album expects the next track, and at the end of the album the next album, not a
 * genre-matched stranger. That was the one thing "keep the music going" could not
 * do, on any backend.
 *
 * Which record follows this one is asked of the artist first and the library second.
 * A discography is the ordering a listener means by "the next album" — the library's
 * alphabetical run is only the answer once the artist is finished, and it is fetched
 * lazily because most sessions never reach it.
 *
 * Falls back to nothing rather than to a guess: a seed with no album, an album the
 * library will not describe, an artist whose last record just ended and a library
 * walk that cannot place it all return empty, and the caller drops to the radio
 * ladder. Silence is the one answer that is never right, and it is not one of these.
 */
class AlbumContinuation(
    private val walk: AlbumWalk,
    /** How many albums one top-up may walk through before giving up. */
    private val maxAlbums: Int = 4,
    /** Page size for the library album list, and the cap on how much of it is held. */
    private val pageSize: Int = 200,
    private val albumLimit: Int = 2_000,
) {

    /**
     * The library's albums in order, fetched once and kept.
     *
     * Only touched when an artist's discography runs out, which most top-ups never
     * do — so the common case costs one album lookup and one artist lookup, and the
     * whole-library scan is paid for at most once a session.
     */
    private var libraryAlbums: List<MaItem>? = null

    /**
     * The next [count] tracks after [seed], reading forwards.
     *
     * [exclude] is what is already queued, for the same reason the radio takes it:
     * appending a track that is about to play anyway is the failure that reads as
     * "it played the same song twice".
     */
    suspend fun next(seed: MaItem?, count: Int, exclude: Set<String> = emptySet()): List<MaItem> {
        if (count <= 0) return emptyList()
        // A track's parentId is its album id. Without one there is no "rest of the
        // record" to speak of, and guessing by title would be worse than declining.
        val albumId = seed?.parentId?.takeIf { it.isNotBlank() } ?: return emptyList()
        val (album, tracks) = runCatching { walk.album(albumId) }.getOrNull() ?: return emptyList()

        // Keyed rather than a plain list, and filtered **as it fills** rather than at
        // the end. Filtering afterwards looks equivalent and is not: the loop below
        // stops once it has enough, so counting tracks that are about to be dropped
        // ends the walk early and returns a short batch — which on a record whose
        // first half is already queued is most of it.
        val picked = LinkedHashMap<String, MaItem>()
        fun offer(candidates: List<MaItem>) {
            for (t in candidates) {
                if (picked.size >= count) return
                val id = t.itemId
                if (id.isBlank() || id == seed.itemId || id in exclude || id in picked) continue
                picked[id] = t
            }
        }

        // Where the seed sits in its own album. Not found (a track that has moved, a
        // server that orders differently) means the record is treated as finished
        // rather than restarted from the top.
        val at = tracks.indexOfFirst { it.itemId == seed.itemId }
        if (at >= 0) offer(tracks.drop(at + 1))

        var current = album
        var walked = 0
        while (current != null && picked.size < count && walked < maxAlbums) {
            val following = nextAlbumAfter(current) ?: break
            offer(runCatching { walk.album(following.itemId).second }.getOrDefault(emptyList()))
            current = following
            walked++
        }

        return picked.values.toList()
    }

    /** The record after [album] — the artist's next, else the library's next. */
    private suspend fun nextAlbumAfter(album: MaItem): MaItem? {
        // An album's parentId is its *artist* id, which is what makes a discography
        // reachable from a track in one hop.
        album.parentId?.takeIf { it.isNotBlank() }?.let { artistId ->
            val discography = runCatching { walk.artistAlbums(artistId) }.getOrDefault(emptyList())
            val i = discography.indexOfFirst { it.itemId == album.itemId }
            if (i >= 0) discography.getOrNull(i + 1)?.let { return it }
        }
        val all = libraryAlbums()
        val i = all.indexOfFirst { it.itemId == album.itemId }
        // Not in the list at all means the walk cannot say what follows — a library
        // bigger than the cap, or an album outside it. Null, so the caller falls back
        // to the radio rather than restarting the library from A.
        return if (i >= 0) all.getOrNull(i + 1) else null
    }

    private suspend fun libraryAlbums(): List<MaItem> {
        libraryAlbums?.let { return it }
        val all = mutableListOf<MaItem>()
        val seen = mutableSetOf<String>()
        var offset = 0
        while (all.size < albumLimit) {
            val page = runCatching { walk.albumsPage(offset, pageSize) }.getOrDefault(emptyList())
            if (page.isEmpty()) break
            val fresh = page.filterNot { it.itemId in seen }
            fresh.forEach { seen += it.itemId }
            if (fresh.isEmpty()) break          // the server is repeating itself
            all += fresh
            if (page.size < pageSize) break
            offset += pageSize
        }
        return all.also { libraryAlbums = it }
    }

}
