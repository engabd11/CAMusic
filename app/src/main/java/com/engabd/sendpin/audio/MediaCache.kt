package com.engabd.sendpin.audio

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * A disk cache for streamed audio, shared by the main player and the crossfade deck.
 *
 * What it buys: a track played twice (repeat, going back, the same album tomorrow) is
 * read from the phone rather than fetched again; a seek backwards into audio already
 * heard is instant; and the crossfade deck, which opens the same URL the main player
 * is on, reads bytes the main player already fetched instead of opening a second
 * stream to the server.
 *
 * Bounded and least-recently-used, in the cache directory, so the system may clear it
 * and it never competes with downloads, which are the thing meant to be kept.
 *
 * One [SimpleCache] per process and per directory is a hard media3 rule — two
 * instances over one folder throw — which is why this is an object.
 */
@OptIn(UnstableApi::class)
object MediaCache {

    /** Enough for a few hours of lossless audio, and small beside a phone's storage. */
    private const val MAX_BYTES = 512L * 1024 * 1024

    @Volatile private var cache: SimpleCache? = null

    private fun cache(context: Context): SimpleCache =
        cache ?: synchronized(this) {
            cache ?: SimpleCache(
                File(context.applicationContext.cacheDir, "media"),
                LeastRecentlyUsedCacheEvictor(MAX_BYTES),
                StandaloneDatabaseProvider(context.applicationContext),
            ).also { cache = it }
        }

    /**
     * [upstream] (the HTTP factory) with the cache in front of it. Only ever handed
     * the HTTP half — [androidx.media3.datasource.DefaultDataSource] still opens
     * `file:` and `content:` itself, so local files and downloads are never copied in.
     */
    fun factory(context: Context, upstream: DataSource.Factory): DataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache(context))
            // A server's error document (Subsonic answers a failed stream with 200 and
            // XML) is refused before it can be stored as the track; see NotAudioGuard.
            .setUpstreamDataSourceFactory(NotAudioGuard.Factory(upstream))
            .setCacheKeyFactory(KEY_FACTORY)
            // A cache that fails (disk full, the system clearing it mid-read) must fall
            // back to the network rather than fail the track.
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /**
     * Drop what is stored for [url], so the next play fetches it again. For a stored
     * stream that turned out not to be playable (see [LocalPlayer]'s error handling).
     */
    fun remove(context: Context, url: String) {
        runCatching { cache(context).removeResource(cacheKey(url)) }
    }

    /**
     * Remove every stored stream that is a server's error document rather than audio.
     *
     * Before [NotAudioGuard], a Subsonic error answered with HTTP 200 was cached as the
     * track and replayed from the phone for as long as it stayed in the cache, even
     * after the server could play the file again. Run once to clear those out; the
     * guard keeps new ones from being stored. Reads only the first bytes of each
     * entry's first span. Returns how many were removed.
     */
    fun removeErrorDocuments(context: Context): Int {
        val c = cache(context)
        var removed = 0
        val head = ByteArray(16)
        for (key in c.keys.toList()) {
            val first = runCatching { c.getCachedSpans(key).firstOrNull { it.position == 0L } }.getOrNull() ?: continue
            val file = first.file ?: continue
            val n = runCatching { file.inputStream().use { it.read(head) } }.getOrDefault(-1)
            if (n > 0 && NotAudioGuard.looksLikeDocument(head, n)) {
                runCatching { c.removeResource(key) }.onSuccess { removed++ }
            }
        }
        return removed
    }

    private val KEY_FACTORY = CacheKeyFactory { spec -> cacheKey(spec.uri.toString()) }

    /**
     * The URL with its credentials taken out.
     *
     * Needed for the cache to hit at all on a Subsonic server: every request is signed
     * with a fresh random salt (`s`) and the token derived from it (`t`), so the same
     * track has a different URL every time it is asked for. Jellyfin's `api_key` and
     * Plex's token are stable, but belong in no key either. Everything that says
     * *which* file and in *which* format — id, format, maxBitRate, the path — stays.
     */
    fun cacheKey(url: String): String {
        val q = url.indexOf('?')
        if (q < 0) return url
        val base = url.substring(0, q)
        val kept = url.substring(q + 1).split('&').filter { part ->
            val name = part.substringBefore('=').lowercase()
            name !in CREDENTIAL_PARAMS
        }
        return if (kept.isEmpty()) base else base + "?" + kept.joinToString("&")
    }

    private val CREDENTIAL_PARAMS = setOf(
        "u", "t", "s", "p", "api_key", "apikey", "x-emby-token", "x-plex-token", "token",
        // Per-request noise that says nothing about the bytes.
        "c", "_",
    )
}
