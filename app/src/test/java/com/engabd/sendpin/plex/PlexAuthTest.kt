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
}
