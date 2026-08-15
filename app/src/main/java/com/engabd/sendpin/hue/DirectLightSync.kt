package com.engabd.sendpin.hue

import android.content.Context
import android.util.Log
import com.engabd.sendpin.audio.ANALYSIS_HOP
import com.engabd.sendpin.audio.ANALYSIS_SAMPLE_RATE
import com.engabd.sendpin.audio.AnalysisFrame
import com.engabd.sendpin.audio.AudioAnalysisTap
import com.engabd.sendpin.audio.AudioLead
import com.engabd.sendpin.audio.BeatGrid
import com.engabd.sendpin.audio.LocalTrack
import com.engabd.sendpin.audio.SongPhase
import com.engabd.sendpin.audio.StructureState
import com.engabd.sendpin.audio.StructureTracker
import com.engabd.sendpin.audio.TempoTracker
import com.engabd.sendpin.audio.TrackScan
import com.engabd.sendpin.audio.TrackScanRepository
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.data.Crypto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Orchestrates the direct Hue Bridge Light Sync connection.
 *
 * Lifecycle:
 * 1. [start] — fetch entertainment configs from the bridge, start the stream
 *    (PUT action:start), open the DTLS channel, create the [SyncoEngine].
 * 2. The [AudioAnalysisTap] feeds [AnalysisFrame]s at ~50 Hz. Each one advances
 *    the rhythm and structure models in [onAnalysisFrame] and is published for
 *    the render loop; nothing is rendered or sent there.
 * 3. [renderLoop] runs at 60 Hz on its own coroutine, renders the latest frame,
 *    holds it in [delayQueue] until the audio it describes is audible, then puts
 *    it through the safety limiters and the encoder onto the wire.
 * 4. [stop] — stop the stream (PUT action:stop), close the DTLS channel,
 *    release the engine.
 *
 * Three threads, and the split matters. The tap's analysis thread owns the
 * analyzer and the two trackers. This orchestrator's IO scope owns the session.
 * The render loop is what talks to the bridge — deliberately not the analysis
 * thread, which delivers hops in bursts as the decoder hands over buffers and
 * would send the bridge clusters of frames instead of an even stream.
 *
 * Keepalive: the bridge drops the entertainment session after ~10 s of silence,
 * so a loop resends the last frame every 9 s when the render loop has gone
 * quiet, and watches for bridge-side alerts.
 *
 * Failure handling distinguishes two cases. A network fault is retried with
 * backoff, keeping the engine so the show resumes rather than restarts. A
 * *bridge-initiated* teardown is never retried — that is the Hue app taking the
 * area, and grabbing it back is what makes its stop button look broken.
 */

private const val TAG = "DirectLightSync"

/**
 * The bridge closes an entertainment session after 10 s of silence, so the
 * keepalive has to beat that. Matches syncoV2's `KEEPALIVE_INTERVAL`.
 */
private const val KEEPALIVE_INTERVAL_MS = 9000L

/** The wire value that means "let the picker choose". */
const val INTENSITY_AUTO = "auto"

/** Minimum gap between repeats of a per-frame warning. See `logThrottled`. */
private const val LOG_THROTTLE_MS = 5000L

/**
 * Consecutive failed sends before the session is rebuilt. Half a second at
 * 60 Hz — long enough that a single dropped datagram is ignored, short enough
 * that a real Wi-Fi drop is noticed before the bridge times the area out.
 */
private const val SEND_FAILURES_BEFORE_RECONNECT = 30

/**
 * Reconnect backoff. Matches syncoV2's window: doubling from a second to ten,
 * over fourteen attempts, is a bit under two minutes of trying — long enough to
 * ride out a Wi-Fi roam or a router reboot without retrying forever.
 */
private const val RECONNECT_ATTEMPTS = 14
private const val RECONNECT_BASE_MS = 1_000L
private const val RECONNECT_MAX_MS = 10_000L

/**
 * Longest run of skipped identical frames before one is resent anyway.
 *
 * The Entertainment API asks for a continuous stream because UDP frames are
 * dropped without retry. Skipping a frame the bulbs already show is still safe —
 * a lost duplicate changes nothing — but the run has to be bounded so a packet
 * lost at the moment the room *does* change cannot leave it stale until the 9 s
 * keepalive. syncoV2 leans on its keepalive for this; a quarter second is a much
 * tighter floor for the same saving.
 */
private const val RESEND_INTERVAL_NANOS = 250_000_000L

/**
 * Below this, a colour change cannot be displayed. The lamps carry 12-bit xy and
 * 11-bit brightness, so 1/4096 is the finest step that can reach a bulb.
 */
private const val COLOUR_QUANT = 1f / 4096f

/**
 * Entertainment stream rate. The Hue Entertainment API is streamed at 50–60 Hz;
 * syncoV2 uses 60 (`const.DEFAULT_STREAM_FPS`) and so does this. Deliberately
 * higher than the ~50 Hz analysis rate — see [DirectLightSync.renderLoop].
 */
private const val STREAM_FPS = 60
private const val FRAME_PERIOD_NANOS = 1_000_000_000L / STREAM_FPS

/** Longest step the engine may be advanced by after a stall, in seconds. */
private const val MAX_STEP_S = 0.1f

/** How long without an analysis frame before the room is treated as silent. */
private const val FRAME_STALE_NANOS = 250_000_000L

/** How long the player must be paused/stopped before the idle ambient show fades in. */
private const val IDLE_FADE_IN_DELAY_S = 5f

/** How long the idle ambient movement takes to fully fade in. */
private const val IDLE_FADE_IN_DURATION_S = 4f

/** What the engine renders when nothing is feeding it. */
private val SILENCE = AnalysisFrame()

/**
 * How far into a track a scan may arrive and still be adopted.
 *
 * One regime per track, decided once. A grid that appears mid-song replaces a
 * PLL that has by then found the beat itself, and the handover between two
 * clocks that agree on tempo but not quite on phase is a visible stumble — the
 * room skips. Better to stay causal for the rest of this play and be exact from
 * the first bar of the next one. syncoV2 draws the line in the same place
 * (`coordinator._MAP_COMMIT_WINDOW_S`) for the same reason.
 */
private const val MAP_COMMIT_WINDOW_S = 6f

/**
 * How far ahead a louder section counts as an approaching drop. Matches
 * syncoV2's `_PREDROP_WINDOW_S`.
 */
private const val PREDROP_WINDOW_S = 2f

/** How much louder the next section must be to read as a drop rather than a change. */
private const val DROP_LEVEL_STEP = 0.15f

/**
 * Whichever backend is actually producing sound right now, and what
 * [DirectLightSync] needs from it.
 *
 * Exactly one backend plays at a time (local or MA — see
 * `com.engabd.sendpin.service.Playback`'s `setBackend`-style exclusivity), and
 * [SendpinApp] is what decides which one this describes moment to moment.
 *
 * @param tap The tap actually installed in that backend's render chain — it
 *   must be *the same instance* the backend handed to its `TapRenderersFactory`
 *   (or, for MA via [com.engabd.sendpin.audio.SendspinExoEngine], its Oboe
 *   sink), not a second one. Activating a tap the audio never flows through
 *   yields a connected bridge and lights that never move. A fresh instance
 *   every time the MA engine reconnects — see [DirectLightSync]'s rewiring.
 * @param lead How far the tap runs ahead of the speaker for *this* backend.
 * @param artUrl The cover art of whatever this backend is currently playing.
 * @param scanTrack The local library identity of what's playing, for offline
 *   scan lookup — null for MA, which has no stable per-track id in its
 *   protocol to key a scan by. Null is exactly the existing "not scanned yet"
 *   path [DirectLightSync] already degrades to gracefully.
 */
data class ActiveLightSyncSource(
    val tap: AudioAnalysisTap,
    val lead: AudioLead,
    val artUrl: String?,
    val scanTrack: LocalTrack?,
)

class DirectLightSync(
    private val context: Context,
    /**
     * Whichever backend is currently playing, and what this needs from it. A
     * [StateFlow] specifically (not a plain [Flow]): its `.value` must be
     * synchronously available the moment [start] runs, with no "hasn't emitted
     * yet" race - see [SendpinApp.activeLightSyncSource]'s construction.
     */
    private val activeSource: StateFlow<ActiveLightSyncSource>,
    /**
     * Offline track analyses. Optional: with no scans the direct path behaves
     * exactly as it did before they existed — causal grid, live-estimated
     * character, no section knowledge — which is also what happens for any
     * individual track that has not been scanned yet (and always, for MA -
     * see [ActiveLightSyncSource.scanTrack]).
     */
    private val scans: TrackScanRepository? = null,
    /**
     * Whether whichever backend is currently active is playing audio. Used to
     * decide when the room has been idle long enough to switch from the
     * music-reactive show to the gentle ambient idle glow.
     */
    private val isPlaying: Flow<Boolean> = flowOf(false),
    private val settings: AppSettings = AppSettings(context),
) {
    /**
     * The tap [onFrame]/[onAnalysisReset] are currently hooked to, or null when
     * no session is running. Tracked separately from `activeSource.value.tap`
     * because the two can legitimately disagree for an instant — the source can
     * change (a backend switch, or MA reconnecting to a fresh engine) before
     * [rewireTap] has run - and because [onAnalysisFrame] (line ~522) needs
     * "the tap that is actually calling back right now", not "whatever the
     * source flow says right now".
     */
    @Volatile private var wiredTap: AudioAnalysisTap? = null

    /**
     * Point [onFrame]/[onAnalysisReset] at [source]'s tap, unhooking whichever
     * tap they previously pointed to. A no-op if it's already the right one -
     * every emission of [activeSource] runs through here, not just changes.
     */
    private fun rewireTap(source: ActiveLightSyncSource) {
        val old = wiredTap
        if (old === source.tap) return
        old?.let { it.setActive(false); it.onFrame = null; it.onAnalysisReset = null }
        source.tap.onFrame = ::onAnalysisFrame
        source.tap.onAnalysisReset = ::onAnalysisReset
        source.tap.setActive(true)
        wiredTap = source.tap
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The paired bridge's id, mirrored off settings so the TLS hostname verifier
     * can read it synchronously during a handshake.
     */
    @Volatile private var pairedBridgeId: String = ""

    /** The bridge client — mDNS discovery, CLIP v2 API. */
    val bridgeClient = HueBridgeClient(context, expectedBridgeId = { pairedBridgeId })

    // Written on the orchestrator scope, read on the ExoPlayer audio thread and
    // by the settings collectors — volatile so a start/stop is seen promptly and
    // a half-built session is never observed.

    /** The DTLS client — opened on start, closed on stop. */
    @Volatile private var dtls: DtlsPskClient? = null

    /** The stream encoder — one per entertainment area. */
    @Volatile private var encoder: HueStreamEncoder? = null

    /** The effects engine. */
    @Volatile private var engine: SyncoEngine? = null

    /** Entertainment area channels. */
    private var channels: List<EntertainmentChannel> = emptyList()

    /** Last rendered frame, for keepalive resends. */
    @Volatile private var lastFrame: ByteArray? = null

    /** Latest analysis frame from the audio thread, and when it landed. */
    @Volatile private var latestFrame: AnalysisFrame? = null
    @Volatile private var latestFrameAt = 0L

    /** Playback state from this phone's player. */
    @Volatile private var playerPlaying = false
    /** When the player last stopped being truly playing, in engine time seconds. */
    @Volatile private var idleStartS = -1f
    /** How long the room has been idle for ambient fade calculations. */
    @Volatile private var idleT = 0f

    /**
     * Rhythm and structure models, advanced once per analysis hop in
     * [onAnalysisFrame]. Both are stateful and single-threaded — only the
     * analysis thread touches them — and their outputs are published as
     * immutable snapshots for the render loop to read.
     */
    private var tempo: TempoTracker? = null
    private var structure: StructureTracker? = null

    @Volatile private var latestGrid: BeatGrid? = null
    @Volatile private var latestStructure: StructureState? = null

    // ── Track scan state ───────────────────────────────────────────────────
    //
    // Written by the orchestrator scope as the track changes, read by the
    // analysis thread once a hop. The analysis thread is the only place the
    // scan is *applied*, because that is where the track position and the two
    // trackers already are.
    //
    /** The scan for the track playing now, once one is available. */
    @Volatile private var activeScan: TrackScan? = null

    /**
     * Whether this track's show is scheduled or causal. Null until decided; see
     * [MAP_COMMIT_WINDOW_S] for why it is decided exactly once.
     */
    @Volatile private var mapCommitted: Boolean? = null

    /**
     * Previous queried position, so a scheduled beat fires exactly once.
     *
     * Volatile because a track change clears it from the orchestrator scope
     * while the analysis thread is reading it — the value only ever goes stale
     * by a frame, but a stale *non-null* one is what makes a beat fire twice.
     */
    @Volatile private var mapPrevPos: Float? = null

    /** The section the playhead was in last frame, for scheduled-drop detection. */
    @Volatile private var mapSection: com.engabd.sendpin.audio.ScanSection? = null

    /**
     * The last album-art colours extracted, cached so they survive the race
     * between the `init` collector (which fires before [start] creates the
     * engine) and the engine becoming available. Applied in [start] if present.
     */
    @Volatile private var lastAlbumColours: AlbumColours? = null

    /** The art URL the collector last saw, for re-extraction on scheme change. */
    @Volatile private var lastArtUrl: String? = null

    /**
     * The scanned Auto-intensity parameters for this track, or null when it is
     * being played causally. The picker takes its live-estimated defaults then,
     * exactly as it did before scans existed.
     */
    @Volatile private var activeProfile: com.engabd.sendpin.audio.IntensityProfile? = null

    /** The lag-free intensity signal at the current playhead. */
    @Volatile private var scannedSignal: Float? = null

    /** When the last packet went out, so the keepalive knows if it is needed. */
    @Volatile private var lastSendAt = 0L

    /** Whether the stream is active. */
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    /** Error state, surfaced to the UI. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Current tunables applied to the engine. */
    @Volatile private var activeTunables: Map<String, Float> = emptyMap()

    private var keepaliveJob: Job? = null
    private var renderJob: Job? = null
    private val running = AtomicBoolean(false)


    /**
     * Start the direct Light Sync session.
     *
     * Reads bridge credentials from [AppSettings], fetches the entertainment
     * area, starts the stream on the bridge, opens the DTLS channel, and
     * activates the audio tap.
     *
     * Safe to call from any dispatcher: the whole sequence moves to [Dispatchers.IO],
     * because the DTLS handshake is a blocking UDP exchange with retransmit timeouts
     * and would hang the caller's thread.
     */
    suspend fun start() = withContext(Dispatchers.IO) {
        if (running.get()) return@withContext

        val host = settings.hueBridgeIp.first()
        val appKey = settings.hueAppKey.first()
        val clientKey = settings.hueClientKey.first()
        val appId = settings.hueAppId.first()
        val configId = settings.hueEntertainmentConfigId.first()

        if (host.isBlank() || appKey.isBlank() || clientKey.isBlank() || configId.isBlank()) {
            _error.value = "Bridge not configured. Set up a bridge in Settings first."
            return@withContext
        }

        try {
            // 1. Fetch the entertainment configuration (channels + positions).
            val configs = bridgeClient.getEntertainmentConfigs(host, appKey)
            val config = configs.firstOrNull { it.id == configId }
                ?: configs.firstOrNull()
                ?: throw DtlsException("No entertainment area found on the bridge")

            channels = config.channels

            // 2. Start the stream on the bridge (PUT action:start).
            bridgeClient.startStream(host, appKey, config.id)

            // 3. Open the DTLS channel.
            val psk = clientKey.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val identity = (appId.ifBlank { appKey }).toByteArray(Charsets.US_ASCII)
            val client = DtlsPskClient(host, 2100, identity, psk)
            client.connect()
            dtls = client

            // 4. Create the stream encoder + effects engine.
            delayQueue.resetDelay(activeSource.value.lead.leadMs)
            lastSent = null
            sendFailures = 0
            revoked = false
            picker.reset()
            limiterMode = null
            selectLimiter(SyncMode.fromWire(settings.lightSyncIntensity.first()))
            safety?.reset()
            // Per-channel gamuts where the bridge reported them; anything it
            // didn't answer for falls back to Gamut C inside the encoder.
            encoder = HueStreamEncoder(
                config.id,
                gamuts = channels.mapNotNull { ch -> ch.gamut?.let { ch.channelId to it } }.toMap(),
            )
            engine = SyncoEngine(channels, config.configurationType).also {
                it.mode = SyncMode.fromWire(settings.lightSyncIntensity.first())
                it.effect = SyncEffect.fromWire(settings.lightSyncEffect.first())
                it.setScheme(ColorScheme.fromWire(settings.lightSyncColor.first()))
                it.brightness = settings.lightSyncBrightness.first() / 100f
                it.setTunables(activeTunables)
                // Apply cached album colours if the collector already extracted
                // them before the engine existed. Without this the first track's
                // colours are silently lost — the collector fired from init, the
                // engine was null, and distinctUntilChanged won't re-emit.
                val ac = lastAlbumColours
                val scheme = ColorScheme.fromWire(settings.lightSyncColor.first())
                if (ac != null && (scheme == ColorScheme.ALBUM_ART || scheme == ColorScheme.ALBUM_ART_V2)) {
                    // v1 passes null weights (even interpolation); v2 passes real weights.
                    val w = if (scheme == ColorScheme.ALBUM_ART) null else ac.weights
                    it.setAlbumColors(ac.colors, w)
                }
            }

            // 5. Activate the audio tap.
            latestFrame = null
            latestFrameAt = 0L
            latestGrid = null
            latestStructure = null
            // The analyzer's frame period, not the render period: these step once
            // per hop, off the analyzer's own clock.
            val framePeriod = ANALYSIS_HOP.toFloat() / ANALYSIS_SAMPLE_RATE
            tempo = TempoTracker(framePeriod)
            structure = StructureTracker(framePeriod)
            rewireTap(activeSource.value)
            acquireLocks()

            running.set(true)
            _active.value = true
            _error.value = null

            // 6. Start the render/send loop and the keepalive.
            renderJob = scope.launch { renderLoop() }
            keepaliveJob = scope.launch { keepaliveLoop() }

            Log.i(TAG, "Direct Light Sync started: ${config.name} (${channels.size} channels)")
        } catch (e: Exception) {
            _error.value = e.message ?: "Failed to start Light Sync"
            Log.e(TAG, "start failed", e)
            cleanup()
        }
    }

    /**
     * Stop the direct Light Sync session and release the entertainment area.
     *
     * Suspends until the bridge has actually been told to stop. Two reasons it
     * cannot be fire-and-forget:
     *
     * - Its caller collects on the main dispatcher, and teardown does real
     *   network I/O — `close()` builds an AES-GCM `close_notify` record and puts
     *   it on the wire, and `stopStream` is an HTTPS PUT.
     * - An area change is a stop followed immediately by a start. When the stop's
     *   PUT was launched asynchronously it could land *after* the start's, and
     *   kill the session that had just been opened on the new area.
     */
    suspend fun stop() = withContext(Dispatchers.IO) {
        if (!running.getAndSet(false)) return@withContext
        cleanup()
    }

    private suspend fun cleanup() {
        wiredTap?.let { it.setActive(false); it.onFrame = null; it.onAnalysisReset = null }
        wiredTap = null
        tempo = null
        structure = null
        latestGrid = null
        latestStructure = null

        renderJob?.cancel(); renderJob = null
        keepaliveJob?.cancel(); keepaliveJob = null

        engine = null
        encoder = null
        delayQueue.clear()
        lastSent = null

        // Close DTLS (sends close_notify) so the bridge frees the session at
        // once; without it a restart inside the ~10 s linger is ignored.
        dtls?.close()
        dtls = null

        // Stop the stream on the bridge, inline so callers can sequence against it.
        try {
            val host = settings.hueBridgeIp.first()
            val appKey = settings.hueAppKey.first()
            val configId = settings.hueEntertainmentConfigId.first()
            if (host.isNotBlank() && appKey.isNotBlank() && configId.isNotBlank()) {
                bridgeClient.stopStream(host, appKey, configId)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop stream on bridge: ${e.message}")
        }

        releaseLocks()
        _active.value = false
        lastFrame = null
    }

    // ── Analysis frame callback ────────────────────────────────────────────

    /**
     * Called on the tap's analysis thread with a new analysis frame.
     *
     * The rhythm and structure models are advanced here rather than in
     * [renderLoop] because both are driven by the analyzer's own `tAudio` clock
     * and expect exactly one step per hop. Stepping them from the 60 Hz render
     * loop would advance them at the wrong rate and against a clock they do not
     * share.
     *
     * Rendering still does not happen here. Hops arrive in bursts — the decoder
     * hands over a buffer at a time — so rendering on arrival would send the
     * bridge clusters of frames instead of an even stream. [renderLoop] paces
     * the output.
     */
    private fun onAnalysisFrame(frame: AnalysisFrame) {
        // The causal tracker runs every frame whether or not a scan is adopted.
        // It is the floor: if the scan turns out not to cover this position, or
        // its grid was never good enough to trust, the room still keeps time.
        var grid = tempo?.update(
            tAudio = frame.tAudio,
            fluxValue = frame.flux,
            beat = frame.beat,
            beatStrength = frame.beatStrength,
            bass = max(frame.bands["sub_bass"] ?: 0f, frame.bands["bass"] ?: 0f),
        )
        var arc = structure?.update(frame)
        var published = frame

        val scan = activeScan
        // The tap that is actually calling back right now, not activeSource.value.tap:
        // the two can disagree for an instant across a backend switch or MA
        // reconnect, and this callback only ever fires from whichever tap is
        // actually wired (see rewireTap).
        val pos = wiredTap?.analysisPositionS ?: Float.NaN
        if (!pos.isNaN()) {
            // Decided once per track, and only inside the window. Before it, a
            // missing scan means "not arrived yet", not "there isn't one".
            if (mapCommitted == null) {
                if (scan != null && pos <= MAP_COMMIT_WINDOW_S) mapCommitted = true
                else if (pos > MAP_COMMIT_WINDOW_S) mapCommitted = false
            }
            if (mapCommitted == true && scan != null) {
                scan.gridAt(pos, mapPrevPos)?.let { grid = it }
                arc = arc?.let { enrichWithScan(it, scan, pos) }
                scannedSignal = scan.intensitySignalAt(pos)
                // The one absolute measure a per-bin AGC cannot leave behind.
                // Extreme's per-lamp brightness reads it; every other rung
                // ignores it, so attaching it unconditionally costs nothing.
                if (scan.melbankRef.isNotEmpty()) {
                    published = frame.copy(melbankRef = scan.melbankRef)
                }
                mapPrevPos = pos
            }
        }

        latestGrid = grid
        latestStructure = arc
        latestFrame = published
        latestFrameAt = System.nanoTime()
    }

    /**
     * Fill in the two things a causal structure tracker cannot know: which
     * section this is, and when the next one lands.
     *
     * The live tracker guesses a drop from "a build that has nearly maxed out",
     * which is right often enough to be worth having and never early enough to
     * anticipate. A scanned section list turns that into a countdown, so the
     * pull-down before a drop can be timed against the boundary instead of
     * reacting once it has already happened.
     */
    private fun enrichWithScan(state: StructureState, scan: TrackScan, pos: Float): StructureState {
        val section = scan.sectionAt(pos) ?: return state
        val previous = mapSection
        mapSection = section

        var dropImminent = state.dropImminent
        var dropEtaS = state.dropEtaS
        val boundary = scan.nextBoundary(pos)
        if (boundary != null &&
            boundary.first <= PREDROP_WINDOW_S &&
            boundary.second > section.energy + DROP_LEVEL_STEP
        ) {
            dropImminent = true
            dropEtaS = boundary.first
        }
        // Crossing into a clearly louder section *is* the drop, known exactly
        // rather than inferred from a surge that has already been heard.
        val dropNow = state.dropNow ||
            (previous != null && previous !== section && section.energy > previous.energy + DROP_LEVEL_STEP)

        return state.copy(
            phase = if (dropNow) SongPhase.DROP else state.phase,
            dropNow = dropNow,
            dropImminent = dropImminent,
            dropEtaS = dropEtaS,
            sectionLevel = section.energy,
        )
    }

    /**
     * Drop rhythm and structure history when the analyzer does.
     *
     * A seek or a track change invalidates both: a beat grid locked to the
     * previous song would keep predicting beats against audio that no longer
     * matches it, and the structure arc would carry the old track's build into
     * the new one. Runs on the analysis thread, before the first frame of the
     * new audio.
     */
    private fun onAnalysisReset() {
        tempo?.reset()
        structure?.reset()
        latestGrid = null
        latestStructure = null
        // The scan itself survives a seek — it describes the whole track, and
        // seeking does not make it less true. Only the per-query state goes.
        mapPrevPos = null
        mapSection = null
    }

    // ── Render loop ─────────────────────────────────────────────────────────

    /**
     * Render and send at [STREAM_FPS], independent of the analysis rate.
     *
     * This is the rate the Entertainment API is streamed at, which syncoV2 puts
     * at 60 Hz against a ~50 Hz analysis rate — so some analysis frames are
     * rendered twice, which is the point: the engine's continuous layers (colour
     * drift, envelopes, melbank) advance on real elapsed time and come out
     * smoother than the frames driving them. The bridge relays to bulbs at ~25 Hz
     * over Zigbee, so this is not about the visible update rate; it is about the
     * temporal resolution of what gets sampled down to it.
     *
     * [dt] is measured, not assumed. The engine integrates everything against it,
     * so a dropped or late tick has to shorten or lengthen the step rather than
     * silently bend engine time away from the clock. It is clamped so that a stall
     * (a GC pause, a thread starved by the decoder) resumes rather than jumping
     * the animation forward by however long it was gone.
     */
    private suspend fun renderLoop() {
        var last = System.nanoTime()
        var next = last
        render@ while (running.get()) {
            next += FRAME_PERIOD_NANOS
            val sleep = (next - System.nanoTime()) / 1_000_000L
            if (sleep > 0) kotlinx.coroutines.delay(sleep)
            // A long stall would otherwise leave the loop sprinting to catch up
            // on a backlog of deadlines nobody is waiting for.
            if (System.nanoTime() - next > FRAME_PERIOD_NANOS * 4) next = System.nanoTime()
            if (!running.get()) break

            val now = System.nanoTime()
            val dt = ((now - last) / 1e9f).coerceIn(0f, MAX_STEP_S)
            last = now

            val eng = engine ?: continue
            val enc = encoder ?: continue
            val client = dtls ?: continue

            // A tap that has gone quiet means paused, seeking, or a gap between
            // tracks. A player that is not playing at all means the same thing.
            // In either case we switch to the slow ambient idle show so the room
            // stays visible without brightening it.
            val fresh = (now - latestFrameAt) < FRAME_STALE_NANOS
            val frame = if (fresh) latestFrame ?: SILENCE else SILENCE
            val isIdle = !playerPlaying || !fresh

            val colours = try {
                if (isIdle) {
                    updateIdle(dt)
                    eng.renderIdleShow(idleT, idleIntensity())
                } else {
                    idleStartS = -1f
                    idleT = 0f
                    if (autoIntensity) applyAutoIntensity(eng, frame, dt)
                    eng.render(frame, dt, latestGrid, latestStructure)
                }
            } catch (e: Exception) {
                logThrottled("Render failed: ${e.message}")
                continue
            }

            // Hold the rendered frame until the audio it describes is actually
            // audible. See FrameDelayQueue: the tap runs ahead of the speaker, so
            // without this the lights lead the music rather than trail it.
            delayQueue.updateDelay(activeSource.value.lead.leadMs, dt)
            delayQueue.offer(colours, now)
            val held = delayQueue.poll(now) ?: continue

            // Safety runs on what is about to be sent, at the moment it is sent —
            // its flash budget is measured in wall-clock seconds, so it has to
            // sit after the delay queue rather than before it.
            val guarded = safety?.process(held, dt) ?: held
            val due = rateLimiter.process(guarded, dt)

            // The bridge relays at 25 Hz over Zigbee and the spec asks for a
            // continuous stream because UDP frames are dropped without retry. A
            // frame the bulbs are already showing is safe to skip — losing a
            // duplicate changes nothing — but only for a bounded run, so a lost
            // packet can never leave the room stale until the 9 s keepalive.
            if (isUnchanged(due) && (now - lastSendAt) < RESEND_INTERVAL_NANOS) continue

            val packets = try {
                enc.buildPackets(due)
            } catch (e: Exception) {
                logThrottled("Encode failed: ${e.message}")
                continue
            }

            for (packet in packets) {
                try {
                    client.send(packet)
                    lastFrame = packet
                    lastSendAt = now
                } catch (e: DtlsPeerClosed) {
                    // Bridge-initiated teardown: the Hue app took the area, or
                    // the user pressed stop there. Deliberately never retried —
                    // reconnecting here is what makes that stop button look
                    // broken, because the app immediately takes the area back.
                    Log.w(TAG, "Bridge revoked the stream: ${e.message}")
                    revoked = true
                    running.set(false)
                    _error.value = "The bridge revoked the stream (another app may have taken over)"
                    scope.launch { cleanup() }
                    return
                } catch (e: Exception) {
                    // A network fault, by elimination. Wi-Fi drops and roams are
                    // ordinary events on a phone, and until now any of them
                    // ended the session for good.
                    logThrottled("DTLS send failed: ${e.message}")
                    if (++sendFailures >= SEND_FAILURES_BEFORE_RECONNECT) {
                        if (!reconnect()) return
                        continue@render
                    }
                }
                sendFailures = 0
            }

            // Recorded only once the frame has been through the socket. Setting
            // it before the send would let a failed write count as displayed, and
            // the next identical frame would then be skipped as a duplicate of
            // something the bulbs never received.
            lastSent = due
        }
    }

    /**
     * Decode the cover at [url] and hand its colours, with their dwell weights,
     * to the engine.
     *
     * Uses syncoV2's own extraction, not the app's UI palette. The UI one ranks
     * candidates by `chroma * sqrt(population)` so a small vivid splash outranks
     * a large flat field, and then lifts lightness and saturation for legibility
     * against black. Both are right for picking a UI accent and wrong for
     * lighting a room — they surface colours the sleeve barely contains, which
     * is what "the album colours are wrong" was.
     *
     * Two extraction modes, matching syncoV2:
     * - `album_art` (even): v1 — accent/base separation, uniform palette.
     * - `album_art_v2` (weighted): v2 — population-weighted, dwell-time faithful.
     *
     * The extracted colours are cached in [lastAlbumColours] so they survive the
     * race between this collector (which fires from `init`) and [start] (which
     * creates the engine). Without the cache, the first extraction lands while
     * the engine is still null and is silently lost; `distinctUntilChanged` then
     * suppresses the re-emit, and the room stays on the Sunset fallback.
     */
    private suspend fun applyAlbumArt(url: String?) = withContext(Dispatchers.IO) {
        if (url.isNullOrBlank()) {
            lastAlbumColours = null
            engine?.setAlbumColors(emptyList())
            return@withContext
        }
        try {
            val request = ImageRequest.Builder(context)
                .data(url)
                .allowHardware(false)  // getPixels needs a software bitmap
                .build()
            val result = context.imageLoader.execute(request)
            val bitmap = (result as? SuccessResult)?.drawable?.toBitmap() ?: return@withContext
            val scheme = ColorScheme.fromWire(settings.lightSyncColor.first())
            val extracted = when (scheme) {
                ColorScheme.ALBUM_ART -> extractAlbumColoursV1(bitmap)
                ColorScheme.ALBUM_ART_V2 -> extractAlbumColours(bitmap)
                else -> extractAlbumColours(bitmap)  // SONG and statics don't use album colours
            } ?: return@withContext
            lastAlbumColours = extracted
            // v1 (album_art/even) has no weights — pass null so the engine uses
            // pure even interpolation (Palette.evenSample), matching syncoV2's
            // Palette(colors, weights=None). v2 passes the real population weights
            // for dwell-time-faithful hold-and-crossfade sampling.
            val paletteWeights = if (scheme == ColorScheme.ALBUM_ART) null else extracted.weights
            engine?.setAlbumColors(extracted.colors, paletteWeights)
        } catch (e: Exception) {
            // A cover that will not load is not a reason to stop the show; the
            // engine keeps whatever palette it already had.
            Log.w(TAG, "Album art palette failed: ${e.message}")
        }
    }

    /**
     * Let the picker choose the rung for this frame.
     *
     * Run here rather than on the analysis thread because it is a rendering
     * decision measured in render frames, and because switching the rung also
     * has to swap the safety limiter — Intense and Extreme relax or bypass it,
     * so the two must move together or a rung change could leave the wrong
     * limiter in place.
     */
    private fun applyAutoIntensity(eng: SyncoEngine, frame: AnalysisFrame, dt: Float) {
        val grid = latestGrid
        // The five parameters the picker has always accepted and nothing has
        // ever supplied. With them it knows, from the first bar, how hard this
        // song goes on an absolute scale, how much of its own range it uses, and
        // where in that range this moment sits — instead of spending twenty
        // seconds working the first out and never learning the rest.
        val profile = activeProfile
        val picked = picker.update(
            dt = dt,
            energy = frame.energy,
            salience = frame.salience,
            bpm = if (grid?.locked == true) grid.bpm else 0f,
            beat = frame.beat,
            allowed = autoLevels,
            onsetWidth = frame.onsetWidth,
            centroid = frame.centroid,
            flux = frame.flux,
            signal = scannedSignal,
            character = profile?.character,
            lo = profile?.sigLo ?: SIG_LO_REF,
            hi = profile?.sigHi ?: SIG_HI_REF,
            dynamics = profile?.dynamics,
            mood = profile?.mood ?: 0f,
        )
        if (picked != eng.mode) {
            eng.mode = picked
            selectLimiter(picked)
        }
    }

    /**
     * Update idle-time bookkeeping for the ambient show.
     *
     * The first moment of idle is recorded so we can wait [IDLE_FADE_IN_DELAY_S]
     * before the wandering movement starts, and [idleT] accumulates elapsed
     * seconds for the renderIdleShow phase parameter.
     */
    private fun updateIdle(dt: Float) {
        if (idleStartS < 0f) idleStartS = 0f
        idleStartS += dt
        idleT += dt
    }

    /** Movement intensity for the idle show, 0 until the delay then fading to 1. */
    private fun idleIntensity(): Float {
        if (idleStartS < 0f) return 0f
        return ((idleStartS - IDLE_FADE_IN_DELAY_S) / IDLE_FADE_IN_DURATION_S).coerceIn(0f, 1f)
    }

    /**
     * Rebuild the bridge session after a network fault, keeping the show going.
     *
     * Only reached from repeated send failures — a bridge-initiated revocation
     * sets [revoked] and never comes here, because retrying that is what makes
     * the Hue app's stop button appear not to work.
     *
     * The engine is deliberately kept across the reconnect: it holds the
     * envelopes, the colour phase and the role assignment, so reusing it means
     * the room picks up where it left off rather than restarting the show. The
     * encoder is rebuilt, since its sequence numbers belong to the old session.
     *
     * Returns false when the attempts are exhausted or the session was stopped
     * meanwhile, in which case the caller should give up.
     */
    private suspend fun reconnect(): Boolean {
        var delayMs = RECONNECT_BASE_MS
        for (attempt in 1..RECONNECT_ATTEMPTS) {
            if (!running.get() || revoked) return false
            Log.i(TAG, "Reconnecting to the bridge (attempt $attempt of $RECONNECT_ATTEMPTS)")
            _error.value = "Reconnecting to the bridge…"
            try {
                dtls?.close()
            } catch (e: Exception) {
                // The old socket is already gone; that is why we are here.
            }
            dtls = null

            kotlinx.coroutines.delay(delayMs)
            delayMs = min(RECONNECT_MAX_MS, delayMs * 2)
            if (!running.get() || revoked) return false

            try {
                val host = settings.hueBridgeIp.first()
                val appKey = settings.hueAppKey.first()
                val clientKey = settings.hueClientKey.first()
                val appId = settings.hueAppId.first()
                val configId = settings.hueEntertainmentConfigId.first()
                if (host.isBlank() || appKey.isBlank() || clientKey.isBlank() || configId.isBlank()) return false

                bridgeClient.startStream(host, appKey, configId)
                val psk = clientKey.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                val identity = (appId.ifBlank { appKey }).toByteArray(Charsets.US_ASCII)
                val client = DtlsPskClient(host, 2100, identity, psk)
                client.connect()
                dtls = client
                encoder = HueStreamEncoder(configId)
                sendFailures = 0
                lastSent = null
                delayQueue.clear()
                _error.value = null
                Log.i(TAG, "Reconnected to the bridge")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Reconnect attempt $attempt failed: ${e.message}")
            }
        }
        Log.w(TAG, "Giving up on the bridge after $RECONNECT_ATTEMPTS attempts")
        _error.value = "Lost the connection to the bridge"
        running.set(false)
        scope.launch { cleanup() }
        return false
    }

    /**
     * Log at most once per [LOG_THROTTLE_MS]. This loop runs at 60 Hz, so a dead
     * network or a persistent render fault would otherwise emit 60 identical
     * lines a second and bury whatever else is in logcat.
     */
    private fun logThrottled(message: String) {
        val now = System.nanoTime()
        if (now - lastLogAt < LOG_THROTTLE_MS * 1_000_000L) return
        lastLogAt = now
        Log.w(TAG, message)
    }

    private var lastLogAt = 0L

    /** Consecutive failed sends, the trigger for a reconnect. */
    private var sendFailures = 0

    /**
     * Auto rung selection. Kept out of [SyncMode] deliberately — that enum keys
     * the engine's parameter table, and an "auto" entry there would be a rung
     * with no parameters. Auto is a *choice between* rungs, so it lives a level
     * up: the picker resolves it each frame and sets the engine's real rung.
     */
    private val picker = AutoIntensityPicker()

    @Volatile private var autoIntensity = false
    @Volatile private var autoLevels: List<SyncMode> = DEFAULT_AUTO_LEVELS

    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    /**
     * Hold the CPU and the Wi-Fi radio awake while streaming.
     *
     * Light Sync is the one thing here that keeps working with the screen off,
     * and it is exactly the case that breaks without this: a 60 Hz UDP sender
     * driven by `delay` is throttled by doze, and Wi-Fi power-save adds latency
     * spikes that show as the room stuttering behind the music. The Sendspin
     * receiver already does this for the same reasons; the local player never
     * needed to, because ExoPlayer's own foreground service covered it and
     * nothing depended on a steady send rate.
     *
     * `WIFI_MODE_FULL_LOW_LATENCY` rather than `WIFI_MODE_FULL_HIGH_PERF`: the
     * traffic is tiny and constant, and latency is the thing that matters.
     */
    @android.annotation.SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = (context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "camusic:lightsync")
                .apply { setReferenceCounted(false); acquire() }
        }
        if (wifiLock == null) {
            wifiLock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager)
                .createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "camusic:lightsync")
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    /**
     * Set when the *bridge* ended the stream. Distinct from a network fault, and
     * the difference decides whether reconnecting is right: a revoked session
     * means something else wants the area, and taking it straight back is what
     * makes the Hue app's stop button look broken.
     */
    @Volatile private var revoked = false

    /** Rendered frames waiting for the audio they describe to become audible. */
    private val delayQueue = FrameDelayQueue<Map<Int, Rgb>>()

    /**
     * Eye-safety limiter, or null on Extreme.
     *
     * The rung decides which one, following syncoV2: Subtle, Medium and High get
     * the strict WCAG limiter; Intense gets the relaxed budget, which never
     * engages on real music but still caps a true strobe; Extreme bypasses it
     * entirely at the user's explicit request. That last decision lives here
     * rather than inside [FieldSafety], so it is a deliberate choice at the call
     * site instead of something inherited by accident.
     */
    @Volatile private var safety: FieldSafety? = FieldSafety()

    /**
     * Philips' 12.5 Hz ceiling. Applied on every rung including Extreme — it is
     * a statement about what Zigbee can deliver, not a comfort setting, so
     * exceeding it produces no visible change and only more strobing.
     */
    private val rateLimiter = EffectRateLimiter()

    private var limiterMode: SyncMode? = null

    /**
     * Swap in the limiter this rung calls for, resetting the state so the new
     * one does not inherit the old one's flash history or anchor.
     */
    @Synchronized
    private fun selectLimiter(mode: SyncMode) {
        if (mode == limiterMode) return
        limiterMode = mode
        safety = when (mode) {
            SyncMode.EXTREME -> null
            SyncMode.INTENSE -> FieldSafety(RELAXED_MAX_FLASHES_PER_S, calmGated = false)
            else -> FieldSafety()
        }
        rateLimiter.reset()
    }

    /** The last frame actually put on the wire, for the unchanged check. */
    private var lastSent: Map<Int, Rgb>? = null

    /**
     * Is this frame visually identical to the one already on the bulbs?
     *
     * Quantised to the bridge's own resolution rather than compared exactly: xy
     * chromaticity is 12-bit at the lamp and brightness 11-bit, so differences
     * below those thresholds cannot be displayed and cost a datagram to send.
     * syncoV2 uses the same 1/2048 and 1/4096 and saves 20–40% of its DTLS
     * traffic by it.
     */
    private fun isUnchanged(next: Map<Int, Rgb>): Boolean {
        val prev = lastSent ?: return false
        if (prev.size != next.size) return false
        for ((id, rgb) in next) {
            val was = prev[id] ?: return false
            if (abs(rgb.first - was.first) >= COLOUR_QUANT) return false
            if (abs(rgb.second - was.second) >= COLOUR_QUANT) return false
            if (abs(rgb.third - was.third) >= COLOUR_QUANT) return false
        }
        return true
    }

    // ── Keepalive ───────────────────────────────────────────────────────────

    /**
     * Watches for bridge-side alerts, and resends only if the stream has actually
     * gone quiet. [renderLoop] sends continuously while a session is up, so the
     * resend is a backstop for a stalled loop rather than the normal path — firing
     * it regardless would inject a nine-second-old frame into a live stream.
     */
    private suspend fun keepaliveLoop() {
        while (running.get()) {
            kotlinx.coroutines.delay(KEEPALIVE_INTERVAL_MS)
            if (!running.get()) break

            // Check for bridge-side alerts (stream revocation).
            val client = dtls ?: break
            try {
                val alert = withContext(Dispatchers.IO) { client.pollAlert() }
                if (alert != null) {
                    val (_, desc) = alert
                    if (desc == 0 || desc == 90) {  // close_notify or user_canceled
                        Log.i(TAG, "Bridge closed the stream (alert $desc)")
                        // Bridge-initiated, so no reconnect — same reasoning as
                        // the send path.
                        revoked = true
                        running.set(false)
                        _error.value = "The bridge stopped the stream"
                        scope.launch { cleanup() }
                        break
                    }
                }
            } catch (e: Exception) {
                // Non-fatal: just log and continue.
            }

            // Only if the render loop has gone quiet — otherwise this would be a
            // stale frame cutting into a stream that is already flowing.
            if (System.nanoTime() - lastSendAt < KEEPALIVE_INTERVAL_MS * 1_000_000L) continue
            val frame = lastFrame ?: continue
            try {
                withContext(Dispatchers.IO) { client.send(frame) }
            } catch (e: Exception) {
                Log.w(TAG, "Keepalive send failed: ${e.message}")
            }
        }
    }

    // ── Live settings updates ───────────────────────────────────────────────

    /**
     * Mirror the stored Light Sync settings onto the running engine.
     *
     * The screen writes [AppSettings] and nothing else — it never touches the
     * engine. So a control moved mid-song lands on the next frame, and one moved
     * while nothing is playing is simply what [start] seeds the engine with. One
     * source of truth, and no path where the UI and the lights disagree.
     *
     * These collectors run for the life of the process. While the engine is null
     * every apply is a no-op, which costs nothing — DataStore only emits on change.
     */
    private fun observeSettings() {
        scope.launch {
            settings.lightSyncIntensity.collect { wire ->
                autoIntensity = wire == INTENSITY_AUTO
                if (autoIntensity) {
                    // Let the picker choose from the next frame rather than
                    // holding whatever rung was showing when Auto was selected.
                    picker.reset()
                } else {
                    val mode = SyncMode.fromWire(wire)
                    engine?.mode = mode
                    selectLimiter(mode)
                }
            }
        }
        scope.launch {
            settings.lightSyncEffect.collect { wire -> engine?.effect = SyncEffect.fromWire(wire) }
        }
        scope.launch {
            settings.hueBridgeId.collect { pairedBridgeId = it }
        }
        scope.launch {
            settings.lightSyncAutoLevels.collect { wires ->
                autoLevels = wires.mapNotNull { w -> SyncMode.entries.firstOrNull { it.wire == w } }
                    .ifEmpty { DEFAULT_AUTO_LEVELS }
                // A checklist change should feel immediate, not wait out a dwell.
                picker.allowImmediateRepick()
            }
        }
        scope.launch {
            settings.lightSyncColor.collect { wire ->
                val scheme = ColorScheme.fromWire(wire)
                engine?.setScheme(scheme)
                // Switching between album_art (v1/even) and album_art_v2
                // (v2/weighted) changes the extraction algorithm. Re-extract
                // the cached cover with the right one so the room reads the
                // sleeve the way the selected option promises.
                if (scheme == ColorScheme.ALBUM_ART || scheme == ColorScheme.ALBUM_ART_V2) {
                    val url = lastArtUrl
                    if (url != null) applyAlbumArt(url)
                }
            }
        }
        // Album artwork. Collected unconditionally rather than only while an
        // album-art scheme is selected, so switching to one mid-track picks up
        // the cover already on screen instead of waiting for the next song.
        scope.launch {
            activeSource.map { it.artUrl }.distinctUntilChanged().collect { url ->
                lastArtUrl = url
                applyAlbumArt(url)
            }
        }
        // The track itself, for the scan.
        scope.launch {
            activeSource.map { it.scanTrack }.distinctUntilChanged { a, b -> scanKeyOf(a) == scanKeyOf(b) }
                .collect { track -> onTrackChanged(track) }
        }
        // Re-point the tap hookup whenever the active backend changes - a local
        // <-> MA switch, or MA reconnecting to a fresh SendspinExoEngine (a new
        // AudioAnalysisTap instance every time). A no-op while nothing is
        // running: start() does its own initial rewireTap() call, and a source
        // change that arrives before that is exactly what wiredTap == null /
        // running == false already handles safely.
        scope.launch {
            activeSource.collect { source -> if (running.get()) rewireTap(source) }
        }
        // A scan that lands while its own track is still inside the adoption
        // window is adopted right away, which is what makes the very first play
        // of a local track already exact rather than only the second.
        scans?.let { repo ->
            scope.launch {
                repo.completed.collect { key ->
                    val track = currentTrack ?: return@collect
                    if (TrackScanRepository.keyFor(track) != key) return@collect
                    if (mapCommitted != null) return@collect  // regime already settled
                    adoptScan(repo.cached(track))
                }
            }
        }
        scope.launch {
            settings.lightSyncBrightness.collect { pct -> engine?.brightness = pct.coerceIn(0, 100) / 100f }
        }
        scope.launch {
            settings.lightSyncTunables.collect { tunables ->
                activeTunables = tunables
                engine?.setTunables(tunables)
            }
        }
        scope.launch {
            isPlaying.collect { playing -> playerPlaying = playing }
        }
    }

    /** Album-art colours, pushed by the player when the track changes. */
    fun setAlbumColors(colors: List<Rgb>) {
        engine?.setAlbumColors(colors)
    }

    // ── Track scans ─────────────────────────────────────────────────────────

    /** What is playing, for matching a completed scan against. */
    @Volatile private var currentTrack: LocalTrack? = null

    private fun scanKeyOf(track: LocalTrack?): String? =
        track?.let { TrackScanRepository.keyFor(it) }

    /**
     * A new song: drop everything the last one's scan told us, then look for
     * this one's.
     *
     * The clearing happens first and synchronously. The analysis thread may
     * already be a hop or two into the new audio by the time this runs, and a
     * grid from the previous track applied to it would be worse than no grid at
     * all — the beats would be confidently, precisely wrong.
     */
    private suspend fun onTrackChanged(track: LocalTrack?) {
        currentTrack = track
        activeScan = null
        activeProfile = null
        scannedSignal = null
        mapCommitted = null
        mapPrevPos = null
        mapSection = null
        picker.allowImmediateRepick()

        val repo = scans ?: return
        val next = track ?: return
        // Cached first, because that is the fast path and the one that lands
        // inside the adoption window.
        adoptScan(repo.cached(next))
        if (activeScan == null) repo.request(next, urgent = true)
    }

    private fun adoptScan(scan: TrackScan?) {
        if (scan == null) return
        activeScan = scan
        activeProfile = scan.intensity
    }

    /**
     * Last in the class body, and that placement is load-bearing.
     *
     * Kotlin runs property initialisers and `init` blocks in declaration order,
     * and [observeSettings] launches collectors that fire immediately — the
     * settings flows have a value to give straight away. From an `init` block
     * near the top of the class those collectors reached `picker`,
     * `rateLimiter`, `safety` and `delayQueue` while their initialisers further
     * down had not run yet, and read them as null. Kotlin cannot warn about it:
     * the types are non-null, so the null-check is elided and the crash surfaces
     * as a bare NullPointerException on a field that "cannot" be null.
     *
     * Everything the collectors touch is declared above this point.
     */
    init {
        observeSettings()
    }
}
