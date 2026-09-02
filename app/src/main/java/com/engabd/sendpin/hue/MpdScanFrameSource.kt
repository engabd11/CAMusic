package com.engabd.sendpin.hue

import com.engabd.sendpin.audio.AnalysisFrame
import com.engabd.sendpin.audio.BeatGrid
import com.engabd.sendpin.audio.LocalPlayer
import com.engabd.sendpin.audio.LocalTrack
import com.engabd.sendpin.audio.ScanFrameSynth
import com.engabd.sendpin.audio.TrackScan
import com.engabd.sendpin.audio.TrackScanRepository
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.download.DownloadManager
import com.engabd.sendpin.library.MusicSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The light show for a library playing itself, with this phone as its remote — MPD.
 *
 * ## The problem
 *
 * [LocalPlayer.remote] being set (see [LocalPlayer.remoteActive]) means MPD is
 * decoding to its own output, usually a DAC on a different box entirely — nothing
 * reaches [LocalPlayer]'s own ExoPlayer, so [com.engabd.sendpin.audio.AudioAnalysisTap]
 * sits wired into a chain that is never fed. This is the exact shape of the problem
 * [ScanFrameSource] already solved for a Music Assistant queue playing on a remote
 * speaker — see that class's doc for the reasoning this mirrors line for line.
 *
 * ## What replaces it
 *
 * The same thing: read [TrackScan] against the interpolated playhead and synthesise
 * frames from it, through the exact same [ScanFrameSynth]. The one real difference
 * from [ScanFrameSource] is which half of "find the track" is hard. MA only ever
 * hands over metadata — title, artist, a duration — so [ScanFrameSource] has to
 * resolve *identity* before it can resolve bytes to analyse. [LocalPlayer.current]
 * already **is** the exact track MPD is playing; there is no identity question here
 * at all. What is still missing is bytes: MPD serves audio only to its own configured
 * output, never to this phone (`MpdSource` offers no download or stream URL), so
 * [resolveScannable] does the same download-then-library search
 * [ScanFrameSource.resolveTrack] does, just starting from an exact track instead of
 * bare metadata.
 *
 * ## What it costs
 *
 * MPD's own transport is already smoothly interpolated for the scrub bar (see
 * [LocalPlayer.positionMs]'s 250 ms tick between 1 Hz polls), so unlike MA's bursty,
 * seconds-apart playhead corrections, there is little for [PositionSlew] to smooth
 * here — it is kept anyway, for the same DAC-latency reason [ScanFrameSource] keeps
 * it: [AppSettings.lightSyncSpeakerOffsetMs] is unknowable from here whichever remote
 * player supplied the position.
 */
class MpdScanFrameSource(
    private val scans: TrackScanRepository,
    private val local: LocalPlayer,
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

    /** Same contract as [ScanFrameSource.driving] — read by the feed picker and folded into `isPlaying`. */
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

    private data class Snap(val remote: Boolean, val track: LocalTrack?, val playing: Boolean)

    /**
     * Follow MPD's track, adopting or requesting an analysis for each one.
     *
     * Keyed on identity, same as [ScanFrameSource.watchTrack] and for the same
     * reason: [LocalPlayer.positionMs] ticks every 250 ms and would re-trigger this
     * on every tick if it were part of the key.
     */
    private suspend fun watchTrack() {
        combine(local.remoteActive, local.current, local.playing) { remote, track, playing ->
            Snap(remote, track, playing)
        }
            .distinctUntilChanged { a, b ->
                a.remote == b.remote && a.playing == b.playing &&
                    RemoteTrackIdentity.sameTrack(
                        a.track?.title, a.track?.artist, a.track?.durationMs ?: 0L,
                        b.track?.title, b.track?.artist, b.track?.durationMs ?: 0L,
                    )
            }
            .collect { snap ->
                // Only MPD - a genuinely local track has real PCM and a far better
                // show behind it already.
                if (!snap.remote || snap.track == null || !snap.playing) {
                    reset()
                    return@collect
                }
                scan = null
                synth.reset()
                slew.clear()
                _driving.value = false

                val analysable = resolveScannable(snap.track)
                if (analysable == null) {
                    _state.value = ScanFeedState.NO_MATCH
                    pendingKey = null
                    return@collect
                }
                val cached = scans.cached(analysable)
                if (cached != null) {
                    adopt(cached)
                    return@collect
                }
                _state.value = ScanFeedState.SCANNING
                pendingKey = analysable.id
                // Urgent: this is the track playing right now, not a sweep.
                scans.request(analysable, urgent = true)
            }
    }

    /** Adopt an analysis the moment the repository finishes one we asked for. */
    private suspend fun watchScans() {
        scans.completed.collect { key ->
            if (key != pendingKey) return@collect
            val track = local.current.value ?: return@collect
            val analysable = resolveScannable(track) ?: return@collect
            scans.cached(analysable)?.let { adopt(it) }
        }
    }

    private fun adopt(next: TrackScan) {
        pendingKey = null
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
     * Find something analysable for the track MPD is playing.
     *
     * [track] already *is* the exact track - no metadata matching needed to know
     * what is playing, only to find a copy of it MPD did not hand over. A downloaded
     * copy first, then the configured self-hosted library, both matched by identity
     * - the same two steps [ScanFrameSource.resolveTrack] takes from bare metadata.
     * If [track] already carries something analysable (a library that does offer
     * MPD a download URL after all), that is used directly.
     */
    private suspend fun resolveScannable(track: LocalTrack): LocalTrack? {
        if (!track.streamUrl.isNullOrBlank() || !track.localPath.isNullOrBlank()) return track

        downloads.downloads.value.firstOrNull {
            RemoteTrackIdentity.sameTrack(
                track.title, track.artist, track.durationMs,
                it.title, it.artist, it.durationMs,
            )
        }?.let { return it.toLocalTrack() }

        val source = musicSource() ?: return null
        val hits = runCatching { source.search("${track.title} ${track.artist}", limit = 10) }
            .getOrNull()?.tracks
            ?: return null
        val hit = hits.firstOrNull {
            RemoteTrackIdentity.sameTrack(
                track.title, track.artist, track.durationMs,
                it.name, it.subtitle, (it.duration ?: 0).toLong() * 1000L,
            )
        } ?: return null
        return LocalTrack(
            id = hit.itemId,
            title = hit.name,
            artist = hit.subtitle,
            album = hit.album,
            durationMs = (hit.duration ?: 0).toLong() * 1000L,
            artUrl = track.artUrl,
            streamUrl = source.downloadUrl(hit.itemId),
            localPath = null,
        )
    }

    /** Produce frames at the analysis hop rate — see [ScanFrameSource.tick] for why 20 ms. */
    private suspend fun tick() {
        var last = System.nanoTime()
        while (scope.isActive) {
            delay(TICK_MS)
            val now = System.nanoTime()
            val dt = ((now - last) / 1e9f).coerceIn(0f, MAX_STEP_S)
            last = now

            val current = scan ?: continue
            if (!_driving.value) continue

            val anchorS = (local.positionMs.value - offsetMs) / 1000f
            slew.anchor(anchorS)
            if (slew.lastWasSnap) synth.reset()
            val pos = slew.advance(dt)

            val out = synth.step(current, pos, dt)
            sink(out.frame, out.grid, current, pos)
        }
    }

    private companion object {
        const val TICK_MS = 20L
        const val MAX_STEP_S = 0.25f
    }
}
