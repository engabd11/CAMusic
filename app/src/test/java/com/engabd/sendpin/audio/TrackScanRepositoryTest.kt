package com.engabd.sendpin.audio

import com.engabd.sendpin.download.DownloadedTrack
import com.engabd.sendpin.library.MusicSources
import com.engabd.sendpin.ma.MaItem
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The analysis queue's promises to whoever asks it for a scan.
 *
 * The repository itself needs a Context, so what is pinned here is the pure part of
 * each promise: every way a request can end says so (nobody is left on "analysing"),
 * a scan that was not saved is never announced, scans filed under the old key are
 * still found, and a track given up on stays given up on across a restart, for a week.
 */
class TrackScanRepositoryTest {

    private val dir = File(System.getProperty("java.io.tmpdir"), "scan-repo-test-${System.nanoTime()}")

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun scan() = TrackScan(
        durationS = 200f,
        bpm = 128f,
        confidence = 0.75f,
        beats = FloatArray(12) { it * 0.46875f },
        accents = FloatArray(12) { 1f },
        downbeat = 0,
        sections = listOf(ScanSection(0f, 200f, 1f)),
        intensity = null,
        melbankRef = FloatArray(0),
    )

    private fun navidrome(id: String) =
        LocalTrack(id = id, title = "Song", scrobbleId = id, scrobbleProvider = "subsonic")

    // ── Every request ends ─────────────────────────────────────────────────

    @Test
    fun `pre-scan switched off ends the request as unavailable`() {
        val end = TrackScanRepository.admit(prescanOn = false, scanned = false, givenUp = false)
        assertEquals(false, end.landed)
    }

    @Test
    fun `a track given up on ends the request as unavailable`() {
        val end = TrackScanRepository.admit(prescanOn = true, scanned = false, givenUp = true)
        assertEquals(false, end.landed)
    }

    @Test
    fun `a scan already stored is announced as landed, even with pre-scan off`() {
        assertEquals(true, TrackScanRepository.admit(prescanOn = false, scanned = true, givenUp = false).landed)
        assertEquals(true, TrackScanRepository.admit(prescanOn = true, scanned = true, givenUp = true).landed)
    }

    @Test
    fun `only a request that is queued is left unannounced, and only for now`() {
        val end = TrackScanRepository.admit(prescanOn = true, scanned = false, givenUp = false)
        assertEquals(TrackScanRepository.Outcome.RETRY, end)
        assertNull(end.landed)
    }

    @Test
    fun `every way a run can finish is announced, and only a retry is not`() {
        // NONE covers no URLs, a failed fetch, a failed decode, a failed save, a skip
        // and a worker exception; PARKED is the Wi-Fi wait.
        assertEquals(true, TrackScanRepository.Outcome.SAVED.landed)
        assertEquals(false, TrackScanRepository.Outcome.NONE.landed)
        assertEquals(false, TrackScanRepository.Outcome.PARKED.landed)
        assertNull(TrackScanRepository.Outcome.RETRY.landed)
    }

    @Test
    fun `a save that fails says so, and leaves nothing to adopt`() {
        // A file where the directory should be: nothing can be written under it.
        dir.parentFile.mkdirs()
        dir.writeText("not a directory")
        val store = TrackScanStore(dir)

        assertFalse(store.save("k", scan()))
        assertNull(store.load("k"))
        assertFalse(store.has("k"))
    }

    @Test
    fun `a save that works says so`() {
        assertTrue(TrackScanStore(dir).save("k", scan()))
    }

    // ── Keys ───────────────────────────────────────────────────────────────

    @Test
    fun `the same id in two libraries is two keys`() {
        val plex = LocalTrack(id = "1234", title = "A", scrobbleId = "1234", scrobbleProvider = "plex")
        val emby = LocalTrack(id = "1234", title = "B", scrobbleId = "1234", scrobbleProvider = "emby")
        assertNotEquals(TrackScanRepository.keyFor(plex), TrackScanRepository.keyFor(emby))
        assertEquals("plex:1234", TrackScanRepository.keyFor(plex))
    }

    @Test
    fun `a track with no library keeps the old key`() {
        val url = LocalTrack(id = "", title = "Stream", streamUrl = "http://x/y.mp3")
        assertEquals("http://x/y.mp3", TrackScanRepository.keyFor(url))
        assertEquals(TrackScanRepository.legacyKeyFor(url), TrackScanRepository.keyFor(url))
    }

    @Test
    fun `a download and its library copy share one key`() {
        val download = DownloadedTrack(id = "abc", title = "Song", filePath = "/x/abc.audio", sourceProvider = "jellyfin")
        val streamed = LocalTrack(id = "abc", title = "Song", scrobbleId = "abc", scrobbleProvider = "jellyfin")
        assertEquals(TrackScanRepository.keyFor(streamed), TrackScanRepository.keyFor(download.toLocalTrack()))
    }

    @Test
    fun `a download indexed before it recorded its library is a Navidrome one`() {
        val old = DownloadedTrack(id = "abc", title = "Song", filePath = "/x/abc.audio")
        assertEquals(TrackScanRepository.keyFor(navidrome("abc")), TrackScanRepository.keyFor(old.toLocalTrack()))
    }

    @Test
    fun `a library listing files a scan under the key playback reads`() {
        val item = MaItem(
            itemId = "abc", provider = "subsonic", name = "Song", uri = null,
            mediaType = "track", subtitle = null, image = null, duration = null,
        )
        assertEquals(TrackScanRepository.keyFor(navidrome("abc")), MusicSources.scanKey(item))
        assertEquals("abc", MusicSources.legacyScanKey(item))
    }

    @Test
    fun `a scan filed under the old key is found under the new one, once`() {
        val track = navidrome("abc")
        val legacy = TrackScanRepository.legacyKeyFor(track)
        val key = TrackScanRepository.keyFor(track)
        TrackScanStore(dir).save(legacy, scan())

        // A fresh store, as after an update: nothing in memory.
        val store = TrackScanStore(dir)
        assertTrue(store.has(key, legacy))
        assertNotNull(store.load(key, legacy))
        assertNotNull(store.peek(key), "kept in memory under the new key")
        // Renamed, not copied: the old name is gone and the new one stands alone.
        assertNotNull(TrackScanStore(dir).load(key))
        assertNull(TrackScanStore(dir).load(legacy))
    }

    @Test
    fun `an old-key file does not overwrite a scan under the new key`() {
        val store = TrackScanStore(dir)
        store.save("abc", scan().copy(bpm = 90f))
        store.save("subsonic:abc", scan())

        assertEquals(128f, TrackScanStore(dir).load("subsonic:abc", "abc")?.bpm)
        // Left alone: with both present, it may be another library's track.
        assertEquals(90f, TrackScanStore(dir).load("abc")?.bpm)
    }

    // ── Failures across restarts ───────────────────────────────────────────

    @Test
    fun `a track given up on stays given up on after a restart`() {
        val file = File(dir, "failures")
        ScanFailureLog(file, now = { 1_000L }).record("subsonic:abc")

        val afterRestart = ScanFailureLog(file, now = { 2_000L })
        assertTrue(afterRestart.givenUp("subsonic:abc"))
        assertFalse(afterRestart.givenUp("subsonic:other"))
    }

    @Test
    fun `a week later it gets another go`() {
        val file = File(dir, "failures")
        ScanFailureLog(file, now = { 0L }).record("k")

        assertTrue(ScanFailureLog(file, now = { ScanFailureLog.RETRY_AFTER_MS - 1 }).givenUp("k"))
        val later = ScanFailureLog(file, now = { ScanFailureLog.RETRY_AFTER_MS })
        assertFalse(later.givenUp("k"))
        // And the expired entry is gone from the file, not merely ignored.
        assertFalse(ScanFailureLog(file, now = { 0L }).givenUp("k"))
    }

    @Test
    fun `asking for a re-scan clears the verdict`() {
        val file = File(dir, "failures")
        val log = ScanFailureLog(file, now = { 0L })
        log.record("a")
        log.record("b")
        log.forget("a")
        assertFalse(ScanFailureLog(file, now = { 0L }).givenUp("a"))
        assertTrue(ScanFailureLog(file, now = { 0L }).givenUp("b"))
        log.forgetAll()
        assertFalse(ScanFailureLog(file, now = { 0L }).givenUp("b"))
    }

    @Test
    fun `an unreadable log costs the memory, not the app`() {
        val file = File(dir, "failures")
        dir.mkdirs()
        file.writeText("garbage\nwith no\ttimes 12 34 56")
        assertFalse(ScanFailureLog(file, now = { 0L }).givenUp("garbage"))
    }
}
