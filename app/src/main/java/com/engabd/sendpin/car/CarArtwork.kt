package com.engabd.sendpin.car

import java.security.MessageDigest

/**
 * The token half of cover art in the car.
 *
 * Android Auto's browse rows want an *icon URI* — the platform's own guidance is
 * explicit that a bitmap posted through the browser's binder is the wrong shape, and
 * a folder of fifty covers would exceed the transaction limit long before it looked
 * good. But the URLs this app holds cannot be handed out: a Subsonic or Jellyfin
 * cover URL carries the account's credentials in its query string, Plex carries a
 * token, and Music Assistant's image proxy answers only to an `Authorization` header
 * that nothing outside this process can attach. That is why the browse tree shipped
 * with no artwork at all.
 *
 * So the URL never leaves. What crosses the binder is a `content://` URI naming an
 * opaque token, and [CarArtworkProvider] resolves the token back to the URL *inside
 * this process*, fetches it through the app's own authenticated image loader, and
 * answers with decoded bytes. The credential is never in anything anyone else can
 * see, and the provider is not exported — each browser is granted read access to the
 * individual URIs it was just handed, and nothing else.
 *
 * The token is the SHA-256 of the URL rather than a random id, so the same cover
 * gets the same URI on every browse: Android Auto caches by URI, and a fresh id per
 * request would re-download every cover each time a folder was opened. It is not a
 * secret — anyone who could compute it already has the URL it is made from — it is
 * simply a name that is not also a password.
 *
 * Pure Kotlin, so the [token] contract is checkable on the JVM. See `CarArtworkTest`.
 */
object CarArtwork {

    /** Appended to the app's package to form the provider authority. */
    const val AUTHORITY_SUFFIX = ".carart"

    /** The single path segment every artwork URI starts with. */
    const val PATH_ART = "art"

    /** Requested edge length, in pixels. The car tells us what it wants; see the bridge. */
    const val QUERY_PX = "px"

    /**
     * How many covers can be addressed at once.
     *
     * An LRU rather than an unbounded map: a browse session over a large library
     * mints one entry per row seen, and this object lives as long as the process.
     * Two thousand is several screens' worth of every folder a driver is plausibly
     * going to open in one trip; past that the oldest URL is forgotten and its URI
     * answers with no art, which is the same outcome as a cover that failed to load.
     */
    private const val CAPACITY = 2_048

    private val urls = object : LinkedHashMap<String, String>(128, 0.75f, /* accessOrder = */ true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean = size > CAPACITY
    }

    /** The stable, non-reversible name for a URL. */
    fun token(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            for (b in digest) {
                val v = b.toInt() and 0xFF
                append(HEX[v ushr 4])
                append(HEX[v and 0x0F])
            }
        }
    }

    /** Note a URL as addressable and return the token that addresses it. */
    @Synchronized
    fun remember(url: String): String = token(url).also { urls[it] = url }

    /** The URL behind a token, or null once it has aged out. */
    @Synchronized
    fun lookup(token: String): String? = urls[token]

    /** Forget everything — used when the libraries change under the car. */
    @Synchronized
    fun forgetAll() = urls.clear()

    @Synchronized
    internal fun size(): Int = urls.size

    private val HEX = "0123456789abcdef".toCharArray()
}
