package com.engabd.sendpin.local

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.engabd.sendpin.library.Capability
import com.engabd.sendpin.library.MusicSource
import com.engabd.sendpin.library.ServerKind
import com.engabd.sendpin.ma.MaAudioFormat
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ma.MaLyrics
import com.engabd.sendpin.ma.MaSearchResults

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
 */
class LocalMediaSource(
    private val context: Context,
    private val folderUris: List<Uri> = emptyList(),
) : MusicSource {

    override val kind: ServerKind = ServerKind.LOCAL
    override val providerId: String = PROVIDER
    override val serverUrl: String = "This device"
    override var streamFormat: String = "raw"

    override val capabilities: Set<Capability> = setOf(
        Capability.SEARCH,
        Capability.TRACKS,
    )

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

    private val allTracks: List<MaItem> by lazy { scanTracks() }

    private fun reachableTracks(): List<MaItem> = allTracks

    override suspend fun probe(): com.engabd.sendpin.library.SourceError? {
        // Touches the lazy scan, so the answer reflects a real query rather than a
        // guess about permissions the user may have granted since.
        allTracks
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
                    image = null,
                    duration = null,
                )
            }
            .sortedBy { it.name.lowercase() }

    override suspend fun albums(offset: Int, limit: Int): List<MaItem> =
        reachableTracks()
            .groupBy { (it.album ?: "Unknown album").lowercase() to it.album }
            .map { (_, tracks) ->
                val name = tracks.first().album ?: "Unknown album"
                val artist = tracks.first().subtitle
                MaItem(
                    itemId = "album:${name}",
                    provider = PROVIDER,
                    name = name,
                    uri = "album:${name}",
                    mediaType = "album",
                    subtitle = artist,
                    image = tracks.firstNotNullOfOrNull { it.image },
                    duration = null,
                )
            }
            .sortedBy { it.name.lowercase() }
            .drop(offset)
            .take(limit)

    override suspend fun artistDetail(id: String): Pair<MaItem?, List<MaItem>> {
        val artist = id.removePrefix("artist:")
        val albums = reachableTracks()
            .filter { it.subtitle.equals(artist, ignoreCase = true) }
            .groupBy { (it.album ?: "Unknown album").lowercase() to it.album }
            .map { (_, tracks) ->
                val name = tracks.first().album ?: "Unknown album"
                MaItem(
                    itemId = "album:${name}",
                    provider = PROVIDER,
                    name = name,
                    uri = "album:${name}",
                    mediaType = "album",
                    subtitle = artist,
                    image = tracks.firstNotNullOfOrNull { it.image },
                    duration = null,
                )
            }
            .sortedBy { it.name.lowercase() }
        return (albums.firstOrNull { it.name.equals(artist, ignoreCase = true) } ?: albums.firstOrNull()) to albums
    }

    override suspend fun albumDetail(id: String): Pair<MaItem?, List<MaItem>> {
        val album = id.removePrefix("album:")
        val tracks = reachableTracks()
            .filter { (it.album ?: "Unknown album").equals(album, ignoreCase = true) }
            .sortedWith(compareBy({ it.discNumber ?: 0 }, { it.trackNumber ?: 0 }))
        val header = tracks.firstOrNull()?.let {
            MaItem(
                itemId = "album:${album}",
                provider = PROVIDER,
                name = album,
                uri = "album:${album}",
                mediaType = "album",
                subtitle = it.subtitle,
                image = it.image,
                duration = null,
            )
        }
        return header to tracks
    }

    override suspend fun children(item: MaItem): List<MaItem> = when (item.mediaType) {
        "artist" -> artistDetail(item.itemId).second
        "album" -> albumDetail(item.itemId).second
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
        val tracks = reachableTracks().filter {
            it.name.lowercase().contains(q) ||
                it.subtitle?.lowercase()?.contains(q) == true ||
                it.album?.lowercase()?.contains(q) == true
        }.take(limit)
        return MaSearchResults(tracks = tracks, albums = emptyList(), artists = emptyList(), playlists = emptyList())
    }

    override suspend fun recentlyAdded(limit: Int): List<MaItem> =
        reachableTracks().sortedByDescending { it.year ?: 0 }.take(limit)

    override fun streamUrl(id: String, format: String): String {
        val track = allTracks.firstOrNull { it.itemId == id }
        return track?.uri ?: ""
    }

    override fun downloadUrl(id: String): String = streamUrl(id)
    override fun coverUrl(id: String?, size: Int): String? = null

    override suspend fun setStarred(item: MaItem, starred: Boolean) {}
    override suspend fun scrobble(id: String, completed: Boolean, startedAtMs: Long?, positionMs: Long?) {}
    override suspend fun lyrics(songId: String): MaLyrics? = null

    /** Every file in the picked folders, by title — the phone is the whole library. */
    override suspend fun tracks(offset: Int, limit: Int): List<MaItem> =
        reachableTracks().sortedBy { it.name.lowercase() }.drop(offset).take(limit)

    // Not supported on local files.
    override suspend fun playlists(): List<MaItem> = emptyList()
    override suspend fun playlistTracks(id: String): List<MaItem> = emptyList()
    override suspend fun recentlyPlayed(limit: Int): List<MaItem> = emptyList()
    override suspend fun mostPlayed(limit: Int): List<MaItem> = emptyList()
    override suspend fun favorites(): MaSearchResults = MaSearchResults(emptyList(), emptyList(), emptyList(), emptyList())
    override suspend fun randomSongs(size: Int): List<MaItem> =
        reachableTracks().shuffled().take(size)
    override suspend fun randomAlbums(limit: Int): List<MaItem> = albums().shuffled().take(limit)
    override suspend fun genres(): List<MaItem> = emptyList()
    override suspend fun songsByGenre(genre: String, count: Int, offset: Int): List<MaItem> = emptyList()

    private fun scanTracks(): List<MaItem> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.SIZE,
            // The two columns a picked folder is actually expressed in. Both exist
            // from API 29 and minSdk is 31, so neither needs a guard.
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.VOLUME_NAME,
        )
        val tracks = mutableListOf<MaItem>()
        var cursor: Cursor? = null
        if (!hasAudioPermission(context)) {
            permissionDenied = true
            return tracks
        }
        try {
            cursor = context.contentResolver.query(
                collection,
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
                val relCol = c.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                val volCol = c.getColumnIndex(MediaStore.Audio.Media.VOLUME_NAME)
                while (c.moveToNext()) {
                    // Filtered here rather than after the fact: a phone with ten
                    // thousand tracks builds ten thousand MaItems otherwise, all but a
                    // handful of which are thrown away.
                    val relPath = if (relCol >= 0) c.getString(relCol) else null
                    val volume = if (volCol >= 0) c.getString(volCol) else null
                    if (!LocalFolders.reachable(scopes, volume, relPath)) continue
                    val id = c.getLong(idCol)
                    val contentUri = ContentUris.withAppendedId(collection, id).toString()
                    val title = c.getString(titleCol)?.takeIf { it.isNotBlank() } ?: "Unknown"
                    val artist = c.getString(artistCol)?.takeIf { it.isNotBlank() }
                    val album = c.getString(albumCol)?.takeIf { it.isNotBlank() }
                    val durationSec = c.getInt(durationCol) / 1000
                    val trackNum = c.getInt(trackCol).takeIf { it > 0 }
                    val discNum = if (trackNum != null) trackNum / 1000 else null
                    val cleanTrackNum = trackNum?.let { it % 1000 }?.takeIf { it > 0 }
                    val year = c.getInt(yearCol).takeIf { it > 0 }
                    val mime = c.getString(mimeCol)?.substringBefore('/')
                    val size = c.getLong(sizeCol)
                    val albumId = album?.let { "album:${it}" }
                    tracks += MaItem(
                        itemId = "local:$id",
                        provider = PROVIDER,
                        name = title,
                        uri = contentUri,
                        mediaType = "track",
                        subtitle = artist,
                        image = null,
                        duration = durationSec,
                        album = album,
                        trackNumber = cleanTrackNum,
                        discNumber = discNum,
                        parentId = albumId,
                        year = year,
                        audioFormat = if (mime.isNullOrBlank()) null else MaAudioFormat(
                            codec = mime,
                            sampleRate = 0,
                            bitDepth = 0,
                            bitRate = 0,
                            channels = 0,
                            sizeBytes = size,
                        ),
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
        return tracks
    }
}
