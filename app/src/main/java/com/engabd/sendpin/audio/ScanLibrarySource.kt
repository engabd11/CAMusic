package com.engabd.sendpin.audio

import android.content.Context
import com.engabd.sendpin.download.DownloadManager
import com.engabd.sendpin.library.MusicSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Every track a library sweep should analyse.
 *
 * Deliberately only the library **this phone plays itself**, plus whatever has been
 * downloaded. Those are exactly the tracks the direct bridge path can ever see: a
 * Music Assistant library is routed to whatever speaker is targeted, which is Home
 * Assistant's business and never reaches the tap. Analysing it would be hours of
 * decoding for a show that cannot use any of it.
 *
 * Which library that is comes from the live [MusicSource] rather than from the stored
 * Navidrome address — that address is a mirror kept for downloads, so reading it meant
 * a Jellyfin-only install swept nothing but its downloads.
 *
 * Downloads come first. They cost no network and decode fastest, so a sweep that
 * gets interrupted has spent its time on the cheapest wins.
 */
object ScanLibrarySource {

    /**
     * Album pages to walk before giving up. A page is 500 albums, so this covers
     * a hundred thousand albums — past that a phone is not the right tool and a
     * bounded loop beats one that can spin forever on a server that keeps
     * answering with the same page.
     */
    private const val MAX_ALBUM_PAGES = 200
    private const val ALBUM_PAGE = 500

    suspend fun tracks(
        context: Context,
        downloads: DownloadManager,
        source: MusicSource? =
            (context.applicationContext as com.engabd.sendpin.SendpinApp).musicSource.value,
    ): List<LocalTrack> =
        withContext(Dispatchers.IO) {
            val out = LinkedHashMap<String, LocalTrack>()

            for (track in downloads.downloads.value) {
                out[track.id] = track.toLocalTrack()
            }

            val client = source
            if (client != null) {
                var offset = 0
                for (page in 0 until MAX_ALBUM_PAGES) {
                    currentCoroutineContext().ensureActive()
                    val albums = try {
                        client.albums(offset = offset, limit = ALBUM_PAGE)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // A server that stops answering partway through has still
                        // given us a useful list; throwing it away would mean the
                        // sweep achieves nothing rather than most of what it could.
                        break
                    }
                    if (albums.isEmpty()) break
                    offset += albums.size
                    for (album in albums) {
                        currentCoroutineContext().ensureActive()
                        val tracks = try {
                            client.albumDetail(album.itemId).second
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            continue
                        }
                        for (item in tracks) {
                            // A downloaded copy already in the map wins: it plays
                            // from storage, and it is the same audio either way.
                            if (item.itemId in out) continue
                            out[item.itemId] = LocalTrack(
                                id = item.itemId,
                                title = item.name,
                                artist = item.subtitle,
                                album = album.name,
                                durationMs = (item.duration ?: 0).toLong() * 1000,
                                streamUrl = client.streamUrl(item.itemId),
                                localPath = downloads.localPath(item.itemId),
                            )
                        }
                    }
                    if (albums.size < ALBUM_PAGE) break
                }
            }
            out.values.toList()
        }
}
