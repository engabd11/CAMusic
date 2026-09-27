package com.engabd.sendpin.scrobble

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScrobbleTest {

    private val play = Play(title = "Jóga", artist = "Björk", album = "Homogenic", durationMs = 305_000, startedAtMs = 1_700_000_000_123)

    @Test
    fun `a ListenBrainz single listen carries its time, metadata and client`() {
        val body = ListenBrainzClient.payload("single", play)
        assertEquals("single", body["listen_type"]!!.jsonPrimitive.content)
        val listen = body["payload"]!!.jsonArray.single().jsonObject
        assertEquals(1_700_000_000, listen["listened_at"]!!.jsonPrimitive.long)
        val meta = listen["track_metadata"]!!.jsonObject
        assertEquals("Björk", meta["artist_name"]!!.jsonPrimitive.content)
        assertEquals("Jóga", meta["track_name"]!!.jsonPrimitive.content)
        assertEquals("Homogenic", meta["release_name"]!!.jsonPrimitive.content)
        val info = meta["additional_info"] as JsonObject
        assertEquals("CAMusic", info["submission_client"]!!.jsonPrimitive.content)
        assertEquals(305_000, info["duration_ms"]!!.jsonPrimitive.long)
    }

    @Test
    fun `a ListenBrainz playing-now listen has no timestamp, which the API would reject`() {
        val listen = ListenBrainzClient.payload("playing_now", play)["payload"]!!.jsonArray.single().jsonObject
        assertNull(listen["listened_at"])
    }

    @Test
    fun `the Last_fm signature matches an independent MD5 of the sorted parameters`() {
        val params = mapOf(
            "method" to "track.scrobble", "artist" to "Björk", "track" to "Jóga",
            "timestamp" to "1700000000", "sk" to "SESSION", "api_key" to "KEY",
        )
        // Computed with Python's hashlib over the same concatenation.
        assertEquals("2064206b2d60bc7b5db6ea29f3bd9fc4", LastFmClient.signature(params, "SECRET"))
        // `format` is never part of the signature.
        assertEquals(LastFmClient.signature(params, "SECRET"), LastFmClient.signature(params + ("format" to "json"), "SECRET"))
    }

    @Test
    fun `the queue survives a new instance, keeps order, and is capped`() {
        val file = File(Files.createTempDirectory("sq").toFile(), "q.json")
        val q = ScrobbleQueue(file)
        q.add(PendingScrobble("listenbrainz", play.copy(title = "a")))
        q.add(PendingScrobble(PendingScrobble.SERVER, play.copy(title = "b"), provider = "subsonic", trackId = "t1"))
        val back = ScrobbleQueue(file).load()
        assertEquals(listOf("a", "b"), back.map { it.play.title })
        assertEquals("t1", back[1].trackId)
        q.replace(emptyList())
        assertFalse(file.exists())
        repeat(ScrobbleQueue.MAX + 5) { q.add(PendingScrobble("lastfm", play.copy(title = "$it"))) }
        val capped = q.load()
        assertEquals(ScrobbleQueue.MAX, capped.size)
        assertTrue(capped.first().play.title == "5", "the oldest are the ones dropped")
    }
}
