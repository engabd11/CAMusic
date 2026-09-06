package com.engabd.sendpin.library

import android.content.Context
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ma.MaSearchResults
import com.engabd.sendpin.audio.RemotePlayback
import com.engabd.sendpin.mpd.MpdArt
import com.engabd.sendpin.mpd.MpdClient
import com.engabd.sendpin.mpd.MpdRemote

/**
 * [MpdClient] as a [MusicSource] — Music Player Daemon as a browsable library,
 * and, alone among the providers, as the thing that plays what it browses.
 *
 * For browsing it is the same shape as Navidrome or Jellyfin: artists, albums, a
 * search, a track list. For playing it is the opposite of them. They hand out a
 * URL per track and this phone decodes it; MPD is already a player, and its only
 * way to send audio elsewhere is a live stream with no duration, no position and
 * no seek. So it hands over a player of its own ([remotePlayback]) and the phone
 * drives that instead of decoding — see
 * [com.engabd.sendpin.audio.RemotePlayback] for the whole of that argument.
 *
 * ## What MPD can't do
 *
 * No lyrics ([Capability.LYRICS]), no artist biographies
 * ([Capability.METADATA]), no similar-track suggestions
 * ([Capability.SIMILAR]). MPD's metadata comes entirely from file tags, so
 * [Capability.RICH_FORMAT] *is* here — MPD reports codec, sample rate and bit
 * depth per track. No [Capability.HISTORY] (no play history without stickers),
 * and no [Capability.DOWNLOAD]: MPD serves audio to its own outputs and has no
 * endpoint that hands a file over.
 *
 * [Capability.REPLAY_GAIN] is here for a different reason from every other provider
 * that declares it: MPD does not report a gain for the app to apply, it *applies*
 * one. `replay_gain_mode` is a server-side setting, and the Now Playing sheet drives
 * it through [MpdRemote.setReplayGain] — which is also why the app's own scalar is
 * left out of the path on an MPD session, since the audio never reaches this phone.
 *
 * ## Favourites, which MPD does not have
 *
 * [Capability.FAVORITES] *is* declared, and the app keeps the list itself — see
 * [LocalFavourites]. MPD has no starred concept natively, which used to mean the
 * Starred category, the heart on every row and both favourites shelves on the
 * library's front page were simply missing here: a browse experience visibly poorer
 * than Navidrome's or Jellyfin's, for a reason that had nothing to do with what MPD
 * can play.
 *
 * The cost is that these favourites live on this phone rather than on the server, so
 * they do not follow the user to another MPD client. That is a real trade and it is
 * the right one: the alternative on offer is MPD's sticker database, which is
 * optional, off on a default install, and would leave the feature silently doing
 * nothing for most people.
 *
 * Every browse result is stamped through [LocalFavourites.mark] on the way out. That
 * is load-bearing rather than cosmetic — `LibraryViewModel` seeds its heart state from
 * `MaItem.favorite` and clears it for anything reported false, so a source that stored
 * a star and then handed the item back unstarred would un-heart it on the next scroll.
 */
class MpdSource(
    private val client: MpdClient,
    /**
     * Application context, for the app-side favourites store.
     *
     * Nullable so a test — or any caller that does not care about favourites — can
     * build a source without one, in which case this behaves exactly as it did before
     * favourites existed: nothing is starred and nothing can be.
     */
    private val context: Context? = null,
) : MusicSource {

    override val kind: ServerKind = ServerKind.MPD
    override val providerId: String get() = MpdClient.PROVIDER
    override val serverUrl: String get() = client.serverUrl

    override var streamFormat: String
        get() = client.streamFormat
        set(value) { client.streamFormat = value }

    /** The underlying client, for setup flows that need MPD itself. */
    val mpd: MpdClient get() = client

    override val capabilities: Set<Capability> = buildSet {
        add(Capability.SEARCH)
        add(Capability.GENRES)
        add(Capability.PLAYLIST_READ)
        add(Capability.TRACKS)
        add(Capability.RICH_FORMAT)
        // MPD applies ReplayGain itself, in its own mixer, from the tags on the
        // files it scanned — `replay_gain_mode`, driven by [MpdRemote.setReplayGain].
        // It is the one provider where the correction never passes through this
        // phone at all, and the only one where the app's own scalar would be acting
        // on a signal that isn't here.
        add(Capability.REPLAY_GAIN)
        // Only when there is somewhere to keep them — see the class docs.
        if (context != null) add(Capability.FAVORITES)
    }

    /** Everything this source hands out, with the app's own stars applied. */
    private fun marked(items: List<MaItem>): List<MaItem> =
        context?.let { LocalFavourites.mark(it, providerId, items) } ?: items

    override suspend fun probe(): SourceError? = client.pingResult()?.let {
        SourceError(it.message ?: "MPD refused the request", isAuth = it.isAuth)
    }

    // ── Browse ────────────────────────────────────────────────────────────

    override suspend fun artists(): List<MaItem> = marked(client.artists())
    override suspend fun albums(offset: Int, limit: Int): List<MaItem> =
        marked(client.albums(offset = offset, limit = limit))
    override suspend fun playlists(): List<MaItem> = marked(client.playlists())

    override suspend fun artistDetail(id: String) =
        client.artistDetail(id).let { (artist, albums) ->
            artist?.let { marked(listOf(it)).first() } to marked(albums)
        }

    override suspend fun albumDetail(id: String) =
        client.albumDetail(id).let { (album, tracks) ->
            album?.let { marked(listOf(it)).first() } to marked(tracks)
        }

    override suspend fun playlistTracks(id: String): List<MaItem> =
        marked(client.playlistTracks(id))

    override suspend fun tracks(offset: Int, limit: Int): List<MaItem> =
        marked(client.tracks(offset, limit))

    override suspend fun children(item: MaItem): List<MaItem> = when (item.mediaType) {
        "artist" -> marked(client.albums(artist = item.itemId))
        "album" -> albumDetail(item.itemId).second
        "playlist" -> playlistTracks(item.itemId)
        else -> emptyList()
    }

    override suspend fun tracksUnder(item: MaItem): List<MaItem> = when (item.mediaType) {
        "track" -> listOf(item)
        "album" -> client.albumDetail(item.itemId).second
        "playlist" -> client.playlistTracks(item.itemId)
        "artist" -> client.albums(artist = item.itemId).flatMap { album ->
            client.albumDetail(album.itemId).second
        }
        else -> emptyList()
    }

    override suspend fun song(id: String): MaItem? =
        client.song(id)?.let { marked(listOf(it)).first() }

    // ── Shelves ───────────────────────────────────────────────────────────

    override suspend fun recentlyAdded(limit: Int): List<MaItem> =
        marked(client.recentlyAdded(limit))

    /**
     * The app's own starred list — MPD has none. See the class docs.
     *
     * This is what puts the Starred category on the browse root and fills the
     * "Favourite albums" and "Favourite artists" shelves on the library's front page,
     * both of which read `MusicSource.favorites()` for every self-hosted library.
     */
    override suspend fun favorites(): MaSearchResults =
        context?.let { LocalFavourites.results(it, providerId) }
            ?: MaSearchResults(emptyList(), emptyList(), emptyList(), emptyList())

    override suspend fun randomSongs(size: Int): List<MaItem> = marked(client.randomSongs(size))

    // ── Search ────────────────────────────────────────────────────────────

    override suspend fun search(query: String, limit: Int): MaSearchResults =
        client.search(query, limit).let {
            MaSearchResults(
                artists = marked(it.artists),
                albums = marked(it.albums),
                playlists = marked(it.playlists),
                tracks = marked(it.tracks),
            )
        }

    // ── Genres ────────────────────────────────────────────────────────────

    override suspend fun genres(): List<MaItem> = client.genres()
    override suspend fun songsByGenre(genre: String, count: Int, offset: Int): List<MaItem> =
        marked(client.songsByGenre(genre, count, offset))

    // ── Write ─────────────────────────────────────────────────────────────

    /** Kept on this phone rather than on the server — see the class docs. */
    override suspend fun setStarred(item: MaItem, starred: Boolean) {
        val ctx = context ?: return
        LocalFavourites.set(ctx, providerId, item, starred)
    }

    // ── URLs ──────────────────────────────────────────────────────────────

    /**
     * There is no URL for this phone to open, and the interface requires one, so
     * it answers with the empty string.
     *
     * Not MPD's httpd stream, which is what this used to return: that URL is the
     * same for every track and plays whatever MPD is playing, so a phone opening
     * it played the music a second time, a second behind, out of a second set of
     * speakers — with a scrub bar that could not move because a live stream has
     * nowhere to move to. MPD plays its own queue now; nothing here opens a URL.
     */
    override fun streamUrl(id: String, format: String): String = ""

    /** Nothing to download: MPD has no endpoint that hands a file over. */
    override fun downloadUrl(id: String): String = ""

    override fun coverUrl(id: String?, size: Int): String? = MpdArt.url(id)

    /**
     * The one source that answers with a player. See [MusicSource.remotePlayback].
     */
    override fun remotePlayback(): RemotePlayback = MpdRemote(client)

    override suspend fun serverQueue(): List<MaItem> = marked(client.queueTracks())

    override suspend fun serverQueueIndex(): Int =
        client.status()?.songIndex?.coerceAtLeast(0) ?: 0
}
