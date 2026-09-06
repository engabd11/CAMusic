package com.engabd.sendpin.car

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Serves one album cover, by token, to whichever media browser was handed the URI.
 *
 * See [CarArtwork] for why the real URL cannot cross the binder and what a token is.
 * This is the other end: resolve the token *inside this process*, fetch through the
 * app's own [coil.ImageLoader] — which is where the Music Assistant bearer token and
 * every other credential lives — and answer with a plain JPEG.
 *
 * **Not exported.** A browser reads these URIs because [CarLibrarySessionCallback]
 * grants it read access to the exact URIs it was just given, and to nothing else.
 * That is a narrower grant than the browse service itself, which has to be exported
 * for Android Auto to bind it at all.
 *
 * Everything is cached on disk under `cacheDir/carart`. Android Auto asks for the
 * same cover on every reconnection and, on a long drive, the same folder repeatedly;
 * decoding it once per phone rather than once per glance is the difference between a
 * folder that fills instantly and one that fills over the network each time.
 */
class CarArtworkProvider : ContentProvider() {

    /**
     * One lock per token, so two rows of the same album — a browse row and a search
     * hit arriving together — decode once rather than racing to write one file.
     */
    private val locks = ConcurrentHashMap<String, Any>()

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = MIME

    /**
     * Enough of [OpenableColumns] for an image loader that asks before it opens.
     *
     * Glide and Coil both query for a display name and size on a `content://` URI.
     * Answering null makes some loaders give up before [openFile] is ever reached,
     * which is a cover that silently never appears.
     */
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val token = tokenOf(uri) ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val file = cacheFile(token, pxOf(uri)) ?: return null
        val cursor = MatrixCursor(columns)
        // The Iterable overload rather than an array: the values are a mix of String,
        // Long and null, and an Array<Any?> of them is the one shape MatrixCursor's
        // two overloads disagree about.
        cursor.addRow(
            columns.map { column ->
                when (column) {
                    OpenableColumns.DISPLAY_NAME -> "$token.jpg"
                    // Null rather than zero while nothing is decoded yet: a loader
                    // that reads a real 0 concludes the file is empty and stops.
                    OpenableColumns.SIZE -> file.length().takeIf { it > 0 }
                    else -> null
                }
            },
        )
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        // Read-only, always. Nothing here is writable and a caller asking to write is
        // asking for something this provider does not have.
        if (mode.trim() != "r") throw FileNotFoundException("Artwork is read-only: $uri")
        val token = tokenOf(uri) ?: throw FileNotFoundException("Not an artwork URI: $uri")
        val px = pxOf(uri)
        val file = cacheFile(token, px) ?: throw FileNotFoundException("No cache directory yet")
        if (!file.isFile || file.length() == 0L) {
            // The lock table is per-token and never shrinks on its own. Clearing it
            // wholesale past a generous cap is enough: losing a lock only ever means
            // two threads decode one cover twice, and the rename below is atomic.
            if (locks.size > MAX_LOCKS) locks.clear()
            synchronized(locks.computeIfAbsent(token) { Any() }) {
                if (!file.isFile || file.length() == 0L) render(token, px, file)
            }
        }
        if (!file.isFile || file.length() == 0L) throw FileNotFoundException("No artwork for $token")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    // Nothing here is a database. A media browser never asks for these, and a caller
    // that does is not one this provider wants to answer.
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

    /**
     * Fetch, decode, scale and write — on the binder thread that called [openFile],
     * which is the thread the platform gives a provider for exactly this.
     *
     * Written to a sibling file and then renamed: a request that dies halfway
     * through, in a car pulling out of range of the house, must not leave a
     * half-written JPEG behind that every later request then serves as the cover.
     */
    private fun render(token: String, px: Int, target: File) {
        val context = context ?: return
        val url = CarArtwork.lookup(token) ?: return
        val bitmap = runBlocking {
            withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                val request = ImageRequest.Builder(context)
                    .data(url)
                    // A hardware bitmap cannot be read back, and reading it back to
                    // compress is the whole job here.
                    .allowHardware(false)
                    .size(px)
                    .build()
                (context.imageLoader.execute(request) as? SuccessResult)?.drawable?.toBitmap()
            }
        } ?: return

        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.part")
        runCatching {
            temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            if (!temp.renameTo(target)) temp.copyTo(target, overwrite = true)
        }.onFailure { temp.delete() }
        temp.delete()
        prune(context)
    }

    /**
     * Keep the cover cache to a size a phone will not notice.
     *
     * Oldest first, and only when over the cap, so the common case costs one
     * directory listing. Coil keeps its own (much larger) cache of the same images
     * as *decoded source bytes*; this directory holds only the scaled JPEGs the car
     * asked for, so it does not need to be generous.
     */
    private fun prune(context: Context) {
        val dir = cacheDir(context)
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= CACHE_BYTES) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= CACHE_BYTES) break
            total -= file.length()
            file.delete()
        }
    }

    private fun cacheDir(context: Context): File = File(context.cacheDir, "carart").apply { mkdirs() }

    /**
     * Where a decoded cover lives, or null before the provider has a context.
     *
     * A provider is constructed before `Application.onCreate`, so a null context here
     * is a real state rather than a defensive flourish — it just cannot coincide with
     * a browser holding a URI to ask about.
     */
    private fun cacheFile(token: String, px: Int): File? =
        context?.let { File(cacheDir(it), "$token-$px.jpg") }

    private fun tokenOf(uri: Uri): String? {
        val segments = uri.pathSegments
        if (segments.size != 2 || segments[0] != CarArtwork.PATH_ART) return null
        // Hex only: the token names a file, and a path segment that is not a token
        // has no business reaching the filesystem.
        return segments[1].takeIf { it.length == TOKEN_LENGTH && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    }

    private fun pxOf(uri: Uri): Int =
        uri.getQueryParameter(CarArtwork.QUERY_PX)?.toIntOrNull()?.coerceIn(MIN_PX, MAX_PX) ?: DEFAULT_PX

    companion object {
        const val MIME = "image/jpeg"

        /** What the car gets when it does not say what size it wants. */
        const val DEFAULT_PX = 480
        const val MIN_PX = 128
        const val MAX_PX = 1024

        private const val TOKEN_LENGTH = 64
        private const val MAX_LOCKS = 512
        private const val JPEG_QUALITY = 88
        private const val FETCH_TIMEOUT_MS = 8_000L
        private const val CACHE_BYTES = 48L * 1024 * 1024

        fun authority(context: Context): String = context.packageName + CarArtwork.AUTHORITY_SUFFIX

        /**
         * The URI for a cover, or null when there is no URL to point at.
         *
         * Minting the token here — rather than at open time — is what makes the URI
         * resolvable at all: [CarArtwork.remember] is the only thing that records
         * which URL a token stands for.
         */
        fun uriFor(context: Context, url: String?, px: Int): Uri? {
            if (url.isNullOrBlank()) return null
            val token = CarArtwork.remember(url)
            return Uri.Builder()
                .scheme("content")
                .authority(authority(context))
                .appendPath(CarArtwork.PATH_ART)
                .appendPath(token)
                .appendQueryParameter(CarArtwork.QUERY_PX, px.coerceIn(MIN_PX, MAX_PX).toString())
                .build()
        }
    }
}
