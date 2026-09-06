package com.engabd.sendpin.library

import com.engabd.sendpin.audio.RemotePlayback
import com.engabd.sendpin.audio.StreamSchemes
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ma.MaSearchResults
import com.engabd.sendpin.spotify.SpotifyApiException
import com.engabd.sendpin.spotify.SpotifyEngine
import com.engabd.sendpin.spotify.SpotifyRemote
import com.engabd.sendpin.spotify.SpotifyWebApi
import com.engabd.sendpin.subsonic.SavedQueue

/**
 * The user's Spotify library, played by the **embedded client in this process**.
 *
 * Route B, end to end: credentials open a librespot session ([SpotifyEngine]),
 * the session's `TokenProvider` mints the user's own Web API token, and
 * [SpotifyWebApi] browses the saved library with it — no Spotify developer app
 * exists anywhere in this path, so the dev-mode 5-user allowlist never applies.
 * Search rides librespot's own `SearchManager` (no Web API call at all).
 *
 * **This source answers with a [RemotePlayback]** — the MPD shape. Its queue plays
 * inside librespot's pipeline (decode → [com.engabd.sendpin.spotify.SpotifySink] →
 * AudioTrack), the phone drives it app-side, and Light Sync reads
 * [com.engabd.sendpin.spotify.SpotifyEngine.tap], which the sink feeds directly.
 * Unlike MPD there is real PCM in this process, but it never passes through
 * ExoPlayer, so handing transport over is still the only correct integration.
 */
class SpotifySource(
    private val context: android.content.Context,
    private val username: String,
    private val password: String,
) : MusicSource {

    override val kind: ServerKind = ServerKind.SPOTIFY
    override val providerId: String get() = SpotifyWebApi.PROVIDER
    override val serverUrl: String get() = "https://open.spotify.com"

    override val capabilities: Set<Capability> =
        setOf(
            Capability.SEARCH,
            Capability.FAVORITES,
            Capability.PLAYLIST_READ,
            Capability.METADATA,
        )

    private fun api(): SpotifyWebApi {
        val session = SpotifyEngine.get(context, username, password)
        return SpotifyWebApi(tokenProvider = { session.tokens().getToken().accessToken })
    }

    override suspend fun probe(): SourceError? = try {
        if (username.isBlank() || password.isBlank()) {
            SourceError("Enter your Spotify username and password", isAuth = true)
        } else {
            withContext_IO { SpotifyEngine.get(context, username, password) }
            null
        }
    } catch (e: SpotifyApiException) {
        SourceError(e.message ?: "Spotify refused the connection", isAuth = e.isAuth)
    } catch (e: Exception) {
        SourceError(authMessage(e) ?: e.message ?: "Could not reach Spotify")
    }

    private fun authMessage(e: Exception): String? =
        if (e.javaClass.name.endsWith("SpotifyAuthenticationException")) {
            "Spotify refused that login — a Premium account is required, and credentials set " +
                "up before November 2024 may need re-entering on spotify.com first"
        } else {
            null
        }

    private suspend fun <T> withContext_IO(block: suspend () -> T): T =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() }

    // ── Browse ────────────────────────────────────────────────────────────

    override suspend fun artists(): List<MaItem> = emptyList()

    /** Saved albums, paged. */
    override suspend fun albums(offset: Int, limit: Int): List<MaItem> =
        withContext_IO { api().savedAlbums(limit = limit, offset = offset) }

    override suspend fun playlists(): List<MaItem> =
        withContext_IO { api().playlists() }

    /** Saved tracks double as the artist-less library listing. */
    override suspend fun artistDetail(id: String): Pair<MaItem?, List<MaItem>> {
        val api = api()
        val artist = withContext_IO { runCatching { api.search(id, 1).artists.firstOrNull() }.getOrNull() }
        val albums = withContext_IO { api.artistAlbums(id) }
        return artist to albums
    }

    /** The album's metadata with its tracks, album context carried onto each. */
    override suspend fun albumDetail(id: String): Pair<MaItem?, List<MaItem>> {
        val api = api()
        val album = withContext_IO { api.album(id) } ?: return null to emptyList()
        val tracks = withContext_IO { api.albumTracks(id, album) }
        return album to tracks
    }

    override suspend fun playlistTracks(id: String): List<MaItem> =
        withContext_IO { api().playlistTracks(id) }

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

    override suspend fun song(id: String): MaItem? =
        withContext_IO { api().track(id) }

    // ── Shelves ───────────────────────────────────────────────────────────

    override suspend fun recentlyAdded(limit: Int): List<MaItem> =
        withContext_IO { api().savedAlbums(limit = limit) }

    override suspend fun favorites(): MaSearchResults {
        val api = api()
        return withContext_IO {
            MaSearchResults(
                artists = emptyList(),
                albums = api.savedAlbums(),
                tracks = api.savedTracks(),
                playlists = api.playlists(),
            )
        }
    }

    // ── Search ────────────────────────────────────────────────────────────

    override suspend fun search(query: String, limit: Int): MaSearchResults {
        // Search rides librespot's own SearchManager — no Web API, no client id.
        val session = SpotifyEngine.get(context, username, password)
        return withContext_IO {
            // SearchManager speaks Gson; convert to kotlinx for the parsers.
            val gson = session.search().request(
                xyz.gianlu.librespot.core.SearchManager.SearchRequest(query).limit(limit),
            )
            val root = kotlinx.serialization.json.Json.parseToJsonElement(gson.toString())
                as kotlinx.serialization.json.JsonObject
            val helper = SpotifyWebApi(tokenProvider = { "" })
            MaSearchResults(
                artists = parseSearchList(root["artists"], helper::parseArtist),
                albums = parseSearchList(root["albums"], helper::parseAlbum),
                tracks = parseSearchList(root["tracks"]) { helper.parseTrack(it) },
                playlists = parseSearchList(root["playlists"], helper::parsePlaylist),
            )
        }
    }

    /** A search response section's `items` array, parsed by [map]. */
    private fun parseSearchList(
        section: kotlinx.serialization.json.JsonElement?,
        map: (kotlinx.serialization.json.JsonObject) -> MaItem?,
    ): List<MaItem> {
        val items = ((section as? kotlinx.serialization.json.JsonObject)
            ?.get("items") as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { it as? kotlinx.serialization.json.JsonObject }
            .orEmpty()
        return items.mapNotNull(map)
    }

    // ── Streaming ─────────────────────────────────────────────────────────

    /**
     * A `spotify://track/<id>` scheme uri. Nothing resolves it through
     * [StreamSchemes] — Spotify audio never re-enters ExoPlayer — but queue
     * builders and download fallbacks treat the string like any other url, and
     * the remote transport keys track ids off it.
     */
    override fun streamUrl(id: String, format: String): String = "spotify://track/$id"

    override fun downloadUrl(id: String): String = streamUrl(id)

    override fun coverUrl(id: String?, size: Int): String? = null

    override var streamFormat: String
        get() = "raw"
        set(_) {}

    /** The queue this phone drives while librespot plays it. See the class docs. */
    override fun remotePlayback(): RemotePlayback = SpotifyRemote { SpotifyEngine.playerOrNull() }

    companion object {
        const val SCHEME = "spotify"
    }
}
