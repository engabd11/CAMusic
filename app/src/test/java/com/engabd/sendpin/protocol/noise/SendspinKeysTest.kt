package com.engabd.sendpin.protocol.noise

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The spec's published constants and the pairing-token reference vector. */
class SendspinKeysTest {

    @Test
    fun `sentinel PSK and its id are the published constants`() {
        assertEquals("1b5e24dbc1aed95fc2a5a338a90c05df44bd10f5ec1f4cd66cbf86272767b9d3", SendspinPsk.SENTINEL.toHex())
        assertEquals("GFsV9tLaSQm9HcFWpKsgYQOr7wFTvNUtkmFwuVz3zoo", SendspinPsk.SENTINEL_ID)
    }

    @Test
    fun `peer ids are 43 characters of base64url with no padding`() {
        val id = SendspinIdentity.generate()
        assertEquals(43, id.peerId.length)
        assertTrue(id.peerId.none { it == '=' || it == '+' || it == '/' })
        assertContentEquals(id.publicKey, SendspinIdentity.decodePeerId(id.peerId))
        assertNull(SendspinIdentity.decodePeerId("too-short"))
    }

    @Test
    fun `pairing token matches the spec reference vector`() {
        val clientKey = ByteArray(32) { it.toByte() }
        val psk = ByteArray(32) { (0xe0 + it).toByte() }
        val expected = "SP:0AAAQEAYEAUDAOCAJBIFQYDIOB4IBCEQTCQKRMFYYDENBWHA5DYP6BYPC4PSOLZXH5DU6V97M5XXO74HR6LZ7J5PW674PT6X37T6757Y"
        assertEquals(expected, PairingToken.encode(clientKey, psk))
        assertEquals(107, expected.length)
        val decoded = assertNotNull(PairingToken.decode(expected))
        assertContentEquals(clientKey, decoded.clientPublicKey)
        assertContentEquals(psk, decoded.pairingPsk)
    }

    @Test
    fun `token decoding is lenient with operator input`() {
        val clientKey = ByteArray(32) { it.toByte() }
        val psk = ByteArray(32) { (0xe0 + it).toByte() }
        val token = PairingToken.encode(clientKey, psk)
        val lower = "  " + token.lowercase() + "\n"
        val decoded = assertNotNull(PairingToken.decode(lower))
        assertContentEquals(psk, decoded.pairingPsk)
        // Missing prefix is fine; a wrong version or damaged body is not.
        assertNotNull(PairingToken.decode(token.removePrefix("SP:")))
        assertNull(PairingToken.decode("SP:1" + token.substring(4)))
        assertNull(PairingToken.decode(token.dropLast(3)))
        assertNull(PairingToken.decode(""))
    }

    @Test
    fun `store starts with an identity, a pairing PSK and the pre-provisioned shared record`() {
        val store = PairingStore(PairingStore.InMemory())
        assertEquals(43, store.identity.peerId.length)
        assertEquals(1, store.records().size)
        val shared = store.records().single()
        assertNull(shared.serverId)
        assertEquals(shared.pskId, store.recordModePskId)
        assertTrue(store.unpairedAccessEnabled)
        assertTrue(store.pairingPskEnabled)
        assertEquals(107, store.pairingToken().length)
    }

    @Test
    fun `store resolves every PSK category by id`() {
        val store = PairingStore(PairingStore.InMemory())
        assertEquals(PskCategory.SENTINEL, store.resolve(SendspinPsk.SENTINEL_ID)?.category)
        assertEquals(PskCategory.PAIRING, store.resolve(store.pairingPskId)?.category)
        assertEquals(PskCategory.LONG_TERM, store.resolve(store.recordModePskId)?.category)
        assertNull(store.resolve("nope"))
        // A disabled Pairing PSK is excluded from the candidate set (spec §Pre-Shared Key).
        store.pairingPskEnabled = false
        assertNull(store.resolve(store.pairingPskId))
    }

    @Test
    fun `a completed pairing binds a fresh PSK to the server and survives a reload`() {
        val mem = PairingStore.InMemory()
        val store = PairingStore(mem)
        val serverId = SendspinIdentity.generate().peerId
        val psk = store.newLongTermPsk()
        assertNull(store.resolve(SendspinPsk.idFor(psk)), "not persisted until the server finalizes")
        val record = store.persistPairing(serverId, psk)
        assertEquals(serverId, record.serverId)
        val resolved = assertNotNull(store.resolve(record.pskId))
        assertEquals(PskCategory.LONG_TERM, resolved.category)
        assertEquals(serverId, resolved.serverId)
        store.markUsed(record.pskId)

        val reloaded = PairingStore(mem)
        assertEquals(store.identity.peerId, reloaded.identity.peerId)
        assertTrue(assertNotNull(reloaded.record(record.pskId)).used)
        // Pairing again with the same server replaces, not duplicates.
        reloaded.persistPairing(serverId, reloaded.newLongTermPsk())
        assertEquals(1, reloaded.records().count { it.serverId == serverId })
    }

    @Test
    fun `record management honours the spec's constraints`() {
        val store = PairingStore(PairingStore.InMemory())
        val psk = SendspinPsk.generate()
        assertEquals(PairingStore.AddResult.OK, store.addRecord(psk, null))
        assertEquals(PairingStore.AddResult.ALREADY_EXISTS, store.addRecord(psk, null))
        assertEquals(PairingStore.AddResult.ALREADY_EXISTS, store.addRecord(SendspinPsk.SENTINEL, null))
        assertEquals(PairingStore.AddResult.ALREADY_EXISTS, store.addRecord(store.pairingPsk, null))
        assertEquals(PairingStore.AddResult.INVALID, store.addRecord(ByteArray(5), null))
        assertEquals(PairingStore.AddResult.INVALID, store.addRecord(SendspinPsk.generate(), "not-a-key"))
        // The record_mode target cannot be removed, and only shared records may be the target.
        assertEquals(PairingStore.RemoveResult.INVALID, store.removeRecord(store.recordModePskId))
        assertEquals(PairingStore.RemoveResult.NOT_FOUND, store.removeRecord("missing"))
        val bound = store.persistPairing(SendspinIdentity.generate().peerId, store.newLongTermPsk())
        assertEquals(false, store.setRecordModePskId(bound.pskId))
        assertEquals(true, store.setRecordModePskId(SendspinPsk.idFor(psk)))
        assertEquals(PairingStore.RemoveResult.OK, store.removeRecord(bound.pskId))
        // Rotating the Pairing PSK to a value already held elsewhere is refused.
        assertEquals(PairingStore.AddResult.ALREADY_EXISTS, store.rotatePairingPsk(psk))
        assertEquals(PairingStore.AddResult.OK, store.rotatePairingPsk())
    }

    @Test
    fun `regenerating the identity forgets every pairing`() {
        val store = PairingStore(PairingStore.InMemory())
        val before = store.identity.peerId
        store.persistPairing(SendspinIdentity.generate().peerId, store.newLongTermPsk())
        store.regenerateIdentity()
        assertTrue(store.identity.peerId != before)
        assertEquals(1, store.records().size)
        assertNull(store.records().single().serverId)
    }

    @Test
    fun `a corrupt persisted document is replaced rather than crashing`() {
        val mem = PairingStore.InMemory().also { it.save("{not json") }
        val store = PairingStore(mem)
        assertEquals(43, store.identity.peerId.length)
        assertFailsWith<IllegalArgumentException> { SendspinIdentity(ByteArray(3)) }
    }
}
