package com.engabd.sendpin.plex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The URL half of the Plex client — and specifically the part that is unlike every
 * other provider here: a stream or download URL can only be built once the track's
 * own metadata has been parsed at least once, because the byte path lives at
 * `Media[0].Part[0].key` rather than being derivable from the id. See
 * `PlexClient`'s class doc.
 */
class PlexUrlTest {

    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun client(url: String, token: String = "tok123") = PlexClient(url, token = token)

    private fun trackWithPart(client: PlexClient, id: String = "301") {
        client.item(
            obj(
                """{"ratingKey": "$id", "type": "track", "title": "T", "Media": [{"Part": [{"key": "/library/parts/$id/x/file.flac"}]}]}"""
            )
        )
    }

    // ─── Scheme guessing ─────────────────────────────────────────────────────

    @Test
    fun `a LAN address without a scheme is http, not https`() {
        assertEquals("http://192.168.0.10:32400", client("192.168.0.10:32400").serverUrl)
    }

    @Test
    fun `a public hostname without a scheme is https`() {
        assertEquals("https://plex.example.com", client("plex.example.com").serverUrl)
    }

    // ─── Streaming ───────────────────────────────────────────────────────────

    @Test
    fun `a known track streams from its own Part key, once parsed`() {
        val c = client("192.168.0.10:32400")
        trackWithPart(c)
        val url = c.streamUrl("301")
        assertEquals("http://192.168.0.10:32400/library/parts/301/x/file.flac?X-Plex-Token=tok123", url)
    }

    /**
     * The whole reason [PlexClient] caches a Part key rather than only ever
     * resolving one on demand: a track that has never been fetched still has to
     * produce *something* playable, and the universal transcoder needs only the id.
     */
    @Test
    fun `an unparsed track falls back to the universal transcoder, not a broken URL`() {
        val url = client("192.168.0.10:32400").streamUrl("999")
        assertTrue(url.startsWith("http://192.168.0.10:32400/music/:/transcode/universal/start.mp3"), url)
        assertTrue("path=%2Flibrary%2Fmetadata%2F999" in url, url)
        assertTrue("directPlay=1" in url, url)
    }

    @Test
    fun `a codec token asks the universal transcoder to actually transcode`() {
        val c = client("192.168.0.10:32400")
        trackWithPart(c)
        val url = c.streamUrl("301", "mp3-192")
        assertTrue(url.startsWith("http://192.168.0.10:32400/music/:/transcode/universal/start.mp3"), url)
        assertTrue("directPlay=0" in url, url)
        assertTrue("audioBitrate=192" in url, url)
    }

    // ─── Download and cover ──────────────────────────────────────────────────

    @Test
    fun `download reuses the known Part key too`() {
        val c = client("192.168.0.10:32400")
        trackWithPart(c)
        assertEquals(
            "http://192.168.0.10:32400/library/parts/301/x/file.flac?X-Plex-Token=tok123",
            c.downloadUrl("301"),
        )
    }

    @Test
    fun `a cover id with no known thumb path is no URL rather than a broken one`() {
        assertEquals(null, client("192.168.0.10:32400").coverUrl(null))
        assertEquals(null, client("192.168.0.10:32400").coverUrl("never-seen"))
    }

    @Test
    fun `a cover URL for a seen item carries its thumb path and the token`() {
        val c = client("192.168.0.10:32400")
        c.item(obj("""{"ratingKey": "201", "type": "album", "title": "A", "thumb": "/library/metadata/201/thumb/9"}"""))
        val url = c.coverUrl("201", 500)!!
        assertTrue(url.startsWith("http://192.168.0.10:32400/photo/:/transcode"), url)
        assertTrue("width=500" in url, url)
        assertTrue("X-Plex-Token=tok123" in url, url)
    }
}
