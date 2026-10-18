package com.engabd.sendpin.party

/**
 * A sliding-window limiter: at most [limit] events per [windowMs], counted per key.
 *
 * Keys are guest ids or addresses. Old events are dropped as they age out, and keys
 * with nothing left are removed so a long party does not collect an entry for every
 * address that ever knocked.
 */
class RateLimiter(
    private val limit: Int,
    private val windowMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val events = HashMap<String, ArrayDeque<Long>>()

    /** Records an event for [key] and returns true, or returns false (recording nothing) when the key is over its limit. */
    @Synchronized
    fun tryAcquire(key: String): Boolean {
        val now = clock()
        if (events.size > MAX_KEYS) prune(now)
        val q = events.getOrPut(key) { ArrayDeque() }
        while (q.isNotEmpty() && now - q.first() >= windowMs) q.removeFirst()
        if (q.size >= limit) return false
        q.addLast(now)
        return true
    }

    private fun prune(now: Long) {
        val it = events.entries.iterator()
        while (it.hasNext()) {
            val q = it.next().value
            while (q.isNotEmpty() && now - q.first() >= windowMs) q.removeFirst()
            if (q.isEmpty()) it.remove()
        }
    }

    private companion object { const val MAX_KEYS = 2_000 }
}
