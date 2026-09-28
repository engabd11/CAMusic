package com.engabd.sendpin.scrobble

import com.engabd.sendpin.util.runCatchingCancellable
import com.engabd.sendpin.BuildConfig
import com.engabd.sendpin.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * ListenBrainz, and anything that speaks its API — Maloja, Koito and multi-scrobbler
 * all accept ListenBrainz submissions at their own address, which is why the base
 * URL is a setting and not a constant.
 *
 * A user token is all it needs: no app registration, nothing signed. See
 * https://listenbrainz.readthedocs.io/en/latest/users/api/core.html.
 */
class ListenBrainzClient(
    private val token: String,
    baseUrl: String = DEFAULT_URL,
    private val http: OkHttpClient = Http.base,
) : ScrobbleService {

    override val id: String = ID
    private val base = baseUrl.trim().trimEnd('/').ifBlank { DEFAULT_URL }

    override suspend fun nowPlaying(play: Play) {
        post("/1/submit-listens", payload("playing_now", play))
    }

    override suspend fun submit(play: Play) {
        post("/1/submit-listens", payload("single", play))
    }

    /** The user name this token belongs to, or null when the token is not valid. */
    suspend fun validate(): String? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$base/1/validate-token").header("Authorization", "Token $token").get().build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            val obj = runCatchingCancellable { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return@use null
            if (obj["valid"]?.jsonPrimitive?.booleanOrNull == true) obj["user_name"]?.jsonPrimitive?.contentOrNull else null
        }
    }

    private suspend fun post(path: String, body: JsonObject) = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(base + path)
            .header("Authorization", "Token $token")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val resp = try {
            http.newCall(req).execute()
        } catch (e: IOException) {
            throw ScrobbleException("ListenBrainz unreachable: ${e.message}", retry = true)
        }
        resp.use {
            if (it.isSuccessful) return@withContext
            // 401 is a bad or revoked token and 400 a listen it will never accept; both
            // stay wrong however often they are sent. 429 and 5xx are "not now".
            val retry = it.code == 429 || it.code >= 500
            throw ScrobbleException("ListenBrainz said ${it.code}", retry)
        }
    }

    companion object {
        const val ID = "listenbrainz"
        const val DEFAULT_URL = "https://api.listenbrainz.org"

        /** The submit-listens body — pure, so its shape is testable. */
        fun payload(type: String, play: Play): JsonObject = buildJsonObject {
            put("listen_type", type)
            put("payload", buildJsonArray {
                add(buildJsonObject {
                    // A "playing_now" listen carries no timestamp; the API rejects one.
                    if (type != "playing_now") put("listened_at", play.startedAtMs / 1000)
                    putJsonObject("track_metadata") {
                        put("artist_name", play.artist)
                        put("track_name", play.title)
                        play.album?.takeIf { it.isNotBlank() }?.let { put("release_name", it) }
                        putJsonObject("additional_info") {
                            put("media_player", "CAMusic")
                            put("submission_client", "CAMusic")
                            put("submission_client_version", BuildConfig.VERSION_NAME)
                            if (play.durationMs > 0) put("duration_ms", play.durationMs)
                            play.trackNumber?.let { put("tracknumber", it) }
                        }
                    }
                })
            })
        }
    }
}
