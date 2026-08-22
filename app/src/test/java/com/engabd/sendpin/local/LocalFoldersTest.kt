package com.engabd.sendpin.local

import com.engabd.sendpin.capture.CaptureSilenceWatchdog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The bridge between the folder the user picks (a SAF tree) and the files the scan
 * finds (MediaStore rows).
 *
 * Pinned because these two namespaces share nothing, and the previous version compared
 * them directly — a test that was never true, so picking any folder emptied the
 * library.
 */
class LocalFoldersTest {

    @Test
    fun `a tree document id decomposes into a volume and a path`() {
        val scope = LocalFolders.scopeOf("primary:Music/Rock")
        assertEquals(LocalFolders.PRIMARY_VOLUME, scope?.volumeName)
        assertEquals("Music/Rock/", scope?.relativePath)
    }

    @Test
    fun `a removable volume is lower-cased to match MediaStore`() {
        // The document id carries the FAT serial as printed; MediaStore lower-cases it.
        assertEquals("1aef-1d0f", LocalFolders.scopeOf("1AEF-1D0F:Music")?.volumeName)
    }

    @Test
    fun `the whole volume is an empty path, not a missing scope`() {
        val scope = LocalFolders.scopeOf("primary:")
        assertEquals("", scope?.relativePath)
        assertTrue(LocalFolders.matches(scope!!, LocalFolders.PRIMARY_VOLUME, "Podcasts/"))
    }

    @Test
    fun `a tree this cannot express yields nothing`() {
        // A cloud provider's documents are not in MediaStore at all. The caller treats
        // this as "cannot restrict", not as "matches nothing" — see LocalMediaSource.
        assertNull(LocalFolders.scopeOf("some-opaque-cloud-id"))
        assertNull(LocalFolders.scopeOf(null))
        assertNull(LocalFolders.scopeOf(""))
    }

    @Test
    fun `matching is by path prefix on the same volume`() {
        val scope = LocalFolders.scopeOf("primary:Music")!!
        assertTrue(LocalFolders.matches(scope, LocalFolders.PRIMARY_VOLUME, "Music/Rock/"))
        assertTrue(LocalFolders.matches(scope, LocalFolders.PRIMARY_VOLUME, "Music/"))
        assertFalse(LocalFolders.matches(scope, LocalFolders.PRIMARY_VOLUME, "Podcasts/"))
        assertFalse(LocalFolders.matches(scope, "1aef-1d0f", "Music/Rock/"))
    }

    @Test
    fun `an unknown path only satisfies a whole-volume scope`() {
        // Guessing a file is inside the folder would put unplayable tracks in the
        // library, which is worse than leaving them out.
        val folder = LocalFolders.scopeOf("primary:Music")!!
        val volume = LocalFolders.scopeOf("primary:")!!
        assertFalse(LocalFolders.matches(folder, LocalFolders.PRIMARY_VOLUME, null))
        assertTrue(LocalFolders.matches(volume, LocalFolders.PRIMARY_VOLUME, null))
    }

    @Test
    fun `no folders picked means everything is reachable`() {
        assertTrue(LocalFolders.reachable(emptyList(), LocalFolders.PRIMARY_VOLUME, "Anywhere/"))
    }

    @Test
    fun `what is written is what is read back`() {
        // The bug: the settings screen wrote a JSON array and MusicSources read it with
        // split("|"). Uri.parse never throws, so the whole array became one nonsensical
        // uri and the folder list was never empty and never right.
        val uris = listOf(
            "content://com.android.externalstorage.documents/tree/primary%3AMusic",
            "content://com.android.externalstorage.documents/tree/1AEF-1D0F%3ASongs",
        )
        assertEquals(uris, LocalFolders.decode(LocalFolders.encode(uris)))
    }

    @Test
    fun `an empty or unreadable stored value is no folders, not one bad folder`() {
        assertEquals(emptyList(), LocalFolders.decode(null))
        assertEquals(emptyList(), LocalFolders.decode(""))
        assertEquals(emptyList(), LocalFolders.decode("content://not-a-json-array"))
        assertEquals(emptyList(), LocalFolders.encode(emptyList()).let { LocalFolders.decode(it) })
    }
}

/**
 * Telling "that app will not let us listen" apart from "nothing is playing".
 *
 * The distinction has no platform signal at all — an app that opts out of capture is
 * delivered as bit-exact silence with no error — so this is the only thing standing
 * between an honest message and the lights appearing broken.
 */
class CaptureSilenceWatchdogTest {

    @Test
    fun `any audio at all is audible`() {
        assertEquals(
            CaptureSilenceWatchdog.Verdict.AUDIBLE,
            CaptureSilenceWatchdog.verdict(elapsedMs = 100, nonZeroSamples = 1, somethingIsPlaying = false),
        )
    }

    @Test
    fun `silence says nothing until the stream has had a chance to start`() {
        assertEquals(
            CaptureSilenceWatchdog.Verdict.WARMING,
            CaptureSilenceWatchdog.verdict(elapsedMs = 200, nonZeroSamples = 0, somethingIsPlaying = true),
        )
        assertEquals(
            CaptureSilenceWatchdog.Verdict.WARMING,
            CaptureSilenceWatchdog.verdict(
                elapsedMs = CaptureSilenceWatchdog.VERDICT_MS - 1,
                nonZeroSamples = 0,
                somethingIsPlaying = true,
            ),
        )
    }

    @Test
    fun `silence while the platform hears music means the app opted out`() {
        assertEquals(
            CaptureSilenceWatchdog.Verdict.SILENT_LIKELY_BLOCKED,
            CaptureSilenceWatchdog.verdict(
                elapsedMs = CaptureSilenceWatchdog.VERDICT_MS,
                nonZeroSamples = 0,
                somethingIsPlaying = true,
            ),
        )
    }

    @Test
    fun `silence with nothing playing is not a fault`() {
        assertEquals(
            CaptureSilenceWatchdog.Verdict.SILENT_NOTHING_PLAYING,
            CaptureSilenceWatchdog.verdict(
                elapsedMs = CaptureSilenceWatchdog.VERDICT_MS,
                nonZeroSamples = 0,
                somethingIsPlaying = false,
            ),
        )
    }
}
