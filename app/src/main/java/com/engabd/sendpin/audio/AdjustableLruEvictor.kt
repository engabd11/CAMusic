package com.engabd.sendpin.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import java.util.TreeSet

/**
 * media3's least-recently-used evictor, with a size that can change while the cache
 * is open.
 *
 * The stock one takes its limit once, at construction, and a [SimpleCache] can only
 * be built once per process, so a size chosen in settings would only take effect
 * after a restart. This is the same eviction order, with [setMaxBytes] to move the
 * limit and trim at once when it shrinks.
 *
 * Every callback here already runs under the cache's own lock (SimpleCache's
 * methods are synchronized), which is what keeps [spans] consistent; [setMaxBytes]
 * takes that same lock before it touches anything.
 */
@OptIn(UnstableApi::class)
class AdjustableLruEvictor(maxBytes: Long) : CacheEvictor {

    @Volatile var maxBytes: Long = maxBytes
        private set

    private val spans = TreeSet<CacheSpan> { a, b ->
        val delta = a.lastTouchTimestamp - b.lastTouchTimestamp
        if (delta == 0L) a.compareTo(b) else if (delta < 0) -1 else 1
    }
    private var size = 0L

    /** Move the limit, evicting the oldest audio straight away if it shrank. */
    fun setMaxBytes(cache: Cache, bytes: Long) {
        synchronized(cache) {
            maxBytes = bytes
            evict(cache, 0)
        }
    }

    override fun requiresCacheSpanTouches(): Boolean = true

    override fun onCacheInitialized() = Unit

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length != C.LENGTH_UNSET.toLong()) evict(cache, length)
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        spans.add(span)
        size += span.length
        evict(cache, 0)
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        spans.remove(span)
        size -= span.length
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        onSpanRemoved(cache, oldSpan)
        onSpanAdded(cache, newSpan)
    }

    private fun evict(cache: Cache, required: Long) {
        while (size + required > maxBytes && spans.isNotEmpty()) {
            cache.removeSpan(spans.first())
        }
    }
}
