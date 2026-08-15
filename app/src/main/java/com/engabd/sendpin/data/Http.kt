package com.engabd.sendpin.data

import com.engabd.sendpin.BuildConfig
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The one OkHttp client every server talks through.
 *
 * There used to be six, built independently in `SubsonicClient`, `JellyfinClient`,
 * `MaApiClient`, `SendspinClient`, `HaClient` and `DownloadManager`. Each carried its
 * own timeouts and its own connection pool, so a phone with a Navidrome library, a
 * Music Assistant server and a Home Assistant instance held three separate pools to
 * what is frequently the *same* host — and none of them cached anything, set a
 * User-Agent, or agreed on what a reasonable timeout was. One of them
 * (`DownloadManager`) had no timeouts at all, so a stalled download hung for ever.
 *
 * Derive with [newBuilder] rather than constructing afresh: an OkHttp client built from
 * another shares its dispatcher, connection pool and cache, which is the whole point.
 *
 * [LanOnlyCleartext] is applied here so it cannot be forgotten by a derived client. It
 * is the only thing stopping a Navidrome password crossing the public internet in the
 * clear, and losing it in a refactor would be silent.
 */
object Http {

    /**
     * What this app calls itself to a server.
     *
     * Nothing set one before, so every server logged `okhttp/4.x` and an admin looking
     * at their access log had no way to tell this app from any other. Self-hosted
     * software is frequently debugged by reading that log.
     */
    val USER_AGENT: String = "CAMusic/${BuildConfig.VERSION_NAME} (Android ${android.os.Build.VERSION.RELEASE})"

    /** Disk budget for cached API responses. Small: this caches JSON, not media. */
    private const val CACHE_BYTES = 16L * 1024 * 1024

    private val userAgent = Interceptor { chain ->
        chain.proceed(
            chain.request().newBuilder()
                .header("User-Agent", USER_AGENT)
                .build(),
        )
    }

    @Volatile
    private var cache: Cache? = null

    /**
     * Install the response cache. Called once from `SendpinApp`; safe to skip, in which
     * case the client simply runs uncached (which is what unit tests do).
     *
     * Separate from [base] because a `Cache` needs a directory and this object is
     * deliberately context-free — a client built during a JVM test must not need
     * Android to exist.
     */
    fun initCache(cacheDir: File) {
        if (cache != null) return
        cache = runCatching { Cache(File(cacheDir, "http"), CACHE_BYTES) }.getOrNull()
        built = null
    }

    @Volatile
    private var built: OkHttpClient? = null

    /**
     * The shared base. Timeouts suit a request to a server on the same LAN that may be
     * cold — generous enough not to fail a NAS spinning its disks up, short enough that
     * an unreachable host is reported rather than hung on.
     */
    val base: OkHttpClient
        get() = built ?: synchronized(this) {
            built ?: OkHttpClient.Builder()
                .addInterceptor(LanOnlyCleartext)
                .addInterceptor(userAgent)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS)
                .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
                .retryOnConnectionFailure(true)
                .apply { cache?.let { cache(it) } }
                .build()
                .also { built = it }
        }

    /**
     * A long-lived socket: WebSockets and the Sendspin audio stream.
     *
     * `readTimeout(0)` because a quiet socket is not a broken one — the protocol's own
     * ping/pong decides that — and no `callTimeout`, which would otherwise kill a
     * connection that is behaving exactly as designed.
     */
    fun socket(pingSeconds: Long): OkHttpClient = base.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(pingSeconds, TimeUnit.SECONDS)
        .build()

    /**
     * A file transfer: a download, or an offline scan reading a whole track.
     *
     * No `callTimeout` — a large FLAC over a slow link is not a failure — but the read
     * timeout stays, so a connection that stops delivering bytes still gives up.
     */
    fun transfer(): OkHttpClient = base.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()
}

/**
 * Retry a request that failed for a reason retrying could fix.
 *
 * Neither `SubsonicClient` nor `JellyfinClient` retried at all, so a single dropped
 * packet on a phone moving between access points surfaced as a hard error with a
 * dialog. Modelled on `MaApiClient`'s classification, which already gets this right:
 * a *transport* failure is worth another go, and a server that answered — even to
 * refuse — is not, because asking again will get the same refusal.
 */
internal suspend inline fun <T> retryTransport(
    attempts: Int = 3,
    backoffMs: Long = 400,
    isTransport: (Exception) -> Boolean,
    block: () -> T,
): T {
    var last: Exception? = null
    repeat(attempts) { attempt ->
        try {
            return block()
        } catch (e: Exception) {
            if (!isTransport(e) || attempt == attempts - 1) throw e
            last = e
            kotlinx.coroutines.delay(backoffMs * (attempt + 1))
        }
    }
    throw last ?: IllegalStateException("retry exhausted")
}
