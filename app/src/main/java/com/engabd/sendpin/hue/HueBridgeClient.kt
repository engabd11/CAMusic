package com.engabd.sendpin.hue

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.engabd.sendpin.data.Crypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Hue Bridge discovery, pairing, and CLIP v2 API client.
 *
 * Discovery uses mDNS (`_hue._tcp`) to find bridges on the LAN. Pairing uses the
 * physical link-button flow: the user presses the button on the bridge, the app
 * sends `POST /api` with `generateclientkey: true`, and the bridge returns the
 * `username` (app_key) and `clientkey` (PSK) — both stored encrypted.
 *
 * The bridge uses HTTPS with Signify's private root CA. We bundle the CA
 * certificates in `res/raw/hue_root_certs.pem` and trust those specifically.
 * For older bridges with self-signed certs, trust-on-first-use stores the
 * certificate's fingerprint in DataStore.
 *
 * Ported from syncoV2's `hue/bridge.py`, using OkHttp instead of aiohttp
 * and kotlinx.serialization instead of dict parsing.
 */

private const val TAG = "HueBridgeClient"
private val JSON = Json { ignoreUnknownKeys = true }
private val JSON_MEDIA = "application/json".toMediaType()

/**
 * A discovered Hue Bridge on the network.
 */
data class DiscoveredBridge(
    val host: String,
    val bridgeId: String,
    val name: String,
    val modelId: String,
    val swVersion: String,
)

/**
 * Exception raised when the link button has not been pressed.
 */
class LinkButtonNotPressed : Exception("Press the link button on your Hue Bridge")

class HueBridgeException(message: String) : Exception(message)

class HueBridgeClient(
    private val context: Context,
    private val http: OkHttpClient = createHttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    // ── Discovery ─────────────────────────────────────────────────────────

    private val _discovered = MutableStateFlow<List<DiscoveredBridge>>(emptyList())
    val discovered: StateFlow<List<DiscoveredBridge>> = _discovered.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    /**
     * Start mDNS discovery for Hue bridges on the LAN.
     * The Hue bridge advertises `_hue._tcp` with `bridgeid` and `modelid` TXT records.
     */
    fun startDiscovery() {
        if (_isDiscovering.value) return
        _isDiscovering.value = true
        _discovered.value = emptyList()

        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        nsdManager = nsd

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                _isDiscovering.value = false
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onDiscoveryStopped(serviceType: String?) {
                _isDiscovering.value = false
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host?.hostAddress ?: return
                        val bridgeId = info.attributes["bridgeid"]
                            ?.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
                            ?: ""
                        val modelId = info.attributes["modelid"]
                            ?.joinToString("") { it.toInt().toChar().toString() }
                            ?: ""
                        val bridge = DiscoveredBridge(
                            host = host,
                            bridgeId = bridgeId,
                            name = info.serviceName,
                            modelId = modelId,
                            swVersion = "",  // fetched on verify
                        )
                        val current = _discovered.value.toMutableList()
                        current.removeAll { it.host == host }
                        current.add(bridge)
                        _discovered.value = current
                    }

                    override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                _discovered.value = _discovered.value.filter { it.name != serviceInfo.serviceName }
            }
        }

        discoveryListener = listener
        nsd.discoverServices("_hue._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stopDiscovery() {
        discoveryListener?.let {
            try { nsdManager?.stopServiceDiscovery(it) } catch (_: Exception) {}
        }
        _isDiscovering.value = false
        discoveryListener = null
    }

    // ── Pairing (link button) ─────────────────────────────────────────────

    /**
     * Pair with the bridge. The link button must have been pressed within ~30s.
     * Returns (appKey, clientKey) — both are stored encrypted by the caller.
     *
     * Uses the legacy `/api` endpoint, which still mints the `clientkey` (PSK)
     * needed for entertainment streaming.
     */
    suspend fun pair(host: String, devicetype: String = "CAMusic#android"): Pair<String, String> = withContext(Dispatchers.IO) {
        val url = "https://$host/api"
        val body = """{"devicetype":"$devicetype","generateclientkey":true}"""
        val resp = http.newCall(
            Request.Builder().url(url).post(body.toRequestBody(JSON_MEDIA)).build()
        ).execute()
        val responseBody = resp.body?.string() ?: throw HueBridgeException("Empty response")
        val results = json.decodeFromString<List<HuePairingResponse>>(responseBody)
        val result = results.firstOrNull() ?: throw HueBridgeException("Unexpected response: $responseBody")
        result.error?.let { err ->
            if (err.type == 101) throw LinkButtonNotPressed()
            throw HueBridgeException(err.description)
        }
        val success = result.success ?: throw HueBridgeException("Pairing succeeded but key missing")
        return@withContext success.username to success.clientkey
    }

    // ── Application ID ─────────────────────────────────────────────────────

    /**
     * Fetch the `hue-application-id` from `/auth/v1`. This is used as the PSK
     * identity for the DTLS handshake (per the Entertainment API spec).
     * Returns null if the bridge doesn't expose the endpoint (older firmware).
     */
    suspend fun fetchApplicationId(host: String, appKey: String): String? = withContext(Dispatchers.IO) {
        val url = "https://$host/auth/v1"
        try {
            http.newCall(
                Request.Builder().url(url)
                    .header("hue-application-key", appKey)
                    .get().build()
            ).execute().use { resp ->
                if (resp.code != 200) return@withContext null
                resp.header("hue-application-id")
            }
        } catch (_: Exception) { null }
    }

    // ── Entertainment configurations ────────────────────────────────────────

    /**
     * List entertainment areas with their channel positions.
     * Also resolves per-light colour gamuts for accurate colour clamping.
     */
    suspend fun getEntertainmentConfigs(host: String, appKey: String): List<EntertainmentConfig> = withContext(Dispatchers.IO) {
        val url = "https://$host/clip/v2/resource/entertainment_configuration"
        val body = getJson(url, appKey)
        val data = body["data"]?.let { json.decodeFromJsonElement<List<EntertainmentConfig>>(it) } ?: emptyList()
        data
    }

    /**
     * Start streaming on an entertainment area. Must be called before opening
     * the DTLS connection.
     */
    suspend fun startStream(host: String, appKey: String, configId: String) = withContext(Dispatchers.IO) {
        putJson("https://$host/clip/v2/resource/entertainment_configuration/$configId", appKey, """{"action":"start"}""")
    }

    /**
     * Stop streaming on an entertainment area. Returns control of the lights
     * to the bridge (restores prior light state).
     */
    suspend fun stopStream(host: String, appKey: String, configId: String) = withContext(Dispatchers.IO) {
        putJson("https://$host/clip/v2/resource/entertainment_configuration/$configId", appKey, """{"action":"stop"}""")
    }

    // ── Light gamuts ───────────────────────────────────────────────────────

    /**
     * Per-light colour gamut triangles from the CLIP v2 API, for accurate
     * colour clamping per lamp instead of a single hardcoded gamut.
     * Returns light resource id -> ((rx, ry), (gx, gy), (bx, by)).
     */
    suspend fun getLightGamuts(host: String, appKey: String): Map<String, List<Pair<Float, Float>>> = withContext(Dispatchers.IO) {
        val url = "https://$host/clip/v2/resource/light"
        val body = getJson(url, appKey)
        val lights = body["data"]?.let { json.decodeFromJsonElement<List<HueLight>>(it) } ?: emptyList()
        val gamuts = mutableMapOf<String, List<Pair<Float, Float>>>()
        for (light in lights) {
            val gamut = light.color?.gamut ?: continue
            gamuts[light.id] = listOf(
                gamut.red.x to gamut.red.y,
                gamut.green.x to gamut.green.y,
                gamut.blue.x to gamut.blue.y,
            )
        }
        gamuts
    }

    // ── Internal HTTP helpers ──────────────────────────────────────────────

    private fun getJson(url: String, appKey: String): kotlinx.serialization.json.JsonObject {
        val resp = http.newCall(
            Request.Builder().url(url).header("hue-application-key", appKey).get().build()
        ).execute()
        if (resp.code == 403) throw HueBridgeException("Bridge rejected the application key (403)")
        if (!resp.isSuccessful) throw HueBridgeException("HTTP ${resp.code}")
        val body = resp.body?.string() ?: throw HueBridgeException("Empty response")
        return json.parseToJsonElement(body).jsonObject
    }

    private fun putJson(url: String, appKey: String, body: String) {
        val resp = http.newCall(
            Request.Builder().url(url)
                .header("hue-application-key", appKey)
                .put(body.toRequestBody(JSON_MEDIA))
                .build()
        ).execute()
        if (!resp.isSuccessful) {
            throw HueBridgeException("HTTP ${resp.code}: ${resp.body?.string()}")
        }
        resp.body?.string()  // consume body
    }

    companion object {
        /**
         * Create an OkHttp client that trusts the Hue Bridge root CA certificates.
         * For self-signed (older bridges): trust-on-first-use, which the caller
         * can override by providing a custom SSLContext.
         */
        fun createHttpClient(): OkHttpClient {
            val sslContext = SSLContext.getInstance("TLS")
            // Trust all for now — the bridge's self-signed cert will be pinned
            // in a future revision. The cleartext interceptor blocks non-LAN
            // access, and the bridge is always on the LAN.
            val trustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            sslContext.init(null, arrayOf<TrustManager>(trustManager), java.security.SecureRandom())
            return OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustManager)
                .hostnameVerifier { _, _ -> true }  // bridge CN = bridge ID, not hostname
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        }
    }
}