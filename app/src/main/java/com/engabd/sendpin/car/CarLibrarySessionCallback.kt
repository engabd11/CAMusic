package com.engabd.sendpin.car

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Thin — every real decision (what the browse tree looks like, what a tap plays)
 * lives in [CarLibraryBridge]. This just translates between media3's callback shape
 * and suspend calls into it.
 */
@OptIn(UnstableApi::class)
class CarLibrarySessionCallback(private val bridge: CarLibraryBridge) : MediaLibrarySession.Callback {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * How many rows the browser can show at the root, from its own root hints — see
     * [onGetLibraryRoot], which is the only call that carries them.
     */
    private var rootChildrenLimit = DEFAULT_ROOT_CHILDREN_LIMIT

    /** Stop the in-flight callbacks when the session goes away. */
    fun release() {
        scope.cancel()
    }

    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
        MediaSession.ConnectionResult.accept(
            MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS,
            MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS,
        )

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> = future {
        // The one call that carries it. A legacy browser - which is what Android Auto
        // is - sends its root hints to `MediaBrowserServiceCompat.onGetRoot` and
        // nothing else, so media3 has a `LibraryParams` to hand here and passes
        // `null` to `onGetChildren` for an ordinary `subscribe()`. Read there, the
        // hint was never anything but the default.
        params?.extras
            ?.getInt(MediaConstants.EXTRAS_KEY_ROOT_CHILDREN_LIMIT, DEFAULT_ROOT_CHILDREN_LIMIT)
            ?.takeIf { it > 0 }
            ?.let { rootChildrenLimit = it }
        // Not awaited: the root itself is answered from settings alone, and the
        // browser is kept waiting for that answer before it can ask for anything else.
        scope.launch { runCatching { bridge.warmUp() } }
        LibraryResult.ofItem(bridge.rootItem(), null)
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
        val children = bridge.children(parentId, rootChildrenLimit)
        LibraryResult.ofItemList(children.page(page, pageSize), null)
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = future {
        bridge.item(mediaId)?.let { LibraryResult.ofItem(it, null) }
            ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
        LibraryResult.ofItemList(bridge.search(query).page(page, pageSize), null)
    }

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> = future {
        val results = bridge.search(query)
        session.notifySearchResultChanged(browser, query, results.size, params)
        LibraryResult.ofVoid()
    }

    /**
     * Where a tap (or "Hey Google, play X in CAMusic") actually starts playback.
     *
     * Two shapes arrive here. A **tap** resends the exact [MediaItem] — and so the
     * exact `mediaId` — it was given by [onGetChildren]/[onGetSearchResult], and
     * [CarLibraryBridge.play] resolves purely from that id, never from the
     * accompanying metadata. A **spoken** request has no id to resend: media3 builds
     * an item with `MediaItem.DEFAULT_MEDIA_ID` and the words in
     * `requestMetadata.searchQuery`, which has to be searched for first.
     */
    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = future {
        val requested = mediaItems.getOrNull(startIndex) ?: mediaItems.firstOrNull()
        val query = requested?.requestMetadata?.searchQuery
        when {
            !query.isNullOrBlank() -> bridge.playSearch(query)
            requested != null -> bridge.play(requested.mediaId)
        }
        MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
    }

    /**
     * The slice of this list that `page`/`pageSize` asked for.
     *
     * A `MediaBrowserCompat.subscribe` that carries pagination options is answered
     * here; one that does not reaches media3 as `page = 0, pageSize = MAX_VALUE`, so
     * the whole list is the page. Returning everything regardless meant a paginated
     * browser was handed page 0 again for every page it asked for.
     */
    private fun <T> List<T>.page(page: Int, pageSize: Int): List<T> {
        if (page <= 0 && pageSize >= size) return this
        val from = page.toLong() * pageSize
        if (from >= size) return emptyList()
        return subList(from.toInt(), minOf(from + pageSize, size.toLong()).toInt())
    }

    /** Bridges a suspend call onto the [ListenableFuture] media3's callbacks expect. */
    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val result = SettableFuture.create<T>()
        scope.launch {
            try {
                result.set(block())
            } catch (e: Exception) {
                result.setException(e)
            }
        }
        return result
    }

    private companion object {
        const val DEFAULT_ROOT_CHILDREN_LIMIT = 4
    }
}
