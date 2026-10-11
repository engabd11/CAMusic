package com.engabd.sendpin.data

import com.engabd.sendpin.local.db.PlayHistoryEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a backup carries beyond the plain settings, and the history cap. */
class BackupExtrasTest {

    @Test
    fun `every stored type survives the trip with its type`() {
        val values = mapOf<String, Any>(
            "s" to "text", "b" to true, "i" to 42, "l" to 7_000_000_000L,
            "f" to 1.5f, "d" to 2.25, "set" to setOf("b", "a"),
        )
        val back = BackupExtras.storeValues(Json.parseToJsonElement(BackupExtras.store(values).toString()))
        assertEquals(values, back)
        assertEquals(42, back["i"])
        assertTrue(back["l"] is Long)
        assertTrue(back["f"] is Float)
    }

    @Test
    fun `values of a type it cannot carry, or that are not its own, are skipped`() {
        assertNull(BackupExtras.typed(listOf(1, 2)))
        assertNull(BackupExtras.untyped(JsonPrimitive("plain")))
        assertNull(BackupExtras.untyped(Json.parseToJsonElement("""{"t":"mystery","v":1}""")))
        assertEquals(emptyMap(), BackupExtras.storeValues(JsonPrimitive("nope")))
    }

    private fun play(ts: Long, id: String, bpm: Float? = null) = PlayHistoryEntity(
        timestamp = ts, trackId = id, title = "T$id", artist = "A", album = "B", provider = "subsonic",
        streamProvider = null, codec = "flac", sampleRate = 44_100, bitDepth = 16,
        durationPlayedMs = 90_000, durationMs = 120_000, bpm = bpm, keyTonic = null, keyMode = "MINOR", energy = 0.4f,
    )

    @Test
    fun `history goes into one compressed string and comes back whole`() {
        val rows = listOf(play(1_000, "a", bpm = 120f), play(2_000, "b"))
        val encoded = BackupExtras.encodeHistory(rows)
        val back = BackupExtras.decodeHistory(encoded)
        assertEquals(rows, back)
        // Base64 of gzip, not a JSON array a reader could mistake for settings.
        assertFalse(encoded.trimStart().startsWith("{") || encoded.trimStart().startsWith("["))
    }

    @Test
    fun `history is read by column name, so added or missing columns do not break it`() {
        val doc = """{"cols":["trackId","timestamp","title","futureColumn"],"rows":[["x",5,"Song","ignored"],["bad"]]}"""
        val bytes = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(doc.toByteArray()) } }
        val back = BackupExtras.decodeHistory(Base64.getEncoder().encodeToString(bytes.toByteArray()))
        assertEquals(1, back.size)
        assertEquals(5L, back.single().timestamp)
        assertEquals("Song", back.single().title)
        assertEquals("", back.single().artist)
        assertEquals(emptyList(), BackupExtras.decodeHistory("not base64 at all"))
    }

    @Test
    fun `a restored history adds only the plays this phone does not have, each once`() {
        val mine = listOf(play(1_000, "a"))
        val incoming = listOf(play(1_000, "a"), play(2_000, "b"), play(2_000, "b"), play(3_000, "c"))
        val fresh = BackupExtras.newPlays(mine.map(BackupExtras::playKey).toSet(), incoming)
        assertEquals(listOf("b", "c"), fresh.map { it.trackId })
    }

    @Test
    fun `the store list leaves out what belongs to this phone alone`() {
        assertFalse("player_identity" in BackupExtras.STORES)
        assertFalse(BackupExtras.STORES.any { it.contains("car") })
        assertTrue("app_playlists" in BackupExtras.STORES)
    }

    @Test
    fun `history is trimmed on the first play and then one in every 200, to 100,000`() {
        val retention = PlayHistoryRetention(every = 200)
        val due = (1..401).map { retention.dueAfterInsert() }
        assertEquals(listOf(0, 200, 400), due.withIndex().filter { it.value }.map { it.index })
        assertEquals(100_000, PlayHistoryRetention.KEEP)
    }

    @Test
    fun `an older build's import rule skips the extras object whole`() {
        // Every build so far imports only plain values: `element as? JsonPrimitive`.
        val backup = Json.parseToJsonElement("""{"theme":"oled","${BackupExtras.KEY}":{"settings":{}}}""") as JsonObject
        val readByOldBuild = backup.filterValues { it is JsonPrimitive }.keys
        assertEquals(setOf("theme"), readByOldBuild)
    }
}
