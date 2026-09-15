package com.engabd.sendpin.plex

import com.engabd.sendpin.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

/**
 * The plex.tv PIN sign-in.
 *
 * Every other provider's [com.engabd.sendpin.library.AuthStyle] is a password or a
 * token typed straight into a field, so `MusicSources.prepare` can exchange it for a
 * session synchronously. Plex refuses to take a password at all — the account lives
 * at plex.tv, not on the server this phone is about to talk to — and asks instead for
 * a **device code**: this app mints a PIN, opens plex.tv in a browser with that code
 * pre-filled, and polls until the user finishes signing in there. That is a whole
 * flow with a UI of its own, which is why it is a separate object rather than a
 * method on [PlexClient]: [PlexClient] talks to *a server the phone already has an
 * address for*, and this talks to plex.tv before any server is involved at all.
 *
 * The three calls run in the order the settings screen needs them: [requestPin]
 * mints the code, [authUrl] is what to open a browser tab to, and [pollPin] is what
 * to call every couple of seconds afterwards until it returns a token or the caller
 * gives up.
 */
object PlexAuth {

    private const val PINS_URL = "https://plex.tv/api/v2/pins"

    /** The app-wide client: one pool, one cache, one User-Agent. See [Http]. */
    private val http: OkHttpClient get() = Http.base
    private val json = Json { ignoreUnknownKeys = true }

    /** [id] and [code] from a fresh [requestPin] call. */
    data class Pin(val id: Long, val code: String)

    /**
     * Mint a fresh PIN. [clientIdentifier] must be the same value passed to
     * [authUrl] and every [pollPin] call for this PIN — plex.tv ties the three
     * together by it, and a mismatched id here is a PIN that never verifies.
     */
    suspend fun requestPin(clientIdentifier: String): Pin = withContext(Dispatchers.IO) {
        val body = "".toRequestBody(null)
        val request = Request.Builder()
            .url("$PINS_URL?strong=true")
            .post(body)
            .plexHeaders(clientIdentifier)
            .build()
        parsePin(execute(request))
    }

    /**
     * Where to send the user. plex.tv reads `clientID` and `code` from the URL
     * fragment and verifies the PIN silently, so the page that loads is a plain sign
     * in — nothing left for the user to type or confirm beyond their password.
     */
    fun authUrl(pin: Pin, clientIdentifier: String): String = buildString {
        append("https://app.plex.tv/auth#?clientID=").append(enc(clientIdentifier))
        append("&code=").append(enc(pin.code))
        append("&context%5Bdevice%5D%5Bproduct%5D=").append(enc("CAMusic"))
    }

    /**
     * One poll of [pin]. Null while the user hasn't finished signing in yet — that is
     * not a failure, and the caller is expected to call this again after a short
     * delay until it returns a token or the user cancels.
     */
    suspend fun pollPin(pin: Pin, clientIdentifier: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$PINS_URL/${pin.id}")
            .get()
            .plexHeaders(clientIdentifier)
            .build()
        parseAuthToken(execute(request))
    }

    private fun execute(request: Request): JsonObject {
        val body = try {
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw PlexException("plex.tv returned HTTP ${resp.code}", httpCode = resp.code)
                }
                resp.body?.string().orEmpty()
            }
        } catch (e: PlexException) {
            throw e
        } catch (e: Exception) {
            throw PlexException(e.message?.takeIf { it.isNotBlank() } ?: "Couldn't reach plex.tv")
        }
        return try {
            json.parseToJsonElement(body) as? JsonObject
                ?: throw PlexException("plex.tv didn't answer as expected")
        } catch (e: PlexException) {
            throw e
        } catch (_: Exception) {
            throw PlexException("plex.tv didn't answer as expected")
        }
    }

    private fun Request.Builder.plexHeaders(clientIdentifier: String): Request.Builder = this
        .header("Accept", "application/json")
        .header("X-Plex-Client-Identifier", clientIdentifier)
        .header("X-Plex-Product", "CAMusic")
        .header("X-Plex-Device", "Android")
        .header("X-Plex-Platform", "Android")

    // Pure, so a PIN payload can be held against these without a network call —
    // see PlexAuthTest.
    internal fun parsePin(o: JsonObject): Pin {
        val id = o["id"]?.jsonPrimitive?.longOrNull
            ?: throw PlexException("plex.tv didn't return a PIN id")
        val code = o["code"]?.jsonPrimitive?.contentOrNull
            ?: throw PlexException("plex.tv didn't return a PIN code")
        return Pin(id, code)
    }

    internal fun parseAuthToken(o: JsonObject): String? {
        val token = o["authToken"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        // `expiresIn`/expiry aren't surfaced: a PIN that plex.tv has already expired
        // answers exactly like one still pending — no `authToken` — and the caller's
        // own poll timeout is what gives up on it.
        return token
    }

    /** One Plex Media Server on the signed-in account, ready to fill a server address. */
    data class PlexResource(val name: String, val url: String)

    /**
     * Every Plex Media Server on this account — LAN and remote alike.
     *
     * Unlike every other discovery source in `docs/plan/server-mdns-discovery.md`,
     * this needs no LAN scan: [pollPin] already has a plex.tv access [token] the
     * moment sign-in succeeds, and plex.tv's own resource list is one more
     * authenticated GET away. `includeHttps=1` asks it to include each server's
     * `.plex.direct` HTTPS connection alongside the plain HTTP one; `local`/`relay`
     * on each connection is what [parseResource] uses to prefer the LAN address
     * over one that would route through Plex's relay.
     *
     * Best-effort at two levels: the call as a whole returns an empty list rather
     * than throwing on an unreachable network or an unparseable response, and one
     * malformed resource in an otherwise-good response is skipped rather than
     * emptying the whole list — either way, the manual address field is always
     * the fallback the caller already offers.
     *
     * **Unverified against a real plex.tv account** — the response shape (in
     * particular the `local`/`relay` fields per connection) is documented, not
     * confirmed live from this environment. See `docs/plan/server-mdns-discovery.md`.
     */
    suspend fun listResources(token: String, clientIdentifier: String): List<PlexResource> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://plex.tv/api/v2/resources?includeHttps=1")
                .get()
                .plexHeaders(clientIdentifier)
                .header("X-Plex-Token", token)
                .build()
            val body = http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                resp.body?.string().orEmpty()
            }
            val array = json.parseToJsonElement(body) as? JsonArray ?: return@withContext emptyList()
            // One resource at a time, and a malformed one is skipped rather than
            // discarding every other, well-formed server on the account: `.jsonPrimitive`
            // throws (not just returns null) when a field exists but isn't the shape
            // expected, and this response shape is admittedly unverified against a real
            // account — see this function's own doc. Without a per-item boundary, that
            // throw would propagate out of `mapNotNull` into the try/catch below and
            // turn "one odd resource" into "no servers found" for the whole account.
            array.mapNotNull { element ->
                (element as? JsonObject)?.let { obj ->
                    try {
                        parseResource(obj)
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // Pure, so a resources payload can be held against this without a network
    // call — mirrors parsePin/parseAuthToken above.
    internal fun parseResource(o: JsonObject): PlexResource? {
        val provides = o["provides"]?.jsonPrimitive?.contentOrNull ?: return null
        if ("server" !in provides.split(",").map { it.trim() }) return null
        val name = o["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "Plex"
        val connections = (o["connections"] as? JsonArray)?.mapNotNull { it as? JsonObject }
            ?.takeIf { it.isNotEmpty() } ?: return null
        // Prefer a local, non-relay connection — the LAN address this phone can
        // reach directly — falling back to whatever plex.tv offered first.
        val best = connections.firstOrNull {
            it["local"]?.jsonPrimitive?.booleanOrNull == true && it["relay"]?.jsonPrimitive?.booleanOrNull != true
        } ?: connections.first()
        val protocol = best["protocol"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "http"
        val address = best["address"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val port = best["port"]?.jsonPrimitive?.intOrNull ?: return null
        return PlexResource(name, "$protocol://$address:$port")
    }
}

private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
