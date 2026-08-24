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

    /**
     * Bytes written after the key: the format-4 tail (metre, tuning, one label
     * per section) plus the format-5 tail's leading "has stems" flag, which is
     * the only byte format-5 contributes here since [scan] never sets [stems] —
     * the `if (stems != null)` block writes nothing more when it's false. The key
     * used to be the end of the file and the tests that bend it counted back from
     * there; naming the offset is what stops the next tail from silently making
     * them bend the wrong byte and pass for the wrong reason.
     */
    private val tailAfterKey = 2 + 2 + 1  // beatsPerBar + tuning + two sections' labels + no-stems flag

    @Test
    fun `a corrupt key byte costs the key, not the whole scan`() {
        TrackScanStore(dir).save("bent", scan(key = MusicalKey(4, MusicalMode.MAJOR, 0.9f)))

        // The key is three bytes — tonic, mode, confidence — sitting just before
        // the format-4 tail. This bends the mode into a value no two-element
        // enum has.
        val file = dir.listFiles()!!.single { it.name.endsWith(".scan") }
        val bytes = file.readBytes()
        bytes[bytes.size - tailAfterKey - 2] = 0x7F
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
        bytes[bytes.size - tailAfterKey - 3] = 99 // no pitch class 99
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
    fun `a scan's stem profile survives the trip to disk and back`() {
        val stems = StemProfile(
            listOf(
                SectionStems(vocals = 0.8f, stereoWidth = 0.2f, bass = 0.5f),
                SectionStems(vocals = 0.1f, stereoWidth = 0.9f, bass = 0.3f),
            ),
        )
        TrackScanStore(dir).save("stemmed", scan().copy(stems = stems))

        val loaded = TrackScanStore(dir).load("stemmed")

        assertNotNull(loaded)
        assertNotNull(loaded.stems)
        assertEquals(2, loaded.stems.sections.size)
        assertEquals(0.8f, loaded.stems.sections[0].vocals, 1f / 255f)
        assertEquals(0.2f, loaded.stems.sections[0].stereoWidth, 1f / 255f)
        assertEquals(0.5f, loaded.stems.sections[0].bass, 1f / 255f)
        assertEquals(0.9f, loaded.stems.sections[1].stereoWidth, 1f / 255f)
    }

    @Test
    fun `a scan with no stem profile round-trips as null`() {
        TrackScanStore(dir).save("no-stems", scan())
        val loaded = TrackScanStore(dir).load("no-stems")
        assertNotNull(loaded)
        assertEquals(null, loaded.stems)
    }

    @Test
    fun `a format-4 file written before stem separation still loads, with no stems`() {
        writeFormat4(File(dir.apply { mkdirs() }, fileNameFor("pre-stems")))

        val loaded = TrackScanStore(dir).load("pre-stems")

        assertNotNull(loaded)
        assertEquals(200f, loaded.durationS)
        assertEquals(null, loaded.stems)
        // Reads back exactly as any pre-format-5 scan does: outdated only by
        // ANALYSER_VERSION, which is a separate, unaffected concept from the
        // file format — see TrackScan.ANALYSER_VERSION's own doc.
        assertTrue(loaded.outdated)
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

    /** A hand-written format-4 file — the shape every scan on a phone had before stems. */
    private fun writeFormat4(file: File) {
        DataOutputStream(file.outputStream().buffered()).use { out ->
            out.writeInt(0x43414D53)   // "CAMS"
            out.writeInt(4)            // format

            out.writeFloat(200f)       // durationS
            out.writeFloat(128f)       // bpm
            out.writeFloat(0.75f)      // confidence
            out.writeInt(2)            // downbeat

            out.writeInt(2)            // beats
            out.writeFloat(0f); out.writeFloat(0.46875f)
            out.writeInt(2)            // accents
            out.writeByte(255); out.writeByte(255)

            out.writeInt(1)            // sections
            out.writeFloat(0f); out.writeFloat(200f); out.writeByte(255)

            out.writeBoolean(false)    // no intensity profile
            out.writeInt(0)            // no melbank reference

            out.writeInt(3)            // analyserVersion — pre-stems
            out.writeFloat(200f)       // analysedS

            out.writeBoolean(false)    // no key

            out.writeByte(4)           // beatsPerBar
            out.writeByte(0)           // tuning, half-cents
            out.writeByte(0)           // one section's label
            // and nothing after it: format 4 predates the stems tail entirely.
        }
    }

    private fun fileNameFor(key: String): String = TrackScanStore.fileName(key)
}
