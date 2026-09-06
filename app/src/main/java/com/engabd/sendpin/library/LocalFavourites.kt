package com.engabd.sendpin.library

import android.content.Context
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ma.MaSearchResults
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Favourites the **app** keeps, for a library that has none of its own.
 *
 * MPD is the one that needs this. It is a complete music library by every other
 * measure — artists, albums, genres, playlists, per-track codec and bit depth — and it
 * has no concept of a starred item at all. (Stickers are the nearest thing, and they
 * are an optional server-side database that has to be enabled in `mpd.conf`; a feature
 * that silently does nothing on a default install is worse than one the app owns
 * outright.) So the Starred category, the heart on every row, and the two favourites
 * shelves on the library's front page were all simply absent there, and the browse
 * experience was poorer than Navidrome's or Jellyfin's for a reason that had nothing
 * to do with what MPD can store.
 *
 * ## What is stored, and why it is the whole item
 *
 * Ids alone would be smaller and useless: `favorites()` has to hand back renderable
 * rows, and resolving a starred artist or album back into one costs a round trip per
 * entry against a server that answers over a single socket. The rows are small — a
 * name, a subtitle, an image url — and the list is bounded by what one person has
 * bothered to star.
 *
 * SharedPreferences and not DataStore, following [com.engabd.sendpin.game.GameRecords]:
 * this is read synchronously from inside a [MusicSource] call, which is a suspend
 * function on an IO dispatcher rather than a flow collector, and a one-shot read of a
 * few KB of JSON is the right shape for that.
 *
 * Keyed by provider, so two MPD servers configured at once do not share a starred list
 * and a future provider that needs the same treatment can have one without colliding.
 *
 * Every access swallows its own failure. A corrupt store should cost the library its
 * hearts, never its music.
 */
object LocalFavourites {

    private const val PREFS = "local_favourites"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(Entry.serializer())

    /**
     * One starred thing, flattened to what a row needs.
     *
     * A trimmed [MaItem] rather than the model itself: `MaItem` is not serializable,
     * carries a dozen fields no starred row reads, and is free to grow more. Widening
     * it for a store would tie the two together for no gain.
     */
    @Serializable
    data class Entry(
        val itemId: String,
        val mediaType: String,
        val name: String,
        val subtitle: String? = null,
        val image: String? = null,
        val uri: String? = null,
        val album: String? = null,
        val duration: Int? = null,
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Everything starred under [provider], oldest first. */
    fun entries(context: Context, provider: String): List<Entry> = runCatching {
        val raw = prefs(context).getString(provider, null) ?: return emptyList()
        json.decodeFromString(serializer, raw)
    }.getOrDefault(emptyList())

    /** Just the ids, for the cheap "is this one starred" test browse results need. */
    fun ids(context: Context, provider: String): Set<String> =
        entries(context, provider).mapTo(HashSet()) { it.itemId }

    /**
     * Star or unstar [item].
     *
     * Idempotent in both directions, and a star of something already starred moves it
     * to the end rather than duplicating it — the list is also the order the Starred
     * page shows, and re-starring is not a reason to change where something sits.
     */
    fun set(context: Context, provider: String, item: MaItem, starred: Boolean) {
        runCatching {
            val next = updated(entries(context, provider), item, starred)
            prefs(context).edit()
                .putString(provider, json.encodeToString(serializer, next))
                .apply()
        }
    }

    /**
     * [entries] as the four typed lists the Starred category and the front-page
     * shelves both read.
     *
     * Everything that is not an artist, an album or a playlist counts as a track,
     * because those are the four buckets [MaSearchResults] has and a starred podcast
     * is better shown as a row than dropped.
     */
    fun results(context: Context, provider: String): MaSearchResults =
        results(entries(context, provider), provider)

    /** [results] over a list already in hand. Split out so it can be tested. */
    internal fun results(entries: List<Entry>, provider: String): MaSearchResults {
        val items = entries.map { it.toItem(provider) }
        return MaSearchResults(
            artists = items.filter { it.mediaType == "artist" },
            albums = items.filter { it.mediaType == "album" },
            playlists = items.filter { it.mediaType == "playlist" },
            tracks = items.filter { it.mediaType !in CONTAINERS },
        )
    }

    /**
     * [items] with `favorite` set from the store.
     *
     * Every browse result a source with app-side favourites returns goes through this,
     * and that is what makes the rest of the app work unchanged: `LibraryViewModel`
     * seeds its heart state from `MaItem.favorite` and *clears* it for anything that
     * comes back false, so a source that starred items and then reported them as
     * unstarred would un-heart them again on the next scroll.
     */
    fun mark(context: Context, provider: String, items: List<MaItem>): List<MaItem> =
        if (items.isEmpty()) items else mark(items, ids(context, provider))

    /** [mark] against a set already in hand. Split out so it can be tested. */
    internal fun mark(items: List<MaItem>, starred: Set<String>): List<MaItem> {
        if (items.isEmpty() || starred.isEmpty()) return items
        return items.map { if (it.itemId in starred) it.copy(favorite = true) else it }
    }

    /**
     * [set] over a list already in hand, returning the list to store.
     *
     * The whole of the add/remove rule, split out so it can be tested: unstarring
     * removes, starring appends, and starring something already starred neither
     * duplicates it nor leaves two entries behind.
     */
    internal fun updated(entries: List<Entry>, item: MaItem, starred: Boolean): List<Entry> {
        val without = entries.filterNot { it.itemId == item.itemId }
        return if (!starred) without else without + item.toEntry()
    }

    private val CONTAINERS = setOf("artist", "album", "playlist")

    private fun MaItem.toEntry() = Entry(
        itemId = itemId,
        mediaType = mediaType,
        name = name,
        subtitle = subtitle,
        image = image,
        uri = uri,
        album = album,
        duration = duration,
    )

    private fun Entry.toItem(provider: String) = MaItem(
        itemId = itemId,
        provider = provider,
        name = name,
        uri = uri,
        mediaType = mediaType,
        subtitle = subtitle,
        image = image,
        duration = duration,
        favorite = true,
        album = album,
    )
}
