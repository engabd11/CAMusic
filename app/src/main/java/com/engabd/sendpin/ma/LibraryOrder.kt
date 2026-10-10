package com.engabd.sendpin.ma

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.random.Random

/**
 * How a library category (Albums, Artists, Songs, Playlists, Genres, Recently added)
 * is ordered and narrowed on screen. Pure and applied to the list the server already
 * sent, so it costs no requests and works the same on every library.
 *
 * [Sort.DEFAULT] is the server's own order, which is what the list always showed.
 */
@Serializable
data class LibraryOrder(
    val sort: Sort = Sort.DEFAULT,
    val descending: Boolean = false,
    val favouritesOnly: Boolean = false,
    val downloadedOnly: Boolean = false,
    val genre: String? = null,
    /** The first year of a decade: 1990 for the nineties. */
    val decade: Int? = null,
) {
    enum class Sort(val label: String) {
        DEFAULT("As the library sends it"),
        NAME("Name"),
        ARTIST("Artist"),
        YEAR("Year"),
        RANDOM("Random"),
    }

    val isDefault: Boolean get() = this == LibraryOrder()
    val filtering: Boolean get() = favouritesOnly || downloadedOnly || genre != null || decade != null

    /** What the data in a list supports, so nothing is offered that would do nothing. */
    data class Options(
        val sorts: List<Sort>,
        val genres: List<String>,
        val decades: List<Int>,
        val canFavourites: Boolean,
        val canDownloaded: Boolean,
    )

    companion object {
        /** Categories of one kind of thing, where an order means something. */
        val SORTABLE = setOf("albums", "artists", "tracks", "playlists", "genres", "newest")

        /** More genres than this is a tag cloud, not a filter. */
        private const val MAX_GENRES = 150

        fun options(items: List<MaItem>, isFavourite: (MaItem) -> Boolean, isDownloaded: (MaItem) -> Boolean): Options {
            val byArtist = items.count { it.mediaType == "album" || it.mediaType == "track" } * 2 > items.size &&
                items.any { !it.subtitle.isNullOrBlank() }
            val years = items.mapNotNull { it.year?.takeIf { y -> y > 0 } }
            return Options(
                sorts = buildList {
                    add(Sort.DEFAULT)
                    add(Sort.NAME)
                    if (byArtist) add(Sort.ARTIST)
                    if (years.isNotEmpty()) add(Sort.YEAR)
                    add(Sort.RANDOM)
                },
                genres = items.flatMap { it.genres }.map { it.trim() }.filter { it.isNotEmpty() }
                    .distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
                    .let { if (it.size > MAX_GENRES) emptyList() else it },
                decades = years.map { it / 10 * 10 }.distinct().sorted(),
                canFavourites = items.any(isFavourite),
                canDownloaded = items.any(isDownloaded),
            )
        }

        /**
         * [items] filtered and sorted by [order]. [seed] fixes a random order, so the
         * list does not reshuffle every time the screen redraws.
         */
        fun apply(
            items: List<MaItem>,
            order: LibraryOrder,
            isFavourite: (MaItem) -> Boolean,
            isDownloaded: (MaItem) -> Boolean,
            seed: Long = 0L,
        ): List<MaItem> {
            if (order.isDefault) return items
            val kept = items.filter { item ->
                (!order.favouritesOnly || isFavourite(item)) &&
                    (!order.downloadedOnly || isDownloaded(item)) &&
                    (order.genre == null || item.genres.any { it.trim().equals(order.genre, ignoreCase = true) }) &&
                    (order.decade == null || item.year?.let { it / 10 * 10 } == order.decade)
            }
            val sorted = when (order.sort) {
                Sort.DEFAULT -> kept
                Sort.NAME -> kept.sortedWith(compareBy<MaItem> { nameKey(it.name) }.thenBy { it.name })
                Sort.ARTIST -> kept.sortedWith(
                    compareBy<MaItem> { nameKey(it.subtitle.orEmpty()) }
                        .thenBy { it.year ?: Int.MAX_VALUE }
                        .thenBy { nameKey(it.name) },
                )
                // Undated last whichever way round, rather than all at the top of a
                // newest-first list.
                Sort.YEAR -> {
                    val (dated, undated) = kept.partition { (it.year ?: 0) > 0 }
                    val byYear = dated.sortedWith(compareBy<MaItem> { it.year }.thenBy { nameKey(it.name) })
                    return (if (order.descending) byYear.reversed() else byYear) + undated
                }
                Sort.RANDOM -> return kept.shuffled(Random(seed))
            }
            return if (order.descending) sorted.reversed() else sorted
        }

        /** "The Beatles" files under B, and case and accents do not split a letter. */
        internal fun nameKey(name: String): String {
            val lower = java.text.Normalizer.normalize(name.trim().lowercase(), java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
            val bare = ARTICLES.firstOrNull { lower.startsWith(it) && lower.length > it.length }?.let { lower.removePrefix(it) } ?: lower
            return bare.trimStart { !it.isLetterOrDigit() }
        }

        private val ARTICLES = listOf("the ", "a ", "an ")

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        fun encode(orders: Map<String, LibraryOrder>): String = json.encodeToString(orders)

        fun decode(raw: String): Map<String, LibraryOrder> =
            runCatching { json.decodeFromString<Map<String, LibraryOrder>>(raw) }.getOrDefault(emptyMap())
    }
}
