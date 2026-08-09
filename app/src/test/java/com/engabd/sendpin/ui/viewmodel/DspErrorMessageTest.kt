package com.engabd.sendpin.ui.viewmodel

import com.engabd.sendpin.ma.MaApiException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the DSP panel says when a save or a load doesn't take.
 *
 * The equaliser has been reporting "DSP saved but server didn't confirm" for what was
 * usually a flat rejection, because both DSP calls swallowed [MaApiException] and
 * answered null. These cover the replacement: the server's own words, its error code,
 * and the one hint worth hard-coding.
 */
class DspErrorMessageTest {

    @Test
    fun `the server's own message survives, with its code`() {
        val msg = dspErrorMessage(MaApiException("Invalid config", 400), "fallback")
        assertTrue("Invalid config" in msg)
        assertTrue("400" in msg)
    }

    @Test
    fun `a refusal names the admin requirement`() {
        val msg = dspErrorMessage(MaApiException("Not allowed for this user", 403), "fallback")
        assertTrue("Not allowed for this user" in msg)
        assertTrue("admin-only" in msg)
    }

    @Test
    fun `an ordinary failure gets no admin lecture`() {
        val msg = dspErrorMessage(MaApiException("Invalid config", 400), "fallback")
        assertFalse("admin-only" in msg)
    }

    @Test
    fun `a transport failure is never dressed up as a permission problem`() {
        // The socket dropping is not the server refusing, however the words fall.
        val msg = dspErrorMessage(
            MaApiException("Request timed out waiting for permission check", -1, isTransport = true),
            "fallback",
        )
        assertFalse("admin-only" in msg)
        // -1 is "no code", not a code worth showing.
        assertFalse("-1" in msg)
    }

    @Test
    fun `a non-MA failure is passed through untouched`() {
        assertEquals("boom", dspErrorMessage(IllegalStateException("boom"), "fallback"))
    }

    @Test
    fun `an exception with no message falls back rather than saying null`() {
        assertEquals("Couldn't load DSP config", dspErrorMessage(RuntimeException(), "Couldn't load DSP config"))
    }
}
