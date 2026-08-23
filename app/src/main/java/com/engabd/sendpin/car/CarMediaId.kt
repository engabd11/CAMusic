package com.engabd.sendpin.car

import android.net.Uri

/**
 * Every `mediaId` this app hands to Android Auto, and back. Resolvable statelessly
 * from the id string alone — `onGetChildren`, `onGetItem` and playback dispatch can
 * each arrive independently and must each answer from just the id they're given, so
 * nothing here is cached server-side keyed by a browse path.
 *
 * Built with [Uri.Builder] rather than a manual delimiter: real provider/item ids
 * already contain `|` (see [com.engabd.sendpin.ma.MaRepository]'s own
 * `"provider|mediaType|itemId"` keys), which would collide with a naive join.
 */
sealed class CarMediaId {
    abstract fun encode(): String

    data class Server(val serverId: String) : CarMediaId() {
        override fun encode(): String =
            Uri.Builder().scheme(SCHEME).authority(AUTH_SERVER).appendQueryParameter("id", serverId).build().toString()
    }

    data class Shelf(val serverId: String, val key: String) : CarMediaId() {
        override fun encode(): String =
            Uri.Builder().scheme(SCHEME).authority(AUTH_SHELF)
                .appendQueryParameter("srv", serverId)
                .appendQueryParameter("key", key)
                .build().toString()
    }

    /**
     * A single library item. [uri] is only ever populated for a Music Assistant item
     * (its MA `uri`, e.g. `library://track/123` — not a credentialed stream URL, and
     * what [com.engabd.sendpin.ma.MaRepository.playOn] needs to start it); a
     * local-source item resolves its stream fresh from [provider]+[itemId] via
     * [com.engabd.sendpin.library.MusicSource.streamUrl] instead.
     */
    data class Item(
        val serverId: String,
        val provider: String,
        val mediaType: String,
        val itemId: String,
        val uri: String?,
    ) : CarMediaId() {
        override fun encode(): String =
            Uri.Builder().scheme(SCHEME).authority(AUTH_ITEM)
                .appendQueryParameter("srv", serverId)
                .appendQueryParameter("p", provider)
                .appendQueryParameter("mt", mediaType)
                .appendQueryParameter("id", itemId)
                .apply { uri?.let { appendQueryParameter("uri", it) } }
                .build().toString()
    }

    companion object {
        private const val SCHEME = "cmid"
        private const val AUTH_SERVER = "server"
        private const val AUTH_SHELF = "shelf"
        private const val AUTH_ITEM = "item"

        const val ROOT = "cmid://root"
        const val MORE = "cmid://more"

        fun parse(mediaId: String): CarMediaId? {
            if (mediaId == ROOT || mediaId == MORE) return null
            val uri = runCatching { Uri.parse(mediaId) }.getOrNull() ?: return null
            if (uri.scheme != SCHEME) return null
            return when (uri.authority) {
                AUTH_SERVER -> uri.getQueryParameter("id")?.let { Server(it) }
                AUTH_SHELF -> {
                    val srv = uri.getQueryParameter("srv") ?: return null
                    val key = uri.getQueryParameter("key") ?: return null
                    Shelf(srv, key)
                }
                AUTH_ITEM -> {
                    val srv = uri.getQueryParameter("srv") ?: return null
                    val provider = uri.getQueryParameter("p") ?: return null
                    val mediaType = uri.getQueryParameter("mt") ?: return null
                    val itemId = uri.getQueryParameter("id") ?: return null
                    Item(srv, provider, mediaType, itemId, uri.getQueryParameter("uri"))
                }
                else -> null
            }
        }
    }
}
