package com.engabd.sendpin.local.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The v5 → v6 upgrade, against a real SQLite.
 *
 * This is the one change in the downloaded-playlists work whose failure mode is
 * losing the user's data. `local_media.db` is the index of files already on the
 * phone — gigabytes of them — and Room's response to a migration that does not
 * produce exactly the schema its entities describe is to throw on open. A single
 * wrong column type or a missing index is enough, and none of it shows up at compile
 * time.
 *
 * Instrumented because `MigrationTestHelper` needs a real database; there is no
 * Robolectric in this project, which is why the `room-testing` already in
 * `testImplementation` could not be used for it.
 *
 * Note the assertion is not "it did not throw". `runMigrationsAndValidate` compares
 * the migrated schema against the exported `6.json` field by field and index by
 * index, so passing means the hand-written SQL in
 * [LocalMediaDatabase.Companion.MIGRATION_5_6] agrees with what Room generates.
 */
@RunWith(AndroidJUnit4::class)
class LocalMediaDatabaseMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LocalMediaDatabase::class.java,
    )

    @Test
    fun migrate5To6_preservesDownloadsAndAddsPlaylistTables() {
        val trackId = "nav:track:991"

        helper.createDatabase(TEST_DB, 5).use { db ->
            // A downloaded track, written the way v5 wrote them. The point of the
            // migration is that this row is still here afterwards — dropping and
            // rebuilding the table would strand the file it names on disk with
            // nothing in the app aware of it.
            db.execSQL(
                """
                INSERT INTO downloads
                    (id, title, artist, filePath, image, album, coverPath, durationMs,
                     trackNumber, discNumber, albumId, codec, sampleRate, bitDepth,
                     bitRate, channels, sizeBytes, sourceProvider)
                VALUES
                    ('$trackId', 'Ojos Verdes', 'BALTHVS', '/data/x.audio', NULL,
                     'Transmutations', NULL, 242000, 3, 1, 'nav:album:12', 'flac',
                     44100, 16, 900000, 2, 27000000, 'navidrome')
                """.trimIndent(),
            )
        }

        // Validates the result against the exported 6.json, not just that it ran.
        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            6,
            true,
            LocalMediaDatabase.MIGRATION_5_6,
        )

        db.query(
            "SELECT title, albumId, trackNumber, discNumber FROM downloads WHERE id = ?",
            arrayOf(trackId),
        ).use {
            assertTrue("the v5 download row must survive the upgrade", it.moveToFirst())
            assertEquals("Ojos Verdes", it.getString(0))
            assertEquals("nav:album:12", it.getString(1))
            assertEquals(3, it.getInt(2))
            assertEquals(1, it.getInt(3))
        }

        // The new tables exist and are empty. Empty is correct and deliberate: a
        // download run before v6 recorded no container at all, so there is nothing
        // honest to backfill — an album is not a playlist.
        for (table in listOf("downloaded_playlists", "downloaded_playlist_tracks")) {
            db.query("SELECT COUNT(*) FROM $table").use {
                assertTrue(it.moveToFirst())
                assertEquals("$table should start empty", 0, it.getInt(0))
            }
        }

        // Ordering is the whole reason the membership table exists, so the composite
        // key and the position column are worth asserting are usable rather than
        // merely declared.
        db.execSQL(
            "INSERT INTO downloaded_playlists " +
                "(id, name, sourceProvider, sourceId, image, coverPath, createdAt, updatedAt) " +
                "VALUES ('dlplaylist:navidrome|7', 'Road trip', 'navidrome', '7', NULL, NULL, 1, 2)",
        )
        db.execSQL(
            "INSERT INTO downloaded_playlist_tracks (playlistId, trackId, position) " +
                "VALUES ('dlplaylist:navidrome|7', '$trackId', 0)",
        )
        db.query(
            """
            SELECT d.title FROM downloads d
            INNER JOIN downloaded_playlist_tracks t ON t.trackId = d.id
            WHERE t.playlistId = 'dlplaylist:navidrome|7'
            ORDER BY t.position ASC
            """.trimIndent(),
        ).use {
            assertTrue("the join the playlist DAO relies on must resolve", it.moveToFirst())
            assertEquals("Ojos Verdes", it.getString(0))
        }

        db.close()
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
