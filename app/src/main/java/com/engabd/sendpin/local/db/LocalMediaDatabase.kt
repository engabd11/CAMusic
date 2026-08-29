package com.engabd.sendpin.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room database for offline downloads, listening history, and in future, local
 * media indexing.
 *
 * Version 2 adds a downloaded track's disc number. Version 3 adds [PlayHistoryEntity]
 * for the Stats screen. Version 4 adds a bpm/key/energy snapshot to it, for the Stats
 * screen's Listening DNA section. Migrated rather than rebuilt: the rows are the index
 * of files already on the phone, and dropping the table would strand every one of
 * them — gigabytes on disk that the app would no longer know it had, and no way back
 * but downloading the lot again.
 */
@Database(entities = [DownloadedTrackEntity::class, PlayHistoryEntity::class], version = 5)
abstract class LocalMediaDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
    abstract fun playHistoryDao(): PlayHistoryDao

    companion object {
        @Volatile
        private var instance: LocalMediaDatabase? = null

        /**
         * v1 → v2: `discNumber`, nullable.
         *
         * Null for everything downloaded before this, which reads as "no disc tag" —
         * the same thing a single-disc album says — so existing downloads keep their
         * track ordering and anything downloaded since sorts by disc properly.
         */
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN discNumber INTEGER")
            }
        }

        /** v2 → v3: new, empty `play_history` table — nothing existing to migrate. */
        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `play_history` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `trackId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `artist` TEXT NOT NULL,
                        `album` TEXT NOT NULL,
                        `provider` TEXT NOT NULL,
                        `codec` TEXT,
                        `sampleRate` INTEGER NOT NULL,
                        `bitDepth` INTEGER NOT NULL,
                        `durationPlayedMs` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * v3 → v4: `bpm`/`keyTonic`/`keyMode`/`energy`, all nullable.
         *
         * Null for every row logged before this — Listening DNA simply has nothing
         * to say about plays from before it existed, the same way the format
         * breakdown has nothing to say about a codec nobody recorded.
         */
        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE play_history ADD COLUMN bpm REAL")
                db.execSQL("ALTER TABLE play_history ADD COLUMN keyTonic INTEGER")
                db.execSQL("ALTER TABLE play_history ADD COLUMN keyMode TEXT")
                db.execSQL("ALTER TABLE play_history ADD COLUMN energy REAL")
            }
        }

        /**
         * v4 → v5: `streamProvider` and `durationMs`, both nullable/zero-defaulted.
         *
         * Added because the Stats screen could not answer two questions it looked like
         * it was already answering. Every Music Assistant play was filed under the
         * literal provider "MA" whatever it had actually streamed from, so the server
         * breakdown was one bar; and `durationPlayedMs` had nothing to be a fraction of,
         * so "how much of this did I hear" was unanswerable and skips were invisible.
         *
         * Existing rows keep null and 0, which the screen reads as "not recorded" — the
         * same way the format breakdown treats a codec nobody wrote down.
         */
        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE play_history ADD COLUMN streamProvider TEXT")
                db.execSQL("ALTER TABLE play_history ADD COLUMN durationMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun get(context: Context): LocalMediaDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LocalMediaDatabase::class.java,
                    "local_media.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build().also { instance = it }
            }
    }
}
