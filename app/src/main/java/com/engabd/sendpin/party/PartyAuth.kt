package com.engabd.sendpin.party

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The party's secret and the checks built on it.
 *
 * A party's link carries a random 128-bit token, and every request has to quote it.
 * The token is the whole access model: it is what makes a guess at the host's
 * address and port worthless, and it is why ending a party (which mints nothing
 * new, it simply stops answering) closes the door for good.
 */
object PartyAuth {
    private val random = SecureRandom()
    private const val HEX = "0123456789abcdef"

    /** 128 random bits, as 32 lower-case hex characters. */
    fun newToken(): String = randomHex(16)

    /** [bytes] random bytes as lower-case hex. Used for tokens, guest ids and opaque item ids. */
    fun randomHex(bytes: Int): String {
        val raw = ByteArray(bytes).also(random::nextBytes)
        val out = StringBuilder(bytes * 2)
        for (b in raw) {
            out.append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
        }
        return out.toString()
    }

    /**
     * Whether [given] is [expected], in time that does not depend on how many leading
     * characters agree. A null or empty value never matches.
     */
    fun matches(expected: String, given: String?): Boolean {
        if (given.isNullOrEmpty() || expected.isEmpty()) return false
        return MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), given.toByteArray(Charsets.UTF_8))
    }
}
