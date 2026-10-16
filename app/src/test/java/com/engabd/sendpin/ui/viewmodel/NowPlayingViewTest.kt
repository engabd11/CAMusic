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

    @Test
    fun `switching to another library hides a paused music assistant stream`() {
        // Played on MA, paused, switched to Navidrome: the mini-player stayed on MA.
        assertEquals(true, NowPlayingView.yieldStep(yielded = false, prevPlaying = false, prevBackend = "ma", playing = false, backend = "subsonic"))
    }

    @Test
    fun `a stream that starts playing afterwards takes the screen back`() {
        assertEquals(false, NowPlayingView.yieldStep(yielded = true, prevPlaying = false, prevBackend = "subsonic", playing = true, backend = "subsonic"))
    }

    @Test
    fun `opening the app on another library is not a switch`() {
        assertEquals(false, NowPlayingView.yieldStep(yielded = false, prevPlaying = false, prevBackend = null, playing = false, backend = "subsonic"))
    }

    @Test
    fun `nothing else changes the answer`() {
        assertEquals(true, NowPlayingView.yieldStep(yielded = true, prevPlaying = true, prevBackend = "subsonic", playing = false, backend = "subsonic"))
        assertEquals(false, NowPlayingView.yieldStep(yielded = false, prevPlaying = false, prevBackend = "subsonic", playing = false, backend = "subsonic"))
    }
}
