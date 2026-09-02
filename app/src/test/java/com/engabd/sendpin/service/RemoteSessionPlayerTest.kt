package com.engabd.sendpin.service

import androidx.media3.common.Player
import com.engabd.sendpin.audio.LocalTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [RemoteSessionPlayer] wraps [SimpleBasePlayer][androidx.media3.common.SimpleBasePlayer],
 * which needs a real `Looper` and so cannot be built in a plain JUnit test — these
 * pin down its pure mapping logic instead: the repeat-mode translation both
 * directions have to agree with each other or a lock-screen repeat tap would settle
 * on a different mode than the one it asked for, and the artwork-by-URL match is
 * what stops a queue edit racing an in-flight cover fetch from showing the wrong
 * track's art.
 */
class RemoteSessionPlayerTest {

    private fun track(id: String = "1", artUrl: String? = null) =
        LocalTrack(id = id, title = "Song", artUrl = artUrl)

    @Test
    fun `app repeat modes map onto media3's`() {
        assertEquals(Player.REPEAT_MODE_OFF, RemoteSessionPlayer.repeatModeToPlayer("off"))
        assertEquals(Player.REPEAT_MODE_ALL, RemoteSessionPlayer.repeatModeToPlayer("all"))
        assertEquals(Player.REPEAT_MODE_ONE, RemoteSessionPlayer.repeatModeToPlayer("one"))
    }

    /** An unrecognised mode is treated as off rather than crashing the session. */
    @Test
    fun `an unknown app mode falls back to off`() {
        assertEquals(Player.REPEAT_MODE_OFF, RemoteSessionPlayer.repeatModeToPlayer("shuffle"))
    }

    @Test
    fun `media3 repeat modes map back onto the app's`() {
        assertEquals("off", RemoteSessionPlayer.repeatModeToApp(Player.REPEAT_MODE_OFF))
        assertEquals("all", RemoteSessionPlayer.repeatModeToApp(Player.REPEAT_MODE_ALL))
        assertEquals("one", RemoteSessionPlayer.repeatModeToApp(Player.REPEAT_MODE_ONE))
    }

    /**
     * The two directions have to be inverses, or a lock-screen repeat tap that
     * reports its new mode straight back through `getState()` would show a
     * different one than what was just requested.
     */
    @Test
    fun `repeat mode mapping round-trips`() {
        for (mode in listOf("off", "all", "one")) {
            assertEquals(mode, RemoteSessionPlayer.repeatModeToApp(RemoteSessionPlayer.repeatModeToPlayer(mode)))
        }
    }

    @Test
    fun `a track whose art matches the loaded url gets the bytes`() {
        val bytes = byteArrayOf(1, 2, 3)
        val t = track(artUrl = "mpd-art://song/42")
        assertEquals(bytes, RemoteSessionPlayer.artworkBytesFor(t, "mpd-art://song/42", bytes))
    }

    @Test
    fun `a track with a different art url gets nothing`() {
        val bytes = byteArrayOf(1, 2, 3)
        val t = track(artUrl = "mpd-art://song/other")
        assertNull(RemoteSessionPlayer.artworkBytesFor(t, "mpd-art://song/42", bytes))
    }

    /** No fetch has completed yet — nothing to show, whatever URL is being awaited. */
    @Test
    fun `no bytes loaded yet means no artwork for anyone`() {
        val t = track(artUrl = "mpd-art://song/42")
        assertNull(RemoteSessionPlayer.artworkBytesFor(t, "mpd-art://song/42", null))
    }

    /** A track with no art of its own never shows another track's cover. */
    @Test
    fun `a track with no art url never borrows another track's cover`() {
        val bytes = byteArrayOf(1, 2, 3)
        val t = track(artUrl = null)
        assertNull(RemoteSessionPlayer.artworkBytesFor(t, null, bytes))
    }

    /**
     * Two tracks from the same album share one fetched cover — a deliberate
     * consequence of matching by URL rather than by queue position, not a bug: it
     * means the art doesn't blink out between two tracks off the same release.
     */
    @Test
    fun `two tracks sharing an art url both show it`() {
        val bytes = byteArrayOf(9)
        val a = track(id = "a", artUrl = "mpd-art://album/1")
        val b = track(id = "b", artUrl = "mpd-art://album/1")
        assertEquals(bytes, RemoteSessionPlayer.artworkBytesFor(a, "mpd-art://album/1", bytes))
        assertEquals(bytes, RemoteSessionPlayer.artworkBytesFor(b, "mpd-art://album/1", bytes))
    }
}
