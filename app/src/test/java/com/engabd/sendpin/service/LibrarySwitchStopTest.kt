package com.engabd.sendpin.service

import com.engabd.sendpin.ma.MaPlayer
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Switching library stops Music Assistant only when the selected player is this phone. */
class LibrarySwitchStopTest {

    private val me = "6d5fe665-6874-307d-8020-09f0602b6671"

    private fun player(id: String, activeOutput: String? = null) =
        MaPlayer(playerId = id, name = id, available = true, powered = true, activeOutputProtocol = activeOutput)

    @Test
    fun `this phone's own player is stopped`() {
        assertTrue(isThisPhonePlayer(me, player(me), me, null))
        assertTrue(isThisPhonePlayer(me, null, me, null))
    }

    @Test
    fun `the MA 2_10 wrapper around this phone is stopped, playing or paused`() {
        assertTrue(isThisPhonePlayer("up0997467a", player("up0997467a", activeOutput = me), me, me))
        assertTrue(isThisPhonePlayer("up0997467a", player("up0997467a"), me, lastOutput = me))
    }

    @Test
    fun `a remote speaker is left playing`() {
        assertFalse(isThisPhonePlayer("kitchen", player("kitchen"), me, null))
        assertFalse(isThisPhonePlayer("upkitchen", player("upkitchen", activeOutput = "kitchen-sendspin"), me, me))
    }

    @Test
    fun `no target or one that cannot be placed is left alone`() {
        assertFalse(isThisPhonePlayer("", null, me, null))
        assertFalse(isThisPhonePlayer("upgone", null, me, me))
        assertFalse(isThisPhonePlayer("up0997467a", player("up0997467a"), me, null))
    }
}
