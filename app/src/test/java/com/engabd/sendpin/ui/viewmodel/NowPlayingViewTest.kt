package com.engabd.sendpin.ui.viewmodel

import kotlin.test.Test
import kotlin.test.assertEquals

/** Which player Now Playing shows, across library and stream combinations. */
class NowPlayingViewTest {

    @Test
    fun `a local session is shown whatever the library`() {
        assertEquals(NowPlayingView.LOCAL, NowPlayingView.of(localActive = true, backend = "ma", sendspinHere = false))
        assertEquals(NowPlayingView.LOCAL, NowPlayingView.of(localActive = true, backend = "subsonic", sendspinHere = true))
    }

    @Test
    fun `the music assistant library shows its player`() {
        assertEquals(NowPlayingView.MA, NowPlayingView.of(localActive = false, backend = "ma", sendspinHere = false))
    }

    @Test
    fun `another library with nothing playing shows the idle local player`() {
        assertEquals(NowPlayingView.LOCAL, NowPlayingView.of(localActive = false, backend = "subsonic", sendspinHere = false))
    }

    @Test
    fun `music assistant streaming to this phone is shown even with another library open`() {
        // The bug: Navidrome open, a Music Assistant stream playing on the phone, and
        // Now Playing said "Nothing playing" with buttons that drove the empty local player.
        assertEquals(NowPlayingView.MA, NowPlayingView.of(localActive = false, backend = "subsonic", sendspinHere = true))
    }
}
