package com.engabd.sendpin.protocol

import com.engabd.sendpin.protocol.noise.B64Url
import com.engabd.sendpin.protocol.noise.CPace
import com.engabd.sendpin.protocol.noise.NoiseCipherSuite
import com.engabd.sendpin.protocol.noise.PairingRecord
import com.engabd.sendpin.protocol.noise.PairingStore
import com.engabd.sendpin.protocol.noise.PinPairing
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The client's half of one PIN pairing attempt (spec §Dynamic PIN Pairing Flow,
 * §Static PIN Pairing Flow), as a state machine the session drives with the pairing
 * messages it receives:
 *
 * ```
 *   [gesture-gated: client/pair-pending, wait for the window]
 *   client/pair-init {pairing_index, commit_B?}
 *   dynamic:  server/pair-init {nonce_A}  → derive and show the PIN
 *   server/pair-auth {pake_msg_1}  → client/pair-auth {pake_msg_2}
 *   server/pair-confirm {server_kc} → verify → client/pair-confirm {client_kc, nonce_B?}
 *                                            + client/pair-finalize {wrapped_psk}
 *   server/pair-finalize → the record is persisted
 * ```
 *
 * Dynamic PIN is gesture-gated when the method is escalated by its failure counter
 * or the session's PIN is shorter than six digits; static PIN always is. The failure
 * counter rules are the spec's mandatory ones: it moves only on this client's own
 * verification of `server_kc`, up on failure, to zero on success.
 *
 * Every outgoing message goes through [send]; the PIN to show goes through [emitPin]
 * (null clears it). Anything a conformant server never sends is a [Outcome.ProtocolError]
 * — the connection is to be closed without an application-level message.
 */
class PinPairingFlow(
    val method: String,
    private val suite: NoiseCipherSuite,
    handshakeHash: ByteArray,
    private val pairingIndex: Int,
    private val pinLength: Int?,
    private val store: PairingStore,
    private val serverId: String,
    private val json: Json,
    private val send: (String) -> Unit,
    private val emitPin: (String?) -> Unit,
) {
    enum class State { AWAIT_WINDOW, AWAIT_SERVER_INIT, AWAIT_AUTH, AWAIT_CONFIRM, AWAIT_FINALIZE, DONE, ENDED }

    sealed class Outcome {
        /** Keep going; nothing for the session to do. */
        data object Continue : Outcome()

        /** The attempt started: `client/pair-init` went out, so the attempt timeout starts. */
        data object Started : Outcome()

        /** Send `pair/abort` with this reason; the connection stays open. */
        data class Abort(val reason: String) : Outcome()

        /** A message no conformant server sends; close the connection. */
        data class ProtocolError(val what: String) : Outcome()

        /** `server/pair-finalize` arrived and the record is persisted. */
        data class Paired(val record: PairingRecord) : Outcome()
    }

    var state: State = State.AWAIT_WINDOW
        private set

    private val sid = PinPairing.sid(handshakeHash, pairingIndex)
    private val h = handshakeHash
    private val dynamic = method == ActivationPolicy.PAIR_METHOD_DYNAMIC_PIN
    private val nonceB: ByteArray? = if (dynamic) PinPairing.generateNonce() else null
    private var pin: String? = if (dynamic) null else store.staticPin
    private var cpace: CPace? = null

    /** The long-term PSK this attempt would establish; persisted only on the server's finalize. */
    val longTermPsk: ByteArray = store.newLongTermPsk()

    /** Whether this attempt must wait for an operator gesture before `client/pair-init`. */
    val gestureGated: Boolean = when {
        !dynamic -> true
        else -> store.isPinEscalated || (pinLength ?: 0) < PinPairing.SHORT_PIN_DIGITS
    }

    /** Validate the activation and, unless gated with no window open, send `client/pair-init`. */
    fun begin(windowOpen: Boolean): Outcome {
        if (dynamic) {
            val length = pinLength ?: return Outcome.Abort("pin_length_unacceptable")
            if (length < store.minPinLength || length > PinPairing.MAX_PIN_DIGITS) return Outcome.Abort("pin_length_unacceptable")
        } else if (pin == null) {
            // Offered only when provisioned, so a server asking for it names a method we no longer offer.
            return Outcome.Abort("method_not_supported")
        }
        if (gestureGated && !windowOpen) {
            send(json.encodeToString(SendspinClientPairPending(payload = ClientPairPendingPayload(pairingIndex))))
            return Outcome.Continue
        }
        return sendInit()
    }

    /** The operator opened the pairing window while this attempt was waiting for it. */
    fun onWindowOpened(): Outcome = if (state == State.AWAIT_WINDOW) sendInit() else Outcome.Continue

    private fun sendInit(): Outcome {
        send(
            json.encodeToString(
                SendspinClientPairInit(
                    payload = ClientPairInitPayload(pairingIndex, commitB = nonceB?.let { B64Url.encode(PinPairing.commit(it)) }),
                ),
            ),
        )
        state = if (dynamic) State.AWAIT_SERVER_INIT else State.AWAIT_AUTH
        return Outcome.Started
    }

    fun onServerPairInit(nonceAText: String?): Outcome {
        if (state != State.AWAIT_SERVER_INIT) return Outcome.ProtocolError("server/pair-init out of sequence")
        val nonceA = decode(nonceAText, PinPairing.NONCE_SIZE) ?: return Outcome.ProtocolError("malformed nonce_A")
        val derived = PinPairing.derivePin(h, nonceA, nonceB!!, pinLength!!)
        pin = derived
        emitPin(derived)
        state = State.AWAIT_AUTH
        return Outcome.Continue
    }

    fun onServerPairAuth(pakeMsg1Text: String?): Outcome {
        if (state != State.AWAIT_AUTH) return Outcome.ProtocolError("server/pair-auth out of sequence")
        val peerShare = decode(pakeMsg1Text, CPace.SHARE_SIZE) ?: return Outcome.ProtocolError("malformed pake_msg_1")
        val c = try {
            CPace.start(CPace.Role.RESPONDER, pin!!.toByteArray(Charsets.US_ASCII), sid, ad = PinPairing.AD_CLIENT)
        } catch (e: CPace.CPaceException) {
            return Outcome.ProtocolError("CPace initialisation failed: ${e.message}")
        }
        send(json.encodeToString(SendspinClientPairAuth(payload = ClientPairAuthPayload(B64Url.encode(c.publicShare)))))
        try {
            c.derive(peerShare, PinPairing.AD_SERVER)
        } catch (e: CPace.CPaceException) {
            return Outcome.ProtocolError("pake_msg_1 encodes a low-order point")
        }
        cpace = c
        state = State.AWAIT_CONFIRM
        return Outcome.Continue
    }

    fun onServerPairConfirm(serverKcText: String?): Outcome {
        if (state != State.AWAIT_CONFIRM) return Outcome.ProtocolError("server/pair-confirm out of sequence")
        val serverKc = decode(serverKcText, CPace.TAG_SIZE) ?: return Outcome.ProtocolError("malformed server_kc")
        val c = cpace!!
        if (!c.verify(serverKc)) {
            if (dynamic) store.recordPinFailure()
            emitPin(null)
            state = State.ENDED
            return Outcome.Abort("pin_mismatch")
        }
        if (dynamic) store.resetPinFailures()
        send(
            json.encodeToString(
                SendspinClientPairConfirm(
                    payload = ClientPairConfirmPayload(clientKc = B64Url.encode(c.tag()), nonceB = nonceB?.let { B64Url.encode(it) }),
                ),
            ),
        )
        // Back to back, no server response awaited: the PSK wrapped under the PAKE output.
        val wrapped = PinPairing.wrapPsk(suite, PinPairing.wrapKey(sid, c.isk), longTermPsk)
        send(json.encodeToString(SendspinClientPairFinalize(payload = ClientPairFinalizePayload(wrappedPsk = B64Url.encode(wrapped)))))
        emitPin(null)
        state = State.AWAIT_FINALIZE
        return Outcome.Continue
    }

    fun onServerPairFinalize(): Outcome {
        if (state != State.AWAIT_FINALIZE) return Outcome.ProtocolError("server/pair-finalize out of sequence")
        state = State.DONE
        return Outcome.Paired(store.persistPairing(serverId, longTermPsk))
    }

    /** The attempt ended without finalizing (an abort, a cancelling activate, a timeout, a drop). */
    fun end() {
        emitPin(null)
        state = State.ENDED
    }

    private fun decode(text: String?, size: Int): ByteArray? {
        if (text == null) return null
        val bytes = try { B64Url.decode(text) } catch (_: IllegalArgumentException) { return null }
        return bytes.takeIf { it.size == size }
    }
}
