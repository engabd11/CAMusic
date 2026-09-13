package com.engabd.sendpin.protocol

import com.engabd.sendpin.protocol.noise.B64Url
import com.engabd.sendpin.protocol.noise.PairingStore
import com.engabd.sendpin.protocol.noise.SendspinIdentity
import com.engabd.sendpin.protocol.noise.SendspinPsk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The management requests against the pairing store (spec §Management). */
class ManagementHandlerTest {
    private val json = Json
    private val store = PairingStore(PairingStore.InMemory())
    private val handler = ManagementHandler(store)

    private fun call(request: String, payload: String? = null, active: Boolean = true, sessionPskId: String = "session") =
        handler.handle(request, payload?.let { json.parseToJsonElement(it).jsonObject }, active, sessionPskId)

    @Test
    fun `outside a management session everything is permission_denied`() {
        for (r in listOf("list-records", "add-record", "remove-record", "get-pairing-config", "set-pairing-config", "open-pairing-window")) {
            assertEquals("permission_denied", call(r, "{}", active = false).result.payload.result, r)
        }
    }

    @Test
    fun `list-records reports the shared record and any pairings`() {
        val serverId = SendspinIdentity.generate().peerId
        store.persistPairing(serverId, store.newLongTermPsk())
        val data = call("list-records").result.payload.data as JsonObject
        val records = data["records"] as JsonArray
        assertEquals(2, records.size)
        val bound = records.map { it.jsonObject }.first { it.containsKey("server_id") }
        assertEquals(serverId, bound["server_id"]?.jsonPrimitive?.content)
        assertEquals("false", bound["used"]?.jsonPrimitive?.content)
    }

    @Test
    fun `add-record and remove-record round trip with the spec's outcomes`() {
        val psk = B64Url.encode(SendspinPsk.generate())
        assertEquals("ok", call("add-record", """{"psk":"$psk"}""").result.payload.result)
        assertEquals("already_exists", call("add-record", """{"psk":"$psk"}""").result.payload.result)
        assertEquals("invalid", call("add-record", """{"psk":"short"}""").result.payload.result)
        assertEquals("invalid", call("add-record", """{}""").result.payload.result)
        val id = SendspinPsk.idFor(B64Url.decode(psk))
        assertEquals("ok", call("remove-record", """{"psk_id":"$id"}""").result.payload.result)
        assertEquals("not_found", call("remove-record", """{"psk_id":"$id"}""").result.payload.result)
        // The record_mode target cannot be removed.
        assertEquals("invalid", call("remove-record", """{"psk_id":"${store.recordModePskId}"}""").result.payload.result)
    }

    @Test
    fun `removing the requester's own record closes the session after the reply`() {
        val psk = B64Url.encode(SendspinPsk.generate())
        call("add-record", """{"psk":"$psk"}""")
        val id = SendspinPsk.idFor(B64Url.decode(psk))
        val outcome = call("remove-record", """{"psk_id":"$id"}""", sessionPskId = id)
        assertEquals("ok", outcome.result.payload.result)
        assertTrue(outcome.closeUnauthorizedAfter)
    }

    @Test
    fun `pairing config reads and patches`() {
        val cfg = call("get-pairing-config").result.payload.data as JsonObject
        assertEquals("true", (cfg["pairing_psk"] as JsonObject)["enabled"]?.jsonPrimitive?.content)
        assertEquals("true", (cfg["unpaired_access"] as JsonObject)["enabled"]?.jsonPrimitive?.content)
        assertEquals(store.recordModePskId, (cfg["record_mode"] as JsonObject)["psk_id"]?.jsonPrimitive?.content)
        // PIN methods are not implemented, so they are absent, and touching them is invalid.
        assertNull(cfg["static_pin"]); assertNull(cfg["dynamic_pin"])
        assertEquals("invalid", call("set-pairing-config", """{"static_pin":{"enabled":true}}""").result.payload.result)

        assertEquals("ok", call("set-pairing-config", """{"unpaired_access":{"enabled":false}}""").result.payload.result)
        assertFalse(store.unpairedAccessEnabled)
        assertTrue(store.pairingPskEnabled, "an absent object leaves the value unchanged")

        val before = store.pairingPskId
        val fresh = B64Url.encode(SendspinPsk.generate())
        assertEquals("ok", call("set-pairing-config", """{"pairing_psk":{"psk":"$fresh","enabled":false}}""").result.payload.result)
        assertTrue(store.pairingPskId != before)
        assertFalse(store.pairingPskEnabled)
        // A PSK already known in another category collides.
        assertEquals("already_exists", call("set-pairing-config", """{"pairing_psk":{"psk":"${B64Url.encode(SendspinPsk.SENTINEL)}"}}""").result.payload.result)
        // record_mode must name an existing shared record.
        assertEquals("invalid", call("set-pairing-config", """{"record_mode":{"psk_id":"nope"}}""").result.payload.result)
        assertEquals("invalid", call("set-pairing-config", """{"unpaired_access":{"enabled":"yes"}}""").result.payload.result)
    }

    @Test
    fun `open-pairing-window is invalid without a PIN method`() {
        assertEquals("invalid", call("open-pairing-window", "{}").result.payload.result)
    }
}
