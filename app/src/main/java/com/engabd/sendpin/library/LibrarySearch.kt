package com.engabd.sendpin.library

import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ma.MaSearchResults
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One query against several libraries at once — the phone's "Search all libraries"
 * and Android Auto's voice search both come through here.
 *
 * Android Auto had this and the phone did not: its search reached every configured
 * library in parallel, while the phone's search box only ever asked the active one,
 * so a song on the other server was findable from the car and not from the app. The
 * fan-out now lives in one place.
 */
object LibrarySearch {

    /** One library's answer. */
    data class Hit(val config: ServerConfig, val results: MaSearchResults)

    /**
     * Ask every one of [configs] at once, each bounded by [timeoutMs] so one slow or
     * unreachable server costs its own answer and never the others'. A library that
     * throws, times out or has nothing to say (null) is simply absent from the result.
     */
    suspend fun fanOut(
        configs: List<ServerConfig>,
        timeoutMs: Long,
        searchOne: suspend (ServerConfig) -> MaSearchResults?,
    ): List<Hit> = coroutineScope {
        configs.map { config ->
            async {
                runCatching { withTimeoutOrNull(timeoutMs) { searchOne(config) } }
                    .getOrNull()
                    ?.let { Hit(config, it) }
            }
        }.awaitAll().filterNotNull()
    }

    /**
     * One result list from several libraries' answers.
     *
     * The active library's hits come first in every section, unlabelled, exactly as a
     * single-library search shows them. Every other library's items follow, tagged
     * with [MaItem.serverId] — which is what tells the play and open paths that the
     * item belongs to a library other than the one the screen is browsing — and with
     * the library's name in [MaItem.serverLabel], which the row shows after the
     * subtitle so two copies of one album on two servers can be told apart.
     */
    fun merge(activeId: String?, hits: List<Hit>): MaSearchResults {
        val ordered = hits.sortedBy { if (it.config.id == activeId) 0 else 1 }
        fun tag(hit: Hit, items: List<MaItem>): List<MaItem> =
            if (hit.config.id == activeId) items
            // Labelled for display only: the subtitle itself stays the artist, since it is
            // what a played track's metadata, notification and scrobble are built from.
            else items.map { item -> item.copy(serverId = hit.config.id, serverLabel = hit.config.displayName) }
        return MaSearchResults(
            artists = ordered.flatMap { tag(it, it.results.artists) },
            albums = ordered.flatMap { tag(it, it.results.albums) },
            tracks = ordered.flatMap { tag(it, it.results.tracks) },
            playlists = ordered.flatMap { tag(it, it.results.playlists) },
        )
    }
}
