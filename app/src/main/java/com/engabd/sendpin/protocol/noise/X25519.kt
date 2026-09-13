package com.engabd.sendpin.protocol.noise

import java.security.SecureRandom

/**
 * X25519 (RFC 7748) scalar multiplication, in plain Kotlin.
 *
 * The platform's `XDH` provider only exists from API 33, and the app runs from 31,
 * so the Diffie-Hellman the Noise handshake needs is done here. This is a line-for-line
 * port of TweetNaCl's `crypto_scalarmult` (public domain): sixteen 16-bit limbs held in
 * `Long`s, a constant-time Montgomery ladder, and the final inversion by
 * exponentiation. It runs twice per handshake, so speed is irrelevant; the RFC 7748
 * test vectors in `X25519Test` are what matter.
 */
object X25519 {
    const val KEY_SIZE = 32

    private val NINE = ByteArray(KEY_SIZE).also { it[0] = 9 }
    private val A24 = longArrayOf(0xDB41, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)

    /** A fresh 32-byte private scalar from a CSPRNG (clamped on use, per RFC 7748). */
    fun generatePrivateKey(random: SecureRandom = SecureRandom()): ByteArray =
        ByteArray(KEY_SIZE).also { random.nextBytes(it) }

    fun publicKey(privateKey: ByteArray): ByteArray = scalarMult(privateKey, NINE)

    /** `n · P` for a 32-byte scalar [n] and a 32-byte u-coordinate [p]. */
    fun scalarMult(n: ByteArray, p: ByteArray): ByteArray {
        require(n.size == KEY_SIZE && p.size == KEY_SIZE) { "X25519 inputs must be 32 bytes" }
        val z = n.copyOf()
        z[31] = ((z[31].toInt() and 127) or 64).toByte()
        z[0] = (z[0].toInt() and 248).toByte()

        val x = unpack(p)
        val a = LongArray(16); val b = x.copyOf(); val c = LongArray(16); val d = LongArray(16)
        val e = LongArray(16); val f = LongArray(16)
        a[0] = 1; d[0] = 1
        for (i in 254 downTo 0) {
            val r = ((z[i shr 3].toInt() ushr (i and 7)) and 1).toLong()
            sel(a, b, r); sel(c, d, r)
            add(e, a, c); sub(a, a, c); add(c, b, d); sub(b, b, d)
            sq(d, e); sq(f, a)
            mul(a, c, a); mul(c, b, e)
            add(e, a, c); sub(a, a, c)
            sq(b, a); sub(c, d, f)
            mul(a, c, A24); add(a, a, d)
            mul(c, c, a); mul(a, d, f); mul(d, b, x); sq(b, e)
            sel(a, b, r); sel(c, d, r)
        }
        val inv = LongArray(16)
        invert(inv, c)
        val out = LongArray(16)
        mul(out, a, inv)
        return pack(out)
    }

    private fun unpack(n: ByteArray): LongArray {
        val o = LongArray(16)
        for (i in 0 until 16) o[i] = (n[2 * i].toLong() and 0xff) + ((n[2 * i + 1].toLong() and 0xff) shl 8)
        o[15] = o[15] and 0x7fff
        return o
    }

    private fun carry(o: LongArray) {
        for (i in 0 until 16) {
            o[i] += 1L shl 16
            val c = o[i] shr 16
            val next = if (i < 15) i + 1 else 0
            o[next] += c - 1 + (if (i == 15) 37 * (c - 1) else 0)
            o[i] -= c shl 16
        }
    }

    private fun sel(p: LongArray, q: LongArray, b: Long) {
        val c = (b - 1).inv()
        for (i in 0 until 16) {
            val t = c and (p[i] xor q[i])
            p[i] = p[i] xor t
            q[i] = q[i] xor t
        }
    }

    private fun pack(n: LongArray): ByteArray {
        val t = n.copyOf()
        carry(t); carry(t); carry(t)
        val m = LongArray(16)
        repeat(2) {
            m[0] = t[0] - 0xffed
            for (i in 1 until 15) {
                m[i] = t[i] - 0xffff - ((m[i - 1] shr 16) and 1)
                m[i - 1] = m[i - 1] and 0xffff
            }
            m[15] = t[15] - 0x7fff - ((m[14] shr 16) and 1)
            val b = (m[15] shr 16) and 1
            m[14] = m[14] and 0xffff
            sel(t, m, 1 - b)
        }
        val o = ByteArray(32)
        for (i in 0 until 16) {
            o[2 * i] = (t[i] and 0xff).toByte()
            o[2 * i + 1] = (t[i] shr 8).toByte()
        }
        return o
    }

    private fun add(o: LongArray, a: LongArray, b: LongArray) { for (i in 0 until 16) o[i] = a[i] + b[i] }
    private fun sub(o: LongArray, a: LongArray, b: LongArray) { for (i in 0 until 16) o[i] = a[i] - b[i] }

    private fun mul(o: LongArray, a: LongArray, b: LongArray) {
        val t = LongArray(31)
        for (i in 0 until 16) for (j in 0 until 16) t[i + j] += a[i] * b[j]
        for (i in 0 until 15) t[i] += 38 * t[i + 16]
        for (i in 0 until 16) o[i] = t[i]
        carry(o); carry(o)
    }

    private fun sq(o: LongArray, a: LongArray) = mul(o, a, a)

    private fun invert(o: LongArray, i: LongArray) {
        val c = i.copyOf()
        for (a in 253 downTo 0) {
            sq(c, c)
            if (a != 2 && a != 4) mul(c, c, i)
        }
        for (a in 0 until 16) o[a] = c[a]
    }
}
