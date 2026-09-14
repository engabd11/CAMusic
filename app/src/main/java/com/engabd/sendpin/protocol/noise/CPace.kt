package com.engabd.sendpin.protocol.noise

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * CPACE-X25519-SHA512 (draft-irtf-cfrg-cpace-21) in initiator–responder mode with the
 * explicit mutual confirmation flow — the PAKE the Sendspin PIN pairing methods run
 * (spec §PAKE). The server is A (initiator), this client is B (responder).
 *
 * A line-for-line port of the `cpace` library the reference server uses, pinned to it
 * by `CPaceTest`: the generator is Elligator2 of SHA-512 over the password-related
 * string, both shares are X25519 over that generator, and the intermediate session
 * key and confirmation tags follow the draft's `lv_cat` transcript encoding. Field
 * arithmetic is `BigInteger` modulo 2²⁵⁵ − 19; it runs once per pairing attempt.
 */
class CPace private constructor(
    private val role: Role,
    private val sid: ByteArray,
    private val ad: ByteArray,
    private var scalar: ByteArray?,
    /** `Ya` for the initiator, `Yb` for the responder — the 32-byte public share to send. */
    val publicShare: ByteArray,
) {
    enum class Role { INITIATOR, RESPONDER }

    class CPaceException(message: String) : Exception(message)

    private var sides: Pair<Pair<ByteArray, ByteArray>, Pair<ByteArray, ByteArray>>? = null
    private var iskBytes: ByteArray? = null
    private var macKey: ByteArray? = null

    /** The intermediate session key; process with a KDF before use. Available after [derive]. */
    val isk: ByteArray get() = iskBytes ?: throw CPaceException("derive() must be called before accessing the ISK")

    /** Ingest the peer's share, deriving the session key and the confirmation MAC key. */
    fun derive(peerShare: ByteArray, peerAd: ByteArray) {
        val s = scalar ?: throw CPaceException("derive() may only be called once")
        scalar = null
        if (peerShare.size != SHARE_SIZE) throw CPaceException("peer share must be $SHARE_SIZE bytes, got ${peerShare.size}")
        val shared = scalarMultVfy(s, peerShare) ?: throw CPaceException("peer share encodes a low-order point")
        val mine = publicShare to ad
        val theirs = peerShare to peerAd
        val ordered = if (role == Role.INITIATOR) mine to theirs else theirs to mine
        sides = ordered
        val transcript = lvCat(ordered.first.first, ordered.first.second) + lvCat(ordered.second.first, ordered.second.second)
        iskBytes = sha512(lvCat(DSI_ISK, sid, shared) + transcript)
        macKey = sha512(MAC_LABEL + sid + iskBytes!!)
    }

    /** This side's confirmation tag (`Ta` for A, `Tb` for B), 64 bytes. */
    fun tag(): ByteArray = mac(own = true)

    /** Whether [peerTag] proves the peer knew the PIN; a reflected share never verifies. */
    fun verify(peerTag: ByteArray): Boolean {
        val s = sides ?: throw CPaceException("derive() must be called before computing confirmation tags")
        if (s.first.first.contentEquals(s.second.first) && s.first.second.contentEquals(s.second.second)) return false
        return MessageDigest.isEqual(peerTag, mac(own = false))
    }

    private fun mac(own: Boolean): ByteArray {
        val s = sides ?: throw CPaceException("derive() must be called before computing confirmation tags")
        val key = macKey!!
        // Ta authenticates (Ya, ADa); Tb authenticates (Yb, ADb).
        val (share, sideAd) = if (own == (role == Role.INITIATOR)) s.first else s.second
        return hmacSha512(key, lvCat(share, sideAd))
    }

    companion object {
        const val SHARE_SIZE = 32
        const val TAG_SIZE = 64

        // Not BigInteger.TWO: that field only exists from API 33, and the app runs from 31.
        private val TWO: BigInteger = BigInteger.valueOf(2)
        private val Q: BigInteger = TWO.pow(255).subtract(BigInteger.valueOf(19))
        private val A: BigInteger = BigInteger.valueOf(486662)
        private val Z: BigInteger = TWO   // the non-square Elligator2 uses on Curve25519
        private val INV2: BigInteger = TWO.modInverse(Q)
        private val LEGENDRE_POWER: BigInteger = Q.subtract(BigInteger.ONE).shiftRight(1)
        private const val FIELD_BYTES = 32
        private const val SHA512_BLOCK_BYTES = 128

        private val DSI = "CPace255".toByteArray(Charsets.US_ASCII)
        private val DSI_ISK = "CPace255_ISK".toByteArray(Charsets.US_ASCII)
        private val MAC_LABEL = "CPaceMac".toByteArray(Charsets.US_ASCII)

        /**
         * Begin a run: sample a scalar (or take [scalar], for tests) and compute the public
         * share over the generator derived from [prs] (the PIN), [sid] and [ci].
         */
        fun start(
            role: Role,
            prs: ByteArray,
            sid: ByteArray,
            ci: ByteArray = ByteArray(0),
            ad: ByteArray = ByteArray(0),
            scalar: ByteArray = ByteArray(FIELD_BYTES).also { SecureRandom().nextBytes(it) },
        ): CPace {
            val share = scalarMultVfy(scalar, calculateGenerator(prs, ci, sid))
                ?: throw CPaceException("generator encodes a low-order point")
            return CPace(role, sid, ad, scalar, share)
        }

        /** The generator: Elligator2 of the first 32 bytes of SHA-512 over the generator string. */
        internal fun calculateGenerator(prs: ByteArray, ci: ByteArray, sid: ByteArray): ByteArray {
            val genHash = sha512(generatorString(prs, ci, sid)).copyOf(FIELD_BYTES)
            return elligator2(decodeU(genHash))
        }

        internal fun generatorString(prs: ByteArray, ci: ByteArray, sid: ByteArray): ByteArray {
            val lenZpad = maxOf(0, SHA512_BLOCK_BYTES - 1 - prependLen(prs).size - prependLen(DSI).size)
            return lvCat(DSI, prs, ByteArray(lenZpad), ci, sid)
        }

        /** The draft's variable-length prefix: 7 bits per byte, high bit as continuation. */
        internal fun prependLen(data: ByteArray): ByteArray {
            val out = ArrayList<Byte>()
            var length = data.size
            while (true) {
                var b = length and 0x7F
                length = length ushr 7
                if (length == 0) { out.add(b.toByte()); break }
                b = b or 0x80
                out.add(b.toByte())
            }
            return out.toByteArray() + data
        }

        internal fun lvCat(vararg parts: ByteArray): ByteArray {
            var out = ByteArray(0)
            for (p in parts) out += prependLen(p)
            return out
        }

        /** A 32-byte little-endian field element with the unused top bit cleared (RFC 7748). */
        private fun decodeU(value: ByteArray): BigInteger {
            val u = value.copyOf()
            u[u.size - 1] = (u[u.size - 1].toInt() and 0x7F).toByte()
            return BigInteger(1, u.reversedArray())
        }

        private fun inv0(x: BigInteger): BigInteger = x.modPow(Q.subtract(TWO), Q)

        /** Elligator2 map to the curve's u-coordinate, exactly as the reference computes it. */
        internal fun elligator2(rIn: BigInteger): ByteArray {
            val r = rIn.mod(Q)
            val v = A.negate().multiply(inv0(BigInteger.ONE.add(Z.multiply(r).multiply(r)).mod(Q))).mod(Q)
            val eps = v.pow(3).add(A.multiply(v).multiply(v)).add(v).mod(Q).modPow(LEGENDRE_POWER, Q)
            val x = eps.multiply(v).subtract(BigInteger.ONE.subtract(eps).multiply(A).multiply(INV2)).mod(Q)
            val le = x.toByteArray().reversedArray()          // little-endian, possibly short or with a sign byte
            val out = ByteArray(FIELD_BYTES)
            System.arraycopy(le, 0, out, 0, minOf(le.size, FIELD_BYTES))
            return out
        }

        /** X25519, or null when the result is the identity (a low-order point on either side). */
        private fun scalarMultVfy(scalar: ByteArray, point: ByteArray): ByteArray? {
            val shared = X25519.scalarMult(scalar, point)
            return if (shared.all { it == 0.toByte() }) null else shared
        }

        private fun sha512(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").digest(data)

        private fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA512").run { init(SecretKeySpec(key, "HmacSHA512")); doFinal(data) }
    }
}
