package com.engabd.sendpin.local

import android.content.ContentUris
import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import coil.size.pxOrElse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cover art for a file on this phone, as a URL the image loader understands.
 *
 * `localart://audio/<MediaStore id>`. There is no public URL for a local file's
 * picture, and a `content://media/.../audio/media/<id>` uri is the *audio*, which
 * Coil would try to decode as an image and fail. So the "This device" items carry
 * this instead, and [Fetcher] turns it into a bitmap with
 * `ContentResolver.loadThumbnail`.
 *
 * That call is the right one rather than reading tags here: the media provider
 * answers it from the file's embedded picture **or** a `cover.jpg` / `folder.jpg`
 * / `AlbumArt.jpg` beside it, which this app could not read itself without also
 * asking for photo access. It caches the result, too.
 */
object LocalArt {
    const val SCHEME = "localart"

    fun url(mediaId: Long): String = "$SCHEME://audio/$mediaId"

    fun idFrom(uri: Uri): Long? =
        uri.takeIf { it.scheme == SCHEME && it.host == "audio" }?.lastPathSegment?.toLongOrNull()

    class Fetcher(
        private val id: Long,
        private val options: Options,
    ) : coil.fetch.Fetcher {
        override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
            val context: Context = options.context
            val audio = ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), id)
            val px = options.size.width.pxOrElse { 512 }.coerceIn(96, 1024)
            // No art is the normal case for plenty of files; null, not an exception,
            // so a grid of them doesn't log a failure per cell.
            val bitmap = runCatching {
                context.contentResolver.loadThumbnail(audio, Size(px, px), null)
            }.getOrNull() ?: return@withContext null
            DrawableResult(
                drawable = BitmapDrawable(context.resources, bitmap),
                isSampled = true,
                dataSource = DataSource.DISK,
            )
        }

        class Factory : coil.fetch.Fetcher.Factory<Uri> {
            override fun create(data: Uri, options: Options, imageLoader: ImageLoader): coil.fetch.Fetcher? =
                idFrom(data)?.let { Fetcher(it, options) }
        }
    }
}
