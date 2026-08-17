package com.engabd.sendpin.audio

import java.io.DataOutputStream
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The scan file, across the version bump.
 *
 * The format's own comment states the rule these enforce: a user's pre-analysed library
 * is worth more than the code saved by discarding it, so a new format reads the old one
 * rather than stepping over it. Format 2 adds the analyser version and the analysed span
 * to the end of the file; a format 1 file has to keep loading, and has to come back
 * saying what it truthfully is — version 0, and no claim about how much it covered
 * beyond what it recorded.
 */
class TrackScanStoreTest {

    private val dir = File(System.getProperty("java.io.tmpdir"), "scan-store-test-${System.nanoTime()}")

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun scan(
        durationS: Float = 200f,
        analysedS: Float = 200f,
        version: Int = TrackScan.ANALYSER_VERSION,
        key: MusicalKey? = null,
    ) = TrackScan(
        durationS = durationS,
        bpm = 128f,
        confidence = 0.75f,
        beats = FloatArray(12) { it * 0.46875f },
        accents = FloatArray(12) { 1f },
        downbeat = 2,
        sections = listOf(ScanSection(0f, 60f, 1f), ScanSection(60f, durationS, 1f)),
        intensity = null,
        melbankRef = FloatArray(0),
        analyserVersion = version,
        analysedS = analysedS,
        key = key,
    )

    @Test
    fun `a scan survives the trip to disk and back`() {
        TrackScanStore(dir).save("track-1", scan())

        // A second store, so the answer comes off the disk rather than out of the
        // first one's memory cache.
        val loaded = TrackScanStore(dir).load("track-1")

        assertNotNull(loaded)
        assertEquals(200f, loaded.durationS)
        assertEquals(128f, loaded.bpm)
        assertEquals(0.75f, loaded.confidence)
        assertEquals(2, loaded.downbeat)
        assertEquals(12, loaded.beats.size)
        assertEquals(TrackScan.ANALYSER_VERSION, loaded.analyserVersion)
        assertEquals(200f, loaded.analysedS)
        assertTrue(loaded.complete)
        assertFalse(loaded.outdated)
    }

    @Test
    fun `a track's musical key survives the trip to disk and back`() {
        val key = MusicalKey(tonic = 4, mode = MusicalMode.MAJOR, confidence = 0.81f)
        TrackScanStore(dir).save("keyed", scan(key = key))

        val loaded = TrackScanStore(dir).load("keyed")

        assertNotNull(loaded)
        assertNotNull(loaded.key)
        assertEquals(4, loaded.key.tonic)
        assertEquals(MusicalMode.MAJOR, loaded.key.mode)
        assertEquals(0.81f, loaded.key.confidence, 1f / 255f)
    }

    @Test
    fun `a corrupt key byte costs the key, not the whole scan`() {
        TrackScanStore(dir).save("bent", scan(key = MusicalKey(4, MusicalMode.MAJOR, 0.9f)))

        // The key is the last three bytes of the file — tonic, mode, confidence
        // — so this bends the mode into a value no two-element enum has.
        val file = dir.listFiles()!!.single { it.name.endsWith(".scan") }
        val bytes = file.readBytes()
        bytes[bytes.size - 2] = 0x7F
        file.writeBytes(bytes)

        val loaded = TrackScanStore(dir).load("bent")

        assertNotNull(loaded, "a bad key byte must not take the beat grid down with it")
        assertEquals(null, loaded.key, "an out-of-range mode should read as no key")
        assertEquals(128f, loaded.bpm, "everything before the key tail should be intact")
        assertTrue(file.exists(), "the file must survive: deleting it costs hours of re-decoding")
    }

    @Test
    fun `an out-of-range tonic reads as no key`() {
        TrackScanStore(dir).save("bent-tonic", scan(key = MusicalKey(4, MusicalMode.MAJOR, 0.9f)))

        val file = dir.listFiles()!!.single { it.name.endsWith(".scan") }
        val bytes = file.readBytes()
        bytes[bytes.size - 3] = 99 // no pitch class 99
        file.writeBytes(bytes)

        val loaded = TrackScanStore(dir).load("bent-tonic")
        assertNotNull(loaded)
        assertEquals(null, loaded.key)
    }

    @Test
    fun `a scan with no key still round-trips`() {
        TrackScanStore(dir).save("no-key", scan(key = null))
        val loaded = TrackScanStore(dir).load("no-key")
        assertNotNull(loaded)
        assertEquals(null, loaded.key)
    }

    @Test
    fun `a partly analysed track says so after a round trip`() {
        // Forty minutes of music, twelve of analysis — the shape of a DJ set past
        // TrackScanner.MAX_TRACK_S.
        TrackScanStore(dir).save("long-one", scan(durationS = 2400f, analysedS = 720f))

        val loaded = TrackScanStore(dir).load("long-one")

        assertNotNull(loaded)
        assertEquals(720f, loaded.analysedS)
        assertFalse(loaded.complete)
    }

    @Test
    fun `a file written by the previous format still loads`() {
        writeFormat1(File(dir.apply { mkdirs() }, fileNameFor("old-one")))

        val loaded = TrackScanStore(dir).load("old-one")

        assertNotNull(loaded)
        assertEquals(180f, loaded.durationS)
        assertEquals(120f, loaded.bpm)
        assertEquals(4, loaded.beats.size)
        // No version field existed, so it is version 0 — which is exactly what marks it
        // as worth re-reading when the analyser has moved on.
        assertEquals(0, loaded.analyserVersion)
        assertTrue(loaded.outdated)
        // And it never recorded an analysed span, so the only honest reading is that it
        // covers what it claims as its duration.
        assertEquals(180f, loaded.analysedS)
    }

    @Test
    fun `outdated counts the old files and not the current ones`() {
        val store = TrackScanStore(dir)
        store.save("current-1", scan())
        store.save("current-2", scan())
        writeFormat1(File(dir, fileNameFor("old-1")))
        writeFormat1(File(dir, fileNameFor("old-2")))
        writeFormat1(File(dir, fileNameFor("old-3")))

        assertEquals(3, store.outdated(TrackScan.ANALYSER_VERSION))
        assertEquals(5, store.usage().first)
    }

    // ── A hand-written format 1 file ──────────────────────────────────────
    //
    // Written out longhand rather than by keeping the old writer around: the point of
    // the test is that *these bytes* — the ones already on users' phones — still load,
    // and a shared writer could drift with the reader and prove nothing.

    private fun writeFormat1(file: File) {
        DataOutputStream(file.outputStream().buffered()).use { out ->
            out.writeInt(0x43414D53)   // "CAMS"
            out.writeInt(1)            // format
            out.writeFloat(180f)       // durationS
            out.writeFloat(120f)       // bpm
            out.writeFloat(0.6f)       // confidence
            out.writeInt(0)            // downbeat

            out.writeInt(4)            // beats
            repeat(4) { out.writeFloat(it * 0.5f) }
            out.writeInt(4)            // accents, one quantised byte each
            repeat(4) { out.writeByte(255) }

            out.writeInt(1)            // sections
            out.writeFloat(0f); out.writeFloat(180f); out.writeByte(200)

            out.writeBoolean(false)    // no intensity profile
            out.writeInt(0)            // no melbank reference
            // and nothing after it: the two format-2 fields did not exist.
        }
    }

    private fun fileNameFor(key: String): String = TrackScanStore.fileName(key)
}
