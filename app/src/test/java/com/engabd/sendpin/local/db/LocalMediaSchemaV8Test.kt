package com.engabd.sendpin.local.db

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The v7 → v8 migration (the radio's `track_signal` table) against Room's exported
 * `8.json`, on the JVM — the same guard [LocalMediaSchemaV7Test] gives v7. The
 * on-device `LocalMediaDatabaseMigrationTest.migrate7To8_addsTrackSignal` runs it
 * against a real SQLite.
 */
class LocalMediaSchemaV8Test {

    private val schema: kotlinx.serialization.json.JsonObject by lazy {
        val text = listOf(
            File("schemas/com.engabd.sendpin.local.db.LocalMediaDatabase/8.json"),
            File("app/schemas/com.engabd.sendpin.local.db.LocalMediaDatabase/8.json"),
        ).first { it.exists() }.readText()
        kotlinx.serialization.json.Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
    }

    private fun entity(table: String): kotlinx.serialization.json.JsonObject {
        val entities = (schema["database"] as kotlinx.serialization.json.JsonObject)["entities"]
            as kotlinx.serialization.json.JsonArray
        return entities.map { it as kotlinx.serialization.json.JsonObject }
            .first { (it["tableName"] as kotlinx.serialization.json.JsonPrimitive).content == table }
    }

    private fun normalise(sql: String) = sql.replace(Regex("\\s+"), " ").trim()

    @Test
    fun `the new table is exactly what Room expects`() {
        val createSql = (entity("track_signal")["createSql"] as kotlinx.serialization.json.JsonPrimitive).content
        val expected = normalise(createSql.replace("\${TABLE_NAME}", "track_signal"))
        assertEquals(listOf(expected), LocalMediaDatabase.MIGRATION_7_8_SQL.map(::normalise))
    }

    @Test
    fun `version 8 adds a table and touches nothing that holds data`() {
        val version = ((schema["database"] as kotlinx.serialization.json.JsonObject)["version"]
            as kotlinx.serialization.json.JsonPrimitive).content
        assertEquals("8", version)
        // Only a CREATE: the downloads index and the play history are left exactly as they were.
        LocalMediaDatabase.MIGRATION_7_8_SQL.forEach {
            assertEquals(true, it.trimStart().startsWith("CREATE TABLE IF NOT EXISTS"))
        }
    }
}
