package com.engabd.sendpin.hue

import com.engabd.sendpin.ma.MaPlayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * When the phone joins a remote speaker's group to listen silently, and when it must not.
 *
 * A join changes a group someone is listening to, so the refusals matter as much as
 * the joins: the phone, an AirPlay speaker, a saved group player and a stopped speaker
 * are all left alone.
 */
class LightListenerRulesTest {

    private val me = "phone-client"

    private fun player(
        id: String,
        provider: String? = "sendspin",
        state: String = "playing",
        features: List<String> = listOf("set_members"),
        type: String = "player",
        syncedTo: String? = null,
        childs: List<String> = emptyList(),
        volume: Int = 50,
        output: String? = null,
    ) = MaPlayer(
        playerId = id, name = id, available = true, powered = true, type = type, state = state,
        volumeLevel = volume, syncedTo = syncedTo, groupChilds = childs, supportedFeatures = features,
        activeOutputProtocol = output, provider = provider,
    )

    private fun plan(
        players: List<MaPlayer>,
        targetId: String? = "kitchen",
        wanted: Boolean = true,
        connected: Boolean = true,
        aloud: Boolean = false,
        targetIsPhone: Boolean = false,
        failed: Pair<String, String>? = null,
    ) = LightListenerRules.plan(
        wanted = wanted,
        playerConnected = connected,
        phonePlayingAloud = aloud,
        targetId = targetId,
        targetPlaying = players.firstOrNull { it.playerId == targetId }?.isPlaying == true,
        targetIsPhone = targetIsPhone,
        players = players,
        phoneIds = setOf(me),
        myPlayerId = me,
        failed = failed,
    )

    private val phone = player(me)

    @Test
    fun `a speaker whose own list leaves the phone out is not asked`() {
        val kitchen = player("kitchen")
        assertTrue(plan(listOf(phone, kitchen.copy(canGroupWith = listOf("lounge")))) is ListenerPlan.Fallback)
        assertEquals(ListenerPlan.Join("kitchen", "kitchen"), plan(listOf(phone, kitchen.copy(canGroupWith = listOf(me)))))
        assertEquals(ListenerPlan.Join("kitchen", "kitchen"), plan(listOf(phone, kitchen.copy(canGroupWith = listOf("sendspin")))))
        // A wrapped speaker's provider is not Sendspin, yet its list takes the phone.
        val wrapped = player("kitchen", provider = "universal_player").copy(canGroupWith = listOf(me))
        assertEquals(ListenerPlan.Join("kitchen", "kitchen"), plan(listOf(phone, wrapped)))
    }

    @Test
    fun `a playing remote Sendspin speaker with the setting on is joined`() {
        assertEquals(
            ListenerPlan.Join("kitchen", "kitchen"),
            plan(listOf(phone, player("kitchen"))),
        )
    }

    @Test
    fun `the setting off joins nothing`() {
        assertEquals(ListenerPlan.Idle(), plan(listOf(phone, player("kitchen")), wanted = false))
    }

    @Test
    fun `the phone as the target joins nothing`() {
        assertEquals(ListenerPlan.Idle(), plan(listOf(phone, player("kitchen")), targetId = me, targetIsPhone = true))
        // Already audible in a group of its own accord: that is its own feed, not ours to touch.
        assertEquals(ListenerPlan.Idle(), plan(listOf(phone, player("kitchen")), aloud = true))
    }

    @Test
    fun `a stopped speaker is left after a grace, not joined`() {
        assertEquals(
            ListenerPlan.Idle(afterGrace = true),
            plan(listOf(phone, player("kitchen", state = "paused"))),
        )
    }

    @Test
    fun `a speaker the phone cannot keep time with falls back`() {
        assertIs<ListenerPlan.Fallback>(plan(listOf(phone, player("kitchen", provider = "airplay"))))
        assertIs<ListenerPlan.Fallback>(plan(listOf(phone, player("kitchen", provider = "chromecast--x1"))))
        // Unknown is no.
        assertIs<ListenerPlan.Fallback>(plan(listOf(phone, player("kitchen", provider = null))))
        // A saved group player is the user's group; adding to it would change it for good.
        assertIs<ListenerPlan.Fallback>(plan(listOf(phone, player("kitchen", type = "group"))))
        assertIs<ListenerPlan.Fallback>(plan(listOf(phone, player("kitchen", features = emptyList()))))
    }

    @Test
    fun `no player connection or an earlier failure falls back with its reason`() {
        assertIs<ListenerPlan.Fallback>(plan(listOf(phone, player("kitchen")), connected = false))
        assertEquals(
            ListenerPlan.Fallback("no audio"),
            plan(listOf(phone, player("kitchen")), failed = "kitchen" to "no audio"),
        )
        // A failure on another speaker says nothing about this one.
        assertIs<ListenerPlan.Join>(plan(listOf(phone, player("kitchen")), failed = "lounge" to "no audio"))
    }

    @Test
    fun `a member target means its leader's group`() {
        val players = listOf(phone, player("lounge", childs = listOf("kitchen")), player("kitchen", syncedTo = "lounge"))
        assertEquals(ListenerPlan.Join("lounge", "lounge"), plan(players))
    }

    @Test
    fun `a wrapped speaker is judged by the protocol player it plays through`() {
        // Music Assistant 2.10: the target is an `up…` wrapper; what carries the audio is
        // named by active_output_protocol.
        val wrapped = listOf(
            phone,
            player("upKitchen", provider = "universal_player", output = "kitchen-sendspin"),
            player("kitchen-sendspin", provider = "sendspin"),
        )
        assertIs<ListenerPlan.Join>(plan(wrapped, targetId = "upKitchen"))
        val airplay = listOf(
            phone,
            player("upKitchen", provider = "universal_player", output = "kitchen-ap"),
            player("kitchen-ap", provider = "airplay"),
        )
        assertIs<ListenerPlan.Fallback>(plan(airplay, targetId = "upKitchen"))
    }

    @Test
    fun `the phone's own mean volume leaves the group average where it was`() {
        val players = listOf(
            player("lounge", volume = 30, childs = listOf("kitchen", me)),
            player("kitchen", volume = 61, syncedTo = "lounge"),
            player(me, volume = 100, syncedTo = "lounge"),
        )
        val v = LightListenerRules.groupVolume(players, "lounge", setOf(me))
        assertEquals(46, v)
        assertTrue(LightListenerRules.phoneInGroup(players, "lounge", setOf(me)))
        assertFalse(LightListenerRules.phoneInGroup(listOf(player("lounge")), "lounge", setOf(me)))
    }
}
