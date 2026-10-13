package com.engabd.sendpin.ma

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Turning a Music Assistant player's crossfade on for DJ Radio, across the shapes the
 * setting has had: a plain boolean, and a mode with named options (Smart Fades).
 */
class MaCrossfadeTest {

    private fun entry(
        key: String,
        type: String,
        value: JsonPrimitive?,
        options: List<Pair<String, String>> = emptyList(),
    ) = MaConfigEntry(
        key = key,
        plainKey = key.substringAfterLast("||"),
        label = key,
        description = null,
        type = type,
        value = value,
        options = options,
    )

    private val smartFadesOptions = listOf(
        "disabled" to "Disabled",
        "standard_crossfade" to "Standard crossfade",
        "smart_crossfade" to "Smart crossfade",
    )

    @Test
    fun `smart fades off is switched to the smart option, keeping the previous value`() {
        val change = MaCrossfade.turnOn(
            listOf(entry("smart_fades_mode", "string", JsonPrimitive("disabled"), smartFadesOptions)),
        )
        assertEquals("smart_fades_mode", change?.key)
        assertEquals(JsonPrimitive("smart_crossfade"), change?.on)
        assertEquals(JsonPrimitive("disabled"), change?.previous)
        assertEquals("Smart Fades", change?.description)
    }

    @Test
    fun `a crossfade the listener already chose is left alone`() {
        assertNull(
            MaCrossfade.turnOn(
                listOf(entry("smart_fades_mode", "string", JsonPrimitive("standard_crossfade"), smartFadesOptions)),
            ),
        )
        assertNull(MaCrossfade.turnOn(listOf(entry("crossfade", "boolean", JsonPrimitive(true)))))
    }

    @Test
    fun `an older boolean crossfade is switched on`() {
        val change = MaCrossfade.turnOn(listOf(entry("crossfade", "boolean", JsonPrimitive(false))))
        assertEquals(JsonPrimitive(true), change?.on)
        assertEquals(JsonPrimitive(false), change?.previous)
        assertEquals("crossfade", change?.description)
    }

    @Test
    fun `a mode with no smart option falls back to any option that is not off`() {
        val change = MaCrossfade.turnOn(
            listOf(entry("crossfade_mode", "string", JsonPrimitive("off"), listOf("off" to "Off", "on" to "On"))),
        )
        assertEquals(JsonPrimitive("on"), change?.on)
        assertEquals("crossfade", change?.description)
    }

    @Test
    fun `a protocol-wrapped key is matched on its plain form and saved verbatim`() {
        val wrapped = "sendspin||protocol||smart_fades_mode"
        val change = MaCrossfade.turnOn(listOf(entry(wrapped, "string", JsonPrimitive("disabled"), smartFadesOptions)))
        assertEquals(wrapped, change?.key)
    }

    @Test
    fun `the newest key wins when a server declares more than one`() {
        val change = MaCrossfade.turnOn(
            listOf(
                entry("crossfade", "boolean", JsonPrimitive(false)),
                entry("smart_fades_mode", "string", JsonPrimitive("disabled"), smartFadesOptions),
            ),
        )
        assertEquals("smart_fades_mode", change?.key)
    }

    @Test
    fun `a player with no crossfade setting gets no change`() {
        assertNull(MaCrossfade.turnOn(listOf(entry("volume_normalization", "boolean", JsonPrimitive(true)))))
        assertNull(MaCrossfade.turnOn(emptyList()))
    }
}
