package com.engabd.sendpin.hue

import android.content.Context
import android.util.Log
import com.engabd.sendpin.data.AppSettings
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private const val TAG = "HueLightBridge"

/** The bridge's DTLS entertainment port. Fixed by the Hue Entertainment API. */
private const val DTLS_PORT = 2100

/**
 * Reconnect backoff. Matches syncoV2's window: doubling from a second to ten,
 * over fourteen attempts, is a bit under two minutes of trying — long enough to
 * ride out a Wi-Fi roam or a router reboot without retrying forever.
 */
private const val RECONNECT_ATTEMPTS = 14
private const val RECONNECT_BASE_MS = 1_000L
private const val RECONNECT_MAX_MS = 10_000L

/**
 * The first [LightBridge] implementation — Hue Entertainment over DTLS-PSK.
 *
 * Wraps [HueBridgeClient] (mDNS/CLIP v2 discovery, `action:start`/`action:stop`),
 * [DtlsPskClient] (the DTLS-PSK session to port 2100) and [HueStreamEncoder]
 * (HueStream v2 datagrams) exactly as [DirectLightSync] drove them before this
 * class existed — this is Phase 0 of `docs/plan/wled-light-backend.md`, a pure
 * extraction with **no intended behaviour change**: same constants, same
 * reconnect backoff, same gamut/chromaticity-carry-over on reconnect, same
 * "bridge revoked the stream" distinction. `docs/plan/wled-light-backend.md`
 * calls for verification against a real bridge before this is relied on in
 * place of the code it replaces.
 */
class HueLightBridge(
    private val context: Context,
    private val settings: AppSettings,
    /**
     * The bridge id the certificate's Common Name must match, when it is known.
     * Forwarded to [HueBridgeClient] unchanged — see its own constructor doc.
     */
    expectedBridgeId: () -> String = { "" },
) : LightBridge {

    /**
     * The bridge client — mDNS discovery, CLIP v2 API, pairing, and the
     * entertainment-area listing the Light Sync settings screen reads
     * directly (`LightSyncSettings.kt` via `DirectLightSync.bridgeClient`).
     * Exposed as-is: that screen's pairing/area-picker flow is unrelated to
     * the streaming session this class otherwise owns.
     */
    val bridgeClient = HueBridgeClient(context, expectedBridgeId = expectedBridgeId)

    /** The entertainment configuration id [fetchRoom] resolved, for the session and any reconnect. */
    @Volatile private var configId: String = ""

    /** The DTLS client — opened by [openSession], closed by [closeSession]. */
    @Volatile private var dtls: DtlsPskClient? = null

    /** The stream encoder — one per entertainment area, rebuilt on [reconnect]. */
    @Volatile private var encoder: HueStreamEncoder? = null

    override suspend fun fetchRoom(): LightRoom = withContext(Dispatchers.IO) {
        val host = settings.hueBridgeIp.first()
        val appKey = settings.hueAppKey.first()
        val wantedId = settings.hueEntertainmentConfigId.first()
        val configs = bridgeClient.getEntertainmentConfigs(host, appKey)
        val config = configs.firstOrNull { it.id == wantedId }
            ?: configs.firstOrNull()
            ?: throw DtlsException("No entertainment area found on the bridge")
        configId = config.id
        LightRoom(config.channels, config.configurationType, config.name)
    }

    override suspend fun openSession(room: LightRoom) = withContext(Dispatchers.IO) {
        val host = settings.hueBridgeIp.first()
        val appKey = settings.hueAppKey.first()
        val clientKey = settings.hueClientKey.first()
        val appId = settings.hueAppId.first()
        if (host.isBlank() || appKey.isBlank() || clientKey.isBlank() || configId.isBlank()) {
            throw DtlsException("Bridge not configured. Set up a bridge in Settings first.")
        }

        // Passing our own application id lets the bridge tell "someone else has
        // this area" from "we already do" — a reconnect must still reclaim it.
        bridgeClient.startStream(host, appKey, configId, appId)

        val psk = clientKey.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val identity = (appId.ifBlank { appKey }).toByteArray(Charsets.US_ASCII)
        val client = DtlsPskClient(host, DTLS_PORT, identity, psk)
        client.connect()
        dtls = client

        // Per-channel gamuts where the bridge reported them; anything it
        // didn't answer for falls back to Gamut C inside the encoder.
        encoder = HueStreamEncoder(
            configId,
            gamuts = room.channels.mapNotNull { ch -> ch.gamut?.let { ch.channelId to it } }.toMap(),
        )
    }

    override suspend fun closeSession() = withContext(Dispatchers.IO) {
        // Close DTLS (sends close_notify) so the bridge frees the session at
        // once; without it a restart inside the ~10 s linger is ignored.
        try {
            dtls?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to close DTLS session: ${e.message}")
        }
        dtls = null
        encoder = null

        try {
            val host = settings.hueBridgeIp.first()
            val appKey = settings.hueAppKey.first()
            if (host.isNotBlank() && appKey.isNotBlank() && configId.isNotBlank()) {
                bridgeClient.stopStream(host, appKey, configId)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop stream on bridge: ${e.message}")
        }
    }

    override fun encode(colors: Map<Int, Rgb>): List<ByteArray> =
        (encoder ?: throw DtlsException("No Hue session open")).buildPackets(colors)

    override suspend fun send(packet: ByteArray): SendOutcome {
        val client = dtls ?: return SendOutcome.Failed("No Hue session open")
        return try {
            client.send(packet)
            SendOutcome.Ok
        } catch (e: DtlsPeerClosed) {
            // Bridge-initiated teardown: the Hue app took the area, or the user
            // pressed stop there. Deliberately never retried by the caller — see
            // SendOutcome.Revoked's own doc.
            Log.w(TAG, "Bridge revoked the stream: ${e.message}")
            SendOutcome.Revoked(e.message ?: "The bridge revoked the stream (another app may have taken over)")
        } catch (e: Exception) {
            // A network fault, by elimination. Wi-Fi drops and roams are
            // ordinary events on a phone; the caller counts these toward reconnect.
            SendOutcome.Failed(e.message ?: "DTLS send failed")
        }
    }

    override suspend fun pollRevocation(): String? {
        val client = dtls ?: return null
        return try {
            val alert = withContext(Dispatchers.IO) { client.pollAlert() }
            val desc = alert?.second ?: return null
            if (desc == 0 || desc == 90) {  // close_notify or user_canceled
                Log.i(TAG, "Bridge closed the stream (alert $desc)")
                "The bridge stopped the stream"
            } else {
                null
            }
        } catch (e: Exception) {
            // Non-fatal: the keepalive loop just tries again next tick.
            null
        }
    }

    /**
     * The engine is deliberately kept across a reconnect by the caller — it
     * holds the envelopes, the colour phase and the role assignment, so
     * reusing it means the room picks up where it left off rather than
     * restarting the show. Only the encoder is rebuilt here, since its
     * sequence numbers belong to the old session; its chromaticity state
     * (`snapshotXy`/`restoreXy`) crosses the rebuild so no channel's colour
     * pops on reconnect.
     */
    override suspend fun reconnect(room: LightRoom, shouldContinue: () -> Boolean, onAttempt: () -> Unit): Boolean {
        var delayMs = RECONNECT_BASE_MS
        for (attempt in 1..RECONNECT_ATTEMPTS) {
            if (!shouldContinue()) return false
            Log.i(TAG, "Reconnecting to the bridge (attempt $attempt of $RECONNECT_ATTEMPTS)")
            onAttempt()
            try {
                dtls?.close()
            } catch (e: Exception) {
                // The old socket is already gone; that is why we are here.
            }
            dtls = null

            kotlinx.coroutines.delay(delayMs)
            delayMs = min(RECONNECT_MAX_MS, delayMs * 2)
            if (!shouldContinue()) return false

            try {
                val host = settings.hueBridgeIp.first()
                val appKey = settings.hueAppKey.first()
                val clientKey = settings.hueClientKey.first()
                val appId = settings.hueAppId.first()
                if (host.isBlank() || appKey.isBlank() || clientKey.isBlank() || configId.isBlank()) return false

                bridgeClient.startStream(host, appKey, configId, appId)
                val psk = clientKey.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                val identity = (appId.ifBlank { appKey }).toByteArray(Charsets.US_ASCII)
                val client = DtlsPskClient(host, DTLS_PORT, identity, psk)
                client.connect()
                dtls = client

                val carryXy = encoder?.snapshotXy()
                encoder = HueStreamEncoder(
                    configId,
                    gamuts = room.channels.mapNotNull { ch -> ch.gamut?.let { ch.channelId to it } }.toMap(),
                )
                carryXy?.let { encoder?.restoreXy(it) }

                Log.i(TAG, "Reconnected to the bridge")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Reconnect attempt $attempt failed: ${e.message}")
            }
        }
        Log.w(TAG, "Giving up on the bridge after $RECONNECT_ATTEMPTS attempts")
        return false
    }
}
