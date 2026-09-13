package com.engabd.sendpin.audio

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.data.Http
import com.engabd.sendpin.library.MusicSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
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
    /**
     * The library this phone plays from, for the original file behind a track.
     *
     * A track's [LocalTrack.streamUrl] is what the *player* opens, and on a Jellyfin
     * or Navidrome set to transcode that is a live ffmpeg stream: throttled once it
     * runs ahead of a playback position nobody is reporting, and cut off when the
     * server decides the session is dead. The scanner is not playing — it wants the
     * whole file, at once, at whatever speed the network gives — and every library
     * that transcodes also serves the stored file untouched. That is the copy the
     * analysis is read from wherever one can be named; the stream is the fallback.
     */
    private val sourceFor: () -> MusicSource? = {
        (context.applicationContext as? com.engabd.sendpin.SendpinApp)?.musicSource?.value
    },
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

    /**
     * Requests set aside because the phone is on a metered connection and the setting
     * says not to pull audio over one.
     *
     * Held rather than dropped, and put back the moment the network becomes unmetered —
     * see [watchNetwork]. Keyed so a track parked twice is parked once.
     */
    private val parked = LinkedHashMap<String, ScanRequest>()

    /**
     * The ones that failed, newest last, with enough of the request kept to try again.
     *
     * Bounded: a sweep over a library of unreadable files should cost a fixed amount of
     * memory, and nobody reads the four-hundredth entry.
     */
    private val failedRequests = LinkedHashMap<String, Pair<ScanRequest, ScanFailureNote>>()

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

    private val http: OkHttpClient by lazy { Http.transfer() }

    /**
     * The scan being decoded right now, so [skipCurrent] has something to cancel.
     *
     * A child of the worker rather than the worker itself: cancelling the worker
     * would end the queue, and the point of a skip is that the queue carries on
     * with the next track.
     */
    @Volatile private var currentJob: Job? = null

    init {
        scope.launch { worker() }
        watchNetwork()
        // Turning "Wi-Fi only" off is the other way the parked ones become runnable, and
        // it would otherwise take a network change to notice.
        scope.launch {
            settings.lightSyncPrescanWifiOnly.collect { wifiOnly -> if (!wifiOnly) releaseParked() }
        }
    }

    /**
     * Put parked requests back on the queue when the connection stops being metered.
     *
     * A callback rather than a poll: the sweep can be parked for hours, and the answer
     * arrives from the system the instant it changes. Best-effort — a device that
     * refuses the callback simply keeps its parked tracks until the next sweep, which
     * is what happened to all of them before.
     */
    private fun watchNetwork() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
                        releaseParked()
                    }
                }
            })
        }.onFailure { Log.w(TAG, "No network callback: ${it.message}") }
    }

    /** Everything parked, back on the queue. Does nothing when nothing is parked. */
    fun releaseParked() {
        val waiting = synchronized(parked) {
            if (parked.isEmpty()) return
            parked.values.toList().also { parked.clear() }
        }
        _progress.update { it.copy(parked = 0) }
        scope.launch { for (request in waiting) enqueue(request) }
    }

    /**
     * Try the tracks that failed for a reason that might not repeat.
     *
     * Silence and too-little-audio are verdicts about the file and are left alone — a
     * retry of those is a decode nobody needs, and on a streamed library a download to
     * go with it.
     */
    fun retryFailed() {
        val retryable = synchronized(failedRequests) {
            failedRequests.values.filter { it.second.retryable }.map { it.first }
                .also { list -> list.forEach { failedRequests.remove(it.key) } }
        }
        synchronized(failures) { retryable.forEach { failures.remove(it.key) } }
        _progress.update { note ->
            note.copy(
                failed = (note.failed - retryable.size).coerceAtLeast(0),
                failures = note.failures.filterNot { it.retryable },
                error = null,
            )
        }
        scope.launch { for (request in retryable) enqueue(request.copy(rescan = true)) }
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

    /**
     * Abandon the track being analysed right now and move on to the next one.
     *
     * For the forty-minute mix in the middle of a sweep of three-minute songs: it is
     * the one track whose analysis is not worth the wait, and without this the only
     * way past it was to stop the whole sweep. The track is left alone for the rest
     * of the session — a skip is a decision, not a failure — but it is listed with
     * the failures as retryable, so "Retry" brings it back if the decision changes.
     */
    fun skipCurrent() {
        currentJob?.cancel(CancellationException("skipped"))
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

    /**
     * How many stored scans a newer analyser would improve on.
     *
     * A directory walk, so it is asked when the analysis screen opens and when a sweep
     * ends — not on the screen's polling timer.
     */
    suspend fun outdatedCount(): Int = withContext(Dispatchers.IO) {
        store.outdated(TrackScan.ANALYSER_VERSION)
    }

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
    fun sweep(refreshOutdated: Boolean = false, enumerate: suspend () -> List<LocalTrack>) {
        if (sweepJob?.isActive == true) return
        sweepJob = scope.launch {
            // A sweep is the fresh start for the session's tally: the forty failures
            // from the last one are not this one's, and anything parked is about to be
            // asked for again.
            synchronized(parked) { parked.clear() }
            _progress.update {
                it.copy(
                    sweeping = true, sweepDone = 0, sweepTotal = 0,
                    parked = 0, failed = 0, failures = emptyList(),
                )
            }
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
            // A refresh wants the tracks that *have* a scan, so "already scanned" stops
            // being a reason to skip. Which of them are actually out of date is decided
            // per track in [run], where the file is being opened anyway — deciding it
            // here would read every scan on disk before the first one was analysed.
            val wanted = tracks.filterNot {
                (!refreshOutdated && store.has(keyFor(it))) || givenUpOn(keyFor(it))
            }
            _progress.update { it.copy(sweepTotal = wanted.size, error = null) }
            for (track in wanted) {
                enqueue(
                    ScanRequest(
                        track, keyFor(track), urgent = false, rescan = false,
                        fromSweep = true, rescanOutdated = refreshOutdated,
                    )
                )
            }
            // The sweep flag comes down when the queue drains, not here — the
            // requests have only been posted at this point.
            if (wanted.isEmpty()) _progress.update { it.copy(sweeping = false) }
        }
    }

    fun cancelSweep() {
        sweepJob?.cancel()
        sweepJob = null
        // Parked sweep tracks go with it: they are waiting to do the work that has just
        // been called off. A parked track from ordinary playback prefetch stays.
        val stillParked = synchronized(parked) {
            parked.entries.removeAll { it.value.fromSweep }
            parked.size
        }
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
            it.copy(
                sweeping = false, sweepTotal = 0, sweepDone = 0,
                pending = stillQueued, parked = stillParked,
            )
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
            var outcome = Outcome.DONE
            try {
                // Run as a child so a skip cancels this request and nothing else. If
                // the child is cancelled, `await` rethrows its cancellation — which is
                // not the worker's own, so it is caught here rather than propagated.
                outcome = coroutineScope {
                    val job = async { run(request) }
                    currentJob = job
                    try {
                        job.await()
                    } catch (e: CancellationException) {
                        // The worker being cancelled arrives the same way; tell the
                        // two apart by asking whether *we* are still meant to run.
                        ensureActive()
                        noteSkipped(request)
                        Outcome.DONE
                    } finally {
                        currentJob = null
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Scan worker: ${e.message}")
            } finally {
                val stillQueued = synchronized(queued) {
                    queued.remove(request.key)
                    queued.size
                }
                // Back of the queue rather than straight away, so a server having a bad
                // moment gets the rest of the sweep's worth of time before it is asked
                // again. `rescan` so the stored-scan check does not short-circuit it.
                if (outcome == Outcome.RETRY) scope.launch { enqueue(request.copy(rescan = true)) }
                _progress.update {
                    // A parked track has not been swept — it is waiting. Counting it as
                    // done is what let a sweep report completion over an empty result.
                    val done = if (request.fromSweep && outcome == Outcome.DONE) {
                        it.sweepDone + 1
                    } else it.sweepDone
                    val left = it.sweepTotal - done - it.parked
                    it.copy(
                        pending = stillQueued,
                        current = null,
                        currentFraction = null,
                        sweepDone = done,
                        sweeping = it.sweeping && left > 0,
                    )
                }
            }
        }
    }

    /** What became of one request, which is not always "it ran". */
    private enum class Outcome {
        DONE, PARKED,
        /** The fetch came up short; the request goes back on the queue for another go. */
        RETRY,
    }

    private suspend fun run(request: ScanRequest): Outcome {
        if (!request.rescan) {
            // `has` rather than `load` for the ordinary case: the common answer is "yes,
            // and nothing else needs to be known", and loading it would read a file per
            // track for nothing. Only a refresh has to look at what is in there.
            if (request.rescanOutdated) {
                val existing = withContext(Dispatchers.IO) { store.load(request.key) }
                if (existing != null && !existing.outdated) return Outcome.DONE
            } else if (store.has(request.key)) {
                return Outcome.DONE
            }
        }
        // Re-checked here, not only when enqueued, so switching the setting off
        // stops a sweep already in flight rather than letting it run to the end.
        // A rescan is exempt: it is something the user just asked for by hand.
        if (!request.rescan && !settings.lightSyncPrescan.first()) return Outcome.DONE

        _progress.update { it.copy(current = request.track.title, currentFraction = null, error = null) }

        val local = request.track.localPath?.takeIf { File(it).exists() }
        val temp: File?
        val path: String
        if (local != null) {
            temp = null
            path = local
        } else {
            val urls = analysisUrls(request.track)
            if (urls.isEmpty()) return Outcome.DONE
            if (settings.lightSyncPrescanWifiOnly.first() && isMetered()) {
                // Not a failure — the setting is being honoured. Held until the
                // connection is unmetered, and counted so the screen can say so.
                park(request)
                return Outcome.PARKED
            }
            temp = urls.firstNotNullOfOrNull { fetch(it) } ?: run {
                noteFailure(request, "could not fetch the audio", ScanFailure.DECODE)
                return Outcome.DONE
            }
            path = temp.absolutePath
        }

        try {
            val result = TrackScanner.scan(path) { fraction ->
                _progress.update { it.copy(currentFraction = fraction) }
            }
            when (result) {
                is ScanResult.Ok -> {
                    val scan = result.scan
                    // A scan that stops short of the track's own length is what
                    // "analysed for the first two minutes, the rest worked out live"
                    // looks like from the inside. From a fetched file that is almost
                    // always the fetch — a transcode the server cut off — and the
                    // fix is to fetch it again, not to keep the fragment. A local file
                    // that reads short is the file, and re-reading it gives the same
                    // answer, so its scan is kept as the best there is. So is the
                    // fragment once the fetch has been given its full run of tries:
                    // a partial grid still beats none for the first play.
                    val short = !scan.complete && !scan.withinCap
                    val attemptsLeft = synchronized(failures) { (failures[request.key] ?: 0) + 1 < MAX_ATTEMPTS }
                    if (short && temp != null && attemptsLeft) {
                        noteFailure(
                            request,
                            "only ${(scan.analysedS / 60f).toInt()} of ${(scan.durationS / 60f).toInt()} min arrived, trying again",
                            ScanFailure.DECODE,
                        )
                        return Outcome.RETRY
                    } else {
                        synchronized(failures) { failures.remove(request.key) }
                        synchronized(failedRequests) { failedRequests.remove(request.key) }
                        store.save(request.key, scan)
                        _completed.tryEmit(request.key)
                    }
                }
                is ScanResult.Failed -> noteFailure(request, describe(result), result.reason)
            }
        } finally {
            temp?.delete()
        }
        return Outcome.DONE
    }

    /**
     * Where to fetch [track]'s audio for analysis, best first.
     *
     * The library's original file where the track can be tied to the active library
     * (see [sourceFor]), then the player's own stream. Each is tried in turn by
     * [run]: a server that refuses downloads to this user falls through to the
     * stream, which is what it was reading before.
     */
    private fun analysisUrls(track: LocalTrack): List<String> {
        val out = LinkedHashSet<String>()
        val source = sourceFor()
        if (source != null && track.scrobbleProvider == source.providerId) {
            track.scrobbleId?.takeIf { it.isNotBlank() }?.let { id ->
                runCatching { source.downloadUrl(id) }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(out::add)
            }
        }
        track.streamUrl?.takeIf { it.isNotBlank() }?.let(out::add)
        return out.toList()
    }

    private fun noteSkipped(request: ScanRequest) {
        Log.i(TAG, "Skipped analysis of \"${request.track.title}\"")
        synchronized(failures) { failures[request.key] = MAX_ATTEMPTS }
        val note = ScanFailureNote(request.track.title, "skipped", retryable = true)
        val notes = synchronized(failedRequests) {
            failedRequests[request.key] = request to note
            while (failedRequests.size > MAX_REMEMBERED_FAILURES) {
                failedRequests.remove(failedRequests.keys.first())
            }
            failedRequests.values.map { it.second }.asReversed()
        }
        _progress.update { it.copy(failures = notes) }
    }

    private fun park(request: ScanRequest) {
        val count = synchronized(parked) {
            parked[request.key] = request
            parked.size
        }
        _progress.update { it.copy(parked = count, current = null) }
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
        val note = ScanFailureNote(request.track.title, why, retryable = !permanent)
        val notes = synchronized(failedRequests) {
            failedRequests[request.key] = request to note
            while (failedRequests.size > MAX_REMEMBERED_FAILURES) {
                failedRequests.remove(failedRequests.keys.first())
            }
            failedRequests.values.map { it.second }.asReversed()
        }
        _progress.update {
            it.copy(
                failed = it.failed + 1,
                failures = notes,
                error = "${request.track.title}, $why",
            )
        }
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
                if (!response.isSuccessful) {
                    Log.i(TAG, "Fetch for analysis refused: HTTP ${response.code}")
                    return@withContext null
                }
                val body = response.body ?: return@withContext null
                val copied = file.outputStream().use { out -> body.byteStream().copyTo(out, 64 * 1024) }
                // A server that says how long the file is and then sends less has cut
                // the transfer off, and what arrived is a fragment. Analysing a fragment
                // produces a scan of the first however-many minutes that then presents
                // itself as the track; better to say so and try again.
                val promised = body.contentLength()
                if (promised > 0 && copied < promised) {
                    Log.w(TAG, "Fetch for analysis short: $copied of $promised bytes")
                    file.delete()
                    return@withContext null
                }
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
        /**
         * Re-analyse if the stored scan came from an older analyser.
         *
         * Distinct from [rescan], which throws the scan away before asking: this one
         * keeps whatever is there unless a newer analyser would do better, so a refresh
         * over a library costs a decode only for the tracks that stand to gain.
         */
        val rescanOutdated: Boolean = false,
    )

    companion object {
        private const val TAG = "TrackScanRepository"
        private const val SCAN_DIR = "light-sync-scans"
        private const val TEMP_DIR = "scan-tmp"

        /** Attempts at one track before it is left alone for this session. */
        private const val MAX_ATTEMPTS = 3

        /**
         * How many failures are kept for the screen to show and the retry to act on.
         *
         * A sweep over a library where a whole folder is unreadable would otherwise grow
         * this without limit, and nobody scrolls a list of four hundred.
         */
        private const val MAX_REMEMBERED_FAILURES = 40

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
    /**
     * How far through [current] the decode is, 0..1, or null before the first buffer
     * and for a file whose container does not say how long it is.
     */
    val currentFraction: Float? = null,
    /** How many are queued, the current one included. */
    val pending: Int = 0,
    /** A library sweep is running. */
    val sweeping: Boolean = false,
    val sweepDone: Int = 0,
    val sweepTotal: Int = 0,
    /** Tracks that produced no usable analysis this session. */
    val failed: Int = 0,
    /**
     * Tracks set aside until the phone is back on an unmetered network.
     *
     * They used to be dropped: the worker returned without a word, counted the track as
     * swept and moved on, so a sweep begun on mobile data "finished" having analysed
     * nothing at all and said so nowhere. Parked instead — held, counted, and put back
     * on the queue when the network changes.
     */
    val parked: Int = 0,
    /** What went wrong, most recent first, for the ones that did. */
    val failures: List<ScanFailureNote> = emptyList(),
    /** The most recent problem worth showing. */
    val error: String? = null,
) {
    val busy: Boolean get() = current != null || pending > 0

    /** Nothing to do until the network changes — not idle, and not working either. */
    val waitingForNetwork: Boolean get() = parked > 0 && !busy

    /** 0..1 through the sweep, or null when there is no sweep to be through. */
    val sweepFraction: Float?
        get() = if (sweepTotal > 0) (sweepDone.toFloat() / sweepTotal).coerceIn(0f, 1f) else null
}

/**
 * One track that could not be analysed, and why.
 *
 * Kept as a list rather than the single "most recent problem" line that came before it:
 * one message at a time, overwritten by the next, made a sweep with forty unreadable
 * files look like a sweep with one. [retryable] is what separates a bad moment on the
 * network from a verdict about the file.
 */
data class ScanFailureNote(
    val title: String,
    val why: String,
    val retryable: Boolean,
)
