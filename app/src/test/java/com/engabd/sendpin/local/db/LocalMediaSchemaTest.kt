package com.engabd.sendpin.local.db

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The v5 → v6 migration SQL, checked against Room's own exported schema — on a plain
 * JVM, with no device.
 *
 * `LocalMediaDatabaseMigrationTest` is the authoritative check: it runs the migration
 * against real SQLite and calls `runMigrationsAndValidate`, which compares field by
 * field and index by index. But it is an **instrumented** test, and this project's CI
 * (`.github/workflows/ci.yml`) runs `testMobileDebugUnitTest` and `testTvDebugUnitTest`
 * with no emulator anywhere. So on an ordinary push the one change in this feature
 * that can destroy a user's download index — gigabytes of files the app would no
 * longer know it had — was going entirely unguarded.
 *
 * This closes that. It compares the hand-written statements in
 * [LocalMediaDatabase.Companion.MIGRATION_5_6_SQL] against the `createSql` Room
 * generated into `app/schemas/…/6.json`, which is the same text Room builds its
 * runtime validation from. A column whose type or nullability drifts from the entity
 * fails here, in seconds, on every build.
 *
 * It cannot replace the instrumented test — it checks the statements, not the state
 * of a database that has actually been through v1..v5 — which is why both exist.
 */
class LocalMediaSchemaTest {

    @Test
    fun `every v6 table and index matches Room's exported schema`() {
        val schema = readSchema()
        val entities = schema.substringAfter("\"entities\"")

        // Only the tables this migration introduces. The others were created by
        // earlier migrations and are not this one's to assert.
        val newTables = listOf("downloaded_playlists", "downloaded_playlist_tracks")

        for (table in newTables) {
            val expected = createSqlFor(entities, table)
            assertNotNull(expected, "6.json has no createSql for `$table` — did the entity move?")
            val normalised = normalise(expected)
            val found = LocalMediaDatabase.MIGRATION_5_6_SQL.any { normalise(it) == normalised }
            assertTrue(
                found,
                "MIGRATION_5_6 does not create `$table` the way Room expects.\n" +
                    "  Room wants: $normalised\n" +
                    "  Migration has:\n" +
                    LocalMediaDatabase.MIGRATION_5_6_SQL.joinToString("\n") { "    " + normalise(it) },
            )
        }
    }

    @Test
    fun `the membership indices Room expects are created`() {
        val schema = readSchema()
        // Both indices exist for a reason — every playlist read joins on playlistId,
        // and deleting a track prunes by trackId — so a missing one is a silent
        // full-table scan rather than an error.
        for (index in listOf(
            "index_downloaded_playlist_tracks_playlistId",
            "index_downloaded_playlist_tracks_trackId",
        )) {
            assertTrue(
                schema.contains(index),
                "6.json no longer declares `$index` — the entity's @Index changed",
            )
            assertTrue(
                LocalMediaDatabase.MIGRATION_5_6_SQL.any { it.contains(index) },
                "MIGRATION_5_6 never creates `$index`",
            )
        }
    }

    @Test
    fun `the migration is additive`() {
        // The rows are the index of files already on the phone. A DROP or an ALTER of
        // an existing table here would strand them — see the note on
        // [LocalMediaDatabase] — so the shape of this migration is asserted, not just
        // its output.
        for (sql in LocalMediaDatabase.MIGRATION_5_6_SQL) {
            val upper = sql.uppercase()
            assertTrue(!upper.contains("DROP"), "v6 must never drop anything: $sql")
            assertTrue(!upper.contains("DELETE"), "v6 must never delete anything: $sql")
            assertTrue(
                upper.startsWith("CREATE TABLE") || upper.startsWith("CREATE INDEX"),
                "v6 statements should only create: $sql",
            )
        }
    }

    @Test
    fun `the declared database version matches the exported schema`() {
        assertEquals(
            6,
            readSchema().substringAfter("\"version\":").substringBefore(",").trim().toInt(),
            "6.json is not version 6",
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /**
     * Room's exported schema for version 6.
     *
     * Located by walking up from the working directory, which Gradle sets to the
     * module for unit tests but which is worth not depending on — a run from the repo
     * root should find it too.
     */
    private fun readSchema(): String {
        val relative = "schemas/com.engabd.sendpin.local.db.LocalMediaDatabase/6.json"
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "app/$relative"))) {
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "Could not find $relative. It is generated by the Room Gradle plugin " +
                "(`room { schemaDirectory(...) }` in app/build.gradle.kts) and is committed.",
        )
    }

    /** The `createSql` for one table, with Room's placeholder substituted. */
    private fun createSqlFor(entities: String, table: String): String? {
        val marker = "\"tableName\": \"$table\""
        val at = entities.indexOf(marker)
        if (at < 0) return null
        val key = "\"createSql\": \""
        val start = entities.indexOf(key, at)
        if (start < 0) return null
        val from = start + key.length
        val end = entities.indexOf("\",", from)
        if (end < 0) return null
        return entities.substring(from, end)
            .replace("\\u0060", "`")
            .replace("\\\"", "\"")
            .replace("\${TABLE_NAME}", table)
    }

    /**
     * Compare SQL by its tokens rather than its formatting.
     *
     * The migration is written across several Kotlin string concatenations for
     * readability and Room emits one line; without this the test would fail on
     * whitespace, which is exactly the kind of false alarm that gets a test deleted.
     */
    private fun normalise(sql: String): String =
        sql.replace(Regex("\\s+"), " ").replace(" )", ")").replace("( ", "(").trim()
}
