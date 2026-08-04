package com.engabd.sendpin.hue

import android.content.Context
import android.util.Log
import com.engabd.sendpin.audio.AnalysisFrame
import com.engabd.sendpin.audio.AudioAnalysisTap
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.data.Crypto
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
private const val KEEPALIVE_INTERVAL_MS = 9000L

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
            encoder = HueStreamEncoder(config.id)
            engine = SyncoEngine(channels).also {
                it.mode = SyncMode.fromWire(settings.lightSyncIntensity.first())
                it.effect = SyncEffect.fromWire(settings.lightSyncEffect.first())
                it.setScheme(ColorScheme.fromWire(settings.lightSyncColor.first()))
                it.brightness = settings.lightSyncBrightness.first() / 100f
            }

            // 5. Activate the audio tap.
            latestFrame = null
            latestFrameAt = 0L
            audioTap.onFrame = ::onAnalysisFrame
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
     * Stop the direct Light Sync session.
     * Safe to call multiple times.
     */
    fun stop() {
        if (!running.getAndSet(false)) return
        cleanup()
    }

    private fun cleanup() {
        audioTap.setActive(false)
        audioTap.onFrame = null

        renderJob?.cancel(); renderJob = null
        keepaliveJob?.cancel(); keepaliveJob = null

        engine = null
        encoder = null

        // Close DTLS (sends close_notify).
        dtls?.close()
        dtls = null

        // Stop the stream on the bridge.
        scope.launch {
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
        }

        _active.value = false
        lastFrame = null
    }

    // ── Analysis frame callback ────────────────────────────────────────────

    /**
     * Called on the ExoPlayer audio thread with a new analysis frame.
     *
     * It only hands the frame over. The audio thread is real-time, and the
     * decoder delivers in chunks rather than one hop at a time — rendering here
     * would emit several frames back to back whenever a buffer landed, so the
     * bridge would receive bursts instead of an even stream. [renderLoop] paces
     * the output instead.
     */
    private fun onAnalysisFrame(frame: AnalysisFrame) {
        latestFrame = frame
        latestFrameAt = System.nanoTime()
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

            val packets = try {
                enc.buildPackets(eng.render(frame, dt))
            } catch (e: Exception) {
                Log.w(TAG, "Render failed: ${e.message}")
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
                    Log.w(TAG, "DTLS send failed: ${e.message}")
                }
            }
        }
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
            settings.lightSyncIntensity.collect { wire -> engine?.mode = SyncMode.fromWire(wire) }
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