package com.engabd.sendpin.plex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The pure parsing half of the PIN flow, held against payloads shaped like plex.tv's
 * `/api/v2/pins` responses — the part of [PlexAuth] that doesn't need a network call
 * to get wrong.
 */
class PlexAuthTest {

    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `a fresh PIN carries its id and code`() {
        val pin = PlexAuth.parsePin(obj("""{"id": 12345, "code": "ABCD", "authToken": null}"""))
        assertEquals(12345L, pin.id)
        assertEquals("ABCD", pin.code)
    }

    @Test
    fun `a PIN response with no id is refused rather than silently misread`() {
        assertFailsWith<PlexException> { PlexAuth.parsePin(obj("""{"code": "ABCD"}""")) }
    }

    @Test
    fun `a pending PIN has no auth token yet`() {
        assertNull(PlexAuth.parseAuthToken(obj("""{"id": 1, "code": "X", "authToken": null}""")))
    }

    @Test
    fun `a blank auth token is treated the same as none`() {
        assertNull(PlexAuth.parseAuthToken(obj("""{"id": 1, "code": "X", "authToken": ""}""")))
    }

    @Test
    fun `a completed sign-in hands back the token`() {
        assertEquals("secret-token", PlexAuth.parseAuthToken(obj("""{"id": 1, "code": "X", "authToken": "secret-token"}""")))
    }

    @Test
    fun `the auth URL carries the client id and code so plex-tv verifies it silently`() {
        val url = PlexAuth.authUrl(PlexAuth.Pin(1, "ABCD"), "client-123")
        assert("clientID=client-123" in url) { url }
        assert("code=ABCD" in url) { url }
        assert(url.startsWith("https://app.plex.tv/auth#?")) { url }
    }

    // ── parseResource — the plex.tv /resources list, for automatic server discovery ──

    @Test
    fun `a server resource prefers its local, non-relay connection`() {
        val resource = PlexAuth.parseResource(
            obj(
                """
                {
                  "name": "Living Room",
                  "provides": "server",
                  "connections": [
                    {"protocol": "https", "address": "1.2.3.4", "port": 32400, "local": false, "relay": true},
                    {"protocol": "http", "address": "192.168.1.5", "port": 32400, "local": true, "relay": false}
                  ]
                }
                """.trimIndent(),
            ),
        )
        assertEquals(PlexAuth.PlexResource("Living Room", "http://192.168.1.5:32400"), resource)
    }

    @Test
    fun `a resource that only provides player, not server, is not a library to add`() {
        assertNull(
            PlexAuth.parseResource(
                obj(
                    """{"name": "iPhone", "provides": "player,pubsub-player",
                        "connections": [{"protocol":"http","address":"1.2.3.4","port":32500,"local":true,"relay":false}]}""",
                ),
            ),
        )
    }

    @Test
    fun `a server reachable only through the relay still resolves, to that connection`() {
        val resource = PlexAuth.parseResource(
            obj(
                """{"name": "Remote Server", "provides": "server",
                    "connections": [{"protocol":"https","address":"5.6.7.8","port":32400,"local":false,"relay":true}]}""",
            ),
        )
        assertEquals(PlexAuth.PlexResource("Remote Server", "https://5.6.7.8:32400"), resource)
    }

    @Test
    fun `a server with no connections at all cannot be addressed`() {
        assertNull(PlexAuth.parseResource(obj("""{"name": "Empty", "provides": "server"}""")))
        assertNull(PlexAuth.parseResource(obj("""{"name": "Empty", "provides": "server", "connections": []}""")))
    }

    @Test
    fun `a connection with a blank address is not offered as a malformed row`() {
        // Present-but-empty, not absent — plausible for a server plex.tv hasn't
        // fully resolved connectivity for yet. Must be rejected the same way a
        // missing address is, not turned into a "http://:32400" row for the user
        // to tap.
        assertNull(
            PlexAuth.parseResource(
                obj(
                    """{"name": "Not Ready", "provides": "server",
                        "connections": [{"protocol":"http","address":"","port":32400,"local":true,"relay":false}]}""",
                ),
            ),
        )
    }

    @Test
    fun `an unnamed server falls back to a plain label rather than a blank row`() {
        val resource = PlexAuth.parseResource(
            obj(
                """{"provides": "server",
                    "connections": [{"protocol":"http","address":"9.9.9.9","port":32400,"local":true,"relay":false}]}""",
            ),
        )
        assertEquals("Plex", resource?.name)
    }

    @Test
    fun `a malformed resource throws rather than silently mismatching — listResources isolates this per item`() {
        // Documents the exact shape that motivates listResources()'s per-item try/catch:
        // parseResource is not defensive against every malformed field on its own — same
        // "let it throw, the caller decides" style as parsePin/parseAuthToken above — so a
        // provides field that isn't a string primitive has to be someone else's problem to
        // isolate, not this function's to swallow.
        assertFailsWith<Exception> {
            PlexAuth.parseResource(obj("""{"provides": ["not", "a", "string"], "connections": []}"""))
        }
    }
}
