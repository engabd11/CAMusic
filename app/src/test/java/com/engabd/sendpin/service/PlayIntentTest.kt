package com.engabd.sendpin.service

import kotlin.test.Test
import kotlin.test.assertEquals

/** A session's requested play state is a set, never a toggle. */
class PlayIntentTest {

    @Test
    fun `a pause request pauses whether or not anything is playing`() {
        assertEquals(PlayIntent.PAUSE, PlayIntent.of(playWhenReady = false, isPlaying = true))
        assertEquals(PlayIntent.PAUSE, PlayIntent.of(playWhenReady = false, isPlaying = false))
    }

    @Test
    fun `a play request while paused plays`() {
        assertEquals(PlayIntent.PLAY, PlayIntent.of(playWhenReady = true, isPlaying = false))
    }

    @Test
    fun `a play request while already playing does nothing rather than toggling to a pause`() {
        assertEquals(PlayIntent.NOTHING, PlayIntent.of(playWhenReady = true, isPlaying = true))
    }
}
