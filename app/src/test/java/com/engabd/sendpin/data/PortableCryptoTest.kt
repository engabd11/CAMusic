package com.engabd.sendpin.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PortableCryptoTest {

    @Test
    fun `round trip with the correct password`() {
        val blob = PortableCrypto.encrypt("hello settings export", "correct horse battery staple")
        assertNotNull(blob)
        assertEquals("hello settings export", PortableCrypto.decrypt(blob!!, "correct horse battery staple"))
    }

    @Test
    fun `wrong password fails closed, not with garbage`() {
        val blob = PortableCrypto.encrypt("secret", "right password")!!
        assertNull(PortableCrypto.decrypt(blob, "wrong password"))
    }

    @Test
    fun `garbage input is rejected, not thrown`() {
        assertNull(PortableCrypto.decrypt("not a valid blob at all", "any password"))
    }

    @Test
    fun `every encryption uses a fresh salt, even for identical input`() {
        val a = PortableCrypto.encrypt("same plaintext", "same password")
        val b = PortableCrypto.encrypt("same plaintext", "same password")
        assertNotEquals(a, b, "a fixed salt would make identical exports fingerprintable")
    }

    @Test
    fun `the blob is tagged so a caller can recognise the format`() {
        val blob = PortableCrypto.encrypt("x", "y")!!
        assertTrue(blob.startsWith("pc1:"))
    }
}
