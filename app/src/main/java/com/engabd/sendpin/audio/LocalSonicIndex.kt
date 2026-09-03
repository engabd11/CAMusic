package com.engabd.sendpin.audio

import com.engabd.sendpin.library.MusicSources
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ma.MaSimilarTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * "What else sounds like this", answered from this phone's own offline scans.
 *
 * Music Assistant answers it for the providers it knows about; a Subsonic or
 * Jellyfin library on its own has no such service, and that is exactly the
 * library this app plays locally. Every track already analysed carries a tempo,
 * a key and an intensity profile — see [TrackScan] — which is enough to compare
 * two tracks without asking anyone.
 *
 * Building the index means enumerating every local library over the network, so
 * it is built on first use and reused for [INDEX_TTL_MS]. Nothing reaches here
 * until Music Assistant has already come back empty, so that cost is only ever
 * paid by someone who would otherwise be looking at an empty panel.
 */
class LocalSonicIndex(
    private val context: android.content.Context,
    private val scanStore: TrackScanStore,
) {
    /** A track that can be both matched on and shown, with no second round-trip. */
    private class Entry(val track: MaSimilarTrack, val fingerprint: SonicFingerprint)

    private val lock = Mutex()
    private var entries: Map<String, Entry> = emptyMap()
    private var builtAtMs = 0L

    /**
     * The closest local tracks to [seed], nearest first.
     *
     * Empty when [seed] has no scan of its own: with nothing to compare against,
     * every answer would be equally wrong, and a wrong list reads worse than none.
     */
    suspend fun similarTo(seed: MaItem, limit: Int = 20): List<MaSimilarTrack> {
        val indexed = index()
        val seedKey = MusicSources.scanKey(seed)
        val target = indexed[seedKey]?.fingerprint ?: return emptyList()
        return indexed.asSequence()
            .filter { it.key != seedKey }
            .map { it.value.track to SonicSimilarity.distance(target, it.value.fingerprint) }
            .sortedBy { it.second }
            .take(limit)
            .map { it.first }
            .toList()
    }

    private suspend fun index(): Map<String, Entry> = lock.withLock {
        val fresh = entries.isNotEmpty() && System.currentTimeMillis() - builtAtMs < INDEX_TTL_MS
        if (fresh) return@withLock entries
        build().also {
            entries = it
            builtAtMs = System.currentTimeMillis()
        }
    }

    private suspend fun build(): Map<String, Entry> = withContext(Dispatchers.IO) {
        val next = mutableMapOf<String, Entry>()
        for (source in MusicSources.allLocal(context)) {
            try {
                var offset = 0
                while (true) {
                    val page = source.tracks(offset = offset, limit = PAGE)
                    if (page.isEmpty()) break
                    for (track in page) {
                        val key = MusicSources.scanKey(track)
                        val scan = scanStore.load(key) ?: continue
                        next[key] = Entry(track.toSimilar(), SonicFingerprint.from(scan))
                    }
                    offset += page.size
                    // A short page is the end of the library. A source that keeps
                    // returning full pages of the same tracks collapses into the
                    // key map rather than growing, so this cannot run away.
                    if (page.size < PAGE) break
                }
            } catch (_: Exception) {
                // One unreachable library should not empty the whole index.
            }
        }
        next
    }

    /**
     * [MaItem.subtitle] is the artist credit — see its own docs. The provider is
     * carried through unchanged because it is what decides whether a suggestion
     * can be queued at all; see `NowPlayingViewModel.localTrackFor`.
     */
    private fun MaItem.toSimilar() = MaSimilarTrack(
        itemId = itemId,
        name = name,
        artist = subtitle,
        image = image,
        uri = uri,
        provider = provider,
    )

    private companion object {
        const val PAGE = 500

        /** Long enough that opening the panel twice does not enumerate twice. */
        const val INDEX_TTL_MS = 10 * 60 * 1000L
    }
}
