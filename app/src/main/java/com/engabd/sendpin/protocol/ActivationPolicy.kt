package com.engabd.sendpin.protocol

import com.engabd.sendpin.protocol.noise.PskCategory

/**
 * Whether a `server/activate` is admissible on this session (spec §server/activate),
 * and what to do if it is not.
 *
 * The activity sets a server may declare are bounded by which PSK the handshake matched:
 *
 * | matched PSK | allowed activity sets                                   |
 * |-------------|---------------------------------------------------------|
 * | Sendspin    | `['pairing']`, or any subset of `{playback, management}` |
 * | Pairing     | `['pairing']`                                            |
 * | Sentinel    | `[]`, `['pairing']`, `['playback']` (the last only with unpaired access) |
 *
 * A connection is *playback-capable* when its activities plus `playback` are an allowed
 * set; only such a connection may carry roles. Rejections are ordered: `pairing_required`
 * when enabling unpaired access would have admitted it, otherwise `unauthorized`, and a
 * pairing method the matched PSK disallows is a `pair/abort` that leaves the socket open.
 *
 * Pure, so the whole table can be unit-tested.
 */
object ActivationPolicy {
    const val PLAYBACK = "playback"
    const val PAIRING = "pairing"
    const val MANAGEMENT = "management"
    const val PAIR_METHOD_PSK = "pairing_psk"
    const val PAIR_METHOD_DYNAMIC_PIN = "dynamic_pin"
    const val PAIR_METHOD_STATIC_PIN = "static_pin"

    sealed class Decision {
        /** Apply: these are the activities and (sticky) roles now in force. */
        data class Accept(val activities: Set<String>, val activeRoles: List<String>) : Decision()

        /** Close with `client/goodbye` carrying [reason]. */
        data class Reject(val reason: String) : Decision()

        /** Reply `pair/abort method_not_supported`; the connection stays open. */
        data class AbortPairing(val activities: Set<String>, val activeRoles: List<String>) : Decision()
    }

    fun allowed(category: PskCategory, activities: Set<String>, unpairedAccess: Boolean): Boolean = when (category) {
        PskCategory.LONG_TERM ->
            activities == setOf(PAIRING) || activities.all { it == PLAYBACK || it == MANAGEMENT }
        PskCategory.PAIRING -> activities == setOf(PAIRING)
        PskCategory.SENTINEL ->
            activities.isEmpty() || activities == setOf(PAIRING) || (activities == setOf(PLAYBACK) && unpairedAccess)
    }

    fun playbackCapable(category: PskCategory, activities: Set<String>, unpairedAccess: Boolean): Boolean =
        allowed(category, activities + PLAYBACK, unpairedAccess)

    fun decide(
        category: PskCategory,
        activities: List<String>,
        explicitRoles: List<String>?,
        persistedRoles: List<String>,
        unpairedAccess: Boolean,
        pairingMethod: String?,
        /** The methods this client currently offers (its `supported_pair_methods`). */
        offeredMethods: Set<String>,
    ): Decision {
        val acts = activities.toSet()

        fun admissibleWith(unpaired: Boolean): Boolean {
            if (!allowed(category, acts, unpaired)) return false
            val capable = playbackCapable(category, acts, unpaired)
            val roles = explicitRoles ?: if (capable) persistedRoles else emptyList()
            if (roles.isNotEmpty() && !capable) return false
            // source@v1 needs 'user' trust; no other role carries a trust constraint.
            if (category != PskCategory.LONG_TERM && roles.any { it.startsWith("source@") }) return false
            return true
        }

        if (!admissibleWith(unpairedAccess)) {
            return if (category == PskCategory.SENTINEL && !unpairedAccess && admissibleWith(true)) {
                Decision.Reject("pairing_required")
            } else {
                Decision.Reject("unauthorized")
            }
        }

        val capable = playbackCapable(category, acts, unpairedAccess)
        val roles = explicitRoles ?: if (capable) persistedRoles else emptyList()

        if (PAIRING in acts) {
            // pairing.method is 'pairing_psk' iff the matched PSK is the Pairing PSK, and
            // must be a method we currently offer — checked against the live config,
            // which may have drifted from what the hello advertised.
            val methodMatchesPsk = (pairingMethod == PAIR_METHOD_PSK) == (category == PskCategory.PAIRING)
            if (pairingMethod == null || !methodMatchesPsk || pairingMethod !in offeredMethods) {
                return Decision.AbortPairing(acts, roles)
            }
        }
        return Decision.Accept(acts, roles)
    }
}
