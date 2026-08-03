package com.engabd.sendpin.subsonic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Subsonic error codes were only ever interpolated into a string, so a rejected
 * password and a missing album read the same. The connect screen is where that
 * mattered: "couldn't reach Navidrome" for a server that answered perfectly well and
 * simply said no.
 */
class SubsonicErrorTest {

    @Test
    fun `auth failures are the ones worth re-prompting for`() {
        assertTrue(SubsonicError.isAuth(SubsonicError.BAD_CREDENTIALS))
        assertTrue(SubsonicError.isAuth(SubsonicError.TOKEN_AUTH_UNSUPPORTED))
        assertFalse(SubsonicError.isAuth(SubsonicError.NOT_FOUND))
        assertFalse(SubsonicError.isAuth(SubsonicError.NOT_AUTHORIZED))
        // Never reached a Subsonic server at all — a transport problem, not a refusal.
        assertFalse(SubsonicError.isAuth(null))
    }

    /** A known code explains itself, whatever the server chose to say. */
    @Test
    fun `a known code beats the server's own wording`() {
        assertEquals(
            "That username or password was rejected",
            SubsonicError.explain(SubsonicError.BAD_CREDENTIALS, "Wrong username or password."),
        )
    }

    @Test
    fun `an unknown code falls back to what the server said`() {
        assertEquals("Something specific", SubsonicError.explain(0, "Something specific"))
    }

    @Test
    fun `with neither a known code nor a message, the code still shows`() {
        assertEquals("The server refused the request (code 99)", SubsonicError.explain(99, null))
        assertEquals("The server refused the request (code 99)", SubsonicError.explain(99, "  "))
        assertEquals("The server refused the request", SubsonicError.explain(null, null))
    }

    @Test
    fun `every documented code has its own wording`() {
        val codes = listOf(
            SubsonicError.BAD_CREDENTIALS,
            SubsonicError.TOKEN_AUTH_UNSUPPORTED,
            SubsonicError.NOT_AUTHORIZED,
            SubsonicError.NOT_FOUND,
            SubsonicError.SERVER_TOO_OLD,
            SubsonicError.CLIENT_TOO_OLD,
            SubsonicError.TRIAL_EXPIRED,
        )
        val messages = codes.map { SubsonicError.explain(it, "generic") }
        assertEquals(codes.size, messages.toSet().size, "codes should not share wording")
        assertTrue(messages.none { it == "generic" })
    }
}
