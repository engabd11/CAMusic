package com.engabd.sendpin.hue

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** A Hue room or zone, reduced to what Wake & Sleep needs: a name and the group to dim. */
data class HueGroup(
    val id: String,
    val name: String,
    val kind: Kind,
    /** The `grouped_light` service that addresses every lamp in the group at once. */
    val groupedLightId: String,
) {
    enum class Kind { ROOM, ZONE }
}

/** What a grouped light is doing now. */
data class HueGroupState(val on: Boolean, val brightness: Double?)

/**
 * CLIP v2 calls that drive a whole room from the bridge itself, with no streaming.
 *
 * This is the path Wake & Sleep uses on purpose: a `grouped_light` PUT with
 * `dynamics.duration` makes the *bridge* do the interpolation, so the phone sends a
 * handful of requests over half an hour rather than holding a DTLS stream open.
 *
 * Takes the HTTP client as a supplier so the pairing client's certificate-pinned
 * OkHttp stays lazy, and the scheme so a test can point it at plain HTTP.
 */
class HueLightsApi(
    private val http: () -> OkHttpClient,
    private val scheme: String = "https",
) {
    /** Every room, then every zone, each with its grouped light. Groups without one are skipped. */
    suspend fun listGroups(host: String, appKey: String): List<HueGroup> = withContext(Dispatchers.IO) {
        parseGroups(get("room", host, appKey), HueGroup.Kind.ROOM) +
            parseGroups(get("zone", host, appKey), HueGroup.Kind.ZONE)
    }

    suspend fun groupState(host: String, appKey: String, groupedLightId: String): HueGroupState? =
        withContext(Dispatchers.IO) { parseState(get("grouped_light/$groupedLightId", host, appKey)) }

    /** PUT one body to a grouped light. Throws on any non-2xx so callers can report the bridge as unreachable. */
    suspend fun putGroup(host: String, appKey: String, groupedLightId: String, body: String) {
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("$scheme://$host/clip/v2/resource/grouped_light/$groupedLightId")
                .header("hue-application-key", appKey)
                .put(body.toRequestBody(JSON_MEDIA))
                .build()
            http().newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw HueBridgeException("HTTP ${resp.code}: ${resp.body?.string()}")
            }
        }
    }

    private fun get(path: String, host: String, appKey: String): JsonObject {
        val req = Request.Builder()
            .url("$scheme://$host/clip/v2/resource/$path")
            .header("hue-application-key", appKey)
            .get().build()
        http().newCall(req).execute().use { resp ->
            if (resp.code == 403) throw HueBridgeException("Bridge rejected the application key (403)")
            if (!resp.isSuccessful) throw HueBridgeException("HTTP ${resp.code}")
            val text = resp.body?.string() ?: throw HueBridgeException("Empty response")
            return Json.parseToJsonElement(text).jsonObject
        }
    }

    companion object {
        private val JSON_MEDIA = "application/json".toMediaType()

        internal fun parseGroups(body: JsonObject, kind: HueGroup.Kind): List<HueGroup> {
            val data = (body["data"] as? JsonArray) ?: return emptyList()
            return data.mapNotNull { el ->
                val o = el.jsonObject
                val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val name = o["metadata"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull ?: id
                val grouped = o["services"]?.jsonArray
                    ?.map { it.jsonObject }
                    ?.firstOrNull { it["rtype"]?.jsonPrimitive?.contentOrNull == "grouped_light" }
                    ?.get("rid")?.jsonPrimitive?.contentOrNull
                    ?: return@mapNotNull null
                HueGroup(id, name, kind, grouped)
            }
        }

        internal fun parseState(body: JsonObject): HueGroupState? {
            val first = (body["data"] as? JsonArray)?.firstOrNull()?.jsonObject ?: return null
            val on = first["on"]?.jsonObject?.get("on")?.jsonPrimitive?.booleanOrNull ?: return null
            val bri = first["dimming"]?.jsonObject?.get("brightness")?.jsonPrimitive?.doubleOrNull
            return HueGroupState(on, bri)
        }

        // ── Request bodies ─────────────────────────────────────────────────

        /** On, at one colour and brightness, reached over [durationMs] (0 = at once). */
        fun bodyColour(x: Double, y: Double, brightness: Double, durationMs: Long): String = buildJsonObject {
            putJsonObject("on") { put("on", true) }
            putJsonObject("dimming") { put("brightness", brightness) }
            putJsonObject("color") { putJsonObject("xy") { put("x", x); put("y", y) } }
            putJsonObject("dynamics") { put("duration", durationMs) }
        }.toString()

        /** On, at a white temperature (mirek) and brightness, reached over [durationMs]. */
        fun bodyWhite(mirek: Int, brightness: Double, durationMs: Long): String = buildJsonObject {
            putJsonObject("on") { put("on", true) }
            putJsonObject("dimming") { put("brightness", brightness) }
            putJsonObject("color_temperature") { put("mirek", mirek) }
            putJsonObject("dynamics") { put("duration", durationMs) }
        }.toString()

        /** Dim only, keeping whatever colour is set. */
        fun bodyDim(brightness: Double, durationMs: Long): String = buildJsonObject {
            putJsonObject("on") { put("on", true) }
            putJsonObject("dimming") { put("brightness", brightness) }
            putJsonObject("dynamics") { put("duration", durationMs) }
        }.toString()

        fun bodyOff(durationMs: Long): String = buildJsonObject {
            putJsonObject("on") { put("on", false) }
            putJsonObject("dynamics") { put("duration", durationMs) }
        }.toString()
    }
}

/** Kelvin to the mirek the bridge speaks, clamped to the 153..500 range every Hue lamp accepts. */
fun kelvinToMirek(kelvin: Int): Int = (1_000_000 / kelvin).coerceIn(153, 500)
