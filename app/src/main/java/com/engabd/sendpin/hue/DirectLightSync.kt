package com.engabd.sendpin.hue

import android.content.Context
import android.util.Log
import com.engabd.sendpin.audio.ANALYSIS_HOP
import com.engabd.sendpin.audio.ANALYSIS_SAMPLE_RATE
import com.engabd.sendpin.audio.AnalysisFrame
import com.engabd.sendpin.audio.AudioAnalysisTap
import com.engabd.sendpin.audio.AudioLead
import com.engabd.sendpin.audio.BeatGrid
import com.engabd.sendpin.audio.StructureState
import com.engabd.sendpin.audio.StructureTracker
import com.engabd.sendpin.audio.TempoTracker
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.data.Crypto
import kotlin.math.abs
import kotlin.math.max
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
 *    (PUT action:start), open the DTLS channel, create the SyncoEngine.
 * 2. The [AudioAnalysisTap] feeds [AnalysisFrame]s at ~50 Hz from ExoPlayer.
 *    Each frame is passed to [onAnalysisFrame], which runs the engine and
 *    sends the resulting per-channel RGB through the DTLS stream.
 * 3. [stop] — stop the stream (PUT action:stop), close the DTLS channel,
 *    release the engine.
 *
 * The orchestrator runs on a background scope. [onAnalysisFrame] runs on the
 * ExoPlayer audio thread and renders *and sends* inline — a ~100-byte UDP
 * datagram at 50 Hz, which is microseconds in the normal case. It is still a
 * blocking socket write on a real-time thread, so if the on-device test turns
 * up audio glitching under a flaky LAN, this is the first place to look: the
 * fix is a single-slot handoff to a sender coroutine.
 *
 * Keepalive: the bridge drops the DTLS channel after ~10s of silence.
 * A keepalive loop resends the last frame every 9s if the audio is idle.
 */

private const val TAG = "DirectLightSync"

/**
 * The bridge closes an entertainment session after 10 s of silence, so the
 * keepalive has to beat that. Matches syncoV2's `KEEPALIVE_INTERVAL`.
 */
private const val KEEPALIVE_INTERVAL_MS = 9000L

/** Minimum gap between repeats of a per-frame warning. See `logThrottled`. */
private const val LOG_THROTTLE_MS = 5000L

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

/** What the engine renders when nothing is feeding it. */
private val SILENCE = AnalysisFrame()

class DirectLightSync(
    private val context: Context,
    /**
     * The tap that is actually installed in ExoPlayer's render chain — it must be
     * *the same instance* [com.engabd.sendpin.audio.LocalPlayer] handed to its
     * `TapRenderersFactory`, not a second one. Activating a tap the audio never
     * flows through yields a connected bridge and lights that never move.
     */
    private val audioTap: AudioAnalysisTap,
    /**
     * How far the tap runs ahead of the speaker, measured by the sink wrapper
     * [com.engabd.sendpin.audio.AudioLeadProbe]. Drives [delayQueue] so a light
     * change lands when the audio is heard rather than when it was decoded.
     */
    private val audioLead: AudioLead,
    private val settings: AppSettings = AppSettings(context),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The bridge client — mDNS discovery, CLIP v2 API. */
    val bridgeClient = HueBridgeClient(context)

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

    /** When the last packet went out, so the keepalive knows if it is needed. */
    @Volatile private var lastSendAt = 0L

    /** Whether the stream is active. */
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    /** Error state, surfaced to the UI. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var keepaliveJob: Job? = null
    private var renderJob: Job? = null
    private val running = AtomicBoolean(false)

    init {
        observeSettings()
    }

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
            delayQueue.resetDelay(audioLead.leadMs)
            lastSent = null
            limiterMode = null
            selectLimiter(SyncMode.fromWire(settings.lightSyncIntensity.first()))
            safety?.reset()
            encoder = HueStreamEncoder(config.id)
            engine = SyncoEngine(channels, config.configurationType).also {
                it.mode = SyncMode.fromWire(settings.lightSyncIntensity.first())
                it.effect = SyncEffect.fromWire(settings.lightSyncEffect.first())
                it.setScheme(ColorScheme.fromWire(settings.lightSyncColor.first()))
                it.brightness = settings.lightSyncBrightness.first() / 100f
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
            audioTap.onFrame = ::onAnalysisFrame
            audioTap.onAnalysisReset = ::onAnalysisReset
            audioTap.setActive(true)

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
        audioTap.setActive(false)
        audioTap.onFrame = null
        audioTap.onAnalysisReset = null
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
        latestGrid = tempo?.update(
            tAudio = frame.tAudio,
            fluxValue = frame.flux,
            beat = frame.beat,
            beatStrength = frame.beatStrength,
            bass = max(frame.bands["sub_bass"] ?: 0f, frame.bands["bass"] ?: 0f),
        )
        latestStructure = structure?.update(frame)
        latestFrame = frame
        latestFrameAt = System.nanoTime()
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
        while (running.get()) {
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
            // tracks. Feeding the last real frame forever would hold the room lit
            // on whatever was playing when the music stopped; an empty frame lets
            // the engine's own silence gate take it down.
            val fresh = (now - latestFrameAt) < FRAME_STALE_NANOS
            val frame = if (fresh) latestFrame ?: SILENCE else SILENCE

            val colours = try {
                eng.render(frame, dt, latestGrid, latestStructure)
            } catch (e: Exception) {
                logThrottled("Render failed: ${e.message}")
                continue
            }

            // Hold the rendered frame until the audio it describes is actually
            // audible. See FrameDelayQueue: the tap runs ahead of the speaker, so
            // without this the lights lead the music rather than trail it.
            delayQueue.updateDelay(audioLead.leadMs, dt)
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
                    Log.w(TAG, "Bridge revoked the stream: ${e.message}")
                    running.set(false)
                    _error.value = "The bridge revoked the stream (another app may have taken over)"
                    scope.launch { cleanup() }
                    return
                } catch (e: Exception) {
                    logThrottled("DTLS send failed: ${e.message}")
                }
            }

            // Recorded only once the frame has been through the socket. Setting
            // it before the send would let a failed write count as displayed, and
            // the next identical frame would then be skipped as a duplicate of
            // something the bulbs never received.
            lastSent = due
        }
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
                val mode = SyncMode.fromWire(wire)
                engine?.mode = mode
                selectLimiter(mode)
            }
        }
        scope.launch {
            settings.lightSyncEffect.collect { wire -> engine?.effect = SyncEffect.fromWire(wire) }
        }
        scope.launch {
            settings.lightSyncColor.collect { wire -> engine?.setScheme(ColorScheme.fromWire(wire)) }
        }
        scope.launch {
            settings.lightSyncBrightness.collect { pct -> engine?.brightness = pct.coerceIn(0, 100) / 100f }
        }
    }

    /** Album-art colours, pushed by the player when the track changes. */
    fun setAlbumColors(colors: List<Rgb>) {
        engine?.setAlbumColors(colors)
    }
}