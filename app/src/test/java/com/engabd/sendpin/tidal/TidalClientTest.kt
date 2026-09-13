package com.engabd.sendpin.tidal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tidal's wire mapping, held against fixtures. The load-bearing pins: the BTS
 * manifest decoder must round-trip a plain-URL manifest and an AES-encrypted-URI
 * manifest (encrypted here with `javax.crypto` itself, then decoded by the code
 * under test — the two halves of the same wire format in one test), device-auth
 * answers parse in both camelCase and snake_case, and token expiry honours its
 * leeway.
 */
class TidalClientTest {

    private val client = TidalClient(clientId = "CID", clientSecret = "CSECRET")

    private fun obj(json: String) = Json.parseToJsonElement(json).jsonObject

    // ── BTS manifest decoding ─────────────────────────────────────────────

    @Test
    fun `a plain-url bts manifest decodes to its urls and codec`() {
        val manifest = """{"codecs":"aac","urls":["https://audio.tidal.com/a.aac"],"securityToken":"tok"}"""
        val b64 = java.util.Base64.getEncoder().encodeToString(manifest.toByteArray())
        val decoded = TidalClient.decodeBtsManifest(b64, "tok")
        assertNotNull(decoded)
        assertEquals(listOf("https://audio.tidal.com/a.aac"), decoded.first)
        assertEquals("aac", decoded.second)
    }

    @Test
    fun `an encrypted-uri manifest decrypts back to its url`() {
        // Build the encrypted form with the same key derivation the decoder uses,
        // so this pins the *derivation* end to end rather than any fixed vector.
        val uri = "https://audio-fa.tidal.com/xyz.m4a"
        val keyMaterial =
            java.util.Base64.getDecoder().decode("UIlTTEMmpLfP63qyX5n6KR6F0IwF8GZIfOEOfCtbTL4=") +
                "tok123".toByteArray()
        val key = java.security.MessageDigest.getInstance("SHA-256").digest(keyMaterial)
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        val hex = cipher.doFinal(uri.toByteArray()).joinToString("") { "%02x".format(it) }
        val manifest = """{"codecs":"flac","uris":["$hex"]}"""
        val b64 = java.util.Base64.getEncoder().encodeToString(manifest.toByteArray())

        val decoded = TidalClient.decodeBtsManifest(b64, "tok123")
        assertNotNull(decoded)
        assertEquals(listOf(uri), decoded.first)
        assertEquals("flac", decoded.second)
    }

    @Test
    fun `a manifest that is not base64 returns null rather than crashing`() {
        assertNull(TidalClient.decodeBtsManifest("!!!not base64!!!", null))
    }

    // ── Device auth & tokens ──────────────────────────────────────────────

    @Test
    fun `a device auth answer parses camelCase fields`() {
        val (device, user, url) = TidalClient.parseDeviceAuth(
            obj(
                """
                {
                  "deviceCode": "DC123", "userCode": "ABCD",
                  "verificationUriComplete": "https://link.tidal.com/ABCD",
                  "interval": 2, "expiresIn": 300
                }
                """.trimIndent(),
            ),
        )
        assertEquals("DC123", device)
        assertEquals("ABCD", user)
        assertEquals("https://link.tidal.com/ABCD", url)
    }

    @Test
    fun `a device auth answer also parses snake_case fields`() {
        // Tidal's endpoints have moved between OAuth spellings; accept both.
        val (device, _, url) = TidalClient.parseDeviceAuth(
            obj(
                """
                {"device_code": "DC9", "user_code": "ZZ",
                 "verification_uri": "https://link.tidal.com/ZZ"}
                """.trimIndent(),
            ),
        )
        assertEquals("DC9", device)
        assertEquals("https://link.tidal.com/ZZ", url)
    }

    @Test
    fun `a token answer normalizes with its user`() {
        val token = TidalClient.tokenFromResponse(
            obj(
                """
                {
                  "access_token": "at", "refresh_token": "rt", "expires_in": 3600,
                  "user": {"id": "9182", "countryCode": "AU"}
                }
                """.trimIndent(),
            ),
        )
        assertNotNull(token)
        assertEquals("at", token.accessToken)
        assertEquals("rt", token.refreshToken)
        assertEquals(3600, token.expiresIn)
        assertEquals("9182", token.userId)
        assertEquals("AU", token.countryCode)
    }

    @Test
    fun `token expiry honours its leeway`() {
        assertFalse(TidalClient.tokenExpired(expiresAt = 1000, nowSeconds = 900, leewaySeconds = 60))
        assertTrue(TidalClient.tokenExpired(expiresAt = 950, nowSeconds = 900, leewaySeconds = 60))
        assertTrue(TidalClient.tokenExpired(expiresAt = 1000, nowSeconds = 1000, leewaySeconds = 60))
    }

    // ── Item parsing ──────────────────────────────────────────────────────

    @Test
    fun `a track parses with artist, album and disc number`() {
        val track = client.parseTrack(
            obj(
                """
                {
                  "id": 12345, "title": "Oh My God", "version": "6id Remix",
                  "duration": 233, "trackNumber": 4, "volumeNumber": 2,
                  "artist": {"id": 77, "name": "Adele"},
                  "album": {"id": 556, "title": "30", "cover": "8e07-8cee-45cd-a3e1"}
                }
                """.trimIndent(),
            ),
        )
        assertEquals("12345", track!!.itemId)
        assertEquals("Oh My God (6id Remix)", track.name)
        assertEquals("Adele", track.subtitle)
        assertEquals("30", track.album)
        assertEquals("556", track.parentId)
        assertEquals(233, track.duration)
        assertEquals(4, track.trackNumber)
        assertEquals(2, track.discNumber)
        // Tidal cover ids hyphenate into a path: - becomes /.
        assertEquals(
            "https://resources.tidal.com/images/8e07/8cee/45cd/a3e1/640x640.jpg",
            track.image,
        )
    }

    @Test
    fun `a favourite's item envelope unwraps`() {
        val track = client.parseTrack(
            obj(
                """
                {"item": {"id": 1, "title": "Wrapped", "artist": {"name": "X"}, "duration": 10}}
                """.trimIndent(),
            ),
        )
        assertEquals("Wrapped", track!!.name)
    }

    @Test
    fun `an album parses with its year`() {
        val album = client.parseAlbum(
            obj(
                """
                {
                  "id": 556, "title": "30", "releaseDate": "2021-11-19",
                  "artist": {"id": 77, "name": "Adele"},
                  "cover": "aa-bb-cc-dd"
                }
                """.trimIndent(),
            ),
        )
        assertEquals("556", album!!.itemId)
        assertEquals(2021, album.year)
        assertEquals("77", album.parentId)
    }

    @Test
    fun `a playlist parses from uuid`() {
        val playlist = client.parsePlaylist(
            obj(
                """
                {"uuid": "pl-1", "title": "Late night", "owner": {"name": "abdullah"}, "duration": 600}
                """.trimIndent(),
            ),
        )
        assertEquals("pl-1", playlist!!.itemId)
        assertEquals("abdullah", playlist.subtitle)
    }

    @Test
    fun `entries without ids are skipped rather than crashed on`() {
        assertNull(client.parseTrack(obj("""{"title": "no id"}""")))
        assertNull(client.parseAlbum(obj("""{"title": "no id"}""")))
        assertNull(client.parseArtist(obj("""{"name": "no id"}""")))
    }

    @Test
    fun `the account card reads the user and subscription answers`() {
        val user = Json.parseToJsonElement("""{"firstName":"Ada","lastName":"Lovelace","countryCode":"GB","username":"ada"}""").jsonObject
        val sub = Json.parseToJsonElement("""{"subscription":{"type":"HIFI_PLUS"},"highestSoundQuality":"HI_RES"}""").jsonObject
        val a = client.parseAccount(user, sub)!!
        assertEquals("Ada Lovelace", a.name)
        assertEquals("Hifi plus", a.plan)
        assertEquals("GB", a.country)
        assertEquals("Max", a.maxQuality, "the tier is named as the app names it")
    }

    @Test
    fun `an account with only a subscription answer still describes itself`() {
        val sub = Json.parseToJsonElement("""{"subscription":{"type":"HIFI"},"highestSoundQuality":"LOSSLESS"}""").jsonObject
        val a = client.parseAccount(null, sub)!!
        assertNull(a.name)
        assertEquals("Hifi", a.plan)
        assertEquals("HiFi", a.maxQuality)
        assertNull(client.parseAccount(null, null))
    }
}
