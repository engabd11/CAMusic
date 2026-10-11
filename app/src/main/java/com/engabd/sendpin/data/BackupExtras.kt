package com.engabd.sendpin.data

import com.engabd.sendpin.local.db.PlayHistoryEntity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * What a backup holds beyond the plain settings, in one object under [KEY].
 *
 * The export was every DataStore setting that is a string or a switch, and nothing
 * else. So a backup restored onto a new phone came back without the playlists made
 * in the app, the favourites kept on the phone for libraries with none of their own,
 * the lyric timing fixes, the game's records, any number setting (a stored int was
 * silently skipped), and every play the Stats screen had counted.
 *
 * One nested object rather than more top-level keys, because the import in every
 * build so far skips anything that is not a plain value: an older build reads a new
 * backup exactly as before, and a new build reads an old backup with no extras.
 *
 * Pure apart from the [PlayHistoryEntity] type it carries, so the format is tested.
 */
object BackupExtras {
    const val KEY = "__extras_v2"

    /**
     * The SharedPreferences files that are yours and travel with you. Left out on
     * purpose: the player identity (each phone must stay a separate speaker to Music
     * Assistant) and the car connection log (a record of this phone's own Bluetooth).
     */
    val STORES = listOf("app_playlists", "local_favourites", "lyrics_offsets", "game_records")

    // ── Any stored value, with its type ───────────────────────────────────

    /** A value as {"t": type, "v": value}, or null for a type this cannot carry. */
    fun typed(value: Any?): JsonObject? = when (value) {
        is String -> tagged("str", JsonPrimitive(value))
        is Boolean -> tagged("bool", JsonPrimitive(value))
        is Int -> tagged("int", JsonPrimitive(value))
        is Long -> tagged("long", JsonPrimitive(value))
        is Float -> tagged("float", JsonPrimitive(value))
        is Double -> tagged("double", JsonPrimitive(value))
        is Set<*> -> tagged("set", buildJsonArray { value.filterIsInstance<String>().sorted().forEach { add(JsonPrimitive(it)) } })
        else -> null
    }

    /** The value [typed] wrote, or null when it is not one. */
    fun untyped(element: JsonElement?): Any? {
        val o = element as? JsonObject ?: return null
        val v = o["v"] ?: return null
        return when ((o["t"] as? JsonPrimitive)?.contentOrNull) {
            "str" -> (v as? JsonPrimitive)?.contentOrNull
            "bool" -> (v as? JsonPrimitive)?.booleanOrNull
            "int" -> (v as? JsonPrimitive)?.intOrNull
            "long" -> (v as? JsonPrimitive)?.longOrNull
            "float" -> (v as? JsonPrimitive)?.floatOrNull
            "double" -> (v as? JsonPrimitive)?.doubleOrNull
            "set" -> (v as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet()
            else -> null
        }
    }

    private fun tagged(type: String, v: JsonElement) = buildJsonObject { put("t", type); put("v", v) }

    /** A whole store (or any key-value map) as {key: typed}. */
    fun store(values: Map<String, *>): JsonObject = buildJsonObject {
        values.toSortedMap().forEach { (k, v) -> typed(v)?.let { put(k, it) } }
    }

    fun storeValues(element: JsonElement?): Map<String, Any> =
        (element as? JsonObject)?.mapNotNull { (k, v) -> untyped(v)?.let { k to it } }?.toMap().orEmpty()

    // ── Play history: columns, gzipped, base64 ────────────────────────────

    val HISTORY_COLUMNS = listOf(
        "timestamp", "trackId", "title", "artist", "album", "provider", "streamProvider", "codec",
        "sampleRate", "bitDepth", "durationPlayedMs", "durationMs", "bpm", "keyTonic", "keyMode", "energy",
    )

    /**
     * Every play, as one string: a column list and a row of values per play, gzipped
     * and base64'd. A hundred thousand plays come to a few megabytes this way instead
     * of tens, and the whole thing still sits inside the encrypted backup.
     */
    fun encodeHistory(rows: List<PlayHistoryEntity>): String {
        val doc = buildJsonObject {
            put("cols", buildJsonArray { HISTORY_COLUMNS.forEach { add(JsonPrimitive(it)) } })
            put("rows", buildJsonArray {
                rows.forEach { r ->
                    add(buildJsonArray {
                        add(JsonPrimitive(r.timestamp)); add(JsonPrimitive(r.trackId)); add(JsonPrimitive(r.title))
                        add(JsonPrimitive(r.artist)); add(JsonPrimitive(r.album)); add(JsonPrimitive(r.provider))
                        add(JsonPrimitive(r.streamProvider)); add(JsonPrimitive(r.codec))
                        add(JsonPrimitive(r.sampleRate)); add(JsonPrimitive(r.bitDepth))
                        add(JsonPrimitive(r.durationPlayedMs)); add(JsonPrimitive(r.durationMs))
                        add(JsonPrimitive(r.bpm)); add(JsonPrimitive(r.keyTonic)); add(JsonPrimitive(r.keyMode))
                        add(JsonPrimitive(r.energy))
                    })
                }
            })
        }
        val bytes = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(doc.toString().toByteArray()) } }
        return Base64.getEncoder().encodeToString(bytes.toByteArray())
    }

    /**
     * The plays in an [encodeHistory] string, new ids and all. Read by column name, so
     * a later build that adds a column still reads this one, and this one reads that.
     * Rows it cannot make sense of are skipped; a string that is not one gives nothing.
     */
    fun decodeHistory(raw: String): List<PlayHistoryEntity> = runCatching {
        val text = GZIPInputStream(ByteArrayInputStream(Base64.getDecoder().decode(raw))).use { it.readBytes() }.decodeToString()
        val doc = kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject
        val cols = doc["cols"]!!.jsonArray.map { it.jsonPrimitive.content }
        val at = cols.withIndex().associate { (i, c) -> c to i }
        doc["rows"]!!.jsonArray.mapNotNull { rowEl ->
            val row = rowEl as? JsonArray ?: return@mapNotNull null
            fun p(name: String): JsonPrimitive? = at[name]?.let { row.getOrNull(it) }?.takeIf { it !is JsonNull } as? JsonPrimitive
            val ts = p("timestamp")?.longOrNull ?: return@mapNotNull null
            val trackId = p("trackId")?.contentOrNull ?: return@mapNotNull null
            PlayHistoryEntity(
                timestamp = ts,
                trackId = trackId,
                title = p("title")?.contentOrNull.orEmpty(),
                artist = p("artist")?.contentOrNull.orEmpty(),
                album = p("album")?.contentOrNull.orEmpty(),
                provider = p("provider")?.contentOrNull.orEmpty(),
                streamProvider = p("streamProvider")?.contentOrNull,
                codec = p("codec")?.contentOrNull,
                sampleRate = p("sampleRate")?.intOrNull ?: 0,
                bitDepth = p("bitDepth")?.intOrNull ?: 0,
                durationPlayedMs = p("durationPlayedMs")?.longOrNull ?: 0,
                durationMs = p("durationMs")?.longOrNull ?: 0,
                bpm = p("bpm")?.floatOrNull,
                keyTonic = p("keyTonic")?.intOrNull,
                keyMode = p("keyMode")?.contentOrNull,
                energy = p("energy")?.floatOrNull,
            )
        }
    }.getOrDefault(emptyList())

    /** A play's identity across devices: when it was logged and what was playing. */
    fun playKey(row: PlayHistoryEntity): String = "${row.timestamp}|${row.trackId}"

    /** The plays in [incoming] this phone does not have yet, each once. */
    fun newPlays(existingKeys: Set<String>, incoming: List<PlayHistoryEntity>): List<PlayHistoryEntity> {
        val seen = existingKeys.toMutableSet()
        return incoming.filter { seen.add(playKey(it)) }
    }
}

/**
 * How much play history to keep, and how often to trim it.
 *
 * It was 5,000 plays, about a year of an hour a day, and Stats' "all time" quietly
 * forgot everything older; and the trim ran after every single play. A hundred
 * thousand plays is a few years of heavy listening in tens of megabytes, and a trim
 * on one play in two hundred costs the same and runs 200 times less often.
 */
class PlayHistoryRetention(private val every: Int = TRIM_EVERY) {
    private val inserts = java.util.concurrent.atomic.AtomicInteger(0)

    /** Call after each insert: true for the first in a process and then one in [every]. */
    fun dueAfterInsert(): Boolean = inserts.getAndIncrement() % every == 0

    companion object {
        const val KEEP = 100_000
        const val TRIM_EVERY = 200

        /** The process-wide counter both writers of history share. */
        val shared = PlayHistoryRetention()
    }
}
