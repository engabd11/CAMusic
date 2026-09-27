package com.engabd.sendpin.service

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The bundled speed-zone database answers on a real phone — a local street as well
 * as a highway.
 *
 * Instrumented because the question is about the *platform's* SQLite, not the data:
 * the same lookup is exact against the file on a desktop, and the JVM tests cannot
 * see what Android's build of SQLite will and will not run.
 */
@RunWith(AndroidJUnit4::class)
class SpeedLimitLookupInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun openedDatabase(): SpeedLimitDatabase = runBlocking {
        val db = SpeedLimitDatabase(context)
        if (!db.open()) db.prepare()
        withTimeout(120_000) { db.ready.first { it } }
        db
    }

    /**
     * Why the lookup reads the tree by hand. If a future Android ships SQLite with
     * R*Tree this starts failing, and the virtual table could be used again.
     */
    @Test
    fun the_platform_sqlite_has_no_rtree_module() {
        openedDatabase().close()
        val file = File(context.filesDir, "speed_zones.sqlite3")
        val raw = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val failed = runCatching {
                raw.rawQuery("SELECT count(*) FROM speed_zones_rtree", null).use { it.moveToFirst() }
            }.exceptionOrNull()
            assertTrue("rtree ran: ${failed?.message}", failed?.message?.contains("no such module: rtree") == true)
        } finally {
            raw.close()
        }
    }

    @Test
    fun streets_and_highways_both_resolve() = runBlocking {
        val db = openedDatabase()
        try {
            // Midpoints of real zones, from the bundled data.
            assertEquals(50, db.querySpeedLimit(-37.8169385, 145.2151175))
            assertEquals(40, db.querySpeedLimit(-38.165268, 144.3936245))
            assertEquals(60, db.querySpeedLimit(-38.403386, 145.0030585))
            assertEquals(100, db.querySpeedLimit(-36.92485, 144.697906))
        } finally {
            db.close()
        }
    }
}
