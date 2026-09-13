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
    fun `client hello uses the spec's field names`() {
        val hello = SendspinClientHello(
            payload = ClientHelloPayload(
                name = "Phone",
                deviceInfo = DeviceInfo("Pixel", "Google", "Android 34"),
                playerV1Support = PlayerV1Support(
                    supportedFormats = listOf(AudioFormatSpec("flac", 2, 48000, 16))
                ),
                trustLevel = "none",
                supportedPairMethods = listOf(PairMethodDescriptor("pairing_psk", listOf("device"))),
                unpairedAccess = UnpairedAccess(true),
            )
        )
        val s = json.encodeToString(hello)
        assertTrue("\"type\":\"client/hello\"" in s)
        assertTrue("\"supported_roles\"" in s)
        assertTrue("\"player@v1_support\"" in s)
        assertTrue("\"supported_formats\"" in s)
        assertTrue("\"sample_rate\":48000" in s)
        assertTrue("\"bit_depth\":16" in s)
        assertTrue("\"product_name\":\"Pixel\"" in s)
        assertTrue("\"trust_level\":\"none\"" in s)
        assertTrue("\"supported_pair_methods\":[{\"method\":\"pairing_psk\",\"locations\":[\"device\"]}]" in s)
        assertTrue("\"unpaired_access\":{\"enabled\":true}" in s)
        // An encrypted hello carries neither: both travel in client/init.
        assertTrue("client_id" !in s, s)
        assertTrue("\"version\"" !in s, s)
        // The player's *name* rides in `payload.name` — the field MA registers a new player under.
        assertTrue("\"name\":\"Phone\"" in s, s)
    }

    @Test
    fun `legacy hello is the pre-spec shape exactly`() {
        val s = json.encodeToString(SendspinClientHello(payload = ClientHelloPayload(name = "P", clientId = "abc", version = 1)))
        assertTrue("\"client_id\":\"abc\"" in s)
        assertTrue("\"version\":1" in s)
        // A Music Assistant 2.10 in transition mode reads unpaired_access on a cleartext
        // hello as a guest approval, after which its downgrade protection refuses the
        // id unencrypted — so none of the encrypted-only fields may appear.
        assertTrue("trust_level" !in s, s)
        assertTrue("supported_pair_methods" !in s, s)
        assertTrue("unpaired_access" !in s, s)
    }

    @Test
    fun `client state carries exactly the spec's player fields`() {
        val s = json.encodeToString(
            SendspinClientState(
                payload = ClientStatePayload(
                    available = true,
                    player = PlayerStateInfo(
                        volume = 40, muted = false, staticDelayMs = 120,
                        requiredLeadTimeMs = 400, minBufferMs = 250,
                        supportedCommands = listOf("set_static_delay"),
                    ),
                ),
            ),
        )
        assertEquals(
            """{"type":"client/state","payload":{"available":true,"player":{"volume":40,"muted":false,"static_delay_ms":120,"required_lead_time_ms":400,"min_buffer_ms":250,"supported_commands":["set_static_delay"]}}}""",
            s,
        )
        // The pre-spec `state` field is gone: clients must not send undefined fields.
        assertTrue("\"state\"" !in s)
    }

    @Test
    fun `init and handshake messages match the spec`() {
        val init = json.encodeToString(SendspinClientInit(payload = ClientInitPayload(clientId = "k", suite = "25519_AESGCM_SHA256")))
        assertEquals("""{"type":"client/init","payload":{"client_id":"k","version":1,"suite":"25519_AESGCM_SHA256"}}""", init)
        val hs = json.encodeToString(SendspinNoiseHandshake(payload = NoiseHandshakePayload("AAAA")))
        assertEquals("""{"type":"noise/handshake","payload":{"data":"AAAA"}}""", hs)
        val fin = json.encodeToString(SendspinClientPairFinalize(payload = ClientPairFinalizePayload("psk")))
        assertEquals("""{"type":"client/pair-finalize","payload":{"long_term_psk":"psk"}}""", fin)
    }

    @Test
    fun `parses activate, pairing and management messages`() {
        val act = SendspinIncoming.parse(
            """{"type":"server/activate","payload":{"activities":["pairing"],"active_roles":[],"pairing":{"method":"pairing_psk"}}}""",
            json,
        )
        assertIs<SendspinIncoming.ServerActivate>(act)
        assertEquals(listOf("pairing"), act.payload.activities)
        assertEquals(emptyList(), act.payload.activeRoles)
        assertEquals("pairing_psk", act.payload.pairing?.method)
        // Omitted active_roles is null (sticky), not empty.
        val sticky = SendspinIncoming.parse("""{"type":"server/activate","payload":{"activities":["playback"]}}""", json)
        assertIs<SendspinIncoming.ServerActivate>(sticky)
        assertEquals(null, sticky.payload.activeRoles)

        assertIs<SendspinIncoming.ServerPairFinalize>(SendspinIncoming.parse("""{"type":"server/pair-finalize","payload":{}}""", json))
        val abort = SendspinIncoming.parse("""{"type":"pair/abort","payload":{"reason":"user_cancelled"}}""", json)
        assertIs<SendspinIncoming.PairAbort>(abort)
        assertEquals("user_cancelled", abort.reason)
        assertIs<SendspinIncoming.ServerUnpair>(SendspinIncoming.parse("""{"type":"server/unpair","payload":{}}""", json))
        val mgmt = SendspinIncoming.parse("""{"type":"management/remove-record","payload":{"psk_id":"x"}}""", json)
        assertIs<SendspinIncoming.Management>(mgmt)
        assertEquals("remove-record", mgmt.request)
        assertEquals("x", mgmt.payload?.get("psk_id")?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
    }

    @Test
    fun `device info carries the client, not the hardware`() {
        // Music Assistant composes a new player's name from what the client announces,
        // so sending Build.MODEL here is what made every rename read back as the
        // phone's model. Both reference clients send a constant; so do we.
        val info = com.engabd.sendpin.discovery.PlayerIdentity.getDeviceInfo()
        assertEquals("CAMusic", info.productName)
        assertEquals("CAMusic", info.manufacturer)
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
        assertEquals("flac", ss.payload.player?.codec)

        assertIs<SendspinIncoming.StreamEnd>(SendspinIncoming.parse("""{"type":"stream/end"}""", json))
        val clear = SendspinIncoming.parse("""{"type":"stream/clear","payload":{"server_transmitted":1,"roles":["player","visualizer"]}}""", json)
        assertIs<SendspinIncoming.StreamClear>(clear)
        assertEquals(listOf("player", "visualizer"), clear.roles)
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
