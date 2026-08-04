package com.engabd.sendpin.hue

import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal pure-Kotlin DTLS 1.2 client for the Hue Entertainment API.
 *
 * The Hue bridge only accepts entertainment colour data over a DTLS 1.2 channel
 * secured with a pre-shared key (PSK) and the single cipher suite
 * `TLS_PSK_WITH_AES_128_GCM_SHA256`. Android's built-in `javax.net.ssl` has no PSK
 * support, so this implements just enough of DTLS 1.2 — PSK key exchange, the
 * TLS 1.2 PRF, and AES-128-GCM record protection — on top of `javax.crypto`,
 * which is always available on API 31+ (our minSdk).
 *
 * Scope is deliberately narrow: one cipher suite, no certificates, no
 * renegotiation, unfragmented handshake flights (the bridge's messages are small).
 *
 * Ported from syncoV2's `hue/dtls.py:DtlsPskClient`, preserving the handshake
 * flight structure, transcript handling, and alert parsing.
 */

// ── Protocol constants ───────────────────────────────────────────────────

private const val CT_CHANGE_CIPHER_SPEC: Byte = 20
private const val CT_ALERT: Byte = 21
private const val CT_HANDSHAKE: Byte = 22
private const val CT_APPLICATION_DATA: Byte = 23

private const val HS_CLIENT_HELLO: Byte = 1
private const val HS_SERVER_HELLO: Byte = 2
private const val HS_HELLO_VERIFY_REQUEST: Byte = 3
private const val HS_SERVER_KEY_EXCHANGE: Byte = 12
private const val HS_SERVER_HELLO_DONE: Byte = 14
private const val HS_CLIENT_KEY_EXCHANGE: Byte = 16
private const val HS_FINISHED: Byte = 20

private val DTLS_1_2 = byteArrayOf(0xFE.toByte(), 0xFD.toByte())
private val CIPHER_PSK_AES128_GCM_SHA256 = byteArrayOf(0x00, 0xA8.toByte())

private const val HANDSHAKE_TIMEOUT_MS = 1000
private const val HANDSHAKE_RETRIES = 6

// ── TLS 1.2 PRF (P_SHA256) ────────────────────────────────────────────────

private fun pHash(secret: ByteArray, seed: ByteArray, length: Int): ByteArray {
    val out = ByteArray(length)
    var offset = 0
    var a = seed
    while (offset < length) {
        a = hmacSha256(secret, a)
        val block = hmacSha256(secret, a + seed)
        val copyLen = minOf(block.size, length - offset)
        System.arraycopy(block, 0, out, offset, copyLen)
        offset += copyLen
    }
    return out
}

private fun prf(secret: ByteArray, label: ByteArray, seed: ByteArray, length: Int): ByteArray =
    pHash(secret, label + seed, length)

private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data)
}

private fun pskPremaster(psk: ByteArray): ByteArray {
    val other = ByteArray(psk.size)  // zeros
    val buf = ByteBuffer.allocate(2 + other.size + 2 + psk.size).order(ByteOrder.BIG_ENDIAN)
    buf.putShort(other.size.toShort())
    buf.put(other)
    buf.putShort(psk.size.toShort())
    buf.put(psk)
    return buf.array()
}

// ── Record keys ───────────────────────────────────────────────────────────

private class DtlsKeys(keyBlock: ByteArray) {
    val clientKey: ByteArray = keyBlock.copyOfRange(0, 16)
    val serverKey: ByteArray = keyBlock.copyOfRange(16, 32)
    val clientIv: ByteArray = keyBlock.copyOfRange(32, 36)
    val serverIv: ByteArray = keyBlock.copyOfRange(36, 40)
}

// ── Byte helpers ──────────────────────────────────────────────────────────

private fun u24(n: Int): ByteArray = byteArrayOf(
    ((n shr 16) and 0xFF).toByte(),
    ((n shr 8) and 0xFF).toByte(),
    (n and 0xFF).toByte(),
)

private fun vec8(data: ByteArray): ByteArray = byteArrayOf(data.size.toByte()) + data

private fun vec16(data: ByteArray): ByteArray {
    val buf = ByteBuffer.allocate(2 + data.size).order(ByteOrder.BIG_ENDIAN)
    buf.putShort(data.size.toShort())
    buf.put(data)
    return buf.array()
}

// ── Exceptions ────────────────────────────────────────────────────────────

open class DtlsException(message: String) : Exception(message)
class DtlsPeerClosed(val description: Int) : DtlsException("bridge closed the stream (${alertName(description)})")

private fun alertName(desc: Int): String = when (desc) {
    0 -> "close_notify"
    10 -> "unexpected_message"
    20 -> "bad_record_mac"
    40 -> "handshake_failure"
    50 -> "decode_error"
    51 -> "decrypt_error"
    70 -> "protocol_version"
    71 -> "insufficient_security"
    80 -> "internal_error"
    90 -> "user_canceled"
    115 -> "unknown_psk_identity"
    else -> "alert $desc"
}

// ── DTLS client ───────────────────────────────────────────────────────────

/**
 * Synchronous DTLS 1.2 PSK client speaking `TLS_PSK_WITH_AES_128_GCM_SHA256`.
 *
 * The client is deliberately synchronous (blocking UDP socket). Callers should
 * run [connect], [send] and [close] on a background thread.
 */
class DtlsPskClient(
    private val host: String,
    private val port: Int,
    private val identity: ByteArray,
    private val psk: ByteArray,
) {
    private var socket: DatagramSocket? = null
    private var serverAddress: InetAddress? = null

    private var clientRandom = ByteArray(32)
    private var serverRandom = ByteArray(32)
    private var cookie = ByteArray(0)
    private var transcript = ByteArray(0)
    private var clientMsgSeq = 0

    private var sendEpoch = 0
    private var sendSeq = 0L
    private var keys: DtlsKeys? = null
    private var gcmClient: Cipher? = null
    private var gcmServer: Cipher? = null
    private var lastFlight: List<ByteArray>? = null

    fun connect() {
        val addr = InetAddress.getByName(host)
        serverAddress = addr
        socket = DatagramSocket().also { it.soTimeout = HANDSHAKE_TIMEOUT_MS }
        socket?.connect(addr, port)
        try {
            doHandshake()
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    private fun doHandshake() {
        clientRandom = ByteArray(32).also { SecureRandom().nextBytes(it) }

        // Flight 1: ClientHello (no cookie). Not recorded in transcript.
        val ch1 = clientHelloBody(ByteArray(0))
        sendRecords(listOf(record(CT_HANDSHAKE, ch1, encrypt = false)))

        // Wait for HelloVerifyRequest
        val hvr = awaitHandshake(setOf(HS_HELLO_VERIFY_REQUEST))
        cookie = parseHelloVerify(hvr[HS_HELLO_VERIFY_REQUEST]!!)

        // Flight 3: ClientHello (with cookie) — first message of the real transcript.
        clientMsgSeq = 1
        val ch2 = handshakeMsg(HS_CLIENT_HELLO, clientHelloBody(cookie))
        sendRecords(listOf(record(CT_HANDSHAKE, ch2, encrypt = false)))

        // Wait for ServerHello + ServerHelloDone
        val msgs = awaitHandshake(setOf(HS_SERVER_HELLO, HS_SERVER_HELLO_DONE))
        parseServerHello(msgs[HS_SERVER_HELLO]!!)

        // Derive keys
        val master = deriveMaster()
        val keyBlock = prf(master, "key expansion".toByteArray(), serverRandom + clientRandom, 40)
        keys = DtlsKeys(keyBlock)
        gcmClient = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys!!.clientKey, "AES")) }
        gcmServer = Cipher.getInstance("AES/GCM/NoPadding")

        // Flight 5: ClientKeyExchange, ChangeCipherSpec, Finished.
        val cke = handshakeMsg(HS_CLIENT_KEY_EXCHANGE, vec16(identity))
        val ckeRecord = record(CT_HANDSHAKE, cke, encrypt = false)
        val ccsRecord = record(CT_CHANGE_CIPHER_SPEC, byteArrayOf(1), encrypt = false)

        // After CCS, switch to epoch 1 and reset record sequence.
        sendEpoch = 1
        sendSeq = 0

        val verify = prf(master, "client finished".toByteArray(), sha256(transcript), 12)
        val finishedMsg = handshakeMsg(HS_FINISHED, verify)
        val finishedRecord = record(CT_HANDSHAKE, finishedMsg, encrypt = true)

        sendRecords(listOf(ckeRecord, ccsRecord, finishedRecord))

        // Expect server ChangeCipherSpec + encrypted Finished.
        awaitServerFinished(master)
    }

    private fun clientHelloBody(cookie: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(256).order(ByteOrder.BIG_ENDIAN)
        buf.put(DTLS_1_2)                          // client version
        buf.put(clientRandom)                     // 32 bytes
        buf.put(vec8(ByteArray(0)))                // session_id (empty)
        buf.put(vec8(cookie))                      // cookie
        buf.put(vec16(CIPHER_PSK_AES128_GCM_SHA256))  // cipher suites
        buf.put(vec8(byteArrayOf(0)))              // compression: null
        return buf.array().copyOfRange(0, buf.position())
    }

    private fun parseHelloVerify(body: ByteArray): ByteArray {
        // ProtocolVersion(2) + cookie<0..255>
        val cookieLen = body[2].toInt() and 0xFF
        return body.copyOfRange(3, 3 + cookieLen)
    }

    private fun parseServerHello(body: ByteArray) {
        var off = 2  // skip server_version
        System.arraycopy(body, off, serverRandom, 0, 32)
        off += 32
        val sidLen = body[off].toInt() and 0xFF
        off += 1 + sidLen
        val cipher = body.copyOfRange(off, off + 2)
        if (!cipher.contentEquals(CIPHER_PSK_AES128_GCM_SHA256)) {
            throw DtlsException("bridge selected unexpected cipher ${cipher.joinToString("") { "%02x".format(it) }}")
        }
    }

    private fun deriveMaster(): ByteArray {
        val pms = pskPremaster(psk)
        return prf(pms, "master secret".toByteArray(), clientRandom + serverRandom, 48)
    }

    // ── Handshake message construction ────────────────────────────────────

    private fun handshakeMsg(hsType: Byte, body: ByteArray): ByteArray {
        val header = ByteArray(12)
        header[0] = hsType
        // length (3 bytes, big-endian)
        header[1] = ((body.size shr 16) and 0xFF).toByte()
        header[2] = ((body.size shr 8) and 0xFF).toByte()
        header[3] = (body.size and 0xFF).toByte()
        // message_seq (2 bytes, big-endian)
        header[4] = ((clientMsgSeq shr 8) and 0xFF).toByte()
        header[5] = (clientMsgSeq and 0xFF).toByte()
        // frag_offset (3 bytes) = 0
        // frag_length (3 bytes) = length
        header[9] = ((body.size shr 16) and 0xFF).toByte()
        header[10] = ((body.size shr 8) and 0xFF).toByte()
        header[11] = (body.size and 0xFF).toByte()
        clientMsgSeq++
        val msg = header + body
        transcript += msg
        return msg
    }

    // ── Record framing ────────────────────────────────────────────────────

    private fun record(contentType: Byte, fragment: ByteArray, encrypt: Boolean): ByteArray {
        val epoch = sendEpoch
        val seq = sendSeq
        sendSeq++
        val seqBytes = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            .putShort(epoch.toShort())
            .putInt((seq shr 16).toInt())
            .putShort((seq and 0xFFFF).toShort())
            .array()
        // Actually the seq is 6 bytes after 2-byte epoch. Let's fix:
        val seqBytesFixed = ByteArray(8)
        seqBytesFixed[0] = ((epoch shr 8) and 0xFF).toByte()
        seqBytesFixed[1] = (epoch and 0xFF).toByte()
        // 6-byte sequence number
        for (i in 0 until 6) {
            seqBytesFixed[2 + i] = ((seq shr ((5 - i) * 8)) and 0xFF).toByte()
        }
        val encFragment = if (encrypt) encryptRecord(contentType, seqBytesFixed, fragment) else fragment
        val header = ByteBuffer.allocate(13 + encFragment.size).order(ByteOrder.BIG_ENDIAN)
        header.put(contentType)
        header.put(DTLS_1_2)
        header.put(seqBytesFixed)
        header.putShort(encFragment.size.toShort())
        header.put(encFragment)
        return header.array()
    }

    private fun encryptRecord(contentType: Byte, seqBytes: ByteArray, plaintext: ByteArray): ByteArray {
        val k = keys ?: throw DtlsException("DTLS not connected")
        // Explicit nonce: 4-byte fixed IV + 8-byte explicit (epoch||seq)
        val explicitNonce = seqBytes  // 8 bytes
        val nonce = k.clientIv + explicitNonce  // 12 bytes total
        // AAD: seq_bytes(8) + content_type(1) + version(2) + length(2)
        val aad = seqBytes + byteArrayOf(contentType) + DTLS_1_2 + shortToBytesBE(plaintext.size)
        gcmClient!!.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k.clientKey, "AES"), GCMParameterSpec(128, nonce))
        val ct = gcmClient!!.doFinal(plaintext)
        return explicitNonce + ct
    }

    private fun decryptRecord(contentType: Byte, seqBytes: ByteArray, fragment: ByteArray): ByteArray {
        val k = keys ?: throw DtlsException("DTLS not connected")
        val explicit = fragment.copyOfRange(0, 8)   // explicit nonce
        val ct = fragment.copyOfRange(8, fragment.size)
        val nonce = k.serverIv + explicit  // 12 bytes
        val ptLen = ct.size - 16  // minus GCM tag
        val aad = seqBytes + byteArrayOf(contentType) + DTLS_1_2 + shortToBytesBE(ptLen)
        gcmServer!!.init(Cipher.DECRYPT_MODE, SecretKeySpec(k.serverKey, "AES"), GCMParameterSpec(128, nonce))
        return gcmServer!!.doFinal(ct)
    }

    // ── Receive ───────────────────────────────────────────────────────────

    private fun awaitHandshake(needed: Set<Byte>): Map<Byte, ByteArray> {
        val collected = mutableMapOf<Byte, ByteArray>()
        for (attempt in 0 until HANDSHAKE_RETRIES) {
            val data = try {
                recv()
            } catch (e: java.net.SocketTimeoutException) {
                retransmitLastFlight()
                continue
            }
            for ((ctype, seqBytes, fragment) in splitRecords(data)) {
                if (ctype != CT_HANDSHAKE) continue
                for ((hsType, rawMsg, body) in splitHandshake(fragment)) {
                    if (hsType == HS_HELLO_VERIFY_REQUEST) {
                        collected[hsType] = body
                    } else {
                        transcript += rawMsg
                        collected[hsType] = body
                    }
                }
            }
            if (needed.all { it in collected }) return collected
        }
        throw DtlsException("handshake timed out waiting for $needed - ${collected.keys}")
    }

    private fun awaitServerFinished(master: ByteArray) {
        for (attempt in 0 until HANDSHAKE_RETRIES) {
            val data = try {
                recv()
            } catch (e: java.net.SocketTimeoutException) {
                retransmitLastFlight()
                continue
            }
            for ((ctype, seqBytes, fragment) in splitRecords(data)) {
                val epoch = ((seqBytes[0].toInt() and 0xFF) shl 8) or (seqBytes[1].toInt() and 0xFF)
                if (ctype != CT_HANDSHAKE || epoch != 1) continue
                try {
                    val plain = decryptRecord(ctype, seqBytes, fragment)
                    if (plain.isNotEmpty() && plain[0] == HS_FINISHED) {
                        val verify = plain.copyOfRange(12, 24)
                        val expected = prf(master, "server finished".toByteArray(), sha256(transcript), 12)
                        if (!verify.contentEquals(expected)) {
                            throw DtlsException("server Finished verification failed")
                        }
                        return
                    }
                } catch (e: DtlsException) {
                    throw e
                } catch (e: Exception) {
                    throw DtlsException("server Finished failed to decrypt (wrong PSK?)")
                }
            }
        }
        throw DtlsException("no server Finished received; peer never proved knowledge of the PSK")
    }

    // ── Record splitting ──────────────────────────────────────────────────

    private data class Record(val contentType: Byte, val seqBytes: ByteArray, val fragment: ByteArray)

    private fun splitRecords(data: ByteArray): List<Record> {
        val records = mutableListOf<Record>()
        var off = 0
        while (off + 13 <= data.size) {
            val contentType = data[off]
            val seqBytes = data.copyOfRange(off + 3, off + 11)  // epoch(2) + seq(6)
            val length = ((data[off + 11].toInt() and 0xFF) shl 8) or (data[off + 12].toInt() and 0xFF)
            val fragment = data.copyOfRange(off + 13, off + 13 + length)
            records.add(Record(contentType, seqBytes, fragment))
            off += 13 + length
        }
        return records
    }

    private data class HsMessage(val hsType: Byte, val rawMsg: ByteArray, val body: ByteArray)

    private fun splitHandshake(fragment: ByteArray): List<HsMessage> {
        val messages = mutableListOf<HsMessage>()
        var off = 0
        while (off + 12 <= fragment.size) {
            val hsType = fragment[off]
            val length = ((fragment[off + 1].toInt() and 0xFF) shl 16) or
                ((fragment[off + 2].toInt() and 0xFF) shl 8) or
                (fragment[off + 3].toInt() and 0xFF)
            val fragLen = ((fragment[off + 9].toInt() and 0xFF) shl 16) or
                ((fragment[off + 10].toInt() and 0xFF) shl 8) or
                (fragment[off + 11].toInt() and 0xFF)
            val body = fragment.copyOfRange(off + 12, off + 12 + fragLen)
            // Canonical single-fragment form for the transcript.
            val raw = fragment.copyOfRange(off, off + 6) + u24(0) + u24(length) + body
            messages.add(HsMessage(hsType, raw, body))
            off += 12 + fragLen
        }
        return messages
    }

    // ── Flight bookkeeping ────────────────────────────────────────────────

    private fun sendRecords(records: List<ByteArray>) {
        lastFlight = records
        val s = socket ?: return
        for (rec in records) {
            val packet = java.net.DatagramPacket(rec, rec.size, serverAddress, port)
            s.send(packet)
        }
    }

    private fun retransmitLastFlight() {
        val s = socket ?: return
        lastFlight?.forEach { rec ->
            try {
                s.send(java.net.DatagramPacket(rec, rec.size, serverAddress, port))
            } catch (e: Exception) { }
        }
    }

    private fun recv(): ByteArray {
        val s = socket ?: throw DtlsException("not connected")
        val buf = ByteArray(4096)
        val packet = java.net.DatagramPacket(buf, buf.size)
        s.receive(packet)
        return packet.data.copyOfRange(0, packet.length)
    }

    // ── Application data ───────────────────────────────────────────────────

    fun send(data: ByteArray) {
        val s = socket ?: throw DtlsException("DTLS channel not connected")
        val rec = record(CT_APPLICATION_DATA, data, encrypt = true)
        s.send(java.net.DatagramPacket(rec, rec.size, serverAddress, port))
    }

    /** Read any pending DTLS alert; returns (level, description) or null. */
    fun pollAlert(): Pair<Int, Int>? {
        val s = socket ?: return null
        val prevTimeout = s.soTimeout
        s.soTimeout = 0
        var alert: Pair<Int, Int>? = null
        try {
            while (true) {
                val buf = ByteArray(4096)
                val packet = java.net.DatagramPacket(buf, buf.size)
                s.receive(packet)
                val data = packet.data.copyOfRange(0, packet.length)
                for ((ctype, seqBytes, fragment) in splitRecords(data)) {
                    if (ctype != CT_ALERT) continue
                    val epoch = ((seqBytes[0].toInt() and 0xFF) shl 8) or (seqBytes[1].toInt() and 0xFF)
                    var frag = fragment
                    if (epoch >= 1 && keys != null) {
                        frag = try { decryptRecord(ctype, seqBytes, fragment) } catch (_: Exception) { continue }
                    }
                    if (frag.size >= 2 && alert == null) {
                        alert = (frag[0].toInt() and 0xFF) to (frag[1].toInt() and 0xFF)
                    }
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            // non-blocking read done
        } catch (e: Exception) {
            // ignore
        } finally {
            s.soTimeout = prevTimeout
        }
        return alert
    }

    fun close() {
        val s = socket ?: return
        // Send close_notify alert (best-effort).
        if (keys != null) {
            try {
                val alert = byteArrayOf(1, 0)  // warning, close_notify
                val rec = record(CT_ALERT, alert, encrypt = true)
                s.send(java.net.DatagramPacket(rec, rec.size, serverAddress, port))
            } catch (_: Exception) { }
        }
        s.close()
        socket = null
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun sha256(data: ByteArray): ByteArray =
        java.security.MessageDigest.getInstance("SHA-256").digest(data)

    private fun shortToBytesBE(value: Int): ByteArray =
        byteArrayOf(((value shr 8) and 0xFF).toByte(), (value and 0xFF).toByte())
}