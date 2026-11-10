package com.engabd.sendpin.audio

import com.engabd.sendpin.local.db.RecentPlay
import com.engabd.sendpin.local.db.TrackSignalEntity
import com.engabd.sendpin.ma.MaItem

/**
 * What the listener's own history says about a radio candidate: whether it was heard
 * too recently to come round again, and how much they have shown they like it.
 *
 * Pure, so the rules can be tested without a database. The caller builds one from
 * what is on disk — `play_history` and the `track_signal` table — before each batch,
 * which is what makes "don't replay" hold across restarts rather than for one session.
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
    /** When each track was last played or served, epoch ms, by any of its keys. */
    heard: Map<String, Long> = emptyMap(),
    /** Skips by key. */
    private val skips: Map<String, Skip> = emptyMap(),
    private val limit: Int = HISTORY_LIMIT,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Every key a candidate may be filed under. A history row carries only the bare
     * library id; a served or skipped one carries the provider-qualified key scans
     * use, so the caller answers with both.
     */
    private val keysOf: (MaItem) -> List<String> = { listOf(it.itemId) },
) {
    data class Skip(val count: Int, val lastAtMs: Long)

    // Insertion-ordered, so ids served in the same millisecond still have an order.
    private val heard = LinkedHashMap(heard)
    private var blockedCache: Set<String>? = null

    /** Note [ids] as served now — what this session has just queued. */
    fun served(ids: Collection<String>) {
        val now = clock()
        ids.forEach {
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

    // Asked from sort comparators, so each item's keys are worked out once.
    private val keyCache = HashMap<String, List<String>>()
    private fun keys(item: MaItem) = keyCache.getOrPut(item.provider + "|" + item.itemId) { keysOf(item) }

    fun isBlocked(item: MaItem): Boolean {
        val set = blocked()
        return keys(item).any { it in set }
    }

    private fun lastHeard(item: MaItem): Long = keys(item).maxOfOrNull { heard[it] ?: 0L } ?: 0L

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
        val skip = keys(item).mapNotNull { skips[it] }.maxByOrNull { it.lastAtMs }
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
         * The library id inside a `play_history.trackId`, which local plays store as
         * "queueIndex-id" (`PlaybackReporter`). Only a leading integer is stripped —
         * "-1-" when the track was not found in the queue — so an id that itself
         * contains dashes, a Jellyfin guid or an MA uri, comes back whole.
         */
        fun historyId(trackId: String): String = trackId.replaceFirst(HISTORY_PREFIX, "")

        /**
         * The memory the rows on disk describe: [plays] from `play_history` and
         * [signals] from `track_signal`, both as their DAOs return them.
         */
        fun fromDisk(
            plays: List<RecentPlay>,
            signals: List<TrackSignalEntity>,
            clock: () -> Long = System::currentTimeMillis,
            keysOf: (MaItem) -> List<String> = { listOf(it.itemId) },
        ): RadioMemory {
            val heard = HashMap<String, Long>()
            plays.forEach { heard.merge(historyId(it.trackId), it.timestamp, ::maxOf) }
            signals.filter { it.lastServedAt > 0 }.forEach { heard.merge(it.trackKey, it.lastServedAt, ::maxOf) }
            // Oldest first, so the insertion order agrees with the clock.
            val ordered = LinkedHashMap<String, Long>()
            heard.entries.sortedBy { it.value }.forEach { ordered[it.key] = it.value }
            val skips = signals.filter { it.skips > 0 }
                .associate { it.trackKey to Skip(it.skips, it.lastSkippedAt) }
            return RadioMemory(ordered, skips, clock = clock, keysOf = keysOf)
        }

        private val HISTORY_PREFIX = Regex("^-?\\d+-")
    }
}
