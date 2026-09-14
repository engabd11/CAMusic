package com.engabd.sendpin.protocol

import com.engabd.sendpin.protocol.ActivationPolicy.Decision
import com.engabd.sendpin.protocol.noise.PskCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The `server/activate` admissibility table and rejection order (spec §server/activate). */
class ActivationPolicyTest {

    private fun decide(
        category: PskCategory,
        activities: List<String>,
        roles: List<String>? = null,
        persisted: List<String> = emptyList(),
        unpaired: Boolean = true,
        method: String? = null,
        offered: Boolean = true,
        offeredMethods: Set<String> = if (offered) setOf("pairing_psk", "dynamic_pin") else emptySet(),
    ) = ActivationPolicy.decide(category, activities, roles, persisted, unpaired, method, offeredMethods)

    @Test
    fun `sentinel guest session with roles is accepted when unpaired access is on`() {
        // What Music Assistant sends after auto-approving guest access.
        val d = decide(PskCategory.SENTINEL, emptyList(), listOf("player@v1", "metadata@v1"))
        assertIs<Decision.Accept>(d)
        assertEquals(listOf("player@v1", "metadata@v1"), d.activeRoles)
        assertIs<Decision.Accept>(decide(PskCategory.SENTINEL, listOf("playback"), listOf("player@v1")))
    }

    @Test
    fun `sentinel with unpaired access off is pairing_required, not unauthorized`() {
        // The spec's worked example: enabling unpaired access would have admitted it.
        val d = decide(PskCategory.SENTINEL, listOf("playback"), listOf("player@v1"), unpaired = false)
        assertEquals(Decision.Reject("pairing_required"), d)
        // …but a set no unpaired-access setting allows is plain unauthorized.
        val e = decide(PskCategory.SENTINEL, listOf("playback", "management"), listOf("player@v1"), unpaired = false)
        assertEquals(Decision.Reject("unauthorized"), e)
    }

    @Test
    fun `sentinel with empty activities and no roles is admissible even without unpaired access`() {
        val d = decide(PskCategory.SENTINEL, emptyList(), emptyList(), unpaired = false)
        assertIs<Decision.Accept>(d)
        assertEquals(emptyList(), d.activeRoles)
    }

    @Test
    fun `management is only for long-term paired sessions`() {
        assertEquals(Decision.Reject("unauthorized"), decide(PskCategory.SENTINEL, listOf("management")))
        assertEquals(Decision.Reject("unauthorized"), decide(PskCategory.PAIRING, listOf("management")))
        assertIs<Decision.Accept>(decide(PskCategory.LONG_TERM, listOf("playback", "management"), listOf("player@v1")))
        assertIs<Decision.Accept>(decide(PskCategory.LONG_TERM, emptyList(), listOf("player@v1")))
    }

    @Test
    fun `pairing PSK sessions may only pair`() {
        assertEquals(Decision.Reject("unauthorized"), decide(PskCategory.PAIRING, listOf("playback"), listOf("player@v1")))
        assertEquals(Decision.Reject("unauthorized"), decide(PskCategory.PAIRING, emptyList(), emptyList()))
        assertIs<Decision.Accept>(decide(PskCategory.PAIRING, listOf("pairing"), emptyList(), method = "pairing_psk"))
    }

    @Test
    fun `roles on a connection that cannot carry playback are unauthorized`() {
        // A long-term session declaring only 'pairing' is not playback-capable.
        assertEquals(Decision.Reject("unauthorized"), decide(PskCategory.LONG_TERM, listOf("pairing"), listOf("player@v1"), method = "pairing_psk"))
    }

    @Test
    fun `omitted active_roles keeps the persisted set only while playback-capable`() {
        val kept = decide(PskCategory.LONG_TERM, listOf("playback"), null, persisted = listOf("player@v1"))
        assertIs<Decision.Accept>(kept)
        assertEquals(listOf("player@v1"), kept.activeRoles)
        // Moving into pairing on the long-term PSK: the persisted roles are treated as empty, not rejected.
        val emptied = decide(PskCategory.LONG_TERM, listOf("pairing"), null, persisted = listOf("player@v1"), method = "pairing_psk", offered = false)
        assertIs<Decision.AbortPairing>(emptied)
        assertEquals(emptyList(), emptied.activeRoles)
    }

    @Test
    fun `source at trust none is unauthorized`() {
        assertEquals(Decision.Reject("unauthorized"), decide(PskCategory.SENTINEL, listOf("playback"), listOf("player@v1", "source@v1")))
        assertIs<Decision.Accept>(decide(PskCategory.LONG_TERM, listOf("playback"), listOf("player@v1", "source@v1")))
    }

    @Test
    fun `pairing method must match the matched PSK and be offered`() {
        // pairing_psk on a sentinel session: mismatch → abort, connection open.
        assertIs<Decision.AbortPairing>(decide(PskCategory.SENTINEL, listOf("pairing"), emptyList(), method = "pairing_psk"))
        // A PIN method on the Sentinel PSK is the normal case; one we do not offer is refused.
        assertIs<Decision.Accept>(decide(PskCategory.SENTINEL, listOf("pairing"), emptyList(), method = "dynamic_pin"))
        assertIs<Decision.AbortPairing>(decide(PskCategory.SENTINEL, listOf("pairing"), emptyList(), method = "static_pin"))
        // A PIN method on the Pairing PSK contradicts the invariant.
        assertIs<Decision.AbortPairing>(decide(PskCategory.PAIRING, listOf("pairing"), emptyList(), method = "dynamic_pin"))
        // Re-verifying a paired device runs a PIN method on the long-term PSK.
        assertIs<Decision.Accept>(decide(PskCategory.LONG_TERM, listOf("pairing"), emptyList(), method = "dynamic_pin"))
        // The right method on the right PSK, but the client has the method disabled.
        assertIs<Decision.AbortPairing>(decide(PskCategory.PAIRING, listOf("pairing"), emptyList(), method = "pairing_psk", offered = false))
        // No method at all.
        assertIs<Decision.AbortPairing>(decide(PskCategory.PAIRING, listOf("pairing"), emptyList(), method = null))
    }
}
