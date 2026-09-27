package com.engabd.sendpin.local.db

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The v6 → v7 migration (downloads keyed by id *and* provider) against Room's exported
 * `7.json`, on the JVM — the same guard [LocalMediaSchemaTest] gives v6, for the same
 * reason: CI has no emulator, and this migration rebuilds the table that indexes every
 * downloaded file.
 */
class LocalMediaSchemaV7Test {

    private val schema: String by lazy {
        val candidates = listOf(
            File("schemas/com.engabd.sendpin.local.db.LocalMediaDatabase/7.json"),
            File("app/schemas/com.engabd.sendpin.local.db.LocalMediaDatabase/7.json"),
        )
        candidates.first { it.exists() }.readText()
    }

    private fun createSql(table: String): String {
        val at = schema.indexOf("\"tableName\": \"$table\"")
        require(at >= 0) { "7.json has no table $table" }
        val key = "\"createSql\": \""
        val start = schema.indexOf(key, at) + key.length
        return schema.substring(start, schema.indexOf("\",\n", start)).replace("\\\"", "\"")
    }

    private fun normalise(sql: String) = sql.replace(Regex("\\s+"), " ").trim()

    @Test
    fun `the rebuilt downloads table is exactly what Room expects`() {
        val expected = normalise(createSql("downloads").replace("\${TABLE_NAME}", "downloads_v7"))
        val created = LocalMediaDatabase.MIGRATION_6_7_SQL.map(::normalise)
        assertTrue(expected in created, "Room wants:\n  $expected\nmigration creates:\n  ${created.first()}")
    }

    @Test
    fun `legacy null providers are carried over as empty strings, and the old table is replaced`() {
        val sql = LocalMediaDatabase.MIGRATION_6_7_SQL.joinToString("\n")
        assertTrue("COALESCE(`sourceProvider`, '')" in sql)
        assertTrue("DROP TABLE `downloads`" in sql)
        assertTrue("ALTER TABLE `downloads_v7` RENAME TO `downloads`" in sql)
    }

    @Test
    fun `the play history timestamp index matches Room's`() {
        val want = "CREATE INDEX IF NOT EXISTS `index_play_history_timestamp` ON `play_history` (`timestamp`)"
        assertTrue(schema.contains("index_play_history_timestamp"))
        assertEquals(1, LocalMediaDatabase.MIGRATION_6_7_SQL.count { normalise(it) == want })
    }
}
