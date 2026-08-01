package com.engabd.sendpin.protocol

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** JVM tests for the Sendspin wire messages. ./gradlew :app:testDebugUnitTest */
class MessagesTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    @Test
    fun `client hello uses MA wire field names`() {
        val hello = SendspinClientHello(
            payload = ClientHelloPayload(
                clientId = "abc",
                name = "Phone",
                deviceInfo = DeviceInfo("Pixel", "Google", "Android 34"),
                playerV1Support = PlayerV1Support(
                    supportedFormats = listOf(AudioFormatSpec("flac", 2, 48000, 16))
                ),
            )
        )
        val s = json.encodeToString(hello)
        assertTrue("\"type\":\"client/hello\"" in s)
        assertTrue("\"client_id\":\"abc\"" in s)
        assertTrue("\"supported_roles\"" in s)
        assertTrue("\"player@v1_support\"" in s)
        assertTrue("\"supported_formats\"" in s)
        assertTrue("\"sample_rate\":48000" in s)
        assertTrue("\"bit_depth\":16" in s)
        assertTrue("\"product_name\":\"Pixel\"" in s)
        // The player's *name* rides in `payload.name`. It was never asserted, and it
        // is the field Music Assistant registers a new player under.
        assertTrue("\"name\":\"Phone\"" in s, s)
    }

    @Test
    fun `device info carries the client, not the hardware`() {
        // Music Assistant composes a new player's name from what the client announces,
        // so sending Build.MODEL here is what made every rename read back as the
        // phone's model. Both reference clients send a constant; so do we.
        val info = com.engabd.sendpin.discovery.PlayerIdentity.getDeviceInfo()
        assertEquals("Sendpin", info.productName)
        assertEquals("Sendpin", info.manufacturer)
    }

    @Test
    fun `parses server time`() {
        val msg = SendspinIncoming.parse(
            """{"type":"server/time","payload":{"client_transmitted":1,"server_received":2,"server_transmitted":3}}""",
            json,
        )
        assertIs<SendspinIncoming.ServerTime>(msg)
        assertEquals(1L, msg.payload.clientTransmitted)
        assertEquals(2L, msg.payload.serverReceived)
        assertEquals(3L, msg.payload.serverTransmitted)
    }

    @Test
    fun `parses server state with float duration (MA quirk)`() {
        val msg = SendspinIncoming.parse(
            """{"type":"server/state","payload":{"metadata":{"title":"T","artist":"A","progress":{"track_duration":123456.0,"track_progress":1000}}}}""",
            json,
        )
        assertIs<SendspinIncoming.ServerState>(msg)
        val meta = msg.payload.metadata!!
        assertEquals("T", meta.title)
        assertEquals(123456L, meta.progress?.trackDuration)
        assertEquals(1000L, meta.progress?.trackProgress)
    }

    @Test
    fun `parses auth, commands, and stream`() {
        assertIs<SendspinIncoming.AuthOk>(SendspinIncoming.parse("""{"type":"auth_ok"}""", json))

        val cmd = SendspinIncoming.parse(
            """{"type":"server/command","payload":{"player":{"command":"volume","volume":42}}}""",
            json,
        )
        assertIs<SendspinIncoming.ServerCommand>(cmd)
        assertEquals("volume", cmd.payload.player?.command)
        assertEquals(42, cmd.payload.player?.volume)

        val ss = SendspinIncoming.parse(
            """{"type":"stream/start","payload":{"player":{"codec":"flac","sample_rate":48000,"channels":2,"bit_depth":16}}}""",
            json,
        )
        assertIs<SendspinIncoming.StreamStart>(ss)
        assertEquals("flac", ss.payload.player.codec)

        assertIs<SendspinIncoming.StreamEnd>(SendspinIncoming.parse("""{"type":"stream/end"}""", json))
        assertIs<SendspinIncoming.Unknown>(SendspinIncoming.parse("""{"type":"whatever"}""", json))
    }

    @Test
    fun `flexible long accepts int and float`() {
        val a = json.decodeFromString(MetadataProgressPayload.serializer(), """{"track_duration":100}""")
        val b = json.decodeFromString(MetadataProgressPayload.serializer(), """{"track_duration":100.0}""")
        assertEquals(100L, a.trackDuration)
        assertEquals(100L, b.trackDuration)
    }
}
