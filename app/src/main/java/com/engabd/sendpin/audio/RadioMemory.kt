package com.engabd.sendpin.audio

import com.engabd.sendpin.local.db.TrackSignalEntity
import com.engabd.sendpin.ma.MaItem

/**
 * What the listener's own history says about a radio candidate: whether it was heard
 * too recently to come round again, and how much they have shown they like it.
 *
 * Pure, so the rules can be tested without a database. The caller builds one from
 * the `track_signal` table before each batch, which is what makes "don't replay"
 * hold across restarts rather than for one session. A play is filed there as well as
 * a serve, under the same key, so a track holds one place in the window however it
 * was heard, and Music Assistant plays count as well as the phone's own.
 *
 * ## The rules
 *
 *  - **Recently heard is excluded.** The newest [limit] tracks played or served are
 *    off the table, the same window the in-memory list used to hold.
 *  - **But never into silence.** On a library smaller than that window the rule
 *    would exclude everything, so [admit] lets back the longest-ago heard first, as
 *    many as a batch needs. Those that were heard within [RECENT_MS] still carry
 *    [RECENT_PENALTY], so they lose to anything fresher rather than being banned.
 *  - **A skip counts against a track**, by [SKIP_PENALTY] per skip up to
 *    [SKIP_CAP], fading to nothing over [SKIP_DECAY_MS]: a song skipped once on a
 *    bad morning a month ago has served its time.
 *  - **A favourite gets [FAVOURITE_BONUS]**, small on purpose: it breaks ties in
 *    the listener's favour without letting the same hearted songs crowd every set.
 */
class RadioMemory(
    /** When each track was last played or served, epoch ms, by its key. */
    heard: Map<String, Long> = emptyMap(),
    /** Skips by key. */
    private val skips: Map<String, Skip> = emptyMap(),
    private val limit: Int = HISTORY_LIMIT,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * The one key a track is filed under, whether served, played or skipped: in the
     * app, its scan key. One form, so a track is never two entries in the window, nor
     * missed because it was filed under the other.
     */
    private val keyOf: (MaItem) -> String = { it.itemId },
) {
    data class Skip(val count: Int, val lastAtMs: Long)

    // Insertion-ordered, so ids served in the same millisecond still have an order.
    private val heard = LinkedHashMap(heard)
    private var blockedCache: Set<String>? = null

    /** Note [keys] as served now — what this session has just queued. See [key]. */
    fun served(keys: Collection<String>) {
        val now = clock()
        keys.forEach {
            heard.remove(it)
            heard[it] = now
        }
        blockedCache = null
    }

    fun forget() {
        heard.clear()
        blockedCache = null
    }

    // Newest first, with ties broken newest-inserted first (the sort is stable).
    private fun blocked(): Set<String> = blockedCache ?: heard.entries.toList().asReversed()
        .sortedByDescending { it.value }
        .take(limit)
        .mapTo(HashSet()) { it.key }
        .also { blockedCache = it }

    // Asked from sort comparators, so each item's key is worked out once.
    private val keyCache = HashMap<String, String>()

    /** The key [item] is filed under. */
    fun key(item: MaItem): String = keyCache.getOrPut(item.provider + "|" + item.itemId) { keyOf(item) }

    fun isBlocked(item: MaItem): Boolean = key(item) in blocked()

    private fun lastHeard(item: MaItem): Long = heard[key(item)] ?: 0L

    /**
     * [candidates] without what was heard recently — topped back up, oldest-heard
     * first, until there are [need] of them when the library is too small to spare
     * the window. Order is otherwise kept.
     */
    fun admit(candidates: List<MaItem>, need: Int): List<MaItem> {
        val (stale, fresh) = candidates.partition { isBlocked(it) }
        if (fresh.size >= need || stale.isEmpty()) return fresh
        return fresh + stale.sortedBy { lastHeard(it) }.take(need - fresh.size)
    }

    /** Added to a candidate's score: a favourite's lift, minus skips and a very recent play. */
    fun adjust(item: MaItem): Float {
        val now = clock()
        var s = if (item.favorite) FAVOURITE_BONUS else 0f
        if (now - lastHeard(item) < RECENT_MS) s -= RECENT_PENALTY
        val skip = skips[key(item)]
        if (skip != null) s -= skipPenalty(skip, now)
        return s
    }

    companion object {
        /** How many recent tracks are kept off the air. */
        const val HISTORY_LIMIT = 200

        /** A track heard within this long is penalised when it has to be let back. */
        const val RECENT_MS = 24 * 60 * 60 * 1000L
        const val RECENT_PENALTY = 0.15f

        const val SKIP_PENALTY = 0.10f
        const val SKIP_CAP = 3
        const val SKIP_DECAY_MS = 30L * 24 * 60 * 60 * 1000

        const val FAVOURITE_BONUS = 0.05f

        /** A track left before this percentage of it has played, by pressing next, is a skip. */
        const val SKIP_PERCENT = 30

        /**
         * Whether leaving a track at [positionMs] of [durationMs] counts as a skip.
         * Only ever asked about a press of next; an unknown length is never one.
         */
        fun isSkip(positionMs: Long, durationMs: Long): Boolean =
            durationMs > 0 && positionMs >= 0 && positionMs * 100 < durationMs * SKIP_PERCENT

        fun skipPenalty(skip: Skip, nowMs: Long): Float {
            val age = (nowMs - skip.lastAtMs).coerceAtLeast(0L)
            val fade = (1f - age.toFloat() / SKIP_DECAY_MS).coerceIn(0f, 1f)
            return SKIP_PENALTY * skip.count.coerceAtMost(SKIP_CAP) * fade
        }

        /**
         * The memory the `track_signal` rows on disk describe, as the DAO returns them:
         * plays and serves in `lastServedAt`, skips in their own columns.
         */
        fun fromDisk(
            signals: List<TrackSignalEntity>,
            clock: () -> Long = System::currentTimeMillis,
            keyOf: (MaItem) -> String = { it.itemId },
        ): RadioMemory {
            val heard = HashMap<String, Long>()
            signals.filter { it.lastServedAt > 0 }.forEach { heard.merge(it.trackKey, it.lastServedAt, ::maxOf) }
            // Oldest first, so the insertion order agrees with the clock.
            val ordered = LinkedHashMap<String, Long>()
            heard.entries.sortedBy { it.value }.forEach { ordered[it.key] = it.value }
            val skips = signals.filter { it.skips > 0 }
                .associate { it.trackKey to Skip(it.skips, it.lastSkippedAt) }
            return RadioMemory(ordered, skips, clock = clock, keyOf = keyOf)
        }
    }
}
