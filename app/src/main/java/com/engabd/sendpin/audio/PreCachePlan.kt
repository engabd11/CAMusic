package com.engabd.sendpin.audio

/**
 * What "fetch ahead" fetches, and when. Pure, so the rules are tested rather than
 * hoped for.
 */
object PreCachePlan {
    /** How many songs ahead the setting offers: off, the next one, the next three or five. */
    val AHEAD_CHOICES = listOf(0, 1, 3, 5)

    /** Stream cache sizes the setting offers, in MB. 512 was the fixed size before. */
    val SIZE_CHOICES_MB = listOf(256, 512, 1024, 2048)
    const val DEFAULT_SIZE_MB = 512

    /** A stored size, kept to one the setting offers. */
    fun sizeMb(stored: Int?): Int = stored?.takeIf { it in SIZE_CHOICES_MB } ?: DEFAULT_SIZE_MB

    /**
     * Whether to fetch at all right now: switched on, music actually playing (a
     * paused queue may never be resumed), on a network the user allows, and a queue
     * this phone streams itself (MPD and the like play on their own box).
     */
    fun allowed(ahead: Int, playing: Boolean, unmetered: Boolean, wifiOnly: Boolean, remotePlayer: Boolean): Boolean =
        ahead > 0 && playing && !remotePlayer && (unmetered || !wifiOnly)

    /**
     * Which of the [upcoming] sources (in play order) to fetch: web streams only
     * (a downloaded file or a streaming service's own scheme has nothing to fetch),
     * each once, at most [ahead] of them.
     */
    fun targets(upcoming: List<String?>, ahead: Int): List<String> =
        upcoming.asSequence()
            .take(ahead.coerceAtLeast(0))
            .filterNotNull()
            .filter { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
            .distinctBy { MediaCache.cacheKey(it) }
            .toList()
}
