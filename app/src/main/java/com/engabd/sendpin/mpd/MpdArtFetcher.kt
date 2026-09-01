package com.engabd.sendpin.mpd

import android.content.Context
import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.library.MpdSource
import okio.Buffer

/**
 * Teaches the image loader to fetch a cover from MPD.
 *
 * MPD's artwork does not live behind a URL — it comes back down the protocol
 * socket in binary chunks, from `readpicture` or `albumart`. So the library items
 * carry an [MpdArt] URL instead, and this is what happens when one reaches Coil:
 * the id is unpacked, an album id is resolved to one of its songs (MPD's art
 * commands only speak about songs), and the bytes are handed over as any other
 * image source would be.
 *
 * Registered on the loader in `SendpinApp.newImageLoader`. Everything downstream
 * — the memory cache, the disk cache, the crossfade, the placeholder — behaves
 * exactly as it does for an HTTP cover, because to Coil this is just another
 * fetcher.
 */
class MpdArtFetcher(
    private val data: Uri,
    private val context: Context,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        // The live connection, not one built here: a cover is not worth a second
        // client, and the art socket should be the configured server's.
        val client = (SendpinApp.instance.musicSource.value as? MpdSource)?.mpd ?: return null
        val id = MpdArt.idFrom(data.toString()) ?: return null

        val file = if (MpdArt.isAlbumId(id)) client.anySongIn(id) else id
        val bytes = file?.let { client.coverArt(it) }
        // Null rather than an exception: a library with no embedded art and no
        // cover files is a normal library, and every one of its items would
        // otherwise log a failure per grid cell.
        if (bytes == null || bytes.isEmpty()) return null

        return SourceResult(
            source = ImageSource(Buffer().write(bytes), context),
            // Let the decoder sniff it. MPD reports a type for `readpicture` and
            // nothing at all for `albumart`, so trusting it would be right half
            // the time and wrong silently the other half.
            mimeType = null,
            dataSource = DataSource.NETWORK,
        )
    }

    class Factory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? =
            if (data.scheme == MpdArt.SCHEME) MpdArtFetcher(data, options.context) else null
    }
}
