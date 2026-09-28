package com.engabd.sendpin.local

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.engabd.sendpin.library.Capability
import com.engabd.sendpin.library.MusicSource
import com.engabd.sendpin.library.ServerKind
import com.engabd.sendpin.ma.MaAudioFormat
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ma.MaLyrics
import com.engabd.sendpin.ma.MaSearchResults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

private const val PROVIDER = "local"

/**
 * A [MusicSource] backed by audio files on this device, discovered through
 * MediaStore.
 *
 * The user picks one or more music folders during setup (stored as URIs in
 * [ServerConfig.options] under [OPT_FOLDER_URIS]). If no folders are picked, the
 * source scans all audio on external storage.
 *
 * Artists and albums are derived from the embedded metadata rather than the file
 * system: a flat folder of properly tagged tracks still presents as a normal
 * browseable library.
 *
 * ## Scanning
 *
 * The scan runs on the IO dispatcher and is cached until MediaStore says something
 * changed ([Changes]). It used to be a `by lazy` touched from whatever thread asked
 * first — often the main one, where a phone with a big library stalled the first
 * frame — and was then kept for the life of the source, so a file copied onto the
 * phone did not appear until the app was restarted.
 *
 * ## What the tags give
 *
 * Covers ([LocalArt]: embedded, or a `cover.jpg` beside the file), genres, the
 * date a file arrived (for Recently added), and on Android 14+ the sample rate and
 * bit depth for the quality badge. Playlists are MediaStore's own — the ones other
 * players on the phone made or imported from `.m3u` files — read-only here; the
 * app's own playlists sit beside them (see `WithAppPlaylists`).
 */
class LocalMediaSource(
    private val context: Context,
    private val folderUris: List<Uri> = emptyList(),
) : MusicSource {

    override val kind: ServerKind = ServerKind.LOCAL
    override val providerId: String = PROVIDER
    override val serverUrl: String = "This device"
    override var streamFormat: String = "raw"

    override val capabilities: Set<Capability> = buildSet {
        add(Capability.SEARCH)
        add(Capability.TRACKS)
        add(Capability.GENRES)
        add(Capability.PLAYLIST_READ)
        // The two columns the quality badge needs arrived in Android 14; before
        // that MediaStore has a codec and a bitrate and nothing else.
        if (Build.VERSION.SDK_INT >= 34) add(Capability.RICH_FORMAT)
    }

    /** Key under which chosen folder URIs are stored in [ServerConfig.options]. */
    companion object {
        const val OPT_FOLDER_URIS = "localFolderUris"

        /**
         * The permission a MediaStore audio query needs.
         *
         * Declared in the manifest since forever and requested nowhere, which is the
         * other half of "I chose a folder and the library is empty": [scanTracks]
         * catches the resulting `SecurityException` and returns an empty list, so an
         * ungranted permission looked exactly like an empty phone.
         */
        val AUDIO_PERMISSION: String =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                android.Manifest.permission.READ_MEDIA_AUDIO
            } else {
                @Suppress("DEPRECATION")
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            }

        fun hasAudioPermission(context: Context): Boolean =
            androidx.core.content.ContextCompat.checkSelfPermission(context, AUDIO_PERMISSION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED

        private val COLLECTION: Uri = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

        /** The playable uri for a `local:<id>` item id, without needing a scan. */
        internal fun contentUriOf(itemId: String): String? =
            itemId.removePrefix("local:").toLongOrNull()?.let { ContentUris.withAppendedId(COLLECTION, it).toString() }
    }

    /**
     * MediaStore's own change signal, once for the process.
     *
     * A counter rather than a flag: every source compares the generation it scanned
     * at with the current one, so two sources (the library and Android Auto's) each
     * notice the change once, and a change during a scan is not lost — the scan
     * that was running is simply out of date the moment it finishes.
     */
    private object Changes {
        val generation = AtomicLong(0)
        @Volatile private var watching = false

        fun watch(context: Context) {
            if (watching) return
            synchronized(this) {
                if (watching) return
                watching = true
                val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        generation.incrementAndGet()
                    }
                }
                runCatching {
                    val resolver = context.applicationContext.contentResolver
                    resolver.registerContentObserver(COLLECTION, true, observer)
                    @Suppress("DEPRECATION")
                    resolver.registerContentObserver(
                        MediaStore.Audio.Playlists.getContentUri(MediaStore.VOLUME_EXTERNAL), true, observer,
                    )
                }
            }
        }
    }

    /**
     * The picked folders as MediaStore volume/path prefixes — see [FolderScope].
     *
     * A tree uri this cannot decompose (a cloud provider, say) yields nothing, and a
     * folder list that yields *no* scopes at all is treated as "no restriction"
     * rather than as "matches nothing". Returning an empty library for a folder we
     * simply cannot express is the worse of the two failures: it looks like the phone
     * has no music on it.
     */
    private val scopes: List<FolderScope> by lazy {
        folderUris.mapNotNull { uri ->
            val docId = runCatching { android.provider.DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            LocalFolders.scopeOf(docId)
        }
    }

    /** Set by [scanTracks] when the query was refused rather than empty. */
    @Volatile
    private var permissionDenied = false

    private val scanLock = Mutex()
    @Volatile private var cached: List<MaItem>? = null
    @Volatile private var cachedAt = -1L

    /** When each track's file arrived, for [recentlyAdded]. Filled by the scan. */
    @Volatile private var dateAdded: Map<String, Long> = emptyMap()

    private suspend fun reachableTracks(): List<MaItem> {
        Changes.watch(context)
        val gen = Changes.generation.get()
        cached?.takeIf { cachedAt == gen }?.let { return it }
        return scanLock.withLock {
            val now = Changes.generation.get()
            cached?.takeIf { cachedAt == now }?.let { return@withLock it }
            val scanned = withContext(Dispatchers.IO) { scanTracks() }
            cached = scanned
            cachedAt = now
            scanned
        }
    }

    override suspend fun probe(): com.engabd.sendpin.library.SourceError? {
        // Runs the scan, so the answer reflects a real query rather than a guess
        // about permissions the user may have granted since.
        reachableTracks()
        return if (permissionDenied) {
            com.engabd.sendpin.library.SourceError("Allow access to audio on this device, then try again.")
        } else {
            null
        }
    }

    override suspend fun artists(): List<MaItem> =
        reachableTracks()
            .groupBy { it.subtitle.orEmpty().lowercase() to it.subtitle }
            .map { (_, tracks) ->
                val name = tracks.first().subtitle ?: "Unknown artist"
                MaItem(
                    itemId = "artist:${name}",
                    provider = PROVIDER,
                    name = name,
                    uri = "artist:${name}",
                    mediaType = "artist",
                    subtitle = "${tracks.size} ${if (tracks.size == 1) "track" else "tracks"}",
                    // One of their covers: a local file has no artist photo, and an
                    // album of theirs says who this is better than a blank circle.
                    image = tracks.firstNotNullOfOrNull { it.image },
                    duration = null,
                )
            }
            .sortedBy { it.name.lowercase() }

    private fun albumItem(name: String, tracks: List<MaItem>, artist: String? = tracks.first().subtitle) = MaItem(
        itemId = "album:${name}",
        provider = PROVIDER,
        name = name,
        uri = "album:${name}",
        mediaType = "album",
        subtitle = artist,
        image = tracks.firstNotNullOfOrNull { it.image },
        duration = tracks.sumOf { it.duration ?: 0 }.takeIf { it > 0 },
        year = tracks.firstNotNullOfOrNull { it.year },
        genres = tracks.flatMap { it.genres }.distinct(),
    )

    private fun albumsOf(tracks: List<MaItem>): List<Pair<String, List<MaItem>>> =
        tracks.groupBy { (it.album ?: "Unknown album").lowercase() }
            .map { (_, t) -> (t.first().album ?: "Unknown album") to t }

    override suspend fun albums(offset: Int, limit: Int): List<MaItem> =
        albumsOf(reachableTracks())
            .map { (name, tracks) -> albumItem(name, tracks) }
            .sortedBy { it.name.lowercase() }
            .drop(offset)
            .take(limit)

    override suspend fun artistDetail(id: String): Pair<MaItem?, List<MaItem>> {
        val artist = id.removePrefix("artist:")
        val theirs = reachableTracks().filter { it.subtitle.equals(artist, ignoreCase = true) }
        val albums = albumsOf(theirs)
            .map { (name, tracks) -> albumItem(name, tracks, artist) }
            .sortedBy { it.name.lowercase() }
        val header = MaItem(
            itemId = "artist:$artist",
            provider = PROVIDER,
            name = theirs.firstOrNull()?.subtitle ?: artist,
            uri = "artist:$artist",
            mediaType = "artist",
            subtitle = "${albums.size} ${if (albums.size == 1) "album" else "albums"}",
            image = albums.firstNotNullOfOrNull { it.image },
            duration = null,
        )
        return (if (theirs.isEmpty()) null else header) to albums
    }

    override suspend fun albumDetail(id: String): Pair<MaItem?, List<MaItem>> {
        val album = id.removePrefix("album:")
        val tracks = reachableTracks()
            .filter { (it.album ?: "Unknown album").equals(album, ignoreCase = true) }
            .sortedWith(compareBy({ it.discNumber ?: 0 }, { it.trackNumber ?: 0 }))
        val header = tracks.firstOrNull()?.let { albumItem(it.album ?: album, tracks) }
        return header to tracks
    }

    override suspend fun children(item: MaItem): List<MaItem> = when (item.mediaType) {
        "artist" -> artistDetail(item.itemId).second
        "album" -> albumDetail(item.itemId).second
        "playlist" -> playlistTracks(item.itemId)
        "genre" -> songsByGenre(item.itemId)
        else -> emptyList()
    }

    override suspend fun tracksUnder(item: MaItem): List<MaItem> = when (item.mediaType) {
        "track" -> listOf(item)
        "album" -> albumDetail(item.itemId).second
        "artist" -> reachableTracks().filter { it.subtitle.equals(item.name, ignoreCase = true) }
        else -> children(item)
    }

    override suspend fun song(id: String): MaItem? = reachableTracks().firstOrNull { it.itemId == id }

    override suspend fun search(query: String, limit: Int): MaSearchResults {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return MaSearchResults(emptyList(), emptyList(), emptyList(), emptyList())
        val all = reachableTracks()
        val tracks = all.filter {
            it.name.lowercase().contains(q) ||
                it.subtitle?.lowercase()?.contains(q) == true ||
                it.album?.lowercase()?.contains(q) == true
        }
        // Albums and artists as well as songs, the way every server answers: typing
        // an artist's name used to list each of their songs and never the artist.
        val albums = albumsOf(tracks.filter { it.album?.lowercase()?.contains(q) == true })
            .map { (name, t) -> albumItem(name, t) }
        val artists = artists().filter { it.name.lowercase().contains(q) }
        return MaSearchResults(
            tracks = tracks.take(limit),
            albums = albums.take(limit),
            artists = artists.take(limit),
            playlists = emptyList(),
        )
    }

    /** By the date the file arrived on the phone, newest first — not by release year. */
    override suspend fun recentlyAdded(limit: Int): List<MaItem> {
        val tracks = reachableTracks()
        val added = dateAdded
        return tracks.sortedByDescending { added[it.itemId] ?: 0L }.take(limit)
    }

    override fun streamUrl(id: String, format: String): String =
        // From the id alone: the uri is a pure function of it, and this is called on
        // the playback path, which must not wait for (or trigger) a scan.
        contentUriOf(id) ?: cached?.firstOrNull { it.itemId == id }?.uri.orEmpty()

    override fun downloadUrl(id: String): String = streamUrl(id)

    override fun coverUrl(id: String?, size: Int): String? =
        id?.removePrefix("local:")?.toLongOrNull()?.let(LocalArt::url)

    override suspend fun setStarred(item: MaItem, starred: Boolean) {}
    override suspend fun scrobble(id: String, completed: Boolean, startedAtMs: Long?, positionMs: Long?) {}
    override suspend fun lyrics(songId: String): MaLyrics? = null

    /** Every file in the picked folders, by title — the phone is the whole library. */
    override suspend fun tracks(offset: Int, limit: Int): List<MaItem> =
        reachableTracks().sortedBy { it.name.lowercase() }.drop(offset).take(limit)

    override suspend fun recentlyPlayed(limit: Int): List<MaItem> = emptyList()
    override suspend fun mostPlayed(limit: Int): List<MaItem> = emptyList()
    override suspend fun favorites(): MaSearchResults = MaSearchResults(emptyList(), emptyList(), emptyList(), emptyList())
    override suspend fun randomSongs(size: Int): List<MaItem> =
        reachableTracks().shuffled().take(size)
    override suspend fun randomAlbums(limit: Int): List<MaItem> = albums().shuffled().take(limit)

    // ── Genres ────────────────────────────────────────────────────────────

    override suspend fun genres(): List<MaItem> =
        reachableTracks()
            .flatMap { t -> t.genres.map { it to t } }
            .groupBy({ it.first.lowercase() }, { it })
            .map { (_, pairs) ->
                val name = pairs.first().first
                val count = pairs.map { it.second.itemId }.distinct().size
                MaItem(
                    itemId = name,
                    provider = PROVIDER,
                    name = name,
                    uri = "genre:$name",
                    mediaType = "genre",
                    subtitle = if (count == 1) "1 song" else "$count songs",
                    image = pairs.firstNotNullOfOrNull { it.second.image },
                    duration = null,
                )
            }
            .sortedBy { it.name.lowercase() }

    override suspend fun songsByGenre(genre: String, count: Int, offset: Int): List<MaItem> =
        reachableTracks()
            .filter { t -> t.genres.any { it.equals(genre, ignoreCase = true) } }
            .sortedWith(compareBy({ it.subtitle?.lowercase() }, { it.album?.lowercase() }, { it.discNumber ?: 0 }, { it.trackNumber ?: 0 }))
            .drop(offset)
            .take(count)

    // ── Playlists: MediaStore's, read-only ───────────────────────────────

    /**
     * The playlists MediaStore holds — made by another player on this phone, or
     * imported from an `.m3u` file by the media scanner.
     *
     * The API is deprecated in favour of files, but it is still what the scanner
     * fills from `.m3u` and still what other players write, so it is the one place
     * a local playlist can be found without asking for access to every file.
     */
    @Suppress("DEPRECATION")
    override suspend fun playlists(): List<MaItem> = withContext(Dispatchers.IO) {
        if (!hasAudioPermission(context)) return@withContext emptyList()
        val known = reachableTracks().associateBy { it.itemId }
        val uri = MediaStore.Audio.Playlists.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val lists = mutableListOf<MaItem>()
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Audio.Playlists._ID, MediaStore.Audio.Playlists.NAME),
                null, null,
                MediaStore.Audio.Playlists.NAME + " ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val name = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                    val members = memberIds(id).mapNotNull { known[it] }
                    lists += MaItem(
                        itemId = "playlist:$id",
                        provider = PROVIDER,
                        name = name,
                        uri = "playlist:$id",
                        mediaType = "playlist",
                        subtitle = if (members.size == 1) "1 song" else "${members.size} songs",
                        image = members.firstNotNullOfOrNull { it.image },
                        duration = members.sumOf { it.duration ?: 0 }.takeIf { it > 0 },
                    )
                }
            }
        }
        lists
    }

    override suspend fun playlistTracks(id: String): List<MaItem> {
        val playlistId = id.removePrefix("playlist:").toLongOrNull() ?: return emptyList()
        val known = reachableTracks().associateBy { it.itemId }
        // Only the members this library can see: a playlist can name files outside
        // the picked folders, and a row for one would play nothing.
        return withContext(Dispatchers.IO) { memberIds(playlistId) }.mapNotNull { known[it] }
    }

    @Suppress("DEPRECATION")
    private fun memberIds(playlistId: Long): List<String> {
        val out = mutableListOf<String>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Playlists.Members.getContentUri(MediaStore.VOLUME_EXTERNAL, playlistId),
                arrayOf(MediaStore.Audio.Playlists.Members.AUDIO_ID),
                null, null,
                MediaStore.Audio.Playlists.Members.PLAY_ORDER + " ASC",
            )?.use { c -> while (c.moveToNext()) out += "local:${c.getLong(0)}" }
        }
        return out
    }

    // ── The scan ──────────────────────────────────────────────────────────

    private fun scanTracks(): List<MaItem> {
        val rich = Build.VERSION.SDK_INT >= 34
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.TRACK)
            add(MediaStore.Audio.Media.YEAR)
            add(MediaStore.Audio.Media.MIME_TYPE)
            add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.Media.DATE_ADDED)
            // API 30 and up; minSdk is 31.
            add(MediaStore.Audio.Media.GENRE)
            add(MediaStore.Audio.Media.BITRATE)
            add(MediaStore.Audio.Media.COMPOSER)
            // The two columns a picked folder is actually expressed in. Both exist
            // from API 29 and minSdk is 31, so neither needs a guard.
            add(MediaStore.Audio.Media.RELATIVE_PATH)
            add(MediaStore.Audio.Media.VOLUME_NAME)
            if (rich) {
                add(MediaStore.Audio.Media.SAMPLERATE)
                add(MediaStore.Audio.Media.BITS_PER_SAMPLE)
            }
        }.toTypedArray()
        val tracks = mutableListOf<MaItem>()
        val added = HashMap<String, Long>()
        var cursor: Cursor? = null
        if (!hasAudioPermission(context)) {
            permissionDenied = true
            return tracks
        }
        permissionDenied = false
        try {
            cursor = context.contentResolver.query(
                COLLECTION,
                projection,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                null,
                MediaStore.Audio.Media.TITLE + " ASC",
            )
            cursor?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val trackCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
                val yearCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
                val mimeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val addedCol = c.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
                val genreCol = c.getColumnIndex(MediaStore.Audio.Media.GENRE)
                val bitrateCol = c.getColumnIndex(MediaStore.Audio.Media.BITRATE)
                val composerCol = c.getColumnIndex(MediaStore.Audio.Media.COMPOSER)
                val relCol = c.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                val volCol = c.getColumnIndex(MediaStore.Audio.Media.VOLUME_NAME)
                val rateCol = if (rich) c.getColumnIndex(MediaStore.Audio.Media.SAMPLERATE) else -1
                val bitsCol = if (rich) c.getColumnIndex(MediaStore.Audio.Media.BITS_PER_SAMPLE) else -1
                while (c.moveToNext()) {
                    // Filtered here rather than after the fact: a phone with ten
                    // thousand tracks builds ten thousand MaItems otherwise, all but a
                    // handful of which are thrown away.
                    val relPath = if (relCol >= 0) c.getString(relCol) else null
                    val volume = if (volCol >= 0) c.getString(volCol) else null
                    if (!LocalFolders.reachable(scopes, volume, relPath)) continue
                    val id = c.getLong(idCol)
                    val itemId = "local:$id"
                    val contentUri = ContentUris.withAppendedId(COLLECTION, id).toString()
                    val title = c.getString(titleCol)?.takeIf { it.isNotBlank() } ?: "Unknown"
                    val artist = c.getString(artistCol)?.takeIf { it.isNotBlank() && it != "<unknown>" }
                    val album = c.getString(albumCol)?.takeIf { it.isNotBlank() }
                    val durationSec = c.getInt(durationCol) / 1000
                    val trackNum = c.getInt(trackCol).takeIf { it > 0 }
                    val discNum = trackNum?.let { it / 1000 }?.takeIf { it > 0 }
                    val cleanTrackNum = trackNum?.let { it % 1000 }?.takeIf { it > 0 }
                    val year = c.getInt(yearCol).takeIf { it > 0 }
                    val mime = c.getString(mimeCol)
                    val size = c.getLong(sizeCol)
                    if (addedCol >= 0) added[itemId] = c.getLong(addedCol)
                    val genres = if (genreCol >= 0) LocalTags.genres(c.getString(genreCol)) else emptyList()
                    val bitrate = if (bitrateCol >= 0) c.getInt(bitrateCol) else 0
                    val rate = if (rateCol >= 0) c.getInt(rateCol) else 0
                    val bits = if (bitsCol >= 0) c.getInt(bitsCol) else 0
                    tracks += MaItem(
                        itemId = itemId,
                        provider = PROVIDER,
                        name = title,
                        uri = contentUri,
                        mediaType = "track",
                        subtitle = artist,
                        image = LocalArt.url(id),
                        duration = durationSec,
                        album = album,
                        trackNumber = cleanTrackNum,
                        discNumber = discNum,
                        parentId = album?.let { "album:${it}" },
                        year = year,
                        genres = genres,
                        composer = if (composerCol >= 0) c.getString(composerCol)?.takeIf { it.isNotBlank() } else null,
                        audioFormat = LocalTags.codecOf(mime)?.let { codec ->
                            MaAudioFormat(
                                codec = codec,
                                sampleRate = rate,
                                bitDepth = bits,
                                // MediaStore's is bits per second; the badge wants kb/s.
                                bitRate = bitrate / 1000,
                                channels = 0,
                                sizeBytes = size,
                            )
                        },
                    )
                }
            }
        } catch (e: SecurityException) {
            // Recorded rather than swallowed, so [probe] can tell the difference
            // between "no permission" and "no music" — they used to look identical.
            permissionDenied = true
        } catch (_: Exception) {
            // No external storage, or a provider that refused the query.
        } finally {
            cursor?.close()
        }
        dateAdded = added
        return tracks
    }
}

/** The pure parts of reading MediaStore's tag columns, so they can be tested. */
internal object LocalTags {
    /**
     * A genre column as a list. Files carry several as `Rock;Pop`, `Rock / Pop` or
     * `Rock, Pop` depending on the tagger; ID3v1's numeric `(17)` form is left out,
     * since MediaStore has already mapped those to names where it could.
     */
    fun genres(raw: String?): List<String> =
        raw.orEmpty()
            .split(';', '/', ',', '\u0000')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !(it.startsWith("(") && it.endsWith(")")) }
            .distinctBy { it.lowercase() }

    /**
     * The codec name the quality badge shows, from a MIME type.
     *
     * The old code passed `substringBefore('/')` — which is `"audio"` for every file
     * — so a local FLAC's badge said "AUDIO".
     */
    fun codecOf(mime: String?): String? {
        val sub = mime?.substringAfter('/', "")?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        return when (sub) {
            "flac", "x-flac" -> "flac"
            "mpeg", "mp3", "mpeg3", "x-mpeg" -> "mp3"
            "mp4", "m4a", "x-m4a", "aac", "mp4a-latm" -> "aac"
            "ogg", "vorbis" -> "vorbis"
            "opus" -> "opus"
            "wav", "x-wav", "vnd.wave" -> "wav"
            "x-aiff", "aiff" -> "aiff"
            "x-ms-wma" -> "wma"
            "x-ape", "ape" -> "ape"
            "alac" -> "alac"
            else -> sub.removePrefix("x-")
        }
    }
}
