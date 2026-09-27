package com.engabd.sendpin.scrobble

import com.engabd.sendpin.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest

/**
 * Last.fm's scrobbling API (and Libre.fm's, which is the same API at another root).
 *
 * Unlike ListenBrainz it wants the *app* registered: every call is signed with an API
 * key and secret belonging to the app, alongside the user's session. A build carries
 * the pair from gradle properties when it has one (see app/build.gradle.kts); a fork
 * or a plain checkout has none, and the settings page then asks for a pair of the
 * user's own — the same arrangement as Qobuz.
 *
 * Sign-in is `auth.getMobileSession` with the account's username and password, which
 * yields a session key that does not expire; the password itself is never stored.
 */
class LastFmClient(
    private val apiKey: String,
    private val apiSecret: String,
    private val sessionKey: String = "",
    apiRoot: String = DEFAULT_ROOT,
    private val http: OkHttpClient = Http.base,
) : ScrobbleService {

    override val id: String = ID
    private val root = apiRoot.trim().ifBlank { DEFAULT_ROOT }.let { if (it.endsWith("/")) it else "$it/" }

    /** Username and password in, a permanent session key and the canonical user name out. */
    suspend fun signIn(username: String, password: String): Pair<String, String> {
        val obj = call(mapOf("method" to "auth.getMobileSession", "username" to username, "password" to password))
        val session = obj["session"]?.jsonObject ?: throw ScrobbleException("Last.fm sign-in returned no session", retry = false)
        val key = session["key"]?.jsonPrimitive?.contentOrNull ?: throw ScrobbleException("Last.fm sign-in returned no key", retry = false)
        val name = session["name"]?.jsonPrimitive?.contentOrNull ?: username
        return key to name
    }

    override suspend fun nowPlaying(play: Play) {
        call(trackParams("track.updateNowPlaying", play, withTimestamp = false))
    }

    override suspend fun submit(play: Play) {
        val obj = call(trackParams("track.scrobble", play, withTimestamp = true))
        // A scrobble can be "accepted" at the HTTP level and still ignored — too old,
        // a filtered artist. That is a verdict on this listen, not a reason to retry.
        val ignored = obj["scrobbles"]?.jsonObject?.get("@attr")?.jsonObject?.get("ignored")?.jsonPrimitive?.intOrNull
        if (ignored != null && ignored > 0) throw ScrobbleException("Last.fm ignored the listen", retry = false)
    }

    private fun trackParams(method: String, play: Play, withTimestamp: Boolean): Map<String, String> = buildMap {
        put("method", method)
        put("artist", play.artist)
        put("track", play.title)
        play.album?.takeIf { it.isNotBlank() }?.let { put("album", it) }
        if (play.durationMs > 0) put("duration", (play.durationMs / 1000).toString())
        play.trackNumber?.let { put("trackNumber", it.toString()) }
        if (withTimestamp) put("timestamp", (play.startedAtMs / 1000).toString())
        put("sk", sessionKey)
    }

    private suspend fun call(params: Map<String, String>): JsonObject = withContext(Dispatchers.IO) {
        val signed = params + ("api_key" to apiKey)
        val form = FormBody.Builder().apply {
            signed.forEach { (k, v) -> add(k, v) }
            add("api_sig", signature(signed, apiSecret))
            add("format", "json")
        }.build()
        val resp = try {
            http.newCall(Request.Builder().url(root).post(form).build()).execute()
        } catch (e: IOException) {
            throw ScrobbleException("Last.fm unreachable: ${e.message}", retry = true)
        }
        resp.use {
            val obj = runCatching { Json.parseToJsonElement(it.body?.string().orEmpty()).jsonObject }.getOrNull()
            val error = obj?.get("error")?.jsonPrimitive?.intOrNull
            if (error != null) {
                val message = obj["message"]?.jsonPrimitive?.contentOrNull ?: "error $error"
                throw ScrobbleException("Last.fm: $message", retry = error in RETRYABLE_ERRORS)
            }
            if (!it.isSuccessful || obj == null) throw ScrobbleException("Last.fm said ${it.code}", retry = it.code >= 500 || it.code == 429)
            obj
        }
    }

    companion object {
        const val ID = "lastfm"
        const val DEFAULT_ROOT = "https://ws.audioscrobbler.com/2.0/"

        /** 11 service offline, 16 temporarily unavailable, 29 rate limited. */
        private val RETRYABLE_ERRORS = setOf(11, 16, 29)

        /**
         * Last.fm's request signature: every parameter except `format` and `callback`,
         * sorted by name, concatenated as name+value, the secret appended, MD5'd.
         */
        fun signature(params: Map<String, String>, secret: String): String {
            val base = params.filterKeys { it != "format" && it != "callback" }
                .toSortedMap()
                .entries.joinToString("") { it.key + it.value } + secret
            return MessageDigest.getInstance("MD5").digest(base.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}
