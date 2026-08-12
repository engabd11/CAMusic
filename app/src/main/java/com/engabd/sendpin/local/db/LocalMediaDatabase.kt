package com.engabd.sendpin.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room database for offline downloads and, in future, local media indexing.
 *
 * Version 1 starts with the download table. Migrations are expected as the local
 * media feature grows.
 */
@Database(entities = [DownloadedTrackEntity::class], version = 1)
abstract class LocalMediaDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao

    companion object {
        @Volatile
        private var instance: LocalMediaDatabase? = null

        fun get(context: Context): LocalMediaDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LocalMediaDatabase::class.java,
                    "local_media.db",
                ).build().also { instance = it }
            }
    }
}
