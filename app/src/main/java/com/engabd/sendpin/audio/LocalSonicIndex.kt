package com.engabd.sendpin.audio

import com.engabd.sendpin.library.MusicSources
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ma.MaSimilarTrack

/**
 * Lightweight in-memory sonic-similarity index for local tracks with scans.
 *
 * The index is rebuilt whenever a batch of scans finishes. Lookups are
 * synchronous against the in-memory map, so the "Similar" panel can fall back to
 * local matches without blocking the UI thread.
 */
class LocalSonicIndex(
    private val context: android.content.Context,
    private val scanStore: TrackScanStore,
) {
    private val index = mutableMapOf<String, SonicFingerprint>()

    /**
     * Rebuild the index from every track every local source will enumerate.
     * This is intentionally bounded and best-effort: tracks with scans contribute,
     * tracks without are skipped.
     */
    suspend fun rebuild(): Int {
        val next = mutableMapOf<String, SonicFingerprint>()
        for (source in MusicSources.allLocal(context)) {
            try {
                var offset = 0
                while (true) {
                    val page = source.tracks(offset = offset, limit = 500)
                    if (page.isEmpty()) break
                    for (track in page) {
                        val key = MusicSources.scanKey(track)
                        val scan = scanStore.load(key) ?: continue
                        next[key] = SonicFingerprint.from(scan)
                    }
                    offset += page.size
                    // Safety cap: a source that returns overlapping pages would loop forever.
                    if (page.size < 500) break
                }
            } catch (_: Exception) {
                // One failing source should not poison the whole index.
            }
        }
        synchronized(index) {
            index.clear()
            index.putAll(next)
        }
        return next.size
    }

    /** Add or update a single track's fingerprint after a scan completes. */
    fun update(track: MaItem, scan: TrackScan) {
        synchronized(index) {
            index[MusicSources.scanKey(track)] = SonicFingerprint.from(scan)
        }
    }

    /** Find the closest local tracks to [seed]. */
    fun findSimilar(seed: MaItem, limit: Int = 20): List<Pair<MaItem, Float>> {
        val targetKey = MusicSources.scanKey(seed)
        val target: SonicFingerprint
        synchronized(index) {
            target = index[targetKey] ?: return emptyList()
        }
        val entries = synchronized(index) { index.entries.toList() }
        val matches = entries
            .asSequence()
            .filter { it.key != targetKey }
            .mapNotNull { (key, fp) ->
                val item = runCatching { MusicSources.findByKey(context, key) }.getOrNull()
                if (item == null) null else item to SonicSimilarity.distance(target, fp)
            }
            .sortedBy { it.second }
            .take(limit)
            .toList()
        return matches
    }

    /** Convert a distance score into the MaSimilarTrack shape the UI already expects. */
    fun toMaSimilarTrack(pair: Pair<MaItem, Float>): MaSimilarTrack {
        val (item, distance) = pair
        return MaSimilarTrack(
            itemId = item.itemId,
            name = item.name,
            artist = item.artist,
            image = item.image,
            uri = item.uri,
            provider = item.provider,
        )
    }
}
