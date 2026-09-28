package com.engabd.sendpin.ui.design

/**
 * Two things every cover request should do to its URL, in one place.
 *
 * 1. **A cache key that ignores the auth.** Subsonic signs each request with a fresh
 *    random salt and the token made from it (`s=`, `t=`), so the same cover had a new
 *    URL every time an item was parsed — every visit to a screen. Coil keys both its
 *    caches on the URL, so every cover missed both, every time, and was fetched again
 *    over the network; the palette cache missed in the same way. [cacheKey] is the URL
 *    with those two dropped, and the request is keyed on it.
 * 2. **The size the tile actually needs.** Music Assistant's image proxy is asked for
 *    `size=0` — the original — because one URL serves the grid tile and the full-screen
 *    player alike. A 200 px tile then decoded a 1500 px original. [sized] asks the proxy
 *    for the tile's size instead, rounded up to a few fixed steps so neighbouring sizes
 *    share cache entries.
 */
object ArtUrls {

    /**
     * [cacheKey] applied to every request the image loader sees — covers, notification
     * art, the car's artwork, Light Sync's colour reads, palettes — rather than to
     * whichever of the nine request builders remembered to. Only a signed Subsonic
     * url is touched; every other request keeps Coil's own keys exactly.
     */
    object StableKeys : coil.intercept.Interceptor {
        override suspend fun intercept(chain: coil.intercept.Interceptor.Chain): coil.request.ImageResult {
            val request = chain.request
            val url = request.data as? String ?: (request.data as? android.net.Uri)?.toString()
                ?: return chain.proceed(request)
            val key = cacheKey(url)
            if (key == url || request.diskCacheKey != null) return chain.proceed(request)
            return chain.proceed(request.newBuilder().diskCacheKey(key).memoryCacheKey(key).build())
        }
    }

    /** The size steps [sized] rounds up to. */
    private val STEPS = intArrayOf(128, 256, 512, 1024)

    /** [url] for caching: Subsonic's per-request salt and token removed. */
    fun cacheKey(url: String): String {
        if (!url.contains("/rest/getCoverArt")) return url
        val q = url.indexOf('?').takeIf { it >= 0 } ?: return url
        val kept = url.substring(q + 1).split('&').filterNot { p ->
            val name = p.substringBefore('=')
            name == "t" || name == "s"
        }
        return url.substring(0, q + 1) + kept.joinToString("&")
    }

    /**
     * [url] asking Music Assistant's proxy for about [pixels] rather than the original.
     * Anything else — another server, a remote URL, no size given — is returned as is.
     */
    fun sized(url: String, pixels: Int?): String {
        if (pixels == null || pixels <= 0) return url
        if (!url.contains("/imageproxy?") || !Regex("[?&]size=0(&|$)").containsMatchIn(url)) return url
        val step = STEPS.firstOrNull { it >= pixels } ?: return url
        return url.replace(Regex("([?&])size=0(&|$)"), "$1size=$step$2")
    }
}
