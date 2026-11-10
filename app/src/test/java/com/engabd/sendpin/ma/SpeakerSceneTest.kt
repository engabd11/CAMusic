package com.engabd.sendpin.ma

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Saving and recalling a speaker grouping. See [SpeakerScene]. */
class SpeakerSceneTest {

    private fun p(id: String, volume: Int = 50, available: Boolean = true, syncedTo: String? = null, childs: List<String> = emptyList()) =
        MaPlayer(
            playerId = id, name = id.uppercase(), available = available, powered = true,
            volumeLevel = volume, syncedTo = syncedTo, groupChilds = childs,
        )

    @Test
    fun `capture keeps the group and its volumes, without the light listener`() {
        val players = listOf(
            p("lead", 40, childs = listOf("lead", "kitchen", "phone")),
            p("kitchen", 25, syncedTo = "lead"),
            p("phone", 0, syncedTo = "lead"),
            p("garage", 70),
        )
        val scene = SpeakerScene.capture("Dinner", "lead", players, listenerId = "phone")
        assertEquals("lead", scene.leader)
        assertEquals(listOf("lead" to 40, "kitchen" to 25), scene.members.map { it.id to it.volume })
    }

    @Test
    fun `capture keeps a speaker the screen shows joined before MA confirms it`() {
        val players = listOf(p("lead", 40), p("kitchen", 25), p("garage", 70))
        val scene = SpeakerScene.capture("Dinner", "lead", players, null, inGroup = setOf("lead", "kitchen"))
        assertEquals(listOf("lead", "kitchen"), scene.members.map { it.id })
    }

    @Test
    fun `a scene shows only on its own server, and an untagged one everywhere`() {
        val here = SpeakerScene(name = "Here", server = "ma1")
        val there = SpeakerScene(name = "There", server = "ma2")
        val old = SpeakerScene(name = "Old")
        assertEquals(listOf(here, old), SpeakerScene.forServer(listOf(here, there, old), "ma1"))
        assertEquals("ma1", SpeakerScene.capture("X", "lead", listOf(p("lead")), null, server = "ma1").server)
    }

    @Test
    fun `recalling the scene the speakers are already in does nothing`() {
        val players = listOf(p("lead", 40, childs = listOf("kitchen")), p("kitchen", 25, syncedTo = "lead"))
        val scene = SpeakerScene.capture("Dinner", "lead", players, listenerId = null)
        assertTrue(SpeakerScene.plan(scene, players, currentLeader = "lead", listenerId = null).isNoop)
    }

    @Test
    fun `recall joins, drops and sets volume only where it differs`() {
        val scene = SpeakerScene(
            name = "Party", leader = "lead",
            members = listOf(SpeakerScene.Member("lead", "Lead", 40), SpeakerScene.Member("garage", "Garage", 80)),
        )
        val players = listOf(
            p("lead", 40, childs = listOf("kitchen")),
            p("kitchen", 25, syncedTo = "lead"),
            p("garage", 30),
        )
        val plan = SpeakerScene.plan(scene, players, currentLeader = "lead", listenerId = null)
        assertEquals(listOf("garage"), plan.add)
        assertEquals(listOf("kitchen"), plan.remove)
        assertEquals(mapOf("garage" to 80), plan.volumes)
        assertFalse(plan.leaderChanges)
    }

    @Test
    fun `an unavailable speaker is skipped and named, and the rest still recalled`() {
        val scene = SpeakerScene(
            name = "Party", leader = "lead",
            members = listOf(SpeakerScene.Member("lead", "Lead", 40), SpeakerScene.Member("garage", "Garage", 80)),
        )
        val players = listOf(p("lead", 10), p("garage", 30, available = false))
        val plan = SpeakerScene.plan(scene, players, currentLeader = "lead", listenerId = null)
        assertEquals(listOf("Garage"), plan.skipped)
        assertTrue(plan.add.isEmpty())
        assertEquals(mapOf("lead" to 40), plan.volumes)
    }

    @Test
    fun `a speaker that is gone altogether is skipped too, and a missing leader hands over`() {
        val scene = SpeakerScene(
            name = "Party", leader = "lead",
            members = listOf(SpeakerScene.Member("lead", "Lead", 40), SpeakerScene.Member("kitchen", "Kitchen", 20)),
        )
        val plan = SpeakerScene.plan(scene, listOf(p("kitchen", 20), p("other")), currentLeader = "other", listenerId = null)
        assertEquals(listOf("Lead"), plan.skipped)
        assertEquals("kitchen", plan.leader)
        assertTrue(plan.leaderChanges)
    }

    @Test
    fun `the light listener is neither added nor taken out`() {
        val scene = SpeakerScene(
            name = "Solo", leader = "lead",
            members = listOf(SpeakerScene.Member("lead", "Lead", 40), SpeakerScene.Member("phone", "Phone", 0)),
        )
        val players = listOf(p("lead", 40, childs = listOf("phone")), p("phone", 0, syncedTo = "lead"))
        val withListenerIn = SpeakerScene.plan(scene, players, currentLeader = "lead", listenerId = "phone")
        assertTrue(withListenerIn.isNoop)

        val listenerOut = listOf(p("lead", 40), p("phone", 0))
        assertFalse("phone" in SpeakerScene.plan(scene, listenerOut, "lead", listenerId = "phone").add)
    }

    @Test
    fun `scenes survive the round trip through settings`() {
        val scene = SpeakerScene(name = "A", leader = "x", members = listOf(SpeakerScene.Member("x", "X", 12)))
        assertEquals(listOf(scene), SpeakerScene.decode(SpeakerScene.encode(listOf(scene))))
    }
}
