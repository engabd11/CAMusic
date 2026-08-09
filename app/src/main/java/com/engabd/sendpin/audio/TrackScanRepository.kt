package com.engabd.sendpin.audio

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.data.LanOnlyCleartext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Decides what gets scanned, when, and what happens to the results.
 *
 * Process-scoped, and separate from `DirectLightSync` on purpose: pre-analysing
 * a library is something you do *before* the party, with nothing playing and no
 * bridge connected, so it cannot live inside the thing that streams to the
 * bridge. The light sync path is one of its callers, not its owner.
 *
 * One scan runs at a time, on a background-priority thread, through a single
 * worker fed by a queue. Two decodes at once would double the memory and halve
 * the priority the scheduler gives each, for no gain: the bottleneck is either
 * the network or one core, never both.
 *
 * The queue is two-tier. The song that is playing jumps it, because a scan that
 * lands after the sixth second of a track is not used until the next play of it
 * (see `DirectLightSync`), so a minute spent on a library sweep first is a
 * minute that costs the listener the show they are having now.
 */
class TrackScanRepository(
    private val context: Context,
    private val settings: AppSettings = AppSettings(context),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val store = TrackScanStore(File(context.filesDir, SCAN_DIR))

    private val _progress = MutableStateFlow(ScanProgress())
    val progress: StateFlow<ScanProgress> = _progress.asStateFlow()

    /**
     * Track keys as their scans land. `DirectLightSync` listens so a scan that
     * finishes while its track is still in the adoption window is picked up
     * rather than waiting for the next play.
     */
    private val _completed = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val completed: SharedFlow<String> = _completed.asSharedFlow()

    /**
     * Requests waiting for the worker. Unbounded, because dropping a request
     * silently is worse than a queue that grows: a library sweep enqueues
     * thousands and every one of them is wanted.
     */
    private val queue = Channel<ScanRequest>(Channel.UNLIMITED)

    private val urgent = Channel<ScanRequest>(Channel.UNLIMITED)

    private var sweepJob: Job? = null

    /** Keys already queued, so a track played twice isn't scanned twice. */
    private val queued = HashSet<String>()

    /**
     * Keys that have failed, and how often.
     *
     * Without this a track that cannot be analysed — a silent file, a codec this
     * phone lacks, a jingle too short to have a tempo — is re-fetched and
     * re-decoded on *every* play, forever. On a streamed library that is a
     * download each time, for a result already known.
     *
     * In memory only, so a restart tries again: a decode that failed because the
     * server was busy, or the phone was on a bad connection, deserves another
     * chance eventually, and an app launch is a reasonable "eventually". A
     * permanent verdict — silence, or too little audio — is remembered outright,
     * since no amount of retrying will change it.
     */
    private val failures = HashMap<String, Int>()

    private fun givenUpOn(key: String): Boolean =
        synchronized(failures) { (failures[key] ?: 0) >= MAX_ATTEMPTS }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(LanOnlyCleartext)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    init {
        scope.launch { worker() }
    }

    // ── What the light sync path uses ──────────────────────────────────────

    /**
     * The scan for [track] if there is one, reading the disk if it isn't in
     * memory yet. Suspending, so it is never called from the render loop.
     */
    suspend fun cached(track: LocalTrack): TrackScan? = withContext(Dispatchers.IO) {
        store.load(keyFor(track))
    }

    /** The scan for [track] if it is already in memory. Safe from any thread. */
    fun peek(track: LocalTrack): TrackScan? = store.peek(keyFor(track))

    /**
     * Ask for [track] to be scanned if it has not been already.
     *
     * [urgent] marks the song that is playing, which goes to the front. Returns
     * without waiting; the result arrives on [completed].
     */
    fun request(track: LocalTrack, urgent: Boolean = false) {
        scope.launch {
            if (!settings.lightSyncPrescan.first()) return@launch
            val key = keyFor(track)
            if (store.has(key) || givenUpOn(key)) return@launch
            enqueue(ScanRequest(track, key, urgent, rescan = false))
        }
    }

    /** Throw away [track]'s scan and analyse it again from scratch. */
    fun rescan(track: LocalTrack) {
        scope.launch {
            val key = keyFor(track)
            store.delete(key)
            synchronized(failures) { failures.remove(key) }
            enqueue(ScanRequest(track, key, urgent = true, rescan = true))
        }
    }

    private suspend fun enqueue(request: ScanRequest) {
        synchronized(queued) {
            if (!queued.add(request.key)) return
        }
        _progress.update { it.copy(pending = it.pending + 1) }
        if (request.urgent) urgent.send(request) else queue.send(request)
    }

    // ── Housekeeping the user can see ──────────────────────────────────────

    fun delete(track: LocalTrack) = store.delete(keyFor(track))

    suspend fun deleteAll(): Int = withContext(Dispatchers.IO) { store.deleteAll() }

    suspend fun usage(): Pair<Int, Long> = withContext(Dispatchers.IO) { store.usage() }

    // ── The library sweep ──────────────────────────────────────────────────

    /**
     * Analyse a whole library.
     *
     * [enumerate] is supplied by the caller rather than derived here, because
     * which tracks make up "the library" is a question about the backend and
     * this class deliberately knows nothing about backends. Already-scanned
     * tracks are skipped, so a sweep interrupted halfway resumes rather than
     * restarts.
     */
    fun sweep(enumerate: suspend () -> List<LocalTrack>) {
        if (sweepJob?.isActive == true) return
        sweepJob = scope.launch {
            _progress.update { it.copy(sweeping = true, sweepDone = 0, sweepTotal = 0) }
            val tracks = try {
                enumerate()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not list the library: ${e.message}")
                _progress.update {
                    it.copy(sweeping = false, error = e.message ?: "Could not list the library")
                }
                return@launch
            }
            val wanted = tracks.filterNot { store.has(keyFor(it)) || givenUpOn(keyFor(it)) }
            _progress.update { it.copy(sweepTotal = wanted.size, error = null) }
            for (track in wanted) {
                enqueue(ScanRequest(track, keyFor(track), urgent = false, rescan = false, fromSweep = true))
            }
            // The sweep flag comes down when the queue drains, not here — the
            // requests have only been posted at this point.
            if (wanted.isEmpty()) _progress.update { it.copy(sweeping = false) }
        }
    }

    fun cancelSweep() {
        sweepJob?.cancel()
        sweepJob = null
        // Drained and re-posted rather than cleared, because the same queue
        // carries the next-track prefetch, and cancelling a sweep is not a
        // request to stop preparing the song about to play.
        val keep = ArrayList<ScanRequest>()
        while (true) {
            val pending = queue.tryReceive().getOrNull() ?: break
            if (pending.fromSweep) synchronized(queued) { queued.remove(pending.key) }
            else keep.add(pending)
        }
        for (request in keep) queue.trySend(request)
        val stillQueued = synchronized(queued) { queued.size }
        _progress.update {
            it.copy(sweeping = false, sweepTotal = 0, sweepDone = 0, pending = stillQueued)
        }
    }

    // ── The worker ─────────────────────────────────────────────────────────

    private suspend fun worker() {
        while (true) {
            // The playing track always goes first: a scan that misses the
            // adoption window is a scan the listener does not get this time.
            val request = urgent.tryReceive().getOrNull() ?: run {
                kotlinx.coroutines.selects.select {
                    urgent.onReceive { it }
                    queue.onReceive { it }
                }
            }
            try {
                run(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Scan worker: ${e.message}")
            } finally {
                val stillQueued = synchronized(queued) {
                    queued.remove(request.key)
                    queued.size
                }
                _progress.update {
                    val done = if (request.fromSweep) it.sweepDone + 1 else it.sweepDone
                    it.copy(
                        pending = stillQueued,
                        current = null,
                        sweepDone = done,
                        sweeping = it.sweeping && done < it.sweepTotal,
                    )
                }
            }
        }
    }

    private suspend fun run(request: ScanRequest) {
        if (!request.rescan && store.has(request.key)) return
        // Re-checked here, not only when enqueued, so switching the setting off
        // stops a sweep already in flight rather than letting it run to the end.
        // A rescan is exempt: it is something the user just asked for by hand.
        if (!request.rescan && !settings.lightSyncPrescan.first()) return

        _progress.update { it.copy(current = request.track.title, error = null) }

        val local = request.track.localPath?.takeIf { File(it).exists() }
        val temp: File?
        val path: String
        if (local != null) {
            temp = null
            path = local
        } else {
            val url = request.track.streamUrl
            if (url.isNullOrBlank()) return
            if (settings.lightSyncPrescanWifiOnly.first() && isMetered()) {
                // Not an error to report: the sweep will pick this up next time
                // the phone is on Wi-Fi, which is what the setting asks for.
                return
            }
            temp = fetch(url) ?: run {
                noteFailure(request, "could not fetch the audio", ScanFailure.DECODE)
                return
            }
            path = temp.absolutePath
        }

        try {
            when (val result = TrackScanner.scan(path)) {
                is ScanResult.Ok -> {
                    synchronized(failures) { failures.remove(request.key) }
                    store.save(request.key, result.scan)
                    _completed.tryEmit(request.key)
                }
                is ScanResult.Failed -> noteFailure(request, describe(result), result.reason)
            }
        } finally {
            temp?.delete()
        }
    }

    private fun noteFailure(request: ScanRequest, why: String, reason: ScanFailure) {
        Log.i(TAG, "No usable analysis for \"${request.track.title}\": $why")
        // Silence and too-little-audio are verdicts about the file, so they are
        // final. A failed decode or fetch might be the network having a bad
        // moment, so it gets a few goes before this track is left alone.
        val permanent = reason == ScanFailure.SILENT || reason == ScanFailure.TOO_SHORT
        synchronized(failures) {
            failures[request.key] = if (permanent) MAX_ATTEMPTS else (failures[request.key] ?: 0) + 1
        }
        _progress.update { it.copy(failed = it.failed + 1, error = "${request.track.title} — $why") }
    }

    private fun describe(result: ScanResult.Failed): String = when (result.reason) {
        ScanFailure.DECODE -> result.detail.ifBlank { "could not decode it" }
        ScanFailure.TOO_SHORT -> "too short to analyse"
        ScanFailure.SILENT -> "it is silent"
        ScanFailure.CANCELLED -> "cancelled"
    }

    /**
     * Pull a remote track to a temporary file.
     *
     * `MediaExtractor` can open an HTTP URL itself, but it does its own
     * networking outside every policy this app sets — the LAN cleartext rules,
     * the timeouts, the connection pool — and it seeks, so a server without
     * range support fails in ways that are hard to tell from a corrupt file.
     * One sequential GET into a scratch file is slower to start and far easier
     * to reason about, and the file is gone before the method returns.
     */
    private suspend fun fetch(url: String): File? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, TEMP_DIR).apply { mkdirs() }
        val file = File(dir, "scan-${System.nanoTime()}.audio")
        try {
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body ?: return@withContext null
                file.outputStream().use { out -> body.byteStream().copyTo(out, 64 * 1024) }
            }
            file
        } catch (e: CancellationException) {
            file.delete()
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Fetch for analysis failed: ${e.message}")
            file.delete()
            null
        }
    }

    private fun isMetered(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private data class ScanRequest(
        val track: LocalTrack,
        val key: String,
        val urgent: Boolean,
        val rescan: Boolean,
        /**
         * Enqueued by a library sweep rather than by playback. Only these count
         * against the sweep's progress, and only these are dropped when it is
         * cancelled — stopping a sweep should not also cancel the scan of the
         * song currently playing.
         */
        val fromSweep: Boolean = false,
    )

    companion object {
        private const val TAG = "TrackScanRepository"
        private const val SCAN_DIR = "light-sync-scans"
        private const val TEMP_DIR = "scan-tmp"

        /** Attempts at one track before it is left alone for this session. */
        private const val MAX_ATTEMPTS = 3

        /**
         * What identifies a track's analysis.
         *
         * The library id, which is stable across plays and unique per song. A
         * downloaded track and its streamed original share it, which is right:
         * they are the same audio, and the analysis of one is the analysis of
         * the other. Falling back to the source path covers a track with no id
         * at all rather than letting them all collide on the empty string.
         */
        fun keyFor(track: LocalTrack): String =
            track.id.takeIf { it.isNotBlank() }
                ?: track.localPath
                ?: track.streamUrl
                ?: track.title
    }
}

/** What the analysis is doing, for the Light Sync screen to show. */
data class ScanProgress(
    /** The track being analysed right now, or null when idle. */
    val current: String? = null,
    /** How many are queued, the current one included. */
    val pending: Int = 0,
    /** A library sweep is running. */
    val sweeping: Boolean = false,
    val sweepDone: Int = 0,
    val sweepTotal: Int = 0,
    /** Tracks that produced no usable analysis this session. */
    val failed: Int = 0,
    /** The most recent problem worth showing. */
    val error: String? = null,
) {
    val busy: Boolean get() = current != null || pending > 0
}
