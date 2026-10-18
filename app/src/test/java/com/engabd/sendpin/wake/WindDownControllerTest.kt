package com.engabd.sendpin.wake

import com.engabd.sendpin.hue.HueGroup
import com.engabd.sendpin.hue.HueGroupState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WindDownControllerTest {
    private class FakeLights(var state: HueGroupState? = HueGroupState(true, 80.0), var fail: Boolean = false) : RoomLights {
        val puts = mutableListOf<Pair<String, String>>()
        override suspend fun state(groupedLightId: String) = if (fail) throw RuntimeException("down") else state
        override suspend fun put(groupedLightId: String, body: String) {
            if (fail) throw RuntimeException("down")
            puts += groupedLightId to body
        }
        override suspend fun rooms(): List<HueGroup> = emptyList()
    }

    private fun TestScope.controller(
        lights: FakeLights,
        cfg: WindDownConfig,
        scenes: MutableList<String> = mutableListOf(),
    ) = WindDownController(this, { cfg }, lights, { scenes += it })

    private val on = WindDownConfig(dim = true, roomId = "gl")

    @Test
    fun `a 30 minute timer sends amber at current brightness, then one long dim, then off`() = runTest {
        val lights = FakeLights()
        val c = controller(lights, on)
        c.onStarted(30 * 60_000L)
        advanceUntilIdle()
        assertEquals(2, lights.puts.size)
        assertEquals(WindDownPlan.amberBody(80.0), lights.puts[0].second)
        assertEquals(WindDownPlan.dimBody(30 * 60_000L - 1_000L), lights.puts[1].second)
        c.onEnded()
        advanceUntilIdle()
        assertEquals(WindDownPlan.offBody(), lights.puts[2].second)
        assertTrue(lights.puts.all { it.first == "gl" })
    }

    @Test
    fun `stop after this song dims to amber and holds`() = runTest {
        val lights = FakeLights()
        controller(lights, on).onStarted(null)
        advanceUntilIdle()
        assertEquals(listOf(WindDownPlan.amberBody(80.0)), lights.puts.map { it.second })
    }

    @Test
    fun `a room that is off is left off`() = runTest {
        val lights = FakeLights(HueGroupState(false, null))
        val c = controller(lights, on)
        c.onStarted(10 * 60_000L)
        advanceUntilIdle()
        c.onEnded()
        advanceUntilIdle()
        assertTrue(lights.puts.isEmpty())
    }

    @Test
    fun `cancelling holds the brightness the fade reached`() = runTest {
        val lights = FakeLights()
        val c = controller(lights, on)
        c.onStarted(30 * 60_000L)
        advanceUntilIdle()
        lights.state = HueGroupState(true, 33.0)
        c.onCancelled()
        advanceUntilIdle()
        assertEquals(WindDownPlan.holdBody(33.0), lights.puts.last().second)
        assertEquals(3, lights.puts.size)
    }

    @Test
    fun `cancelling a timer that never dimmed sends nothing`() = runTest {
        val lights = FakeLights()
        controller(lights, WindDownConfig()).also { it.onStarted(5 * 60_000L); it.onCancelled() }
        advanceUntilIdle()
        assertTrue(lights.puts.isEmpty())
    }

    @Test
    fun `an unreachable bridge is swallowed so the timer carries on`() = runTest {
        val lights = FakeLights(fail = true)
        val c = controller(lights, on)
        c.onStarted(5 * 60_000L)
        c.onEnded()
        advanceUntilIdle()
        assertTrue(lights.puts.isEmpty())
    }

    @Test
    fun `a scene starts a moment after the music ends`() = runTest {
        val scenes = mutableListOf<String>()
        val c = controller(FakeLights(), WindDownConfig(dim = false, ambience = "fireplace"), scenes)
        c.onStarted(5 * 60_000L)
        c.onEnded()
        advanceTimeBy(WindDownController.AMBIENCE_GAP_MS - 1)
        assertTrue(scenes.isEmpty())
        advanceUntilIdle()
        assertEquals(listOf("fireplace"), scenes)
    }

    @Test
    fun `no scene chosen means none requested`() = runTest {
        val scenes = mutableListOf<String>()
        controller(FakeLights(), on, scenes).also { it.onStarted(60_000); it.onEnded() }
        advanceUntilIdle()
        assertTrue(scenes.isEmpty())
    }
}
