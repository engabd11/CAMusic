package com.engabd.sendpin.ma

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Recognising this phone's own player behind Music Assistant's universal-player wrapper.
 *
 * From MA 2.10 a Sendspin client is not a transport-side player in its own right: the
 * server registers the protocol client under the client's own id and then creates a
 * `universal_player` (`up…`) around it, and it is that wrapper the queues address and
 * the app targets. Comparing the two ids directly is always false, which left the app
 * treating its own precise Sendspin playhead as untrustworthy and falling back to MA's
 * five-second poll — and a poll that has not yet noticed a skip drives the seek bar
 * back onto the outgoing track before dropping it to zero.
 */
class MaPlayerOutputProtocolTest {

    private val protocolId = "6d5fe665-6874-307d-8020-09f0602b6671"

    private fun player(id: String, activeOutput: String? = null) =
        MaPlayer(playerId = id, name = id, available = true, powered = true, activeOutputProtocol = activeOutput)

    @Test
    fun `the universal player wrapping us counts as us`() {
        val wrapper = player("up0997467a", activeOutput = protocolId)
        assertTrue(wrapper.isSelfOrActiveOutput(protocolId))
    }

    @Test
    fun `a wrapper rendering through some other client does not`() {
        // Same phone, but MA is currently sending this player's audio somewhere else.
        val wrapper = player("up0997467a", activeOutput = "someone-elses-client")
        assertFalse(wrapper.isSelfOrActiveOutput(protocolId))
    }

    @Test
    fun `a direct id match still works`() {
        // Servers that target the protocol client itself, and every unwrapped player.
        assertTrue(player(protocolId).isSelfOrActiveOutput(protocolId))
    }

    @Test
    fun `an unrelated player is not us`() {
        assertFalse(player("apbe9f8074c660").isSelfOrActiveOutput(protocolId))
    }
}
