package com.engabd.sendpin.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.discovery.PlayerIdentity
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ma.MaRepository
import com.engabd.sendpin.library.MusicSource
import com.engabd.sendpin.library.MusicSources
import com.engabd.sendpin.subsonic.SubsonicClient
import com.engabd.sendpin.ui.viewmodel.NowPlayingViewModel.Load
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Backs [com.engabd.sendpin.ui.screens.ArtistDetailScreen]. Loads the artist's
 * metadata and albums, then exposes the hero's play / shuffle / queue / download
 * actions over the artist's whole catalogue.
 *
 * Mirrors [AlbumDetailViewModel] in structure: the artist's identity is passed in
 * from the navigation route, the header renders immediately, and data loads in the
 * background. Navidrome plays through the process-scoped
 * [com.engabd.sendpin.audio.LocalPlayer] as a *queue*, downloaded copies preferred —
 * firing a single URL at it would make "shuffle" a lie and leave the offline files
 * unplayable from this screen.
 *
 * [resolveByName] covers the one caller that cannot know the artist's id: the album
 * screen. [MaItem] carries artist *names* and no artist ids, so the album header
 * hands over a name and the real id is looked up here.
 */
class ArtistDetailViewModel(
    app: Application,
    val itemId: String,
    val provider: String,
    val initialName: String,
    val initialArt: String?,
    private val resolveByName: Boolean = false,
) : AndroidViewModel(app) {

    private val settings = AppSettings(app)
    /** Live, not captured — see [PlayerIdentity.getPlayerId]. */
    private val myPlayerId: String get() = PlayerIdentity.getPlayerId(getApplication<Application>())
    private val maApi = (app as SendpinApp).maApi
    private val maRepo = MaRepository(maApi)
    /** Process-scoped, not owned by this screen — see the note in [AlbumDetailViewModel]. */
    private val localPlayer = (app as SendpinApp).localPlayer
    private val downloads = (app as SendpinApp).downloads

    /** Built once per load rather than per action. Null until the artist resolves. */
    /**
     * The library this phone plays itself, when this artist came from one. Read from
     * the process-scoped holder rather than built here — see the same field on
     * [AlbumDetailViewModel].
     */
    private val sourceHolder = (app as SendpinApp).musicSource
    /**
     * Null unless the live source is the one this screen's items came from.
     *
     * The provider check is load-bearing now that there can be two locally-played
     * libraries: without it, switching from Navidrome to Jellyfin while an album was
     * open left the screen building stream URLs out of Jellyfin for Navidrome ids.
     */
    private val source: MusicSource?
        get() = sourceHolder.value?.takeIf { it.providerId == provider }

    private val isLocal get() = MusicSources.isLocalProvider(provider)

    /**
     * What API calls are made against. Replaced by the real library item once the
     * load (or a by-name lookup) resolves, so later calls carry the resolved id and
     * provider rather than the placeholder the route arrived with.
     */
    private var ref = MaItem(
        itemId = itemId, provider = provider, name = initialName,
        uri = null, mediaType = "artist", subtitle = null,
        image = initialArt, duration = null,
    )

    /** A name-only route has been turned into a real id, so don't search again. */
    private var resolved = false

    private val _artist = MutableStateFlow<MaItem?>(null)
    val artist: StateFlow<MaItem?> = _artist

    private val _albums = MutableStateFlow<List<MaItem>>(emptyList())
    val albums: StateFlow<List<MaItem>> = _albums

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /**
     * Artist biography, or null when neither server has one.
     *
     * On Navidrome it comes from `getArtistInfo2`. On Music Assistant it is MA's own
     * `metadata.description` — and, failing that, Navidrome's, because the two
     * libraries usually hold the same artists and a biography is a fact about the
     * artist rather than about the server that happens to be selected. See
     * [navidromeBiographyFor] for why that borrowing is name-matched.
     */
    private val _biography = MutableStateFlow<String?>(null)
    val biography: StateFlow<String?> = _biography

    /**
     * The artist's best-known tracks.
     *
     * `music/artists/top_tracks` has been implemented in [MaRepository] with no
     * callers at all, and Subsonic's `getTopSongs` was never bound. Both are one
     * call, and "what should I put on" is the first question an artist page should
     * answer.
     */
    private val _topTracks = MutableStateFlow<Load<List<MaItem>>>(Load.Idle)
    val topTracks: StateFlow<Load<List<MaItem>>> = _topTracks

    /**
     * Who else to try. `music/artists/similar_artists` was also implemented and
     * uncalled, and on the Navidrome side `getArtistInfo2` was being asked for
     * `count=0` similar artists — the app was telling the server not to send them.
     */
    private val _similar = MutableStateFlow<Load<List<MaItem>>>(Load.Idle)
    val similar: StateFlow<Load<List<MaItem>>> = _similar

    /** Outbound links from the server's metadata, when it had any. */
    private val _lastFmUrl = MutableStateFlow<String?>(null)
    val lastFmUrl: StateFlow<String?> = _lastFmUrl

    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toast: SharedFlow<String> = _toast.asSharedFlow()

    private val _targetPlayer = MutableStateFlow("")
    private fun playTarget() = _targetPlayer.value.ifBlank { myPlayerId }

    init {
        // Seed from the route so the header paints before the round-trip lands.
        _artist.value = ref
        viewModelScope.launch { settings.targetPlayer.collect { _targetPlayer.value = it } }
        loadArtist()
    }

    fun loadArtist() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                if (isLocal) {
                    val sc = source
                    if (sc == null) { _error.value = "That library isn't connected"; return@launch }
                    if (!resolveRef()) return@launch
                    val (artistMeta, albumList) = sc.artistDetail(ref.itemId)
                    if (artistMeta != null) { ref = artistMeta; _artist.value = artistMeta }
                    _albums.value = albumList.distinctBy { it.itemId }
                    // One call now carries the biography, the links and the similar
                    // artists it was previously asked not to send.
                    runCatching { sc.artistInfo(ref.itemId, similarCount = 12) }.getOrNull()?.let {
                        _biography.value = it.biography
                        _lastFmUrl.value = it.lastFmUrl
                        _similar.value = Load.Ready(it.similar)
                    } ?: run { _similar.value = Load.Ready(emptyList()) }
                } else {
                    if (!resolveRef()) return@launch
                    val artistMeta = maRepo.getArtist(ref)
                    if (artistMeta != null) { ref = artistMeta; _artist.value = artistMeta }
                    // De-duplicated by id: MA answers an artist's albums per provider
                    // mapping and concatenates the results, so an artist held by two
                    // providers comes back with every album twice.
                    _albums.value = maRepo.artistAlbums(ref).distinctBy { it.itemId }
                    // MA's own `metadata.description` first — it is the same field
                    // the web UI shows, and it costs nothing extra.
                    _biography.value = ref.description
                        ?: artistMeta?.description
                        ?: navidromeBiographyFor(ref.name)
                    _similar.value = Load.Ready(
                        runCatching { maRepo.similarArtists(ref).distinctBy { it.itemId } }
                            .getOrDefault(emptyList()),
                    )
                }
                loadTopTracks()
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load artist"
            }
            _loading.value = false
        }
    }

    /**
     * Top tracks, after the albums rather than before them.
     *
     * Its own coroutine and its own load state: this is a bonus shelf, and an artist
     * whose top tracks time out should still show a discography.
     */
    private fun loadTopTracks() {
        viewModelScope.launch {
            _topTracks.value = Load.Loading
            val tracks = runCatching {
                if (isLocal) {
                    // Keyed by artist *name* rather than id — the one Subsonic
                    // endpoint that is, and the shape the interface kept.
                    source?.topSongs(ref.name, count = 10).orEmpty()
                } else {
                    maRepo.topTracks(ref).take(10)
                }
            }.getOrDefault(emptyList())
            _topTracks.value = Load.Ready(tracks)
        }
    }

    /**
     * Borrow an artist biography from Navidrome for an artist we are browsing on
     * Music Assistant.
     *
     * Navidrome fills these from Last.fm, and MA often has nothing — so the About
     * section existed on one backend and not the other for the same artist, which is
     * the gap this closes.
     *
     * **Matched on the name, exactly.** Navidrome can only be asked for an artist by
     * id, so the name has to be searched first, and a search returns near misses
     * cheerfully. Anything short of an exact name match is dropped rather than shown:
     * the failure mode of guessing is a confident biography of the wrong musician,
     * which is worse than no biography at all. Same reasoning as the lyrics lookup.
     *
     * Returns null on any failure — no server configured, no match, no biography.
     * This is a bonus, so it never surfaces an error.
     */
    private suspend fun navidromeBiographyFor(artistName: String): String? {
        if (artistName.isBlank()) return null
        return runCatching {
            val url = settings.navUrl.first().trim()
            if (url.isBlank()) return null
            // Deliberately a Navidrome client and not [source]: the point of this
            // is to borrow a biography from Navidrome *while browsing Music
            // Assistant*, so the active library is the wrong place to ask.
            val sc = SubsonicClient(url, settings.navUsername.first(), settings.navPassword.first())
            val match = sc.search(artistName, limit = 10).artists
                .firstOrNull { it.name.equals(artistName, ignoreCase = true) }
                ?: return null
            sc.getArtistInfo2(match.itemId)
        }.getOrNull()
    }

    /**
     * Turn a name-only [ref] into a real library item, by searching for it.
     *
     * Returns false — with the error already set — when the artist isn't in the
     * library, so the caller stops rather than asking the API for an artist whose
     * id is actually a display name.
     */
    private suspend fun resolveRef(): Boolean {
        if (!resolveByName || resolved) return true
        // A joined multi-artist credit ("Wilco, Billy Bragg") links to the first name.
        val wanted = initialName.substringBefore(",").trim().ifBlank { initialName }
        val hits =
            if (isLocal) source?.search(wanted, limit = 10)?.artists.orEmpty()
            else maRepo.search(wanted, limit = 10).artists
        val hit = hits.firstOrNull { it.name.equals(wanted, ignoreCase = true) } ?: hits.firstOrNull()
        if (hit == null) {
            _error.value = "Couldn't find $wanted in your library"
            return false
        }
        ref = hit
        resolved = true
        // The route's art is usually the album cover the user tapped from, which is
        // better than nothing while the artist's own image loads.
        _artist.value = hit.copy(image = hit.image ?: initialArt)
        return true
    }

    // --- playback actions -------------------------------------------------

    /**
     * Everything by this artist, in album order — what the hero's Play / Shuffle /
     * Add to Queue and the download button all act on. Empty until first use, then
     * held: the Subsonic path costs a round-trip per album.
     */
    private val _catalogue = MutableStateFlow<List<MaItem>>(emptyList())

    /**
     * How many album reads to have in flight at once when building [catalogue].
     *
     * Enough to hide the latency of a LAN round-trip; few enough that a self-hosted
     * Navidrome on a Raspberry Pi is not asked to serve forty queries simultaneously.
     */
    private val CATALOGUE_CONCURRENCY = 6

    private suspend fun catalogue(): List<MaItem> {
        _catalogue.value.takeIf { it.isNotEmpty() }?.let { return it }
        val all = try {
            val sc = source
            when {
                // The albums are already loaded, so walk those rather than asking
                // for the artist's albums a second time.
                //
                // In parallel, bounded. This was a sequential `flatMap`, so a
                // forty-album artist meant forty serial round-trips before anything
                // could be played — several seconds on a LAN, and much worse over a
                // VPN. The cap keeps a large discography from opening forty sockets
                // at once, which a small self-hosted server handles badly.
                isLocal && sc != null -> coroutineScope {
                    val gate = Semaphore(CATALOGUE_CONCURRENCY)
                    _albums.value
                        .map { album -> async { gate.withPermit { sc.albumDetail(album.itemId).second } } }
                        .awaitAll()
                        .flatten()
                }
                isLocal -> emptyList()
                else -> maRepo.artistTracks(ref)
            }
        } catch (_: Exception) { emptyList() }
        val tracks = all.distinctBy { it.itemId }
        _catalogue.value = tracks
        return tracks
    }

    /** Runs [block] over the artist's catalogue, or says why it can't. */
    private fun onCatalogue(verb: String, block: suspend (List<MaItem>) -> Unit) {
        viewModelScope.launch {
            try {
                val tracks = catalogue()
                if (tracks.isEmpty()) {
                    _toast.tryEmit("No tracks for ${ref.name}")
                    return@launch
                }
                block(tracks)
            } catch (e: Exception) { _toast.tryEmit(e.message ?: "Couldn't $verb") }
        }
    }

    /** Play everything by this artist, in order. */
    fun playAll() = onCatalogue("play") { tracks ->
        if (isLocal) {
            stopMaPlayback()
            localPlayer.setShuffle(false)
            localPlayer.setQueue(localTracks(tracks))
        } else {
            maRepo.playOn(playTarget(), tracks.mapNotNull { it.uri }, "replace")
        }
        _toast.tryEmit("Playing ${ref.name}")
    }

    /** Shuffle and play everything by this artist. */
    fun shuffleAll() = onCatalogue("shuffle") { tracks ->
        if (isLocal) {
            stopMaPlayback()
            // Shuffle on *before* the queue is set, so the play order is built
            // shuffled rather than starting on track 1 and jumping.
            localPlayer.setShuffle(true)
            localPlayer.setQueue(localTracks(tracks))
        } else {
            maRepo.playOn(playTarget(), tracks.mapNotNull { it.uri }.shuffled(), "replace")
            maRepo.setShuffle(playTarget(), true)
        }
        _toast.tryEmit("Shuffling ${ref.name}")
    }

    /**
     * Put everything by this artist straight after what is playing.
     *
     * The long-press sheet has offered this per album since it was written; the
     * artist's own catalogue had play, shuffle and add-to-the-end and no way to say
     * "next", which is the one that does not make you wait out the rest of a queue.
     */
    fun playNext() = onCatalogue("play next") { tracks ->
        if (isLocal) {
            localPlayer.playNext(localTracks(tracks))
        } else {
            maRepo.playOn(playTarget(), tracks.mapNotNull { it.uri }, "next")
        }
        _toast.tryEmit("Playing ${ref.name} next")
    }

    /** Add everything by this artist to the end of the queue. */
    fun addToQueue() = onCatalogue("add to queue") { tracks ->
        if (isLocal) {
            localPlayer.addToQueue(localTracks(tracks))
        } else {
            maRepo.playOn(playTarget(), tracks.mapNotNull { it.uri }, "add")
        }
        _toast.tryEmit("Added ${tracks.size} tracks to queue")
    }

    /**
     * Play or queue one of this artist's albums, from the long-press sheet.
     *
     * [option] is Music Assistant's own vocabulary — `replace` | `next` | `add` —
     * and MA takes an album uri on `play_media` exactly as it takes a track's. On
     * Navidrome the album has to be resolved to its tracks first, because the local
     * player is a track queue.
     */
    fun playAlbum(album: MaItem, option: String) {
        viewModelScope.launch {
            try {
                if (isLocal) {
                    val sc = source ?: throw IllegalStateException("That library isn't connected")
                    val tracks = localTracks(sc.albumDetail(album.itemId).second)
                    if (tracks.isEmpty()) { _toast.tryEmit("Nothing to play"); return@launch }
                    when (option) {
                        "next" -> localPlayer.playNext(tracks)
                        "add" -> localPlayer.addToQueue(tracks)
                        else -> {
                            stopMaPlayback()
                            localPlayer.setShuffle(false)
                            localPlayer.setQueue(tracks)
                        }
                    }
                } else {
                    val uri = album.uri ?: run { _toast.tryEmit("Couldn't play that"); return@launch }
                    maRepo.playOn(playTarget(), listOf(uri), option)
                }
                _toast.tryEmit(
                    when (option) {
                        "next" -> "Playing next"
                        "add" -> "Added to queue"
                        else -> "Playing ${album.name}"
                    }
                )
            } catch (e: Exception) { _toast.tryEmit(e.message ?: "Couldn't play") }
        }
    }

    /**
     * Play one of the top tracks, with the rest of that shelf behind it.
     *
     * The shelf is the queue, not the artist's whole catalogue: tapping the third of
     * ten best-known songs means "play these", and replacing that with 300 album
     * tracks would be a different request than the one made.
     */
    fun playTrack(track: MaItem) {
        val shelf = (_topTracks.value as? Load.Ready)?.value.orEmpty()
        viewModelScope.launch {
            try {
                if (isLocal) {
                    val start = shelf.indexOfFirst { it.itemId == track.itemId }.coerceAtLeast(0)
                    localPlayer.setShuffle(false)
                    localPlayer.setQueue(localTracks(shelf), start)
                } else {
                    // MA's play_media with "replace" always starts at index 0, so the
                    // tapped track goes first and the rest follows it.
                    track.uri?.let { maRepo.playOn(playTarget(), listOf(it), "replace") }
                    val rest = shelf.mapNotNull { it.uri }.filter { it != track.uri }
                    if (rest.isNotEmpty()) maRepo.playOn(playTarget(), rest, "add")
                    maRepo.play(playTarget())
                }
                _toast.tryEmit("Playing ${track.name}")
            } catch (e: Exception) {
                _toast.tryEmit(e.message ?: "Couldn't play")
            }
        }
    }

    /** Star or unstar one of the top tracks, optimistically. */
    fun toggleTrackFavorite(track: MaItem) {
        val want = !track.favorite
        _topTracks.update { load ->
            if (load !is Load.Ready) load
            else Load.Ready(load.value.map { if (it.itemId == track.itemId) it.copy(favorite = want) else it })
        }
        viewModelScope.launch {
            val ok = runCatching {
                if (isLocal) { source?.setStarred(track, want); true }
                else {
                    if (want) maRepo.addFavorite(track) else maRepo.removeFavorite(track)
                    true
                }
            }.getOrDefault(false)
            if (!ok) {
                // Put it back rather than leaving the heart lying about the server.
                _topTracks.update { load ->
                    if (load !is Load.Ready) load
                    else Load.Ready(load.value.map { if (it.itemId == track.itemId) it.copy(favorite = !want) else it })
                }
                _toast.tryEmit("Couldn't update favourite")
            }
        }
    }

    /** Take one album offline, from the long-press sheet. */
    fun downloadAlbum(album: MaItem) {
        val sc = source
        if (!isLocal || sc == null) { _toast.tryEmit("That library isn't connected"); return }
        viewModelScope.launch {
            try {
                val tracks = sc.albumDetail(album.itemId).second
                val pending = tracks.filterNot { downloads.isDownloaded(it.itemId) }
                if (pending.isEmpty()) { _toast.tryEmit("Already downloaded"); return@launch }
                _toast.tryEmit("Downloading ${pending.size} tracks…")
                val ok = downloads.downloadAll(
                    pending,
                    urlFor = { sc.downloadUrl(it.itemId) },
                    wifiOnly = settings.downloadWifiOnly.first(),
                    storageCapMb = settings.downloadStorageCapMb.first(),
                )
                _toast.tryEmit(
                    when (ok) {
                        pending.size -> "Downloaded ${album.name}"
                        0 -> "Download failed"
                        else -> "Downloaded $ok of ${pending.size}"
                    }
                )
            } catch (e: Exception) { _toast.tryEmit(e.message ?: "Couldn't download") }
        }
    }

    /**
     * Favourite (or unfavourite) the artist.
     *
     * Flipped locally first — the command is a round-trip and the heart should not
     * sit still while it happens — and rolled back if the server refuses. Both
     * backends already take an artist: MA's `favorites/add_item` accepts any media
     * item's uri, and Subsonic's `star` has an `artistId` parameter.
     */
    fun toggleFavorite() {
        val current = _artist.value ?: return
        val wanted = !current.favorite
        _artist.value = current.copy(favorite = wanted)
        viewModelScope.launch {
            try {
                val sc = source
                when {
                    isLocal && sc != null -> sc.setStarred(current, wanted)
                    isLocal -> throw IllegalStateException("That library isn't connected")
                    wanted -> maRepo.addFavorite(current)
                    else -> maRepo.removeFavorite(current)
                }
                ref = ref.copy(favorite = wanted)
                _toast.tryEmit(if (wanted) "Added to favorites" else "Removed from favorites")
            } catch (e: Exception) {
                _artist.value = _artist.value?.copy(favorite = !wanted)
                _toast.tryEmit(e.message ?: "Couldn't toggle favorite")
            }
        }
    }

    /** A track list as a local queue, offline copies preferred over the stream. */
    private fun localTracks(list: List<MaItem>) = list.map {
        downloads.toLocalTrack(it, streamUrl = source?.streamUrl(it.itemId))
    }

    // --- offline ----------------------------------------------------------

    /** Only Navidrome hands over the file; MA streams, so there is nothing to keep. */
    // See the same property on [AlbumDetailViewModel] for why this is not a live
    // source read.
    val canDownload: Boolean get() = isLocal

    /**
     * Every track by this artist is already on the phone.
     *
     * False until the catalogue has been resolved once — the track ids simply aren't
     * known before that, and walking every album of every artist on the off-chance
     * the user might tap Download would be a round-trip per album per screen.
     */
    val allDownloaded: StateFlow<Boolean> =
        combine(_catalogue, downloads.downloads) { tracks, index ->
            tracks.isNotEmpty() && tracks.all { t -> index.any { it.id == t.itemId } }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Download the artist's whole discography for offline. */
    fun downloadAll() {
        val sc = source
        if (!isLocal || sc == null) { _toast.tryEmit("That library isn't connected"); return }
        viewModelScope.launch {
            val tracks = catalogue()
            if (tracks.isEmpty()) { _toast.tryEmit("No tracks for ${ref.name}"); return@launch }
            val pending = tracks.filterNot { downloads.isDownloaded(it.itemId) }
            if (pending.isEmpty()) { _toast.tryEmit("Already downloaded"); return@launch }
            _toast.tryEmit("Downloading ${pending.size} tracks…")
            val ok = downloads.downloadAll(
                pending,
                urlFor = { sc.downloadUrl(it.itemId) },
                wifiOnly = settings.downloadWifiOnly.first(),
                storageCapMb = settings.downloadStorageCapMb.first(),
            )
            _toast.tryEmit(
                when (ok) {
                    pending.size -> "Downloaded ${ref.name}"
                    0 -> "Download failed"
                    else -> "Downloaded $ok of ${pending.size}"
                }
            )
        }
    }

    /**
     * Hand the speaker over before the local player takes it. Fire-and-forget: if MA
     * is down or idle there is nothing on it to stop.
     */
    private fun stopMaPlayback() {
        viewModelScope.launch { runCatching { maRepo.stop(playTarget()) } }
    }

    override fun onCleared() {
        super.onCleared()
        // The local player is process-scoped and shared — leaving the artist screen
        // must not stop the music the user just started from it.
    }
}

class ArtistDetailViewModelFactory(
    private val app: Application,
    private val itemId: String,
    private val provider: String,
    private val name: String,
    private val art: String?,
    private val resolveByName: Boolean = false,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
        ArtistDetailViewModel(app, itemId, provider, name, art, resolveByName) as T
}
