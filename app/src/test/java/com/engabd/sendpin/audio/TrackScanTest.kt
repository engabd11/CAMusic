package com.engabd.sendpin.audio

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a scan promises the show, and what survives a round trip to disk.
 *
 * The grid tests matter because [TrackScan.gridAt] is queried fifty times a
 * second and its answers drive every scheduled pulse in the room: a beat that
 * fires twice is a double flash, one that fires never is a hole in the bar, and
 * a bar phase off by one is every downbeat landing on the snare.
 */
class TrackScanTest {

    private val temp: File = Files.createTempDirectory("scan-store-test").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    /** Four beats a second... at 120 BPM, from 1.0 s, for [beats] of them. */
    private fun scanOf(
        beats: Int = 40,
        bpm: Float = 120f,
        confidence: Float = 0.7f,
        downbeat: Int = 0,
        key: MusicalKey? = null,
    ): TrackScan {
        val period = 60f / bpm
        return TrackScan(
            durationS = 1f + beats * period,
            bpm = bpm,
            confidence = confidence,
            beats = FloatArray(beats) { 1f + it * period },
            accents = FloatArray(beats) { if (it % 4 == 0) 1f else 0.4f },
            downbeat = downbeat,
            sections = listOf(
                ScanSection(0f, 10f, 0.4f),
                ScanSection(10f, 20f, 0.9f),
            ),
            intensity = IntensityProfile(
                sigLo = 0.2f, sigHi = 0.8f, dynamics = 0.6f, tilt = 0.3f,
                tempo = 0.5f, character = 0.62f,
                curve = floatArrayOf(0f, 0.5f, 1f), curveRateHz = 1f,
            ),
            melbankRef = FloatArray(MELBANK_BINS) { (it + 1) / MELBANK_BINS.toFloat() },
            key = key,
        )
    }

    // ── The grid ───────────────────────────────────────────────────────────

    @Test
    fun `a scheduled beat fires exactly once as the clock sweeps past it`() {
        val scan = scanOf()
        // Step across the beat at 2.0 s in ten-millisecond ticks.
        var previous: Float? = null
        var fired = 0
        var t = 1.90f
        while (t <= 2.10f) {
            val grid = scan.gridAt(t, previous)
            assertNotNull(grid, "no grid at ${t}s")
            if (grid.predictedBeat) fired++
            previous = t
            t += 0.01f
        }
        assertEquals(1, fired, "the beat at 2.0 s should fire once")
    }

    @Test
    fun `phase and time-to-next run the length of the beat`() {
        val scan = scanOf()
        val quarter = scan.gridAt(2.125f, 2.115f)
        assertNotNull(quarter)
        assertEquals(0.25f, quarter.phase, 0.02f, "a quarter of the way through")
        assertEquals(0.375f, quarter.timeToNextBeat, 0.02f)
        assertEquals(120f, quarter.bpm, 0.5f)
        assertTrue(quarter.locked)
        // An offline grid is authoritative; nothing about it is hedged.
        assertEquals(1f, quarter.scheduleStrength)
    }

    @Test
    fun `the bar walks 0 1 2 3 from the downbeat`() {
        val scan = scanOf(downbeat = 1)
        // Beat index 1 is the downbeat, so beats 1,2,3,4 are bar positions 0..3.
        val seen = (1..4).map { i ->
            scan.gridAt(1f + i * 0.5f + 0.1f, null)!!.beatInBar
        }
        assertEquals(listOf(0, 1, 2, 3), seen)
    }

    @Test
    fun `accents describe the beat now and the beat next`() {
        val scan = scanOf(downbeat = 0)
        // Sitting inside beat index 3 (accent 0.4), with index 4 (accent 1.0) next.
        val grid = scan.gridAt(1f + 3 * 0.5f + 0.1f, null)
        assertNotNull(grid)
        assertEquals(0.4f, grid.accentNow, 1e-4f)
        assertEquals(1f, grid.accent, 1e-4f)
    }

    @Test
    fun `a grid the analysis could not trust is not served`() {
        assertFalse(scanOf(confidence = 0.1f).gridUsable)
        assertNull(scanOf(confidence = 0.1f).gridAt(2f, null))
        // Nor is one with almost no beats in it, however confident.
        assertNull(scanOf(beats = 4, confidence = 0.9f).gridAt(2f, null))
    }

    @Test
    fun `outside the analysed span there is no grid`() {
        val scan = scanOf(beats = 20)  // beats run 1.0 s .. 10.5 s
        assertNull(scan.gridAt(-10f, null), "before the track")
        assertNull(scan.gridAt(600f, null), "past the end")
        // Just outside the last beat is still served: the grid extrapolates a
        // few seconds so the final bar does not fall apart.
        assertNotNull(scan.gridAt(12f, null))
    }

    @Test
    fun `a backwards jump does not fire a beat`() {
        val scan = scanOf()
        // A seek: the previous position is later than this one.
        val grid = scan.gridAt(2f, 8f)
        assertNotNull(grid)
        assertFalse(grid.predictedBeat, "a seek must not announce a beat")
    }

    // ── Sections and the profile ───────────────────────────────────────────

    @Test
    fun `sections answer where the playhead is and what is coming`() {
        val scan = scanOf()
        assertEquals(0.4f, scan.sectionAt(5f)?.energy)
        assertEquals(0.9f, scan.sectionAt(15f)?.energy)
        assertNull(scan.sectionAt(50f), "past the last section")

        val (untilS, energy) = scan.nextBoundary(9f)!!
        assertEquals(1f, untilS, 1e-4f)
        assertEquals(0.9f, energy)
        assertNull(scan.nextBoundary(15f), "nothing after the last section")
    }

    @Test
    fun `the intensity signal is sampled ahead of the playhead`() {
        val scan = scanOf()
        // The curve is a 1 Hz ramp 0, 0.5, 1. The lookahead means a query at
        // 1.0 s reads slightly past the midpoint rather than exactly at it.
        val here = scan.intensity!!.sampleAt(1f)!!
        val scheduled = scan.intensitySignalAt(1f)!!
        assertTrue(scheduled > here, "the lookahead should read ahead: $scheduled vs $here")
    }

    @Test
    fun `mood combines tilt and tempo, moderately`() {
        val profile = scanOf().intensity!!
        // tilt 0.3 and tempo 0.5 (neutral): only the tilt term contributes.
        assertEquals(0.03f, profile.mood, 1e-5f)
    }

    // ── Key detection versioning ───────────────────────────────────────────

    /**
     * A deliberate speed bump. The number itself does not matter; being made to
     * come here and change it does, because bumping it is what re-reads a whole
     * library and not bumping it is what leaves an improvement unreachable by
     * every scan already on disk.
     */
    @Test
    fun `the analyser version is pinned so a bump is a decision`() {
        assertEquals(3, TrackScan.ANALYSER_VERSION)
    }

    @Test
    fun `a scan from before key detection is outdated`() {
        val preKey = scanOf().copy(analyserVersion = 1)
        assertTrue(preKey.outdated, "an analyser-version-1 scan should be flagged for re-analysis")
        assertNull(preKey.key)

        val current = preKey.copy(analyserVersion = TrackScan.ANALYSER_VERSION)
        assertFalse(current.outdated)
    }

    // ── The store ──────────────────────────────────────────────────────────

    @Test
    fun `a scan survives the round trip to disk`() {
        val store = TrackScanStore(temp)
        val original = scanOf(key = MusicalKey(tonic = 7, mode = MusicalMode.MINOR, confidence = 0.62f))
        store.save("track-1", original)

        // A fresh store, so the answer cannot come out of the memory map.
        val loaded = TrackScanStore(temp).load("track-1")
        assertNotNull(loaded, "nothing came back")

        assertEquals(original.durationS, loaded.durationS)
        assertEquals(original.bpm, loaded.bpm)
        assertEquals(original.confidence, loaded.confidence)
        assertEquals(original.downbeat, loaded.downbeat)
        assertTrue(original.beats.contentEquals(loaded.beats), "beat times must be exact")
        assertEquals(original.sections.size, loaded.sections.size)
        assertEquals(original.sections[1].startS, loaded.sections[1].startS)

        // Accents, energies, the curve and the melbank reference are stored as
        // bytes — a 1/255 step, well past what a bulb can show.
        for (i in original.accents.indices) {
            assertEquals(original.accents[i], loaded.accents[i], 1f / 255f, "accent $i")
        }
        assertEquals(original.sections[1].energy, loaded.sections[1].energy, 1f / 255f)
        for (i in original.melbankRef.indices) {
            assertEquals(original.melbankRef[i], loaded.melbankRef[i], 1f / 255f, "ref bin $i")
        }

        val profile = loaded.intensity
        assertNotNull(profile)
        assertEquals(original.intensity!!.character, profile.character)
        assertEquals(original.intensity!!.dynamics, profile.dynamics)
        assertEquals(original.intensity!!.curveRateHz, profile.curveRateHz)
        assertEquals(original.intensity!!.curve.size, profile.curve.size)

        val key = loaded.key
        assertNotNull(key, "the key should have survived the round trip")
        assertEquals(7, key.tonic)
        assertEquals(MusicalMode.MINOR, key.mode)
        assertEquals(0.62f, key.confidence, 1f / 255f)
    }

    @Test
    fun `a scan with no key round-trips too`() {
        val store = TrackScanStore(temp)
        store.save("no-key", scanOf(key = null))
        val loaded = TrackScanStore(temp).load("no-key")
        assertNotNull(loaded)
        assertNull(loaded.key)
    }

    @Test
    fun `a format-2 file, written before key detection existed, still reads with no key`() {
        // A hand-rolled format-2 file: everything format 3 added is simply
        // absent, which is exactly what every scan on disk before this
        // release looks like.
        val store = TrackScanStore(temp)
        temp.mkdirs()
        val file = java.io.File(temp, TrackScanStore.fileName("legacy"))
        java.io.DataOutputStream(file.outputStream()).use { out ->
            out.writeInt(0x43414D53)
            out.writeInt(2)
            out.writeFloat(30f); out.writeFloat(120f); out.writeFloat(0.7f)
            out.writeInt(0)
            out.writeInt(0) // beats
            out.writeInt(0) // accents
            out.writeInt(0) // sections
            out.writeBoolean(false) // no intensity profile
            out.writeInt(0) // melbankRef
            out.writeInt(1) // analyserVersion
            out.writeFloat(30f) // analysedS
        }
        val loaded = store.load("legacy")
        assertNotNull(loaded, "a format-2 file must still be readable")
        assertNull(loaded.key)
        assertTrue(loaded.outdated)
    }

    @Test
    fun `a scan with no profile round-trips too`() {
        val store = TrackScanStore(temp)
        val ambient = scanOf().copy(intensity = null, melbankRef = FloatArray(0))
        store.save("ambient", ambient)
        val loaded = TrackScanStore(temp).load("ambient")
        assertNotNull(loaded)
        assertNull(loaded.intensity)
        assertEquals(0, loaded.melbankRef.size)
    }

    @Test
    fun `a file from another format is ignored, not misread`() {
        val store = TrackScanStore(temp)
        store.save("track-1", scanOf())
        val file = File(temp, TrackScanStore.fileName("track-1"))
        assertTrue(file.exists())

        // Bump the version field (bytes 4..7) to something unknown.
        val bytes = file.readBytes()
        bytes[7] = 99
        file.writeBytes(bytes)

        assertNull(TrackScanStore(temp).load("track-1"), "a stale format must not be read")
    }

    @Test
    fun `a truncated file is discarded rather than half-read`() {
        val store = TrackScanStore(temp)
        store.save("track-1", scanOf())
        val file = File(temp, TrackScanStore.fileName("track-1"))
        file.writeBytes(file.readBytes().copyOf(40))

        val fresh = TrackScanStore(temp)
        assertNull(fresh.load("track-1"))
        // And it is cleaned up, so the next play scans instead of failing again.
        assertFalse(file.exists(), "an unreadable scan should be deleted")
    }

    @Test
    fun `garbage in the directory cannot allocate the heap`() {
        // A plausible header followed by an enormous length. Without the bound
        // this asks for a two-billion-element array before noticing.
        val file = File(temp, TrackScanStore.fileName("hostile"))
        temp.mkdirs()
        java.io.DataOutputStream(file.outputStream()).use { out ->
            out.writeInt(0x43414D53)
            out.writeInt(1)
            out.writeFloat(1f); out.writeFloat(1f); out.writeFloat(1f)
            out.writeInt(0)
            out.writeInt(Int.MAX_VALUE)  // "this many beats"
        }
        assertNull(TrackScanStore(temp).load("hostile"))
    }

    @Test
    fun `deleting one leaves the others alone`() {
        val store = TrackScanStore(temp)
        store.save("a", scanOf())
        store.save("b", scanOf())
        assertTrue(store.has("a") && store.has("b"))

        store.delete("a")
        assertFalse(store.has("a"))
        assertTrue(store.has("b"))
        assertNull(store.load("a"), "a deleted scan must not come back from memory")
    }

    @Test
    fun `deleting everything reports what it removed`() {
        val store = TrackScanStore(temp)
        store.save("a", scanOf())
        store.save("b", scanOf())
        val (count, bytes) = store.usage()
        assertEquals(2, count)
        assertTrue(bytes > 0)

        assertEquals(2, store.deleteAll())
        assertEquals(0 to 0L, store.usage())
        assertNull(store.load("a"))
    }

    @Test
    fun `a rescan replaces rather than appends`() {
        val store = TrackScanStore(temp)
        store.save("a", scanOf(bpm = 120f))
        store.save("a", scanOf(bpm = 90f))
        assertEquals(1, store.usage().first, "a rescan must not leave a second file")
        assertEquals(90f, TrackScanStore(temp).load("a")!!.bpm)
    }

    @Test
    fun `keys that are not filenames still get their own file`() {
        // Music Assistant ids carry slashes and colons; a Navidrome one is a
        // bare token. None of them may reach the filesystem as written.
        val awkward = "library://track/12:34?x=1"
        val store = TrackScanStore(temp)
        store.save(awkward, scanOf())
        assertNotNull(TrackScanStore(temp).load(awkward))
        assertFalse(TrackScanStore.fileName(awkward).contains('/'))
        assertFalse(TrackScanStore.fileName(awkward).contains(':'))
    }
}
