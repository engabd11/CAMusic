package com.engabd.sendpin.audio

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
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
 * and it never competes with downloads, which are the thing meant to be kept. The
 * bound is a setting (Settings › Downloads & storage), and "fetch ahead" fills it
 * with the next songs before they are reached — see [PreCacher].
 *
 * One [SimpleCache] per process and per directory is a hard media3 rule — two
 * instances over one folder throw — which is why this is an object.
 */
@OptIn(UnstableApi::class)
object MediaCache {

    /** What every stream request calls itself, the player's and a fetch-ahead alike. */
    val USER_AGENT: String = "CAMusic/${com.engabd.sendpin.BuildConfig.VERSION_NAME} (Android)"

    @Volatile private var cache: SimpleCache? = null
    @Volatile private var evictor: AdjustableLruEvictor? = null

    /**
     * Built on first use at the size last chosen. Read from the boot mirror rather
     * than DataStore because the first track can ask for this before any coroutine
     * has had a chance to read a preference.
     */
    private fun cache(context: Context): SimpleCache =
        cache ?: synchronized(this) {
            cache ?: run {
                val app = context.applicationContext
                val lru = AdjustableLruEvictor(mb(com.engabd.sendpin.data.AppSettings(app).bootMediaCacheMb))
                SimpleCache(File(app.cacheDir, "media"), lru, StandaloneDatabaseProvider(app))
                    .also { evictor = lru; cache = it }
            }
        }

    private fun mb(value: Int): Long = value.toLong() * 1024 * 1024

    /** Change the size; shrinking evicts the least recently heard audio at once. */
    fun setMaxMb(context: Context, value: Int) {
        val c = cache(context)
        evictor?.setMaxBytes(c, mb(value))
    }

    /** Bytes of audio held now. */
    fun usedBytes(context: Context): Long = runCatching { cache(context).cacheSpace }.getOrDefault(0L)

    /**
     * Empty it, except for whatever [keepUrl] is (the song playing), which is being
     * read as this runs.
     */
    fun clear(context: Context, keepUrl: String?) {
        val c = cache(context)
        val keep = keepUrl?.let(::cacheKey)
        for (key in c.keys.toList()) {
            if (key != keep) runCatching { c.removeResource(key) }
        }
    }

    /** Whether all of [url] is already on the phone, so fetching it again would be waste. */
    fun isFullyCached(context: Context, url: String): Boolean {
        val c = cache(context)
        val key = cacheKey(url)
        val length = androidx.media3.datasource.cache.ContentMetadata.getContentLength(c.getContentMetadata(key))
        return length > 0 && c.isCached(key, 0, length)
    }

    /**
     * The HTTP source every stream is fetched with: the app's own client, and the
     * player's User-Agent. One definition, so a pre-fetched file is fetched exactly as
     * the player would have fetched it.
     */
    fun httpUpstream(): DataSource.Factory =
        androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(com.engabd.sendpin.data.Http.stream())
            .setUserAgent(USER_AGENT)

    /**
     * A source that writes into the cache, for [PreCacher]'s CacheWriter. Guarded like
     * [factory]: a song fetched ahead from a server that answers with an error document
     * would otherwise be stored as that document, and played as it, for as long as it
     * stays in the cache.
     */
    fun writingSource(context: Context): CacheDataSource =
        CacheDataSource.Factory()
            .setCache(cache(context))
            .setUpstreamDataSourceFactory(NotAudioGuard.Factory(httpUpstream()))
            .setCacheKeyFactory(KEY_FACTORY)
            .createDataSource()

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
