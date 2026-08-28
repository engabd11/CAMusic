package com.engabd.sendpin.hue

import com.engabd.sendpin.audio.AnalysisFrame
import com.engabd.sendpin.audio.BeatGrid
import com.engabd.sendpin.audio.LocalTrack
import com.engabd.sendpin.audio.ScanFrameSynth
import com.engabd.sendpin.audio.TrackScan
import com.engabd.sendpin.audio.TrackScanRepository
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.download.DownloadManager
import com.engabd.sendpin.library.MusicSource
import com.engabd.sendpin.service.MaNowPlaying
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The light show for a Music Assistant queue playing on a **remote** speaker.
 *
 * ## The problem
 *
 * When MA targets a speaker that is not this phone, nothing is decoded here: the
 * analysis tap is fed by nothing, `renderLoop` sees stale frames and falls to the
 * idle pattern, and the bridge session is open the whole time — which is why the UI
 * said "Reacting to the beat" while the lamps did nothing. There is no audio to be
 * had and there never will be.
 *
 * ## What replaces it
 *
 * The same thing syncoV2 does: read the precomputed analysis and schedule against it.
 * [TrackScan] already carries a beat grid, section boundaries, an intensity curve and
 * an absolute per-band reference; [MaNowPlaying] already projects the playhead.
 * Together those are a show — a beat-and-structure one, not a spectrum one, and
 * [ScanFrameSynth] is explicit about which of its fields are real.
 *
 * ## What it costs
 *
 * The timing is only as good as MA's playhead, which
 * [com.engabd.sendpin.ui.viewmodel.PlayerPositionTracker] interpolates between server
 * readings: a repeated `elapsed_time_last_updated` means the server has not recomputed
 * it, and for a remote speaker that is most polls — so corrections land seconds
 * apart and carry accumulated drift. [PositionSlew] absorbs them without ever jumping. What
 * it cannot absorb is the speaker's own latency, which is device-specific and
 * unknowable from here (a cast group can be half a second), hence the user-tunable
 * offset in [AppSettings.lightSyncSpeakerOffsetMs]. syncoV2 has the same slider for
 * the same reason.
 */
class ScanFrameSource(
    private val scans: TrackScanRepository,
    private val nowPlaying: MaNowPlaying,
    private val downloads: DownloadManager,
    private val settings: AppSettings,
    /** The live self-hosted library, for finding a file to analyse. May be null. */
    private val musicSource: () -> MusicSource?,
    /** Where a synthesised frame goes. Wired to `DirectLightSync.onSynthFrame`. */
    private val sink: (AnalysisFrame, BeatGrid?, TrackScan, Float) -> Unit,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val synth = ScanFrameSynth()
    private val slew = PositionSlew()

    private val _state = MutableStateFlow(ScanFeedState.IDLE)
    val state: StateFlow<ScanFeedState> = _state.asStateFlow()

    /**
     * Whether frames are being produced right now.
     *
     * Read by the feed picker *and* folded into `DirectLightSync`'s `isPlaying` — the
     * render loop's `isIdle` is `!playerPlaying || !fresh`, so without the second the
     * idle show would run over perfectly good synthetic frames.
     */
    private val _driving = MutableStateFlow(false)
    val driving: StateFlow<Boolean> = _driving.asStateFlow()

    @Volatile private var scan: TrackScan? = null
    @Volatile private var pendingKey: String? = null
    @Volatile private var offsetMs: Int = 0

    private var jobs = mutableListOf<Job>()

    fun start() {
        if (jobs.isNotEmpty()) return
        jobs += scope.launch { watchTrack() }
        jobs += scope.launch { watchScans() }
        jobs += scope.launch { settings.lightSyncSpeakerOffsetMs.collect { offsetMs = it } }
        jobs += scope.launch { tick() }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        reset()
    }

    private fun reset() {
        scan = null
        pendingKey = null
        slew.clear()
        synth.reset()
        _driving.value = false
        _state.value = ScanFeedState.IDLE
    }

    /**
     * Follow the remote track, adopting or requesting an analysis for each one.
     *
     * Keyed on identity rather than on the `Now` object: MA republishes that on every
     * volume nudge and every progress tick, and re-resolving a library search per tick
     * would be absurd.
     */
    private suspend fun watchTrack() {
        nowPlaying.now
            .distinctUntilChanged { a, b ->
                RemoteTrackIdentity.sameTrack(
                    a?.title, a?.artist, a?.durationMs ?: 0L,
                    b?.title, b?.artist, b?.durationMs ?: 0L,
                ) && (a?.isSelf == b?.isSelf) && (a?.isPlaying == b?.isPlaying)
            }
            .collect { now ->
                // Only remote playback. Anything reaching this phone's own output has
                // real PCM and a far better show behind it.
                if (now == null || now.isSelf || !now.isPlaying) {
                    reset()
                    return@collect
                }
                scan = null
                synth.reset()
                slew.clear()
                _driving.value = false

                val track = resolveTrack(now)
                if (track == null) {
                    _state.value = ScanFeedState.NO_MATCH
                    pendingKey = null
                    return@collect
                }
                val cached = scans.cached(track)
                if (cached != null) {
                    adopt(cached)
                    return@collect
                }
                _state.value = ScanFeedState.SCANNING
                pendingKey = track.id
                // Urgent: this is the track playing right now, not a sweep.
                scans.request(track, urgent = true)
            }
    }

    /** Adopt an analysis the moment the repository finishes one we asked for. */
    private suspend fun watchScans() {
        scans.completed.collect { key ->
            if (key != pendingKey) return@collect
            val now = nowPlaying.now.value ?: return@collect
            val track = resolveTrack(now) ?: return@collect
            scans.cached(track)?.let { adopt(it) }
        }
    }

    private fun adopt(next: TrackScan) {
        pendingKey = null
        // A grid the scanner itself does not trust is worse than none: scheduling
        // against a wrong beat is visibly, rhythmically wrong in a way that an idle
        // glow is not. Say so instead.
        if (!next.gridUsable) {
            scan = null
            _driving.value = false
            _state.value = ScanFeedState.NO_GRID
            return
        }
        scan = next
        synth.reset()
        slew.clear()
        _state.value = ScanFeedState.DRIVING
        _driving.value = true
    }

    /**
     * Find something analysable for a track named only by metadata.
     *
     * A downloaded copy first — it is a file on this phone, so the analysis costs
     * nothing but CPU. Failing that, the configured self-hosted library, whose
     * `downloadUrl` the scanner can fetch. Failing that, nothing: MA's own stream is
     * pointed at another speaker and there is no second copy to ask for.
     */
    private suspend fun resolveTrack(now: MaNowPlaying.Now): LocalTrack? {
        downloads.downloads.value.firstOrNull {
            RemoteTrackIdentity.sameTrack(
                now.title, now.artist, now.durationMs,
                it.title, it.artist, it.durationMs,
            )
        }?.let { return it.toLocalTrack() }

        val source = musicSource() ?: return null
        val hits = runCatching { source.search("${now.title} ${now.artist}", limit = 10) }
            .getOrNull()?.tracks
            ?: return null
        val hit = hits.firstOrNull {
            RemoteTrackIdentity.sameTrack(
                now.title, now.artist, now.durationMs,
                it.name, it.subtitle, (it.duration ?: 0).toLong() * 1000L,
            )
        } ?: return null
        return LocalTrack(
            id = hit.itemId,
            title = hit.name,
            artist = hit.subtitle,
            album = hit.album,
            durationMs = (hit.duration ?: 0).toLong() * 1000L,
            artUrl = now.artworkUrl,
            streamUrl = source.downloadUrl(hit.itemId),
            localPath = null,
        )
    }

    /**
     * Produce frames at the analysis hop rate.
     *
     * ~50 Hz, matching what the live tap publishes, so the engine's per-frame
     * assumptions and `renderLoop`'s 60 Hz oversampling both behave exactly as they do
     * for real audio. Anything slower and the beat lands late; anything faster is work
     * for nothing.
     */
    private suspend fun tick() {
        var last = System.nanoTime()
        while (scope.isActive) {
            delay(TICK_MS)
            val now = System.nanoTime()
            val dt = ((now - last) / 1e9f).coerceIn(0f, MAX_STEP_S)
            last = now

            val current = scan ?: continue
            if (!_driving.value) continue

            // The server's word, plus the user's own offset for the speaker's latency.
            // Positive delays the lights, matching the offset control everywhere else.
            val anchorS = (nowPlaying.positionMs.value - offsetMs) / 1000f
            slew.anchor(anchorS)
            if (slew.lastWasSnap) synth.reset()
            val pos = slew.advance(dt)

            val out = synth.step(current, pos, dt)
            sink(out.frame, out.grid, current, pos)
        }
    }

    private companion object {
        /** ~20 ms, the analysis hop the live path publishes at. */
        const val TICK_MS = 20L

        /** A dropped tick past this is a stall, not a step; the grid re-anchors. */
        const val MAX_STEP_S = 0.25f
    }
}
