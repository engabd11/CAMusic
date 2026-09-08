package com.engabd.sendpin.library

import com.engabd.sendpin.audio.StreamSchemes
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ma.MaSearchResults
import com.engabd.sendpin.tidal.TidalClient
import com.engabd.sendpin.tidal.TidalException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The user's Tidal library, browsed over Tidal's v1 API and played by **this
 * phone** through expiring playback manifests.
 *
 * Sign-in is Tidal's device authorization flow (the same one Tidal's own TV
 * clients use): the app shows a URL and a code, the user approves in a browser,
 * and the resulting token + refresh token live in the [ServerConfig] options —
 * see `TidalSignInRow`, the Plex-PIN-row shape. No password is ever typed here,
 * which is why this kind is `AuthStyle.LINKED_ACCOUNT`.
 *
 * Streaming walks the `playbackinfopostpaywall` quality tiers (HI_RES → LOSSLESS →
 * HIGH); each answer is a BTS manifest — base64 JSON whose URLs may be
 * AES-encrypted — decoded by [TidalClient.decodeBtsManifest]. The resolved URL is
 * short-lived, so like Qobuz this source stamps `tidal://track/<id>` scheme uris
 * and [StreamSchemes] resolves them at player-open time through [client]'s
 * `streamUrl`.
 *
 * Favourites are the library, as on Qobuz; `setStarred` is deliberately unwired
 * until the write endpoints have been exercised live.
 */
class TidalSource(private val client: TidalClient) : MusicSource {

    override val kind: ServerKind = ServerKind.TIDAL
    override val providerId: String get() = TidalClient.PROVIDER
    override val serverUrl: String get() = "https://tidal.com"

    override val capabilities: Set<Capability> =
        setOf(
            Capability.SEARCH,
            Capability.FAVORITES,
            Capability.PLAYLIST_READ,
            Capability.METADATA,
        )

    /** The underlying client, for setup flows that need Tidal itself. */
    val tidal: TidalClient get() = client

    /** Register the player-open resolver; call once the client holds a token. */
    fun open() {
        StreamSchemes.register(TIDAL_SCHEME) { id -> client.streamUrl(id) }
    }

    fun close() {
        StreamSchemes.unregister(TIDAL_SCHEME)
    }

    override suspend fun probe(): SourceError? = try {
        if (client.clientId.isBlank() || client.accessToken.isBlank()) {
            SourceError("Sign in to Tidal to connect this library", isAuth = true)
        } else if (client.ensureSignedIn()) {
            open()
            null
        } else {
            SourceError("Tidal sign-in expired — sign in again", isAuth = true)
        }
    } catch (e: TidalException) {
        SourceError(e.message ?: "Tidal refused the connection", isAuth = e.isAuth)
    } catch (e: Exception) {
        SourceError(e.message ?: "Could not reach Tidal")
    }

    private suspend fun signed(): TidalClient {
        if (!client.ensureSignedIn()) throw TidalException("Tidal sign-in expired — sign in again", 401)
        open()
        return client
    }

    // ── Browse ────────────────────────────────────────────────────────────

    override suspend fun artists(): List<MaItem> =
        signed().favoriteArtists().mapNotNull { client.parseArtist(it) }

    override suspend fun albums(offset: Int, limit: Int): List<MaItem> =
        signed().favoriteAlbums().drop(offset).take(limit).mapNotNull { client.parseAlbum(it) }

    override suspend fun playlists(): List<MaItem> =
        signed().playlists().mapNotNull { client.parsePlaylist(it) }

    override suspend fun artistDetail(id: String): Pair<MaItem?, List<MaItem>> {
        val api = signed()
        val albums = api.artistAlbums(id).mapNotNull { client.parseAlbum(it) }
        return null to albums
    }

    override suspend fun albumDetail(id: String): Pair<MaItem?, List<MaItem>> {
        val api = signed()
        val album = client.parseAlbum(api.album(id))
        val tracks = api.albumTracks(id).mapNotNull { client.parseTrack(it, albumContext = album) }
        return album to tracks
    }

    override suspend fun playlistTracks(id: String): List<MaItem> =
        signed().playlistTracks(id).mapNotNull { client.parseTrack(it) }

    override suspend fun children(item: MaItem): List<MaItem> = when (item.mediaType) {
        "artist" -> artistDetail(item.itemId).second
        "album" -> albumDetail(item.itemId).second
        "playlist" -> playlistTracks(item.itemId)
        else -> emptyList()
    }

    override suspend fun tracksUnder(item: MaItem): List<MaItem> = when (item.mediaType) {
        "track" -> listOf(item)
        "artist", "album", "playlist" -> children(item).flatMap { tracksUnder(it) }
        else -> emptyList()
    }

    override suspend fun song(id: String): MaItem? = client.parseTrack(signed().track(id))

    // ── Shelves ───────────────────────────────────────────────────────────

    override suspend fun recentlyAdded(limit: Int): List<MaItem> =
        signed().favoriteAlbums().take(limit).mapNotNull { client.parseAlbum(it) }

    override suspend fun favorites(): MaSearchResults {
        val api = signed()
        return MaSearchResults(
            artists = api.favoriteArtists().mapNotNull { client.parseArtist(it) },
            albums = api.favoriteAlbums().mapNotNull { client.parseAlbum(it) },
            tracks = api.favoriteTracks().mapNotNull { client.parseTrack(it) },
            playlists = api.playlists().mapNotNull { client.parsePlaylist(it) },
        )
    }

    // ── Search ────────────────────────────────────────────────────────────

    override suspend fun search(query: String, limit: Int): MaSearchResults =
        signed().mapSearch(client.search(query, limit))

    // ── Streaming ─────────────────────────────────────────────────────────

    /**
     * A `tidal://track/<id>` scheme uri — the real manifest url expires within
     * minutes and is resolved at player-open time, exactly like Qobuz's.
     */
    override fun streamUrl(id: String, format: String): String = "$TIDAL_SCHEME://track/$id"

    override fun downloadUrl(id: String): String = streamUrl(id)

    override fun coverUrl(id: String?, size: Int): String? = null

    override var streamFormat: String
        get() = "raw"
        set(_) {}

    companion object {
        const val TIDAL_SCHEME = "tidal"
    }
}
