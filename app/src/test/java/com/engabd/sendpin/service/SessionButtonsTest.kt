package com.engabd.sendpin.service

import kotlin.test.Test
import kotlin.test.assertEquals

/** Which extra media buttons are drawn, for each setting and track. */
class SessionButtonsTest {

    @Test
    fun `off draws nothing extra`() {
        assertEquals(emptyList(), SessionButtons.shown(SessionButtons.OFF, canFavourite = true))
        assertEquals(emptyList(), SessionButtons.shown("nonsense", canFavourite = true))
    }

    @Test
    fun `each choice draws its two buttons in order`() {
        assertEquals(listOf(SessionButton.FAVOURITE, SessionButton.SHUFFLE), SessionButtons.shown(SessionButtons.FAV_SHUFFLE, true))
        assertEquals(listOf(SessionButton.FAVOURITE, SessionButton.REPEAT), SessionButtons.shown(SessionButtons.FAV_REPEAT, true))
        assertEquals(listOf(SessionButton.SHUFFLE, SessionButton.REPEAT), SessionButtons.shown(SessionButtons.SHUFFLE_REPEAT, true))
    }

    @Test
    fun `no heart for a track nothing can favourite`() {
        assertEquals(listOf(SessionButton.SHUFFLE), SessionButtons.shown(SessionButtons.FAV_SHUFFLE, canFavourite = false))
        assertEquals(listOf(SessionButton.SHUFFLE, SessionButton.REPEAT), SessionButtons.shown(SessionButtons.SHUFFLE_REPEAT, canFavourite = false))
    }

    @Test
    fun `every offered choice is understood`() {
        SessionButtons.CHOICES.forEach { SessionButtons.chosen(it) }
        assertEquals(4, SessionButtons.CHOICES.size)
    }
}
