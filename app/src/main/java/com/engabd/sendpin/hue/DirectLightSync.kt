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
 * The orchestrator runs on a background scope. DTLS sends are synchronous
 * (blocking UDP) and run on [Dispatchers.IO]. The analysis frame callback
 * runs on the ExoPlayer audio thread — it only touches the engine (which
 * is single-threaded by contract) and queues the RGB output for the send
 * loop, never blocks.
 *
 * Keepalive: the bridge drops the DTLS channel after ~10s of silence.
 * A keepalive loop resends the last frame every 9s if the audio is idle.
 */

private const val TAG = "DirectLightSync"
private const val KEEPALIVE_INTERVAL_MS = 9000L
private const val STREAM_FPS = 50
private const val FRAME_PERIOD_MS = 1000L / STREAM_FPS

class DirectLightSync(
    private val context: Context,
    private val settings: AppSettings = AppSettings(context),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The bridge client — mDNS discovery, CLIP v2 API. */
    val bridgeClient = HueBridgeClient(context)

    /** The audio tap — plugged into ExoPlayer's render chain. */
    val audioTap = AudioAnalysisTap()

    /** The DTLS client — opened on start, closed on stop. */
    private var dtls: DtlsPskClient? = null

    /** The stream encoder — one per entertainment area. */
    private var encoder: HueStreamEncoder? = null

    /** The effects engine. */
    private var engine: SyncoEngine? = null

    /** Entertainment area channels. */
    private var channels: List<EntertainmentChannel> = emptyList()

    /** Last rendered frame, for keepalive resends. */
    @Volatile private var lastFrame: ByteArray? = null

    /** Whether the stream is active. */
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    /** Error state, surfaced to the UI. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var keepaliveJob: Job? = null
    private var sendJob: Job? = null
    private val running = AtomicBoolean(false)

    /**
     * Start the direct Light Sync session.
     *
     * Reads bridge credentials from [AppSettings], fetches the entertainment
     * area, starts the stream on the bridge, opens the DTLS channel, and
     * activates the audio tap.
     *
     * Must be called from a coroutine (reads DataStore).
     */
    suspend fun start() {
        if (running.get()) return

        val host = settings.hueBridgeIp.first()
        val appKey = settings.hueAppKey.first()
        val clientKey = settings.hueClientKey.first()
        val appId = settings.hueAppId.first()
        val configId = settings.hueEntertainmentConfigId.first()

        if (host.isBlank() || appKey.isBlank() || clientKey.isBlank() || configId.isBlank()) {
            _error.value = "Bridge not configured. Set up a bridge in Settings first."
            return
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
                it.setScheme(ColorScheme.fromWire(settings.lightSyncColor.first()))
                it.brightness = settings.lightSyncBrightness.first() / 100f
            }

            // 5. Activate the audio tap.
            audioTap.onFrame = ::onAnalysisFrame
            audioTap.setActive(true)

            running.set(true)
            _active.value = true
            _error.value = null

            // 6. Start keepalive loop.
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

        keepaliveJob?.cancel(); keepaliveJob = null
        sendJob?.cancel(); sendJob = null

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
     * Called from the ExoPlayer audio thread (~50 Hz) with a new analysis
     * frame. Runs the engine and sends the result through DTLS.
     *
     * Must not block — the audio thread is real-time.
     */
    private fun onAnalysisFrame(frame: AnalysisFrame) {
        val eng = engine ?: return
        val enc = encoder ?: return
        val client = dtls ?: return
        if (!running.get()) return

        // dt is the frame period (1/50s = 0.02s)
        val dt = 0.02f
        val colors = eng.render(frame, dt)

        // Encode + send. The DTLS send is a blocking UDP write but at
        // 50 Hz with a ~100-byte datagram it completes in microseconds.
        val packets = enc.buildPackets(colors)
        for (packet in packets) {
            try {
                client.send(packet)
                lastFrame = packet
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

    // ── Keepalive ───────────────────────────────────────────────────────────

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

            // Resend the last frame to keep the channel alive.
            val frame = lastFrame ?: continue
            try {
                withContext(Dispatchers.IO) { client.send(frame) }
            } catch (e: Exception) {
                Log.w(TAG, "Keepalive send failed: ${e.message}")
            }
        }
    }

    // ── Live settings updates (from the Light Sync screen) ──────────────────

    fun setMode(mode: SyncMode) {
        engine?.mode = mode
        scope.launch { settings.setLightSyncIntensity(mode.wire) }
    }

    fun setEffect(effect: SyncEffect) {
        engine?.effect = effect
        scope.launch { settings.setLightSyncEffect(effect.wire) }
    }

    fun setColor(scheme: ColorScheme) {
        engine?.setScheme(scheme)
        scope.launch { settings.setLightSyncColor(scheme.wire) }
    }

    fun setAlbumColors(colors: List<Rgb>) {
        engine?.setAlbumColors(colors)
    }

    fun setBrightness(pct: Int) {
        engine?.brightness = pct.coerceIn(0, 100) / 100f
        scope.launch { settings.setLightSyncBrightness(pct) }
    }

    fun setTiming(ms: Int) {
        // Timing offset — deferred (needs delay buffer for negative offsets).
        // For now this is a no-op that stores the setting.
        scope.launch { settings.setLightSyncTiming(ms) }
    }

    fun setTunables(tunables: Map<String, Float>) {
        // Advanced tunables — deferred for MVP.
        // The engine supports them but the MVP uses the mode's defaults.
    }
}