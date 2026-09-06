package com.engabd.sendpin.car

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The token that stands in for a cover URL in the car.
 *
 * Two properties matter and neither is obvious from reading the code. It has to be
 * *stable*, or Android Auto re-downloads every cover each time a folder is opened;
 * and it has to be *one-way*, because the URL it names carries the account's
 * credentials and the token is the thing that crosses to another app.
 */
class CarArtworkTest {

    private val credentialed =
        "http://nas.local:4533/rest/getCoverArt?id=al-42&u=abdullah&t=9f8e7d&s=abc&v=1.16.1&c=CAMusic"

    @Test
    fun `the same URL always gets the same token`() {
        assertEquals(CarArtwork.token(credentialed), CarArtwork.token(credentialed))
    }

    @Test
    fun `different URLs get different tokens`() {
        assertNotEquals(CarArtwork.token(credentialed), CarArtwork.token(credentialed + "x"))
    }

    @Test
    fun `a token is hex and fixed width, which is what the provider validates`() {
        val token = CarArtwork.token(credentialed)
        assertEquals(64, token.length)
        assertTrue(token.all { it in '0'..'9' || it in 'a'..'f' }, token)
    }

    @Test
    fun `no part of the URL survives into the token`() {
        // The whole point: this string is handed to another app.
        val token = CarArtwork.token(credentialed)
        assertFalseContains(token, "abdullah")
        assertFalseContains(token, "9f8e7d")
        assertFalseContains(token, "nas.local")
    }

    @Test
    fun `a remembered URL is found again, and an unknown token is not`() {
        val token = CarArtwork.remember(credentialed)
        assertEquals(credentialed, CarArtwork.lookup(token))
        assertNull(CarArtwork.lookup("0".repeat(64)))
    }

    @Test
    fun `forgetting clears the table`() {
        val token = CarArtwork.remember(credentialed)
        CarArtwork.forgetAll()
        assertNull(CarArtwork.lookup(token))
    }

    @Test
    fun `the table is bounded, so a long browse cannot grow it without limit`() {
        CarArtwork.forgetAll()
        repeat(3_000) { CarArtwork.remember("http://nas.local/cover/$it") }
        assertTrue(CarArtwork.size() <= 2_048, "grew to ${CarArtwork.size()}")
        // The most recent are the ones a driver is looking at, and those must survive.
        assertEquals("http://nas.local/cover/2999", CarArtwork.lookup(CarArtwork.token("http://nas.local/cover/2999")))
    }

    private fun assertFalseContains(haystack: String, needle: String) {
        assertTrue(!haystack.contains(needle), "token leaked \"$needle\"")
    }
}
