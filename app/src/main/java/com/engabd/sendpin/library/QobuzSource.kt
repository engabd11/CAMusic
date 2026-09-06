package com.engabd.sendpin.library

import com.engabd.sendpin.audio.StreamSchemes
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ma.MaSearchResults
import com.engabd.sendpin.qobuz.QobuzClient
import com.engabd.sendpin.qobuz.QobuzException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The Qobuz catalog and the account's library, browsed and played **by this phone**.
 *
 * The thinnest `MusicSource` in the app by design: [QobuzClient] already speaks the
 * wire and answers [MaItem]s, so this is the mapping from the source contract onto
 * it — which shelves exist (favourites are the library), which do not (nothing is
 * ever "most played": Qobuz counts plays for its own recommendations but exposes no
 * per-user play history over this API), and the one non-obvious rule, [streamUrl]
 * handing back a `qobuz://track/<id>` scheme uri instead of the real file url.
 *
 * **Why scheme uris:** Qobuz's `track/getFileUrl` answer is signed per request and
 * expires within minutes. A queue can sit for hours between build and play, so the
 * real url is fetched by the player at open time — [StreamSchemes] resolves the
 * scheme through [QobuzClient.streamUrl] the moment a track actually starts. This
 * is also why the class registers/unregisters its handler in [open]/[close] and why
 * it refuses to serve a `probe()` before [open] has logged in: the handler must
 * never belong to a client whose session was never established.
 *
 * The same lifecycle applies to every caller that builds a source ad hoc
 * (`streamUrlFor`-style paths): an un-opened source simply has no handler registered,
 * its scheme uris fail with an honest load error, and nothing half-plays.
 */
class QobuzSource(private val client: QobuzClient) : MusicSource {

    override val kind: ServerKind = ServerKind.QOBUZ
    override val providerId: String get() = QobuzClient.PROVIDER
    override val serverUrl: String get() = "https://www.qobuz.com"

    override val capabilities: Set<Capability> =
        setOf(
            Capability.SEARCH,
            Capability.FAVORITES,
            Capability.PLAYLIST_READ,
            Capability.METADATA,
        )

    /** Register the player-open resolver. Call once, after [login] has succeeded. */
    fun open() {
        StreamSchemes.register(QOBUZ_SCHEME) { id -> client.streamUrl(id) }
    }

    /** Remove the player-open resolver. Call when the source is torn down. */
    fun close() {
        StreamSchemes.unregister(QOBUZ_SCHEME)
    }

    /**
     * Sign in and register the stream resolver. Returns the config unchanged —
     * Qobuz keeps no per-source state beyond the client's own session.
     */
    suspend fun login() {
        client.login()
        open()
    }

    override suspend fun probe(): SourceError? = try {
        if (client.username.isBlank() || client.password.isBlank()) {
            SourceError("Enter your Qobuz email and password", isAuth = true)
        } else {
            login()
            null
        }
    } catch (e: QobuzException) {
        SourceError(e.message ?: "Qobuz refused the connection", isAuth = e.isAuth)
    } catch (e: Exception) {
        SourceError(e.message ?: "Could not reach Qobuz")
    }

    // ── Browse ────────────────────────────────────────────────────────────

    /** Favourited artists are Qobuz's library for this type. */
    override suspend fun artists(): List<MaItem> = client.favoriteArtists()

    override suspend fun albums(offset: Int, limit: Int): List<MaItem> =
        client.favoriteAlbums(limit = limit, offset = offset)

    override suspend fun playlists(): List<MaItem> = client.playlists()

    /** The artist's own metadata plus their album list, one round-trip. */
    override suspend fun artistDetail(id: String): Pair<MaItem?, List<MaItem>> {
        val body = client.artist(id, withAlbums = true)
        val albums = body["albums"]?.jsonObject?.itemList().orEmpty().mapNotNull { client.parseAlbum(it) }
        return client.parseArtist(body) to albums
    }

    /** The album's full metadata and its track list, one round-trip. */
    override suspend fun albumDetail(id: String): Pair<MaItem?, List<MaItem>> {
        val body = client.album(id)
        val tracks = body["tracks"]?.jsonObject?.itemList().orEmpty().mapNotNull { client.parseTrack(it) }
        return client.parseAlbum(body) to tracks
    }

    override suspend fun playlistTracks(id: String): List<MaItem> =
        client.playlist(id, withTracks = true)
            .get("tracks")?.jsonObject?.itemList().orEmpty()
            .mapNotNull { client.parseTrack(it) }

    /** An artist's albums, an album's tracks, a playlist's tracks. */
    override suspend fun children(item: MaItem): List<MaItem> = when (item.mediaType) {
        "artist" -> artistDetail(item.itemId).second
        "album" -> albumDetail(item.itemId).second
        "playlist" -> playlistTracks(item.itemId)
        else -> emptyList()
    }

    /** Everything playable under [item]: a track is itself; albums/artists recurse. */
    override suspend fun tracksUnder(item: MaItem): List<MaItem> = when (item.mediaType) {
        "track" -> listOf(item)
        "artist", "album", "playlist" -> children(item).flatMap { tracksUnder(it) }
        else -> emptyList()
    }

    override suspend fun song(id: String): MaItem? = client.parseTrack(client.track(id))

    /** Items lists arrive as a JSON array beside the object's own metadata. */
    private fun JsonObject.itemList(): List<JsonObject> =
        (this["items"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    // ── Shelves ───────────────────────────────────────────────────────────

    override suspend fun recentlyAdded(limit: Int): List<MaItem> =
        client.favoriteAlbums(limit = limit)

    /** Favourites, across all three types — the whole library. */
    override suspend fun favorites(): MaSearchResults = MaSearchResults(
        artists = client.favoriteArtists(),
        albums = client.favoriteAlbums(),
        tracks = client.favoriteTracks(),
        playlists = client.playlists(),
    )

    // ── Search ────────────────────────────────────────────────────────────

    override suspend fun search(query: String, limit: Int): MaSearchResults =
        client.search(query, limit)

    // ── Streaming ─────────────────────────────────────────────────────────

    /**
     * A `qobuz://track/<id>` scheme uri, **not** the real file url — the player
     * resolves it at open time through [StreamSchemes] (see the class docs for why;
     * the real url expires within minutes and the queue does not).
     */
    override fun streamUrl(id: String, format: String): String = "$QOBUZ_SCHEME://track/$id"

    /** There is no original file to keep: Qobuz serves what it serves. */
    override fun downloadUrl(id: String): String = streamUrl(id)

    override fun coverUrl(id: String?, size: Int): String? = null

    /** Qobuz transcodes for nobody: the format token is meaningless here. */
    override var streamFormat: String
        get() = "raw"
        set(_) {}

    /**
     * Qobuz favourites are writable (`favorite/create` / `favorite/delete`), but
     * they are also the *library* — a wrong write would silently reshelve the user's
     * music. Wired up with the real endpoints in a follow-up, once the round-trip
     * has been exercised live; until then the star button does not pretend.
     */
    override suspend fun setStarred(item: MaItem, starred: Boolean) = Unit

    companion object {
        /** The scheme [streamUrl] stamps on items and [StreamSchemes] resolves. */
        const val QOBUZ_SCHEME = "qobuz"
    }
}
