package com.engabd.sendpin.util

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Crash-safe small state files: readable ones read, unreadable ones kept aside. */
class DurableFileTest {

    private fun tempDir(): File = Files.createTempDirectory("durable").toFile()

    @Test
    fun `a write is read back`() {
        val f = File(tempDir(), "state.json")
        DurableFile.write(f, "[1,2,3]")
        assertEquals("[1,2,3]", DurableFile.read(f) { it })
        assertFalse(File(f.parentFile, "state.json.tmp").exists())
    }

    @Test
    fun `a missing file reads as nothing`() {
        assertNull(DurableFile.read(File(tempDir(), "none.json")) { it })
    }

    @Test
    fun `an unreadable file is kept aside, not written over`() {
        val dir = tempDir()
        val f = File(dir, "queue.json")
        f.writeText("{ truncated")
        val parsed = DurableFile.read(f) { text -> require(text.endsWith("}")); text }
        assertNull(parsed)
        assertFalse(f.exists())
        val bad = File(dir, "queue.json.bad")
        assertTrue(bad.exists())
        assertEquals("{ truncated", bad.readText())
        // The next write starts fresh beside it and leaves the evidence alone.
        DurableFile.write(f, "[]")
        assertEquals("{ truncated", bad.readText())
    }
}
