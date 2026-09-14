package com.engabd.sendpin.protocol

import com.engabd.sendpin.protocol.noise.B64Url
import com.engabd.sendpin.protocol.noise.CPace
import com.engabd.sendpin.protocol.noise.NoiseCipherSuite
import com.engabd.sendpin.protocol.noise.NoiseHash
import com.engabd.sendpin.protocol.noise.PairingStore
import com.engabd.sendpin.protocol.noise.PinPairing
import com.engabd.sendpin.protocol.noise.SendspinIdentity
import com.engabd.sendpin.protocol.noise.SendspinPsk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The client's PIN pairing state machine driven by a simulated server that runs the
 * other half of the same protocol (CPace initiator, the spec's PIN derivation and
 * PSK unwrapping), for both methods and the failure paths.
 */
class PinPairingFlowTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val h = NoiseHash.sha256("a handshake".toByteArray())
    private val serverId = SendspinIdentity.generate().peerId
    private val sent = ArrayList<String>()
    private val pins = ArrayList<String?>()

    private fun flow(store: PairingStore, method: String, pinLength: Int? = 6, index: Int = 1) = PinPairingFlow(
        method = method, suite = NoiseCipherSuite.AESGCM, handshakeHash = h, pairingIndex = index,
        pinLength = pinLength, store = store, serverId = serverId, json = json,
        send = { sent += it }, emitPin = { pins += it },
    )

    private fun last(): kotlinx.serialization.json.JsonObject = json.parseToJsonElement(sent.last()).jsonObject
    private fun field(o: kotlinx.serialization.json.JsonObject, k: String) = o["payload"]!!.jsonObject[k]?.jsonPrimitive?.content

    /** The server's half from `client/pair-init` onward; returns the PSK it unwrapped. */
    private fun runServer(flow: PinPairingFlow, index: Int = 1, serverPin: (nonceA: ByteArray) -> String): ByteArray {
        val sid = PinPairing.sid(h, index)
        val init = last()
        assertEquals("client/pair-init", init["type"]!!.jsonPrimitive.content)
        val nonceA = PinPairing.generateNonce()
        val pin = if (flow.method == "dynamic_pin") {
            assertNotNull(field(init, "commit_B"))
            assertIs<PinPairingFlow.Outcome.Continue>(flow.onServerPairInit(B64Url.encode(nonceA)))
            serverPin(nonceA)
        } else {
            assertNull(field(init, "commit_B"))
            serverPin(nonceA)
        }
        val a = CPace.start(CPace.Role.INITIATOR, pin.toByteArray(), sid, ad = PinPairing.AD_SERVER)
        assertIs<PinPairingFlow.Outcome.Continue>(flow.onServerPairAuth(B64Url.encode(a.publicShare)))
        val auth = last()
        assertEquals("client/pair-auth", auth["type"]!!.jsonPrimitive.content)
        a.derive(B64Url.decode(field(auth, "pake_msg_2")!!), PinPairing.AD_CLIENT)
        val outcome = flow.onServerPairConfirm(B64Url.encode(a.tag()))
        if (outcome is PinPairingFlow.Outcome.Abort) throw IllegalStateException(outcome.reason)
        // client/pair-confirm then client/pair-finalize, back to back.
        val confirm = json.parseToJsonElement(sent[sent.size - 2]).jsonObject
        assertEquals("client/pair-confirm", confirm["type"]!!.jsonPrimitive.content)
        assertTrue(a.verify(B64Url.decode(field(confirm, "client_kc")!!)), "client tag verifies")
        if (flow.method == "dynamic_pin") {
            val nonceB = B64Url.decode(field(confirm, "nonce_B")!!)
            assertContentEquals(PinPairing.commit(nonceB), B64Url.decode(field(init, "commit_B")!!), "commitment opens")
            assertEquals(pin, PinPairing.derivePin(h, nonceA, nonceB, 6), "PIN binding")
        }
        val fin = last()
        assertEquals("client/pair-finalize", fin["type"]!!.jsonPrimitive.content)
        val wrapped = B64Url.decode(field(fin, "wrapped_psk")!!)
        val key = PinPairing.wrapKey(sid, a.isk)
        return NoiseCipherSuite.AESGCM.decrypt(key, 0L, ByteArray(0), wrapped)
    }

    @Test
    fun `dynamic PIN pairs and persists on the server's finalize`() {
        val store = PairingStore(PairingStore.InMemory())
        val f = flow(store, "dynamic_pin")
        assertFalse(f.gestureGated, "six digits, not escalated: no gesture")
        assertIs<PinPairingFlow.Outcome.Started>(f.begin(windowOpen = false))
        val psk = runServer(f) { nonceA ->
            // The server learns nonce_B only at confirm; the operator types the PIN the phone shows.
            pins.last()!!
        }
        assertContentEquals(f.longTermPsk, psk, "server unwrapped the PSK the client minted")
        assertNull(pins.last(), "PIN cleared after confirm")
        assertNull(store.resolve(SendspinPsk.idFor(psk)), "not persisted before server/pair-finalize")
        val done = assertIs<PinPairingFlow.Outcome.Paired>(f.onServerPairFinalize())
        assertEquals(serverId, done.record.serverId)
        assertNotNull(store.resolve(SendspinPsk.idFor(psk)))
        assertEquals(0, store.pinFailures)
    }

    @Test
    fun `a wrong PIN is pin_mismatch and counts a failure`() {
        val store = PairingStore(PairingStore.InMemory())
        val f = flow(store, "dynamic_pin")
        f.begin(windowOpen = false)
        val ex = runCatching { runServer(f) { _ -> "000000".takeIf { it != pins.last() } ?: "111111" } }.exceptionOrNull()
        assertEquals("pin_mismatch", ex?.message)
        assertEquals(1, store.pinFailures)
        assertNull(pins.last())
        assertEquals(PinPairingFlow.State.ENDED, f.state)
    }

    @Test
    fun `static PIN pairs with the provisioned PIN`() {
        val store = PairingStore(PairingStore.InMemory())
        store.setStaticPin("87654321", enabled = true)
        val f = flow(store, "static_pin", pinLength = null)
        assertTrue(f.gestureGated, "every static attempt is gesture-gated")
        assertIs<PinPairingFlow.Outcome.Continue>(f.begin(windowOpen = false))
        assertEquals("client/pair-pending", last()["type"]!!.jsonPrimitive.content)
        assertIs<PinPairingFlow.Outcome.Started>(f.onWindowOpened())
        val psk = runServer(f) { _ -> "87654321" }
        assertIs<PinPairingFlow.Outcome.Paired>(f.onServerPairFinalize())
        assertNotNull(store.resolve(SendspinPsk.idFor(psk)))
        assertTrue(pins.all { it == null }, "a static PIN is never emitted")
    }

    @Test
    fun `short and escalated dynamic PINs are gesture-gated, and pin_length is validated`() {
        val store = PairingStore(PairingStore.InMemory())
        assertTrue(flow(store, "dynamic_pin", pinLength = 4).gestureGated, "a four-digit PIN is bought with a gesture")
        // Below the client's minimum (6) is refused outright.
        assertEquals(PinPairingFlow.Outcome.Abort("pin_length_unacceptable"), flow(store, "dynamic_pin", pinLength = 4).begin(true))
        assertEquals(PinPairingFlow.Outcome.Abort("pin_length_unacceptable"), flow(store, "dynamic_pin", pinLength = 13).begin(true))
        assertEquals(PinPairingFlow.Outcome.Abort("pin_length_unacceptable"), flow(store, "dynamic_pin", pinLength = null).begin(true))
        // Ten failures escalate the method: even an eight-digit PIN then waits for the gesture.
        repeat(10) { store.recordPinFailure() }
        assertTrue(store.isPinEscalated)
        assertTrue(flow(store, "dynamic_pin", pinLength = 8).gestureGated)
        store.resetPinFailures()
        assertFalse(flow(store, "dynamic_pin", pinLength = 8).gestureGated)
    }

    @Test
    fun `out-of-sequence and malformed server messages are protocol errors`() {
        val store = PairingStore(PairingStore.InMemory())
        val f = flow(store, "dynamic_pin")
        assertIs<PinPairingFlow.Outcome.ProtocolError>(f.onServerPairAuth("AAAA"), "auth before init")
        f.begin(windowOpen = false)
        assertIs<PinPairingFlow.Outcome.ProtocolError>(f.onServerPairInit("not-32-bytes"))
        assertIs<PinPairingFlow.Outcome.ProtocolError>(f.onServerPairFinalize(), "finalize before confirm")
    }
}
