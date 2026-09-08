package com.engabd.sendpin.tidal

import com.engabd.sendpin.data.Http
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ma.MaSearchResults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Base64

class TidalException(message: String, val httpCode: Int? = null) : Exception(message) {
    /** A refused token (401 after refresh) is worth re-prompting; a dead host is not. */
    val isAuth: Boolean get() = httpCode == 401 || httpCode == 403
}

/**
 * Tidal's device sign-in flow, the client's own v1 catalog API, and the playback
 * manifest decoder that turns a `playbackinfopostpaywall` answer into a streamable
 * url.
 *
 * Tidal offers no public third-party API; what exists publicly is the **device
 * authorization grant** (RFC 8628) on `auth.tidal.com` — the same flow Tidal's own
 * TV and speaker clients use — and the catalog endpoints those clients call with
 * the resulting token. Music Assistant's provider is the wire reference. App
 * credentials (`client_id`/`client_secret`) are the caller's own Tidal developer
 * registration, handed in like Qobuz's — Music Assistant's pair is theirs.
 *
 * Pure logic ([decodeBtsManifest], [parseDeviceAuth], [tokenFromResponse],
 * [tokenExpired]) is static/`internal` for fixture tests; the HTTP layer is the
 * thin shell the other clients are.
 *
 * v1 stream quality: `HIGH` (AAC 320) answers with a plain-URL BTS manifest that
 * any player can open; `LOSSLESS`/`HI_RES` answers are BTS manifests whose URLs are
 * AES-128-ECB encrypted and need the vendor key to decrypt — supported here via
 * [decodeBtsManifest]'s decrypt step, deferring the v2 (openapi) HiRes flows.
 */
class TidalClient(
    /** The caller's own Tidal developer registration. */
    @Volatile var clientId: String = "",
    @Volatile var clientSecret: String = "",
    /** Access token from the device flow; blank until signed in. */
    @Volatile var accessToken: String = "",
    /** Refresh token; blank until signed in. */
    @Volatile var refreshToken: String = "",
    /** Epoch seconds when [accessToken] expires. */
    @Volatile var tokenExpiresAt: Long = 0,
    /** The signed-in user's id, for favourites endpoints. */
    @Volatile var userId: String = "",
    /** The account's country code, required by most v1 endpoints. */
    @Volatile var countryCode: String = "US",
    private val http: OkHttpClient = shared,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    companion object {
        const val PROVIDER = "tidal"
        const val BASE = "https://api.tidal.com/v1"
        const val AUTH = "https://auth.tidal.com/v1/oauth2"
        const val SCOPE = "r_usr w_usr w_sub"

        /** Shared "magic" key Tidal uses for BTS manifest URLs — public knowledge. */
        private val MANIFEST_KEY = "UIlTTEMmpLfP63qyX5n6KR6F0IwF8GZIfOEOfCtbTL4=".decodeBase64()

        private val shared: OkHttpClient get() = Http.base

        private fun String.decodeBase64(): ByteArray = Base64.getDecoder().decode(this)

        /**
         * Decode a `application/vnd.tidal.bts` manifest: base64 (optionally padded)
         * JSON carrying `urls` (plain https) or `uris` (AES-encrypted, hex) plus the
         * codec and security token. Returns the playable url(s) and codec, or null
         * when the manifest shape is unknown.
         */
        internal fun decodeBtsManifest(manifestB64: String, securityToken: String?): Pair<List<String>, String>? {
            val cleaned = manifestB64.trim().replace("\n", "")
            val padded = cleaned + "=".repeat((4 - cleaned.length % 4) % 4)
            val bytes = try {
                Base64.getMimeDecoder().decode(padded)
            } catch (e: IllegalArgumentException) {
                return null
            }
            val obj = Json.parseToJsonElement(String(bytes)).jsonObject
            val codec = obj["codecs"]?.jsonPrimitive?.contentOrNull ?: return null
            val keyId = obj["keyId"]?.jsonPrimitive?.contentOrNull
            val urls = mutableListOf<String>()
            (obj["urls"] as? JsonArray)?.forEach { el ->
                el.jsonPrimitive.contentOrNull?.let { urls.add(it) }
            }
            (obj["uris"] as? JsonArray)?.forEach { el ->
                val hex = el.jsonPrimitive.contentOrNull ?: return@forEach
                val encrypted = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                urls.add(decryptUri(encrypted, securityToken, keyId))
            }
            return if (urls.isEmpty()) null else urls to codec
        }

        /** AES-128-ECB decrypt of a manifest URI; key = SHA-256(manifestKey + securityToken)[0..15]. */
        private fun decryptUri(encrypted: ByteArray, securityToken: String?, keyId: String?): String {
            val keyMaterial = MANIFEST_KEY + (securityToken ?: "").toByteArray(Charsets.UTF_8)
            val sha = java.security.MessageDigest.getInstance("SHA-256").digest(keyMaterial)
            val cipher = javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(sha, "AES"))
            val plain = cipher.doFinal(encrypted)
            return String(plain, Charsets.UTF_8)
        }

        /** The fields a device-authorization answer must carry, or a readable error. */
        internal fun parseDeviceAuth(obj: JsonObject): Triple<String, String, String> {
            val deviceCode = obj["deviceCode"]?.jsonPrimitive?.contentOrNull
                ?: obj["device_code"]?.jsonPrimitive?.contentOrNull
            val userCode = obj["userCode"]?.jsonPrimitive?.contentOrNull
                ?: obj["user_code"]?.jsonPrimitive?.contentOrNull
            val uri = obj["verificationUriComplete"]?.jsonPrimitive?.contentOrNull
                ?: obj["verification_uri_complete"]?.jsonPrimitive?.contentOrNull
                ?: obj["verificationUri"]?.jsonPrimitive?.contentOrNull
                ?: obj["verification_uri"]?.jsonPrimitive?.contentOrNull
            if (deviceCode == null || userCode == null || uri == null) {
                throw TidalException("Tidal's device sign-in answered an unexpected shape")
            }
            return Triple(deviceCode, userCode, uri)
        }

        /** Auth + user fields out of a token-endpoint answer, normalized. */
        internal fun tokenFromResponse(obj: JsonObject): TokenResponse? {
            val access = obj["access_token"]?.jsonPrimitive?.contentOrNull ?: return null
            val refresh = obj["refresh_token"]?.jsonPrimitive?.contentOrNull
            val expiresIn = obj["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600L
            val userId = obj["user"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
            val country = obj["user"]?.jsonObject?.get("countryCode")?.jsonPrimitive?.contentOrNull
            return TokenResponse(access, refresh, expiresIn, userId, country)
        }

        /** Whether a stored token is expired, with [leewaySeconds] of slack. */
        internal fun tokenExpired(expiresAt: Long, nowSeconds: Long, leewaySeconds: Long = 60): Boolean =
            nowSeconds + leewaySeconds >= expiresAt
    }

    /** Normalized token-endpoint answer: the pair, the expiry, and the user. */
    data class TokenResponse(
        val accessToken: String,
        val refreshToken: String?,
        val expiresIn: Long,
        val userId: String?,
        val countryCode: String?,
    )

    // ── Device sign-in ────────────────────────────────────────────────────

    /**
     * Start the device flow: returns (deviceCode, userCode, verificationUrl). The
     * user opens the URL and enters the code; [pollDeviceLogin] then completes it.
     */
    suspend fun startDeviceLogin(): Triple<String, String, String> = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("scope", SCOPE)
            .build()
        val request = Request.Builder().url("$AUTH/device_authorization").post(body).build()
        http.newCall(request).execute().use { response ->
            val obj = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
            if (response.code != 200) {
                throw TidalException("Tidal's device sign-in could not start (HTTP ${response.code})", response.code)
            }
            parseDeviceAuth(obj)
        }
    }

    /**
     * One poll of the token endpoint. Returns the token data when the user has
     * approved; null while still pending; throws on terminal errors. The caller
     * (UI) owns the interval and the overall deadline, per RFC 8628.
     */
    suspend fun pollDeviceLoginOnce(deviceCode: String): TokenResponse? = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("device_code", deviceCode)
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .add("scope", SCOPE)
            .build()
        val auth = okhttp3.Credentials.basic(clientId, clientSecret)
        val request = Request.Builder().url("$AUTH/token").post(body)
            .header("Authorization", auth).build()
        http.newCall(request).execute().use { response ->
            val obj = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
            val error = obj["error"]?.jsonPrimitive?.contentOrNull
            when {
                response.code == 200 -> tokenFromResponse(obj)
                    ?: throw TidalException("Tidal answered the login without a token")
                // `expired_token` is terminal, not pending: the device code has died
                // and no amount of further polling will mint a token. Lumping it in
                // with the pending errors left the caller looping out its whole
                // five-minute deadline before reporting a timeout, when Tidal had
                // already given the real answer. `slow_down` stays pending — it asks
                // for a longer interval, which is the caller's to lengthen.
                error == "authorization_pending" || error == "slow_down" -> null
                error == "expired_token" ->
                    throw TidalException("That Tidal sign-in code expired — start again")
                else -> throw TidalException("Tidal device sign-in failed: ${error ?: "HTTP ${response.code}"}")
            }
        }
    }

    /**
     * Sign in from stored token data, refreshing when expired. Returns false when
     * the refresh was refused — the caller should start a fresh device flow.
     */
    suspend fun ensureSignedIn(): Boolean = withContext(Dispatchers.IO) {
        if (accessToken.isBlank()) return@withContext false
        if (!tokenExpired(tokenExpiresAt, System.currentTimeMillis() / 1000)) return@withContext true
        if (refreshToken.isBlank()) return@withContext false
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("refresh_token", refreshToken)
            .add("grant_type", "refresh_token")
            .add("scope", SCOPE)
            .build()
        val auth = okhttp3.Credentials.basic(clientId, clientSecret)
        val request = Request.Builder().url("$AUTH/token").post(body)
            .header("Authorization", auth).build()
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext false
                val refreshed = tokenFromResponse(
                    json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject,
                ) ?: return@withContext false
                accessToken = refreshed.accessToken
                refreshed.refreshToken?.let { refreshToken = it }
                tokenExpiresAt = System.currentTimeMillis() / 1000 + refreshed.expiresIn
                true
            }
        } catch (e: java.io.IOException) {
            false
        }
    }

    // ── Catalog & playback ────────────────────────────────────────────────

    private suspend fun get(path: String): JsonObject = withContext(Dispatchers.IO) {
        if (!ensureSignedIn()) throw TidalException("Tidal sign-in expired — sign in again", 401)
        val url = "$BASE/$path${if (path.contains('?')) "&" else "?"}countryCode=$countryCode"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .build()
        http.newCall(request).execute().use { response ->
            when {
                response.code == 401 -> throw TidalException("Tidal session expired — sign in again", 401)
                response.code == 429 -> throw TidalException("Tidal is rate-limiting this app — try again shortly", 429)
                response.code in 500..599 -> throw TidalException("Tidal had a server error (${response.code})", response.code)
                !response.isSuccessful -> throw TidalException("Tidal returned ${response.code} for $path", response.code)
            }
            json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
        }
    }

    /** Favourited tracks (the user's library). */
    suspend fun favoriteTracks(): List<JsonObject> =
        get("users/$userId/favorites/tracks")["items"].jsonArrayItems()

    /** Favourited albums. */
    suspend fun favoriteAlbums(): List<JsonObject> =
        get("users/$userId/favorites/albums")["items"].jsonArrayItems()

    /** Favourited artists. */
    suspend fun favoriteArtists(): List<JsonObject> =
        get("users/$userId/favorites/artists")["items"].jsonArrayItems()

    /** The user's playlists. */
    suspend fun playlists(): List<JsonObject> =
        get("users/$userId/playlists")["items"].jsonArrayItems()

    /** One playlist's tracks. */
    suspend fun playlistTracks(id: String): List<JsonObject> =
        get("playlists/$id/tracks")["items"].jsonArrayItems()

    /** One album's metadata and tracks. */
    suspend fun album(id: String): JsonObject = get("albums/$id")

    suspend fun albumTracks(id: String): List<JsonObject> =
        get("albums/$id/tracks")["items"].jsonArrayItems()

    /** One artist's albums. */
    suspend fun artistAlbums(id: String): List<JsonObject> =
        get("artists/$id/albums")["items"].jsonArrayItems()

    /** One track. */
    suspend fun track(id: String): JsonObject = get("tracks/$id")

    /** Search everything; the answer keys off `type`. */
    suspend fun search(query: String, limit: Int = 20): JsonObject = get(
        "search?query=" + java.net.URLEncoder.encode(query, "UTF-8") + "&limit=$limit",
    )

    /**
     * The playable url for [trackId], walking quality from the top: HI_RES and
     * LOSSLESS need the manifest decrypt; HIGH (AAC 320) answers plain. Null
     * quality means the manifest could not be decoded — try the next tier.
     */
    suspend fun streamUrl(trackId: String): String {
        for (quality in listOf("HI_RES", "LOSSLESS", "HIGH")) {
            val body = get(
                "tracks/$trackId/playbackinfopostpaywall?audioquality=$quality" +
                    "&playbackmode=STREAM&assetpresentation=PREFERRED",
            )
            val manifest = body["manifest"]?.jsonPrimitive?.contentOrNull ?: continue
            val securityToken = body["securityToken"]?.jsonPrimitive?.contentOrNull
            val decoded = decodeBtsManifest(manifest, securityToken) ?: continue
            val url = decoded.first.firstOrNull()
            if (!url.isNullOrBlank()) return url
        }
        throw TidalException("Tidal has no streamable file for track $trackId")
    }

    // ── Parsers (pure; fixture-tested) ────────────────────────────────────

    fun parseTrack(obj: JsonObject, albumContext: MaItem? = null): MaItem? {
        // Favourites endpoints wrap their payload in `item`; plain listings and
        // search do not. Unwrapping here makes every parser accept both shapes.
        val target = obj["item"]?.jsonObject ?: obj
        val id = target["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val title = target["title"]?.jsonPrimitive?.contentOrNull ?: return null
        val artist = target["artist"]?.jsonObject
        val album = target["album"]?.jsonObject
        return MaItem(
            itemId = id,
            provider = PROVIDER,
            name = title + (target["version"]?.jsonPrimitive?.contentOrNull
                ?.let { " ($it)" } ?: ""),
            uri = "https://tidal.com/browse/track/$id",
            mediaType = "track",
            subtitle = artist?.get("name")?.jsonPrimitive?.contentOrNull,
            image = parseImage(obj, album) ?: albumContext?.image,
            duration = target["duration"]?.jsonPrimitive?.intOrNull,
            trackNumber = target["trackNumber"]?.jsonPrimitive?.intOrNull,
            discNumber = target["volumeNumber"]?.jsonPrimitive?.intOrNull,
            album = album?.get("title")?.jsonPrimitive?.contentOrNull ?: albumContext?.name,
            parentId = album?.get("id")?.jsonPrimitive?.contentOrNull ?: albumContext?.itemId,
        )
    }

    fun parseAlbum(obj: JsonObject): MaItem? {
        val target = obj["item"]?.jsonObject ?: obj
        val id = target["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val title = target["title"]?.jsonPrimitive?.contentOrNull ?: return null
        val artist = target["artist"]?.jsonObject
        return MaItem(
            itemId = id,
            provider = PROVIDER,
            name = title,
            uri = "https://tidal.com/browse/album/$id",
            mediaType = "album",
            subtitle = artist?.get("name")?.jsonPrimitive?.contentOrNull,
            image = parseImage(obj),
            duration = null,
            year = target["releaseDate"]?.jsonPrimitive?.contentOrNull?.take(4)?.toIntOrNull(),
            parentId = artist?.get("id")?.jsonPrimitive?.contentOrNull,
        )
    }

    fun parseArtist(obj: JsonObject): MaItem? {
        val target = obj["item"]?.jsonObject ?: obj
        val id = target["id"]?.jsonPrimitive?.contentOrNull ?: return null
        return MaItem(
            itemId = id,
            provider = PROVIDER,
            name = target["name"]?.jsonPrimitive?.contentOrNull ?: return null,
            uri = "https://tidal.com/browse/artist/$id",
            mediaType = "artist",
            subtitle = null,
            image = parseImage(obj),
            duration = null,
        )
    }

    fun parsePlaylist(obj: JsonObject): MaItem? {
        val id = obj["uuid"]?.jsonPrimitive?.contentOrNull ?: obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
        return MaItem(
            itemId = id,
            provider = PROVIDER,
            name = obj["title"]?.jsonPrimitive?.contentOrNull ?: return null,
            uri = "https://tidal.com/browse/playlist/$id",
            mediaType = "playlist",
            subtitle = obj["owner"]?.jsonObject
                ?.get("name")?.jsonPrimitive?.contentOrNull
                ?: obj["description"]?.jsonPrimitive?.contentOrNull,
            image = parseImage(obj),
            duration = obj["duration"]?.jsonPrimitive?.intOrNull,
        )
    }

    /**
     * Tidal art lives at `resources.tidal.com/images/<id>/<w>x<h>.jpg`; the object
     * carries the id pieces, and the caller picks a resolution. A favourited item
     * wraps its payload in `item`, which this unwraps first.
     */
    fun parseImage(obj: JsonObject, album: JsonObject? = null): String? {
        val target = obj["item"]?.jsonObject ?: obj
        val cover = target["cover"]?.jsonPrimitive?.contentOrNull
            ?: album?.get("cover")?.jsonPrimitive?.contentOrNull
            ?: target["picture"]?.jsonPrimitive?.contentOrNull
        if (cover.isNullOrBlank()) return null
        val id = cover.replace("-", "/").replace("_", "/")
        return "https://resources.tidal.com/images/$id/640x640.jpg"
    }

    private fun kotlinx.serialization.json.JsonElement?.jsonArrayItems(): List<JsonObject> =
        (this as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    /** Reusable search mapping onto [MaSearchResults], for [TidalSource]. */
    fun mapSearch(body: JsonObject, limit: Int = 20): MaSearchResults = MaSearchResults(
        artists = body["artists"]?.jsonObject?.get("items").jsonArrayItems().mapNotNull { parseArtist(it) },
        albums = body["albums"]?.jsonObject?.get("items").jsonArrayItems().mapNotNull { parseAlbum(it) },
        tracks = body["tracks"]?.jsonObject?.get("items").jsonArrayItems().take(limit).mapNotNull { parseTrack(it) },
        playlists = body["playlists"]?.jsonObject?.get("items").jsonArrayItems().mapNotNull { parsePlaylist(it) },
    )
}
