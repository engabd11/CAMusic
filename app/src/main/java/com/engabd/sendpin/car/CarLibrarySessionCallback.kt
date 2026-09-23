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
import com.google.common.util.concurrent.Futures
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

    /**
     * Every controller is accepted, and the connection is written down.
     *
     * Deliberately not an allow-list. This session hands out no credential and no
     * stream URL — see [CarLibraryBridge]'s class note — so there is nothing here to
     * protect by rejecting callers, and a browse tree that silently refuses the one
     * host that matters is far worse than one that answers a caller it did not need
     * to. Covers are the one thing that *is* gated, per URI, in
     * [CarLibraryBridge.grantArtwork].
     *
     * The record is the diagnostic half — see [CarConnectionLog] for why "did a car
     * ever reach this app" was unanswerable without it.
     */
    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
        bridge.recordConnection(controller.packageName)
        return MediaSession.ConnectionResult.accept(
            MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS,
            MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS,
        )
    }

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        // A request for the *recent* root is not a request to browse. It is the system
        // (or the car) asking "what was playing last, so I can offer to resume it",
        // and it expects a root whose one child is that item.
        //
        // This app cannot answer it yet and says so, rather than falling through and
        // returning the ordinary browse root — which is what used to happen, and is
        // worse than declining. A caller handed the browse root for a resumption
        // request takes it as a yes: it draws a resume tile whose target is the
        // library root, which is browsable and not playable, so tapping it does
        // nothing at all. An explicit error is read as "this app has nothing to
        // resume" and no tile is drawn.
        //
        // Answering it properly needs a record this app does not keep. `play_history`
        // stores a `trackId` and a `provider` but neither the server id nor the uri
        // that [CarMediaId.Item] needs to address a track again, so a resume target
        // cannot be rebuilt from it. That is the work, and it is not a one-line
        // change; see the PR notes.
        if (params?.isRecent == true) {
            return Futures.immediateFuture(
                LibraryResult.ofError(
                    SessionError(SessionError.ERROR_NOT_SUPPORTED, "Nothing to resume"),
                ),
            )
        }
        // The one call that carries it. A legacy browser - which is what Android Auto
        // is - sends its root hints to `MediaBrowserServiceCompat.onGetRoot` and
        // nothing else, so media3 has a `LibraryParams` to hand here and passes
        // `null` to `onGetChildren` for an ordinary `subscribe()`. Read there, the
        // hint was never anything but the default.
        params?.extras
            ?.getInt(MediaConstants.EXTRAS_KEY_ROOT_CHILDREN_LIMIT, DEFAULT_ROOT_CHILDREN_LIMIT)
            ?.takeIf { it > 0 }
            ?.let { rootChildrenLimit = it }
        // The other hint worth having, and the one the platform documents rather than
        // media3: how large the browser intends to draw a thumbnail. Read as a plain
        // key because it belongs to `MediaBrowserServiceCompat.BrowserRoot` — media3
        // has no constant for it — and passed to the bridge so a cover is fetched at
        // the size the car will use rather than at whatever the server defaults to.
        params?.extras
            ?.getInt(EXTRA_MEDIA_ART_SIZE_HINT_PIXELS, 0)
            ?.takeIf { it > 0 }
            ?.let { bridge.setArtworkSizeHint(it) }
        // Not awaited: warming the libraries is what makes the *first folder* fast,
        // and has nothing to do with answering the root.
        scope.launch { runCatching { bridge.warmUp() } }
        // Immediate, deliberately — this is the call a connecting browser blocks its
        // whole connection on. It used to be answered through [future], which parks
        // the result on a coroutine and completes the future later; Google's guidance
        // is that the root must return quickly, and media3 has had real connection
        // bugs in the non-immediate path (androidx/media#3393). Nothing here needs
        // I/O, so nothing here should wait for any.
        val (root, rootParams) = bridge.rootResult()
        return Futures.immediateFuture(LibraryResult.ofItem(root, rootParams))
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
        // Caught rather than allowed to fail the future. A folder that throws — a home
        // server out of Wi-Fi range is the ordinary case, on a phone that has just been
        // driven away from the house — used to reach the car as an unexplained failure
        // and, on some head units, as a dead tab for the rest of the trip.
        runCatching {
            val children = bridge.children(parentId, rootChildrenLimit).page(page, pageSize)
            bridge.grantArtwork(browser.packageName, children)
            LibraryResult.ofItemList(children, null)
        }.getOrElse {
            LibraryResult.ofError(
                SessionError(SessionError.ERROR_IO, "Couldn't reach your library"),
            )
        }
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = future {
        bridge.item(mediaId)
            ?.also { bridge.grantArtwork(browser.packageName, listOf(it)) }
            ?.let { LibraryResult.ofItem(it, null) }
            // A bare error code reaches the car as an unexplained failure. media3 turns
            // the message into what the head unit shows, so it is worth writing one.
            ?: LibraryResult.ofError(
                SessionError(SessionError.ERROR_BAD_VALUE, "That item is no longer in your library"),
            )
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
        val hits = bridge.search(query).page(page, pageSize)
        bridge.grantArtwork(browser.packageName, hits)
        LibraryResult.ofItemList(hits, null)
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

        /**
         * `MediaBrowserServiceCompat.BrowserRoot.EXTRA_MEDIA_ART_SIZE_HINT_PIXELS`.
         *
         * Spelled out rather than imported: this app depends on media3, not on the
         * legacy `media-compat` artifact the constant lives in, and media3 has no
         * equivalent of its own. The string is part of the platform's browser
         * protocol and is as fixed as any of the ids in [CarMediaId].
         */
        const val EXTRA_MEDIA_ART_SIZE_HINT_PIXELS = "android.media.extras.MEDIA_ART_SIZE_HINT_PIXELS"
    }
}
