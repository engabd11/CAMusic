package com.engabd.sendpin.protocol

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `static_delay_ms` and the `client/state` report, on the wire.
 *
 * Both directions had holes: the field was declared on the client but never
 * populated, and `set_static_delay` — the third command the spec defines for
 * `server/command`, next to volume and mute — was parsed into nothing.
 */
class StaticDelayMessageTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `set_static_delay carries its value`() {
        val msg = SendspinIncoming.parse(
            """{"type":"server/command","payload":{"player":{"command":"set_static_delay","static_delay_ms":250}}}""",
            json,
        )
        assertIs<SendspinIncoming.ServerCommand>(msg)
        val p = msg.payload.player!!
        assertEquals("set_static_delay", p.command)
        assertEquals(250, p.staticDelayMs)
    }

    /** The existing commands must keep parsing with the field absent. */
    @Test
    fun `volume still parses without a static delay`() {
        val msg = SendspinIncoming.parse(
            """{"type":"server/command","payload":{"player":{"command":"volume","volume":42}}}""",
            json,
        )
        assertIs<SendspinIncoming.ServerCommand>(msg)
        assertEquals(42, msg.payload.player?.volume)
        assertEquals(null, msg.payload.player?.staticDelayMs)
    }

    @Test
    fun `client state encodes the trim and the sync report`() {
        val encoded = json.encodeToString(
            SendspinClientState(
                payload = ClientStatePayload(
                    state = "error",
                    player = PlayerStateInfo(volume = 80, muted = false, staticDelayMs = 250),
                )
            )
        )
        assertTrue("\"static_delay_ms\":250" in encoded, encoded)
        assertTrue("\"state\":\"error\"" in encoded, encoded)
    }

    @Test
    fun `the default report is synchronized`() {
        val encoded = json.encodeToString(SendspinClientState(payload = ClientStatePayload()))
        assertTrue("\"state\":\"synchronized\"" in encoded, encoded)
    }
}
