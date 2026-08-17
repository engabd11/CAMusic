package com.engabd.sendpin.library

import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ma.MaLyrics
import com.engabd.sendpin.ma.MaSearchResults
import com.engabd.sendpin.subsonic.AlbumInfo
import com.engabd.sendpin.subsonic.ArtistInfo
import com.engabd.sendpin.subsonic.SavedQueue

/**
 * A music library this phone browses and plays **itself**.
 *
 * Navidrome was hard-wired into `LibraryViewModel` as a single nullable
 * `SubsonicClient` field with `when (backend)` branches around every call. That was
 * fine while there were two backends and impossible with three: Jellyfin, Plex, Emby,
 * Audiobookshelf, Kodi, a WebDAV share and five cloud drives are all *the same shape
 * of thing* — something that lists artists and albums, answers a search, and hands
 * out a URL this phone can decode — and none of them could be added without touching
 * forty call sites.
 *
 * ## What is deliberately not here
 *
 * **Music Assistant.** It looks like it belongs and it doesn't: MA owns a server-side
 * queue and plays to speakers this app never decodes for, so "give me a stream URL"
 * is the wrong question to ask it. Wrapping it here would mean an interface whose
 * every method had an MA-shaped exception in it. It stays the app's second mode, and
 * [ServerKind.playsLocally] is the line between them.
 *
 * ## Capabilities rather than optional methods
 *
 * Every provider answers a different subset. A plain Subsonic server has no lyrics
 * and no ReplayGain; Jellyfin has both but no `getSimilarSongs`; a WebDAV folder has
 * nothing but files. So the optional half of this interface has default
 * implementations that return nothing, and [capabilities] says up front what is worth
 * asking for — so the UI can leave a shelf out rather than render an empty one, and a
 * new adapter is only as much work as the server is capable.
 */
interface MusicSource {

    val kind: ServerKind

    /** The tag every [MaItem] from this source carries, so items can be traced back. */
    val providerId: String

    /** What this source can actually answer. See [Capability]. */
    val capabilities: Set<Capability>

    /** The normalised server root, for display. */
    val serverUrl: String

    fun has(capability: Capability): Boolean = capability in capabilities

    // ── Reachability ──────────────────────────────────────────────────────

    /**
     * Null when the server answered, else why not.
     *
     * Deliberately not the provider's own exception type: the library branches on the
     * *distinction* between a rejected login and an unreachable host — one is worth
     * re-prompting for, the other is worth falling back to downloads for — and that
     * distinction is the only thing about the failure it needs. Every provider spells
     * it differently (a Subsonic error code, a Jellyfin 401) and none of that should
     * reach the view model.
     */
    suspend fun probe(): SourceError?

    /** [probe] when only the message matters. */
    suspend fun ping(): String? = probe()?.message

    // ── Browse ────────────────────────────────────────────────────────────

    suspend fun artists(): List<MaItem>
    suspend fun albums(offset: Int = 0, limit: Int = 200): List<MaItem>
    suspend fun playlists(): List<MaItem>

    /** Artist metadata and their albums, in one round-trip where the server allows. */
    suspend fun artistDetail(id: String): Pair<MaItem?, List<MaItem>>

    /** Album metadata and its tracks, likewise. */
    suspend fun albumDetail(id: String): Pair<MaItem?, List<MaItem>>

    suspend fun playlistTracks(id: String): List<MaItem>

    /** One level down from [item] — an artist's albums, an album's tracks. */
    suspend fun children(item: MaItem): List<MaItem>

    /**
     * Every track under [item], however deep. What a download or a "play all" needs:
     * a track is itself, an album or playlist is its entries, an artist is everything.
     */
    suspend fun tracksUnder(item: MaItem): List<MaItem>

    suspend fun song(id: String): MaItem?

    // ── Shelves ───────────────────────────────────────────────────────────

    /** Newest additions to the library. */
    suspend fun recentlyAdded(limit: Int = 200): List<MaItem>

    /** Recently played. Empty where the server doesn't track it. */
    suspend fun recentlyPlayed(limit: Int = 200): List<MaItem> = emptyList()

    /** Most played. Empty where the server doesn't track it. */
    suspend fun mostPlayed(limit: Int = 200): List<MaItem> = emptyList()

    /** Everything starred / favourited, across types. */
    suspend fun favorites(): MaSearchResults

    suspend fun randomSongs(size: Int = 100): List<MaItem> = emptyList()

    /** A handful of albums picked at random, for the "something else" shelf. */
    suspend fun randomAlbums(limit: Int = 12): List<MaItem> = emptyList()

    // ── Search ────────────────────────────────────────────────────────────

    suspend fun search(query: String, limit: Int = 30): MaSearchResults

    // ── Genres ────────────────────────────────────────────────────────────

    suspend fun genres(): List<MaItem> = emptyList()
    suspend fun songsByGenre(genre: String, count: Int = 300, offset: Int = 0): List<MaItem> = emptyList()

    // ── URLs ──────────────────────────────────────────────────────────────

    /**
     * What the local player should open. [format] is the source's own transcode
     * token; a source that only ever serves the original file ignores it.
     */
    fun streamUrl(id: String, format: String = streamFormat): String

    /** The original file, whatever [streamUrl] would have transcoded to. */
    fun downloadUrl(id: String): String

    fun coverUrl(id: String?, size: Int = 1000): String?

    /**
     * The transcode the user asked for, as a source-specific token.
     *
     * Mutable and not a constructor argument for the same reason it was on
     * `SubsonicClient`: the source is long-lived and shared, and rebuilding it when
     * the setting changes would drop the connection state with it.
     */
    var streamFormat: String

    // ── Write ─────────────────────────────────────────────────────────────

    suspend fun setStarred(item: MaItem, starred: Boolean) = Unit

    /**
     * Report a play. [completed] false is a now-playing ping, true is a finished play.
     *
     * [startedAtMs] is a **wall-clock epoch** — when the listening began — not a
     * position within the track. Subsonic's `scrobble` takes exactly that as its
     * `time` parameter; a provider that wants a playback position instead must not
     * forward this one, which is a mistake worth naming here because the two are both
     * `Long` milliseconds and the compiler has nothing to say about it.
     *
     * [positionMs] is the real, player-tracked position at the moment of the report —
     * unlike [startedAtMs], it does not drift when playback is paused. Providers whose
     * completion report wants a position (Jellyfin) should prefer this over deriving
     * one from [startedAtMs]; callers that have a live position (anything driven by
     * [com.engabd.sendpin.audio.LocalPlayer]) should always pass it.
     */
    suspend fun scrobble(id: String, completed: Boolean, startedAtMs: Long? = null, positionMs: Long? = null) = Unit

    /**
     * Periodic "still playing, and here is where" — [positionMs] *is* a position
     * within the track, unlike [scrobble]'s `startedAtMs`.
     *
     * Only servers that keep a live session need this. Jellyfin does: its dashboard
     * session and its resume positions both go stale within about a minute without it,
     * so a report every few seconds is the difference between "Now Playing" working
     * and not. Subsonic has no equivalent — its `scrobble` is the whole protocol — so
     * the default is a no-op and costs those providers nothing.
     */
    suspend fun reportProgress(id: String, positionMs: Long, paused: Boolean) = Unit

    suspend fun createPlaylist(name: String, songIds: List<String> = emptyList()): String? = null
    suspend fun addToPlaylist(playlistId: String, songIds: List<String>) = Unit
    suspend fun deletePlaylist(id: String) = Unit

    // ── Extras ────────────────────────────────────────────────────────────

    suspend fun lyrics(songId: String): MaLyrics? = null
    suspend fun artistInfo(id: String, similarCount: Int = 0): ArtistInfo = ArtistInfo()
    suspend fun albumInfo(id: String): AlbumInfo = AlbumInfo()
    suspend fun topSongs(artistName: String, count: Int = 10): List<MaItem> = emptyList()
    suspend fun similarSongs(id: String, count: Int = 50): List<MaItem> = emptyList()

    /**
     * The queue the server is holding for this user, so playback resumes where it
     * left off on another device. Null where the server has no such concept.
     */
    suspend fun savedQueue(): SavedQueue? = null
    suspend fun saveQueue(songIds: List<String>, currentId: String?, positionMs: Long) = Unit
}

/**
 * Why a source didn't answer.
 *
 * [isAuth] is the only distinction the library acts on: rejected credentials mean
 * the connect form should stay up with the message on it, because that is something
 * the user can fix; anything else means the server is simply not there, and falling
 * back to downloads is a better answer than a dead end.
 */
data class SourceError(val message: String, val isAuth: Boolean = false)

/**
 * Sign-in was refused, as opposed to the server being unreachable.
 *
 * Thrown out of [MusicSources.prepare] so the caller can tell the two apart without
 * catching each provider's own exception type. It matters: a refused password keeps
 * the connect form up with something the user can fix, while an unreachable host
 * should drop to playing downloads instead.
 */
class SourceAuthException(message: String) : Exception(message)

/**
 * What a source can answer.
 *
 * Read by the UI to decide what to *offer*, not only what to call: a shelf that is
 * always empty, a lyrics button that never finds any, a star that silently does
 * nothing — those are worse than the feature being absent, because the user cannot
 * tell a missing capability from a broken one.
 */
enum class Capability {
    SEARCH,
    GENRES,
    FAVORITES,
    PLAYLIST_READ,
    PLAYLIST_WRITE,
    /** Original files can be fetched for offline playback. */
    DOWNLOAD,
    LYRICS,
    /** Play counts / recently played are tracked server-side. */
    HISTORY,
    SCROBBLE,
    /** The server can suggest similar tracks or artists. */
    SIMILAR,
    /** Artist biographies and album notes. */
    METADATA,
    /** A cross-device play queue the server holds. */
    SAVED_QUEUE,
    /** The library reports codec, rate, depth and bitrate per track. */
    RICH_FORMAT,
}
