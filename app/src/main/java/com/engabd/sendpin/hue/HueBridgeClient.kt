package com.engabd.sendpin.hue

import android.content.Context
import com.engabd.sendpin.R
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
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
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
 * The bridge uses HTTPS with Signify's private root CA. The CA certificates are
 * bundled in `res/raw/hue_root_certs.pem` and are the only ones trusted, and
 * because the connection is made to an IP the certificate's Subject Common Name
 * is checked against the bridge id in place of hostname verification — both as
 * the Using HTTPS page specifies. There is no trust-on-first-use: the early
 * self-signed bridges have all been updated, and the spec says that logic can be
 * dropped.
 *
 * Ported from syncoV2's `hue/bridge.py`, using OkHttp instead of aiohttp
 * and kotlinx.serialization instead of dict parsing.
 */

private const val TAG = "HueBridgeClient"
private val JSON = Json { ignoreUnknownKeys = true }
private val JSON_MEDIA = "application/json".toMediaType()

/** Philips rate-limits cloud discovery to one request per 15 minutes per client. */
private const val CLOUD_DISCOVERY_TTL_MS = 15 * 60 * 1000L

/** One entry from `https://discovery.meethue.com`. */
@kotlinx.serialization.Serializable
private data class CloudBridge(
    val id: String? = null,
    val internalipaddress: String? = null,
    val name: String? = null,
)

/**
 * A Hue Bridge found on the network, by whichever of the three methods the spec
 * lists: mDNS, the cloud discovery endpoint, or the user typing an address.
 * [bridgeId] is empty for a manually entered one, since nothing has told us what
 * it is yet.
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
    /**
     * The bridge id the certificate's Common Name must match, when it is known.
     * A lambda rather than a value because the client is built before pairing,
     * and the id arrives from discovery afterwards.
     */
    private val expectedBridgeId: () -> String = { "" },
    private val http: OkHttpClient = createHttpClient(context, expectedBridgeId),
) {
    private val json = Json { ignoreUnknownKeys = true }

    // ── Discovery ─────────────────────────────────────────────────────────

    private val _discovered = MutableStateFlow<List<DiscoveredBridge>>(emptyList())
    val discovered: StateFlow<List<DiscoveredBridge>> = _discovered.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    /** Cached cloud-discovery result: (fetched at, bridges). See the 15-minute limit. */
    private var cloudCache: Pair<Long, List<DiscoveredBridge>>? = null

    /**
     * A plain client for discovery.meethue.com. Deliberately *not* [http] —
     * that one trusts only Signify's private bridge roots and checks the CN
     * against a bridge id, neither of which applies to a public web service
     * with an ordinary certificate.
     */
    private val cloudHttp = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

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

    /**
     * Ask Philips' discovery endpoint which bridges are on this network.
     *
     * The spec's second discovery method, after mDNS and before manual entry. It
     * works because the bridge phones home, so the cloud can match this
     * network's public IP to the bridge's local one — which also means it
     * returns nothing for a bridge that has never been online, and nothing at
     * all when the phone is on mobile data.
     *
     * Rate limited to **one request per 15 minutes per client**, so results are
     * cached for that long. Deliberately not called on every scan: mDNS is the
     * primary and this is what fills in when it finds nothing.
     *
     * SSDP is not implemented and should not be — the spec records it as
     * deprecated and disabled since Q2 2022.
     */
    suspend fun discoverViaCloud(): List<DiscoveredBridge> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        cloudCache?.let { (at, bridges) ->
            if (now - at < CLOUD_DISCOVERY_TTL_MS) return@withContext bridges
        }
        try {
            val resp = cloudHttp.newCall(
                Request.Builder().url("https://discovery.meethue.com").get().build()
            ).execute()
            val body = resp.use { it.body?.string() } ?: return@withContext emptyList()
            val found = json.decodeFromString<List<CloudBridge>>(body).mapNotNull { b ->
                val ip = b.internalipaddress ?: return@mapNotNull null
                DiscoveredBridge(
                    host = ip,
                    bridgeId = b.id ?: "",
                    name = b.name ?: "Philips Hue",
                    modelId = "",
                    swVersion = "",
                )
            }
            cloudCache = now to found
            found
        } catch (e: Exception) {
            Log.w(TAG, "Cloud discovery failed: ${e.message}")
            // Cached as empty so a network with no internet does not re-ask on
            // every scan and burn the rate limit.
            cloudCache = now to emptyList()
            emptyList()
        }
    }

    /**
     * Merge cloud results into whatever mDNS has found, preferring mDNS — its
     * entries carry the model id and were resolved on this LAN just now.
     */
    suspend fun addCloudDiscovered() {
        val cloud = discoverViaCloud()
        if (cloud.isEmpty()) return
        val known = _discovered.value
        val extra = cloud.filter { c -> known.none { it.host == c.host } }
        if (extra.isNotEmpty()) _discovered.value = known + extra
    }

    /**
     * Add a bridge the user typed in. The spec's last resort, and a required one:
     * mDNS is blocked on some networks and cloud discovery returns nothing for a
     * bridge that has never been online.
     */
    fun addManual(host: String) {
        val trimmed = host.trim()
        if (trimmed.isBlank()) return
        if (_discovered.value.any { it.host == trimmed }) return
        _discovered.value = _discovered.value + DiscoveredBridge(
            host = trimmed,
            bridgeId = "",  // unknown until the certificate is seen
            name = "Bridge at $trimmed",
            modelId = "",
            swVersion = "",
        )
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
        val configs = body["data"]?.let { json.decodeFromJsonElement<List<EntertainmentConfig>>(it) } ?: emptyList()

        // Resolve each channel's own colour triangle, following the same path
        // the bridge models it on: channel member -> entertainment service ->
        // device -> light. Without this the encoder clamps everything to Gamut C,
        // which is wrong for older bulbs and visibly shifts saturated colours on
        // a mixed room. Best effort — a bridge that will not answer these still
        // gives a working stream on the default gamut.
        val gamuts = try {
            resolveChannelGamuts(host, appKey)
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve per-light gamuts, falling back to Gamut C: ${e.message}")
            emptyMap()
        }
        if (gamuts.isEmpty()) return@withContext configs

        configs.map { config ->
            config.copy(
                channels = config.channels.map { ch ->
                    val rid = ch.members.firstNotNullOfOrNull { it.service?.rid }
                    ch.copy(gamut = gamuts[rid])
                }
            )
        }
    }

    /**
     * Map entertainment-service id to that lamp's gamut triangle.
     *
     * Two extra GETs, once per area listing rather than per frame. One
     * `GET /light` serves both the gamut map and the device-to-light map, so it
     * is not fetched twice.
     */
    private suspend fun resolveChannelGamuts(
        host: String,
        appKey: String,
    ): Map<String, List<Pair<Float, Float>>> {
        val lightsBody = getJson("https://$host/clip/v2/resource/light", appKey)
        val lights = lightsBody["data"]
            ?.let { json.decodeFromJsonElement<List<HueLight>>(it) } ?: return emptyMap()

        val gamutByLight = HashMap<String, List<Pair<Float, Float>>>()
        val lightByDevice = HashMap<String, String>()
        for (light in lights) {
            light.color?.gamut?.let { g ->
                gamutByLight[light.id] = listOf(
                    g.red.x to g.red.y,
                    g.green.x to g.green.y,
                    g.blue.x to g.blue.y,
                )
            }
            val owner = light.owner
            if (owner?.rtype == "device") lightByDevice[owner.rid] = light.id
        }
        if (gamutByLight.isEmpty()) return emptyMap()

        val entBody = getJson("https://$host/clip/v2/resource/entertainment", appKey)
        val services = entBody["data"]
            ?.let { json.decodeFromJsonElement<List<EntertainmentService>>(it) } ?: return emptyMap()

        val out = HashMap<String, List<Pair<Float, Float>>>()
        for (svc in services) {
            val device = svc.owner?.takeIf { it.rtype == "device" }?.rid ?: continue
            val lightId = lightByDevice[device] ?: continue
            gamutByLight[lightId]?.let { out[svc.id] = it }
        }
        return out
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
         * An OkHttp client that validates the bridge the way Philips specifies.
         *
         * Two halves, per the Using HTTPS page. The certificate chain is checked
         * against Signify's two private root CAs, bundled in
         * `res/raw/hue_root_certs.pem` — which was already in the repo and read
         * by nothing. And because the connection is made to an IP rather than a
         * hostname, ordinary hostname verification cannot apply; the spec's
         * substitute is to check the certificate's Subject Common Name against
         * the bridge id, which is what [bridgeIdVerifier] does.
         *
         * What this replaces trusted every certificate and returned true from
         * the hostname verifier — the spec is blunt that disabling validation
         * "must never be used in production", and it meant the application key
         * and the DTLS pre-shared key crossed a connection any device on the LAN
         * could read.
         *
         * No trust-on-first-use. A small number of early bridges shipped
         * self-signed certificates, but the spec records that they have all been
         * updated and says the TOFU logic "can be removed to simplify your app".
         *
         * @param expectedBridgeId when known, the CN must equal it. When blank —
         *   a bridge reached by manual IP, before discovery has named it — the CN
         *   must still look like a bridge id, so a valid Signify certificate for
         *   some *other* device is not accepted either.
         */
        fun createHttpClient(context: Context, expectedBridgeId: () -> String = { "" }): OkHttpClient {
            val trustManager = signifyTrustManager(context)
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(trustManager), java.security.SecureRandom())
            }
            return OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustManager)
                .hostnameVerifier(bridgeIdVerifier(expectedBridgeId))
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        }

        /**
         * A trust manager over the bundled Signify roots.
         *
         * Chain support matters here: the spec notes device certificates are
         * currently signed directly by a root but will likely gain an
         * intermediate, and that only the roots need bundling because the bridge
         * presents the intermediate itself. [TrustManagerFactory] handles that.
         */
        private fun signifyTrustManager(context: Context): X509TrustManager {
            val factory = CertificateFactory.getInstance("X.509")
            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
            context.resources.openRawResource(R.raw.hue_root_certs).use { input ->
                val certs = factory.generateCertificates(input)
                require(certs.isNotEmpty()) { "hue_root_certs.pem contained no certificates" }
                certs.forEachIndexed { i, cert -> keyStore.setCertificateEntry("hue-root-$i", cert) }
            }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(keyStore)
            return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        }

        /** 16 hex characters, the shape of every bridge id. */
        private val BRIDGE_ID = Regex("^[0-9a-fA-F]{16}$")

        private fun bridgeIdVerifier(expected: () -> String) = HostnameVerifier { _, session ->
            val cn = try {
                (session.peerCertificates.firstOrNull() as? X509Certificate)?.let { commonName(it) }
            } catch (e: Exception) {
                null
            } ?: return@HostnameVerifier false
            if (!BRIDGE_ID.matches(cn)) return@HostnameVerifier false
            val want = expected()
            want.isBlank() || cn.equals(want, ignoreCase = true)
        }

        /** Subject Common Name, or null if the certificate has none. */
        internal fun commonName(cert: X509Certificate): String? =
            Regex("CN=([^,]+)").find(cert.subjectX500Principal.name)?.groupValues?.get(1)?.trim()
    }
}