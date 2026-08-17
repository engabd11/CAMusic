package com.engabd.sendpin.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room database for offline downloads and, in future, local media indexing.
 *
 * Version 2 adds a downloaded track's disc number. Migrated rather than rebuilt:
 * the rows are the index of files already on the phone, and dropping the table would
 * strand every one of them — gigabytes on disk that the app would no longer know it
 * had, and no way back but downloading the lot again.
 */
@Database(entities = [DownloadedTrackEntity::class], version = 2)
abstract class LocalMediaDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao

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

        fun get(context: Context): LocalMediaDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LocalMediaDatabase::class.java,
                    "local_media.db",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
