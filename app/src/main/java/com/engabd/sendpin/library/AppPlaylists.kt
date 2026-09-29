package com.engabd.sendpin.library

import com.engabd.sendpin.util.runCatchingCancellable
import android.content.Context
import com.engabd.sendpin.ma.MaItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Playlists kept by the app, for libraries whose server cannot store one.
 *
 * Plex's write API wants a machine identifier this app does not track; Qobuz, Tidal
 * and Spotify have write endpoints nobody has exercised here; foobar2000 and a folder
 * on the phone have no playlists at all. On every one of them "New playlist" either
 * did nothing or was missing — so a playlist is kept here instead, and the library
 * lists it beside the server's own. See [WithAppPlaylists], which is what makes it
 * look like any other playlist to the rest of the app.
 *
 * The same shape as [LocalFavourites], for the same reasons: SharedPreferences, read
 * synchronously from inside a source call; whole rows rather than ids, so a playlist
 * renders and plays without a round trip per track; and every access swallows its own
 * failure, because a corrupt store should cost the playlists, never the music.
 *
 * Keyed by library — provider *and* server — so two Plex servers do not share a list.
 */
object AppPlaylists {

    private const val PREFS = "app_playlists"

    /** Every id this store hands out starts with this, which is how a caller tells. */
    const val ID_PREFIX = "app-playlist:"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(Playlist.serializer())

    @Serializable
    data class Playlist(
        val id: String,
        val name: String,
        val createdAt: Long,
        val tracks: List<LocalFavourites.Entry> = emptyList(),
    )

    fun isAppPlaylist(id: String): Boolean = id.startsWith(ID_PREFIX)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(context: Context, library: String): List<Playlist> = runCatching {
        val raw = prefs(context).getString(library, null) ?: return emptyList()
        json.decodeFromString(serializer, raw)
    }.getOrDefault(emptyList())

    private fun save(context: Context, library: String, list: List<Playlist>) {
        runCatching {
            prefs(context).edit().putString(library, json.encodeToString(serializer, list)).apply()
        }
    }

    fun get(context: Context, library: String, id: String): Playlist? =
        all(context, library).firstOrNull { it.id == id }

    /** A new playlist holding [tracks], in order. Returns its id. */
    fun create(context: Context, library: String, name: String, tracks: List<MaItem>): String {
        val playlist = Playlist(
            id = ID_PREFIX + UUID.randomUUID(),
            name = name,
            createdAt = System.currentTimeMillis(),
            tracks = tracks.map(::entryOf),
        )
        save(context, library, all(context, library) + playlist)
        return playlist.id
    }

    /** Append [tracks] to [id], skipping any it already holds. */
    fun append(context: Context, library: String, id: String, tracks: List<MaItem>) {
        save(context, library, appended(all(context, library), id, tracks))
    }

    fun delete(context: Context, library: String, id: String) {
        save(context, library, all(context, library).filterNot { it.id == id })
    }

    /** Apply [change] to playlist [id], leaving the others as they are. */
    private fun update(context: Context, library: String, id: String, change: (Playlist) -> Playlist) {
        save(context, library, all(context, library).map { if (it.id == id) change(it) else it })
    }

    fun removeAt(context: Context, library: String, id: String, positions: List<Int>) =
        update(context, library, id) { it.copy(tracks = PlaylistEdits.removed(it.tracks, positions)) }

    fun move(context: Context, library: String, id: String, from: Int, to: Int) =
        update(context, library, id) { it.copy(tracks = PlaylistEdits.moved(it.tracks, from, to)) }

    fun rename(context: Context, library: String, id: String, name: String) =
        update(context, library, id) { it.copy(name = name) }

    // ── Pure, for tests ─────────────────────────────────────────────────────

    internal fun appended(list: List<Playlist>, id: String, tracks: List<MaItem>): List<Playlist> =
        list.map { p ->
            if (p.id != id) return@map p
            val known = p.tracks.mapTo(HashSet()) { it.itemId }
            p.copy(tracks = p.tracks + tracks.filter { known.add(it.itemId) }.map(::entryOf))
        }

    internal fun entryOf(item: MaItem) = LocalFavourites.Entry(
        itemId = item.itemId,
        mediaType = item.mediaType,
        name = item.name,
        subtitle = item.subtitle,
        image = item.image,
        uri = item.uri,
        album = item.album,
        duration = item.duration,
    )

    /** A stored track back as a playable row of [provider]. */
    internal fun itemOf(entry: LocalFavourites.Entry, provider: String) = MaItem(
        itemId = entry.itemId,
        provider = provider,
        name = entry.name,
        uri = entry.uri ?: entry.itemId,
        mediaType = entry.mediaType,
        subtitle = entry.subtitle,
        image = entry.image,
        duration = entry.duration,
        album = entry.album,
    )

    /** [AppPlaylists] for one library, as a [Store]. */
    fun store(context: Context, library: String): Store = object : Store {
        override fun all() = all(context, library)
        override fun create(name: String, tracks: List<MaItem>) = create(context, library, name, tracks)
        override fun append(id: String, tracks: List<MaItem>) = append(context, library, id, tracks)
        override fun delete(id: String) = delete(context, library, id)
        override fun removeAt(id: String, positions: List<Int>) = removeAt(context, library, id, positions)
        override fun move(id: String, from: Int, to: Int) = move(context, library, id, from, to)
        override fun rename(id: String, name: String) = rename(context, library, id, name)
    }

    /** One library's app-kept playlists. An interface so a test can hold them in memory. */
    interface Store {
        fun all(): List<Playlist>
        fun create(name: String, tracks: List<MaItem>): String
        fun append(id: String, tracks: List<MaItem>)
        fun delete(id: String)
        fun removeAt(id: String, positions: List<Int>)
        fun move(id: String, from: Int, to: Int)
        fun rename(id: String, name: String)
    }

    /** A playlist as a library row: its first track's cover, and where it lives. */
    internal fun rowOf(p: Playlist, provider: String) = MaItem(
        itemId = p.id,
        provider = provider,
        name = p.name,
        uri = p.id,
        mediaType = "playlist",
        subtitle = "${p.tracks.size} ${if (p.tracks.size == 1) "song" else "songs"} · on this phone",
        image = p.tracks.firstNotNullOfOrNull { it.image },
        duration = p.tracks.sumOf { it.duration ?: 0 }.takeIf { it > 0 },
    )
}

/**
 * [inner], with playlists the app keeps for it — for a library whose server has none,
 * or none this app can write. See [AppPlaylists].
 *
 * Everything else is [inner]'s own, by delegation: browsing, streaming, favourites.
 * Only the playlist calls are answered here, and only for the app's own playlists;
 * a playlist that came from the server still goes to the server.
 */
class WithAppPlaylists(
    val inner: MusicSource,
    /** This library's list — see [AppPlaylists.store]. */
    private val store: AppPlaylists.Store,
) : MusicSource by inner {

    override val capabilities: Set<Capability>
        get() = inner.capabilities + Capability.PLAYLIST_READ + Capability.PLAYLIST_WRITE

    private fun own(): List<MaItem> = store.all().map { AppPlaylists.rowOf(it, inner.providerId) }

    private fun tracksOf(id: String): List<MaItem> =
        store.all().firstOrNull { it.id == id }?.tracks.orEmpty().map { AppPlaylists.itemOf(it, inner.providerId) }

    override suspend fun playlists(): List<MaItem> {
        // The server's own first, when it has any; an error there must not take the
        // app's playlists down with it.
        val server = if (Capability.PLAYLIST_READ in inner.capabilities) {
            runCatchingCancellable { inner.playlists() }.getOrDefault(emptyList())
        } else emptyList()
        return server + own()
    }

    override suspend fun playlistTracks(id: String): List<MaItem> =
        if (AppPlaylists.isAppPlaylist(id)) tracksOf(id) else inner.playlistTracks(id)

    override suspend fun children(item: MaItem): List<MaItem> =
        if (item.mediaType == "playlist" && AppPlaylists.isAppPlaylist(item.itemId)) tracksOf(item.itemId)
        else inner.children(item)

    override suspend fun tracksUnder(item: MaItem): List<MaItem> =
        if (item.mediaType == "playlist" && AppPlaylists.isAppPlaylist(item.itemId)) tracksOf(item.itemId)
        else inner.tracksUnder(item)

    override suspend fun createPlaylistFrom(name: String, tracks: List<MaItem>): String? =
        store.create(name, tracks)

    override suspend fun addToPlaylistFrom(playlistId: String, tracks: List<MaItem>) {
        if (AppPlaylists.isAppPlaylist(playlistId)) store.append(playlistId, tracks)
        else inner.addToPlaylistFrom(playlistId, tracks)
    }

    override suspend fun deletePlaylist(id: String) {
        if (AppPlaylists.isAppPlaylist(id)) store.delete(id)
        else inner.deletePlaylist(id)
    }

    // The app's own playlists are always editable; a server playlist is exactly as
    // editable as the server makes it.
    override fun canEditPlaylist(playlistId: String): Boolean =
        AppPlaylists.isAppPlaylist(playlistId) || inner.canEditPlaylist(playlistId)

    override fun canRenamePlaylist(playlistId: String): Boolean =
        AppPlaylists.isAppPlaylist(playlistId) || inner.canRenamePlaylist(playlistId)

    override suspend fun removeFromPlaylist(playlistId: String, positions: List<Int>, tracks: List<MaItem>) {
        if (AppPlaylists.isAppPlaylist(playlistId)) store.removeAt(playlistId, positions)
        else inner.removeFromPlaylist(playlistId, positions, tracks)
    }

    override suspend fun movePlaylistEntry(playlistId: String, from: Int, to: Int, tracks: List<MaItem>) {
        if (AppPlaylists.isAppPlaylist(playlistId)) store.move(playlistId, from, to)
        else inner.movePlaylistEntry(playlistId, from, to, tracks)
    }

    override suspend fun renamePlaylist(playlistId: String, name: String): String =
        if (AppPlaylists.isAppPlaylist(playlistId)) { store.rename(playlistId, name); playlistId }
        else inner.renamePlaylist(playlistId, name)
}
