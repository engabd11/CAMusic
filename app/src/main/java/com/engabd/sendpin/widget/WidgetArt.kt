package com.engabd.sendpin.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.engabd.sendpin.util.runCatchingCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The cover the home-screen widget shows, loaded once per track.
 *
 * A widget is RemoteViews underneath, and its bitmap crosses to the launcher with
 * every update, so it is kept small: 256 px, software, one at a time. Loaded through
 * the app's own image loader, so a library cover that needs the server's sign-in
 * loads here exactly as it does in the app.
 */
object WidgetArt {
    private const val PX = 256

    private val _bitmap = MutableStateFlow<Bitmap?>(null)
    val bitmap: StateFlow<Bitmap?> = _bitmap.asStateFlow()

    @Volatile private var loadedUrl: String? = null

    /** Load [url]'s cover, or let go of the last one when [url] is null. */
    suspend fun load(context: Context, url: String?) {
        if (url == loadedUrl) return
        loadedUrl = url
        _bitmap.value = if (url == null) null else runCatchingCancellable {
            val result = context.imageLoader.execute(
                ImageRequest.Builder(context).data(url).allowHardware(false).size(PX).build(),
            )
            (result as? SuccessResult)?.drawable?.toBitmap(PX, PX)
        }.getOrNull()
    }
}
