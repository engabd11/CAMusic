package com.engabd.sendpin.usb

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UsbDacReturnTest {

    private val btd = "3542:3001"

    @Test
    fun `a DAC back and allowed within seconds carries on`() {
        val r = UsbDacReturn()
        r.lost(btd, 1_000)
        r.awaitReturn(1_050)
        assertTrue(r.attached(btd, 2_500, permitted = true))
    }

    @Test
    fun `a DAC needing the dialog carries on once allowed`() {
        val r = UsbDacReturn()
        r.lost(btd, 1_000)
        r.awaitReturn(1_050)
        assertFalse(r.attached(btd, 8_000, permitted = false))
        assertTrue(r.permitted(btd, 15_000))
    }

    @Test
    fun `it carries on once, not at every later plug-in`() {
        val r = UsbDacReturn()
        r.lost(btd, 1_000)
        r.awaitReturn(1_050)
        assertTrue(r.attached(btd, 2_500, permitted = true))
        assertFalse(r.attached(btd, 5_000, permitted = true))
        assertFalse(r.permitted(btd, 5_000))
    }

    @Test
    fun `nothing carries on unless the player paused for the loss`() {
        val r = UsbDacReturn()
        r.lost(btd, 1_000)
        assertFalse(r.attached(btd, 2_500, permitted = true))
    }

    @Test
    fun `a DAC back after the window was unplugged on purpose`() {
        val r = UsbDacReturn(returnWindowMs = 20_000)
        r.lost(btd, 1_000)
        r.awaitReturn(1_050)
        assertFalse(r.attached(btd, 30_000, permitted = true))
    }

    @Test
    fun `a dialog answered too late does not start the music`() {
        val r = UsbDacReturn(permissionWindowMs = 60_000)
        r.lost(btd, 1_000)
        r.awaitReturn(1_050)
        assertFalse(r.attached(btd, 3_000, permitted = false))
        assertFalse(r.permitted(btd, 90_000))
    }

    @Test
    fun `permission alone, before the DAC is back, does nothing`() {
        val r = UsbDacReturn()
        r.lost(btd, 1_000)
        r.awaitReturn(1_050)
        assertFalse(r.permitted(btd, 1_200))
    }

    @Test
    fun `a different DAC plugged in is not the one that dropped`() {
        val r = UsbDacReturn()
        r.lost(btd, 1_000)
        r.awaitReturn(1_050)
        assertFalse(r.attached("262a:9302", 2_500, permitted = true))
        assertTrue(r.attached(btd, 3_000, permitted = true))
    }

    @Test
    fun `play pressed meanwhile cancels the carry-on`() {
        val r = UsbDacReturn()
        r.lost(btd, 1_000)
        r.awaitReturn(1_050)
        r.cancel()
        assertFalse(r.attached(btd, 2_500, permitted = true))
    }

    @Test
    fun `a stale loss is not awaited`() {
        val r = UsbDacReturn(returnWindowMs = 20_000)
        r.lost(btd, 1_000)
        r.awaitReturn(40_000)
        assertFalse(r.attached(btd, 41_000, permitted = true))
    }
}
