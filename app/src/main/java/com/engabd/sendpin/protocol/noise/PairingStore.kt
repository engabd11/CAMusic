package com.engabd.sendpin.protocol.noise

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Which kind of PSK admitted a connection — it bounds what the server may do on it. */
enum class PskCategory { SENTINEL, PAIRING, LONG_TERM }

/** A PSK picked for a handshake, with the trust it carries and any server it is bound to. */
class ResolvedPsk(val pskId: String, val psk: ByteArray, val category: PskCategory, val serverId: String? = null)

/** One stored pairing: a long-term Sendspin PSK, bound to a server or shared. */
@Serializable
data class PairingRecord(
    val pskId: String,
    val psk: String,
    val serverId: String? = null,
    val used: Boolean = false,
) {
    fun pskBytes(): ByteArray = B64Url.decode(psk)
}

/**
 * Everything a Sendspin client has to remember across reboots (spec §Identities,
 * §Pairing PSK Flow, §Management): its identity keypair, its Pairing PSK, the pairing
 * records servers have established with it, the pre-provisioned shared record that
 * `record_mode` falls back to, and the two operator toggles.
 *
 * Persistence is a single JSON document handed to whatever [Persistence] the caller
 * supplies — SharedPreferences in the app, memory in tests — so the store itself is plain
 * JVM. All access is synchronised; the data is tiny and touched a few times per session.
 */
class PairingStore(private val persistence: Persistence) {

    interface Persistence {
        fun load(): String?
        fun save(json: String)
    }

    class InMemory : Persistence {
        private var doc: String? = null
        override fun load() = doc
        override fun save(json: String) { doc = json }
    }

    enum class AddResult { OK, ALREADY_EXISTS, INVALID }
    enum class RemoveResult { OK, NOT_FOUND, INVALID }

    @Serializable
    private data class Document(
        val identityPrivate: String,
        val pairingPsk: String,
        val pairingPskEnabled: Boolean = true,
        val unpairedAccessEnabled: Boolean = true,
        val records: List<PairingRecord> = emptyList(),
        val recordModePskId: String,
        /** The static PIN, 8 digits; null until an operator provisions one (spec: shipped unprovisioned). */
        val staticPin: String? = null,
        val staticPinEnabled: Boolean = false,
        val dynamicPinEnabled: Boolean = true,
        val minPinLength: Int = PinPairing.DEFAULT_MIN_PIN_DIGITS,
        /** Dynamic-PIN inner-authentication failures, persisted; escalates the method at ten. */
        val pinFailures: Int = 0,
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private var doc: Document
    private var identityCache: SendspinIdentity

    init {
        val loaded = persistence.load()?.let { runCatching { json.decodeFromString<Document>(it) }.getOrNull() }
        doc = loaded ?: fresh().also { persistence.save(json.encodeToString(it)) }
        identityCache = SendspinIdentity(B64Url.decode(doc.identityPrivate))
    }

    /** A brand-new store: new identity, new Pairing PSK, and the device-specific shared record. */
    private fun fresh(): Document {
        val shared = SendspinPsk.generate()
        val sharedId = SendspinPsk.idFor(shared)
        return Document(
            identityPrivate = B64Url.encode(SendspinIdentity.generate().privateKey),
            pairingPsk = B64Url.encode(SendspinPsk.generate()),
            records = listOf(PairingRecord(pskId = sharedId, psk = B64Url.encode(shared), serverId = null)),
            recordModePskId = sharedId,
        )
    }

    private fun commit(next: Document) {
        doc = next
        persistence.save(json.encodeToString(next))
    }

    val identity: SendspinIdentity get() = synchronized(lock) { identityCache }

    /**
     * Mint a new identity. Every pairing record was bound to the old public key on the
     * server side, so they go too; the Pairing PSK is rotated with it because the token
     * that carried it named the old key.
     */
    fun regenerateIdentity() = synchronized(lock) {
        commit(fresh())
        identityCache = SendspinIdentity(B64Url.decode(doc.identityPrivate))
    }

    // ---- PIN methods (spec §Dynamic PIN Pairing Flow, §Static PIN Pairing Flow) ----

    val staticPin: String? get() = synchronized(lock) { doc.staticPin }

    /** Offered only when enabled *and* provisioned. */
    val staticPinEnabled: Boolean get() = synchronized(lock) { doc.staticPinEnabled && doc.staticPin != null }

    /**
     * Set the static PIN and/or its enabled flag. Enabling with nothing provisioned is
     * refused (spec: "rejected as invalid"); a null [pin] keeps the current one.
     */
    fun setStaticPin(pin: String?, enabled: Boolean?): AddResult = synchronized(lock) {
        if (pin != null && !PinPairing.isValidStaticPin(pin)) return AddResult.INVALID
        val effectivePin = pin ?: doc.staticPin
        val effectiveEnabled = enabled ?: doc.staticPinEnabled
        if (effectiveEnabled && effectivePin == null) return AddResult.INVALID
        commit(doc.copy(staticPin = effectivePin, staticPinEnabled = effectiveEnabled))
        AddResult.OK
    }

    var dynamicPinEnabled: Boolean
        get() = synchronized(lock) { doc.dynamicPinEnabled }
        set(value) = synchronized(lock) { commit(doc.copy(dynamicPinEnabled = value)) }

    val minPinLength: Int get() = synchronized(lock) { doc.minPinLength }

    fun setMinPinLength(length: Int): Boolean = synchronized(lock) {
        if (length !in PinPairing.MIN_PIN_DIGITS..PinPairing.MAX_PIN_DIGITS) return false
        commit(doc.copy(minPinLength = length))
        true
    }

    val pinFailures: Int get() = synchronized(lock) { doc.pinFailures }

    /** Dynamic PIN is escalated to gesture-gating once the failure counter reaches the spec's ten. */
    val isPinEscalated: Boolean get() = synchronized(lock) { doc.pinFailures >= PinPairing.ESCALATION_FAILURES }

    fun recordPinFailure() = synchronized(lock) { commit(doc.copy(pinFailures = doc.pinFailures + 1)) }
    fun resetPinFailures() = synchronized(lock) { if (doc.pinFailures != 0) commit(doc.copy(pinFailures = 0)) }

    val pairingPsk: ByteArray get() = synchronized(lock) { B64Url.decode(doc.pairingPsk) }
    val pairingPskId: String get() = SendspinPsk.idFor(pairingPsk)

    var pairingPskEnabled: Boolean
        get() = synchronized(lock) { doc.pairingPskEnabled }
        set(value) = synchronized(lock) { commit(doc.copy(pairingPskEnabled = value)) }

    var unpairedAccessEnabled: Boolean
        get() = synchronized(lock) { doc.unpairedAccessEnabled }
        set(value) = synchronized(lock) { commit(doc.copy(unpairedAccessEnabled = value)) }

    /** The token to show the operator: this identity plus the current Pairing PSK. */
    fun pairingToken(): String = synchronized(lock) {
        PairingToken.encode(identityCache.publicKey, B64Url.decode(doc.pairingPsk))
    }

    /** Replace the Pairing PSK (a `management/set-pairing-config` rotation or a local reset). */
    fun rotatePairingPsk(psk: ByteArray = SendspinPsk.generate()): AddResult = synchronized(lock) {
        if (psk.size != SendspinPsk.PSK_SIZE) return AddResult.INVALID
        val id = SendspinPsk.idFor(psk)
        // A psk_id must be unique across all three categories (spec §Pre-Shared Key).
        if (id == SendspinPsk.SENTINEL_ID || doc.records.any { it.pskId == id }) return AddResult.ALREADY_EXISTS
        commit(doc.copy(pairingPsk = B64Url.encode(psk)))
        AddResult.OK
    }

    fun records(): List<PairingRecord> = synchronized(lock) { doc.records }
    fun record(pskId: String): PairingRecord? = synchronized(lock) { doc.records.firstOrNull { it.pskId == pskId } }
    val recordModePskId: String get() = synchronized(lock) { doc.recordModePskId }

    fun addRecord(psk: ByteArray, serverId: String?): AddResult = synchronized(lock) {
        if (psk.size != SendspinPsk.PSK_SIZE) return AddResult.INVALID
        if (serverId != null && SendspinIdentity.decodePeerId(serverId) == null) return AddResult.INVALID
        val id = SendspinPsk.idFor(psk)
        if (id == SendspinPsk.SENTINEL_ID || id == pairingPskIdLocked() || doc.records.any { it.pskId == id }) {
            return AddResult.ALREADY_EXISTS
        }
        commit(doc.copy(records = doc.records + PairingRecord(id, B64Url.encode(psk), serverId)))
        AddResult.OK
    }

    /** Removes a record; the one `record_mode` points at cannot go (spec §Record mode). */
    fun removeRecord(pskId: String): RemoveResult = synchronized(lock) {
        if (doc.records.none { it.pskId == pskId }) return RemoveResult.NOT_FOUND
        if (pskId == doc.recordModePskId) return RemoveResult.INVALID
        commit(doc.copy(records = doc.records.filterNot { it.pskId == pskId }))
        RemoveResult.OK
    }

    /** `record_mode.psk_id` may only name a shared-PSK record that exists. */
    fun setRecordModePskId(pskId: String): Boolean = synchronized(lock) {
        val target = doc.records.firstOrNull { it.pskId == pskId } ?: return false
        if (target.serverId != null) return false
        commit(doc.copy(recordModePskId = pskId))
        true
    }

    fun markUsed(pskId: String) = synchronized(lock) {
        if (doc.records.any { it.pskId == pskId && !it.used }) {
            commit(doc.copy(records = doc.records.map { if (it.pskId == pskId) it.copy(used = true) else it }))
        }
    }

    /**
     * A fresh long-term PSK for a pairing attempt: unique across every id this store
     * already knows, and **not** persisted — the spec says the record is written only
     * on `server/pair-finalize`; see [persistPairing].
     */
    fun newLongTermPsk(): ByteArray = synchronized(lock) {
        generateSequence { SendspinPsk.generate() }.first { psk ->
            val id = SendspinPsk.idFor(psk)
            id != SendspinPsk.SENTINEL_ID && id != pairingPskIdLocked() && doc.records.none { it.pskId == id }
        }
    }

    /**
     * The record a completed pairing with [serverId] produces: a stored-pubkey record
     * holding the per-server PSK from [newLongTermPsk]. Storage here is unbounded, so
     * the shared-record fallback never applies. Any earlier record for the same server
     * is replaced.
     */
    fun persistPairing(serverId: String, psk: ByteArray): PairingRecord = synchronized(lock) {
        val record = PairingRecord(SendspinPsk.idFor(psk), B64Url.encode(psk), serverId)
        commit(doc.copy(records = doc.records.filterNot { it.serverId == serverId || it.pskId == record.pskId } + record))
        record
    }

    /** Which PSK the handshake's `psk_id` names, if we hold one for it. */
    fun resolve(pskId: String): ResolvedPsk? = synchronized(lock) {
        when {
            pskId == SendspinPsk.SENTINEL_ID -> ResolvedPsk(pskId, SendspinPsk.SENTINEL, PskCategory.SENTINEL)
            doc.pairingPskEnabled && pskId == pairingPskIdLocked() ->
                ResolvedPsk(pskId, B64Url.decode(doc.pairingPsk), PskCategory.PAIRING)
            else -> doc.records.firstOrNull { it.pskId == pskId }?.let {
                ResolvedPsk(pskId, it.pskBytes(), PskCategory.LONG_TERM, it.serverId)
            }
        }
    }

    private fun pairingPskIdLocked(): String = SendspinPsk.idFor(B64Url.decode(doc.pairingPsk))
}
