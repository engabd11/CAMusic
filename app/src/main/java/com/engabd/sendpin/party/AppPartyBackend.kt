package com.engabd.sendpin.party

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.ma.MaItem
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [PartyBackend] over the real app: now-playing from [SendpinApp.unifiedNowPlaying]
 * (which already picks between the local player, this phone as a Sendspin speaker and
 * a remote Music Assistant speaker), search and queueing through [PartyLibraries].
 *
 * Library items stay on the phone: a guest is given a random id, this class keeps the
 * [MaItem] behind it, and the item's server, stream URL and file path never leave.
 */
class AppPartyBackend(private val app: SendpinApp) : PartyBackend {

    private val items = object : LinkedHashMap<String, MaItem>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MaItem>?) = size > 400
    }

    override fun nowPlaying(): PartyNowPlaying? {
        val s = app.unifiedNowPlaying.state.value
        if (s.owner == com.engabd.sendpin.service.UnifiedNowPlaying.Owner.NONE || s.title.isBlank()) return null
        return PartyNowPlaying(
            key = "${s.title}|${s.artist}|${s.durationMs}",
            title = s.title, artist = s.artist, album = s.album,
            durationMs = s.durationMs, positionMs = s.positionMs, playing = s.isPlaying,
            artRef = s.artworkUrl?.takeIf { it.isNotBlank() },
        )
    }

    override suspend fun upNext(limit: Int): List<PartyUpNext> =
        PartyLibraries.current?.upNext(limit).orEmpty()

    override suspend fun search(query: String, limit: Int): List<PartyCandidate> {
        val library = PartyLibraries.current ?: return emptyList()
        return library.searchTracks(query, limit).map { item ->
            val ref = PartyAuth.randomHex(8)
            synchronized(items) { items[ref] = item }
            PartyCandidate(
                ref = ref, title = item.name, artist = item.subtitle.orEmpty(), album = item.album.orEmpty(),
                durationMs = (item.duration ?: 0) * 1000L, artRef = item.image?.takeIf { it.isNotBlank() },
            )
        }
    }

    override suspend fun enqueue(ref: String, position: Int): String? {
        val item = synchronized(items) { items[ref] } ?: return null
        return PartyLibraries.current?.enqueue(item, position)
    }

    /**
     * Fetched through the app's own image loader, which is where the Music Assistant
     * token and every other library credential live; the guest only receives the JPEG.
     */
    override suspend fun artwork(artRef: String): ByteArray? = withTimeoutOrNull(8_000) {
        val request = ImageRequest.Builder(app)
            .data(artRef)
            .size(THUMB_PX, THUMB_PX)
            .allowHardware(false)
            .diskCachePolicy(CachePolicy.ENABLED)
            .build()
        val result = app.imageLoader.execute(request) as? SuccessResult ?: return@withTimeoutOrNull null
        val bitmap = (result.drawable as? BitmapDrawable)?.bitmap ?: return@withTimeoutOrNull null
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 78, out)
            out.toByteArray()
        }
    }

    override fun skip() { PartyLibraries.current?.skip() }

    private companion object { const val THUMB_PX = 256 }
}
