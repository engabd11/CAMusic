package com.engabd.sendpin.audio

import com.engabd.sendpin.library.MusicSources
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ma.MaSimilarTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
 * it is built on first use and rebuilt only when it may be out of date: more or fewer
 * scans than it was built from (and not within [INDEX_TTL_MS] of the last build, so a
 * sweep landing a scan a second does not re-read the library a second), or
 * [LIBRARY_TTL_MS] old, for tracks a library dropped. A stale index keeps answering
 * while the new one builds in the background. The radio never waits on a cold one
 * (see [similarTo]'s `wait`); the Similar panel does, being someone looking at it.
 */
class LocalSonicIndex(
    private val context: android.content.Context,
    private val scanStore: TrackScanStore,
) {
    /** A track that can be both matched on and shown, with no second round-trip. */
    private class Entry(val track: MaSimilarTrack, val fingerprint: SonicFingerprint)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var entries: Map<String, Entry> = emptyMap()
    @Volatile private var builtAtMs = 0L
    @Volatile private var builtFromScans = 0
    private var building: Deferred<Map<String, Entry>>? = null

    /**
     * The closest local tracks to [seed], nearest first.
     *
     * Empty when [seed] has no scan of its own: with nothing to compare against,
     * every answer would be equally wrong, and a wrong list reads worse than none.
     * Empty too, without [wait], while the first index is still being built.
     */
    suspend fun similarTo(seed: MaItem, limit: Int = 20, wait: Boolean = true): List<MaSimilarTrack> {
        val indexed = index(wait)
        val seedKey = MusicSources.legacyScanKey(seed)
        val target = indexed[seedKey]?.fingerprint ?: return emptyList()
        return indexed.asSequence()
            .filter { it.key != seedKey }
            .map { it.value.track to SonicSimilarity.distance(target, it.value.fingerprint) }
            .sortedBy { it.second }
            .take(limit)
            .map { it.first }
            .toList()
    }

    private suspend fun index(wait: Boolean): Map<String, Entry> {
        if (builtAtMs > 0) {
            val age = System.currentTimeMillis() - builtAtMs
            if (needsRebuild(age, builtFromScans, scanStore.count())) rebuild()
            return entries
        }
        val pending = rebuild()
        return if (wait) pending.await() else emptyMap()
    }

    /** The build in progress, or a new one. One at a time. */
    private fun rebuild(): Deferred<Map<String, Entry>> = synchronized(this) {
        building?.takeIf { it.isActive } ?: scope.async {
            val scans = scanStore.count()
            build().also {
                entries = it
                builtFromScans = scans
                builtAtMs = System.currentTimeMillis()
            }
        }.also { building = it }
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
                        // Indexed by the item id, which a Downloads seed shares with its
                        // library's copy; read by the full key the scan is filed under.
                        val key = MusicSources.legacyScanKey(track)
                        val scan = scanStore.load(MusicSources.scanKey(track), key) ?: continue
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

    internal companion object {
        const val PAGE = 500

        /** The least time between two builds for a change in scans. */
        const val INDEX_TTL_MS = 10 * 60 * 1000L

        /** Rebuilt this often regardless, for tracks a library no longer has. */
        const val LIBRARY_TTL_MS = 6 * 60 * 60 * 1000L

        /** Whether an index [ageMs] old, built from [builtFromScans] scans, is out of date now there are [scansNow]. */
        fun needsRebuild(ageMs: Long, builtFromScans: Int, scansNow: Int): Boolean =
            ageMs >= LIBRARY_TTL_MS || (ageMs >= INDEX_TTL_MS && scansNow != builtFromScans)
    }
}
