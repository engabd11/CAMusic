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
        assertTrue(blob.startsWith("pc2:"))
    }

    /** A backup exactly as the 210,000-round version wrote it. */
    private fun legacyV1(plain: String, password: String): String {
        val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val spec = javax.crypto.spec.PBEKeySpec(password.toCharArray(), salt, 210_000, 256)
        val raw = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(raw, "AES"))
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return "pc1:" + java.util.Base64.getEncoder().encodeToString(salt + c.iv + ct)
    }

    @Test
    fun `a backup from before the iteration bump still opens`() {
        val old = legacyV1("{\"theme\":\"oled\"}", "correct horse")
        assertEquals("{\"theme\":\"oled\"}", PortableCrypto.decrypt(old, "correct horse"))
        assertNull(PortableCrypto.decrypt(old, "wrong horse"))
    }

    @Test
    fun `a new backup is not readable as the old format`() {
        val blob = PortableCrypto.encrypt("x", "correct horse")!!
        // Same bytes with the old tag: derived at 210k rounds, so the key is wrong.
        assertNull(PortableCrypto.decrypt(blob.replaceFirst("pc2:", "pc1:"), "correct horse"))
    }
}
