package com.engabd.sendpin.lyrics

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LyricTranslationStoreTest {
    private val root = File.createTempFile("lyric-store", "").let { it.delete(); it.mkdirs(); it }
    private val cache = File(root, "cache/lyric-translations")
    private val files = File(root, "files/lyric-translations")

    @AfterTest fun tearDown() {
        root.deleteRecursively()
    }

    private fun put(dir: File, name: String, size: Int = 10, age: Long = 0): File {
        dir.mkdirs()
        return File(dir, name).apply {
            writeText("x".repeat(size))
            setLastModified(1_000_000_000_000L + age)
        }
    }

    @Test
    fun `migration moves files, keeps contents and removes the old folder`() {
        put(cache, "a.json", 5).writeText("""{"lines":["hi"]}""")
        put(cache, "b.json", 7)
        LyricTranslationStore.migrate(cache, files)
        assertFalse(cache.exists())
        assertEquals("""{"lines":["hi"]}""", File(files, "a.json").readText())
        assertTrue(File(files, "b.json").isFile)
    }

    @Test
    fun `migration keeps a file already in the new folder`() {
        put(cache, "a.json").writeText("old")
        put(files, "a.json").writeText("new")
        LyricTranslationStore.migrate(cache, files)
        assertEquals("new", File(files, "a.json").readText())
        assertFalse(cache.exists())
    }

    @Test
    fun `migration with no old folder does nothing`() {
        LyricTranslationStore.migrate(cache, files)
        assertFalse(files.exists())
    }

    @Test
    fun `prune drops the least recently used first, down to the target`() {
        put(files, "old.json", 100, age = 1)
        put(files, "mid.json", 100, age = 2)
        put(files, "new.json", 100, age = 3)
        LyricTranslationStore.prune(files, maxBytes = 250, trimTo = 150)
        assertFalse(File(files, "old.json").exists())
        assertFalse(File(files, "mid.json").exists())
        assertTrue(File(files, "new.json").exists())
    }

    @Test
    fun `prune leaves a folder under the cap alone`() {
        put(files, "a.json", 100)
        put(files, "b.json", 100)
        LyricTranslationStore.prune(files, maxBytes = 250, trimTo = 150)
        assertEquals(2, files.list()!!.size)
    }

    @Test
    fun `touching a file saves it from pruning`() {
        val a = put(files, "a.json", 100, age = 1)
        put(files, "b.json", 100, age = 2)
        put(files, "c.json", 100, age = 3)
        LyricTranslationStore.touch(a)
        LyricTranslationStore.prune(files, maxBytes = 250, trimTo = 150)
        assertTrue(a.exists())
        assertFalse(File(files, "b.json").exists())
    }

    @Test
    fun `summary counts songs and bytes, and clear removes them all`() {
        put(files, "a.json", 30)
        put(files, "b.json", 12)
        val s = LyricTranslationStore.summary(files)
        assertEquals(2, s.count)
        assertEquals(42L, s.bytes)
        assertEquals(2, LyricTranslationStore.clear(files))
        assertEquals(0, LyricTranslationStore.summary(files).count)
    }

    @Test
    fun `summary of a missing folder is empty`() {
        assertEquals(LyricTranslationStore.Summary(0, 0), LyricTranslationStore.summary(files))
        assertEquals(0, LyricTranslationStore.clear(files))
    }
}
