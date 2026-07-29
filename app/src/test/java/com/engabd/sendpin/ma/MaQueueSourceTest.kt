package com.engabd.sendpin.ma

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Where a track is streaming from. A library item's own `provider` is always
 * `library` — that's where Music Assistant filed it, not who holds the file — so
 * the source badge has to read the stream details and the provider mappings, which
 * is what stopped every Navidrome track from claiming to come from MA.
 */
class MaQueueSourceTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun queue(body: String) =
        MaParse.queues(json.parseToJsonElement("[$body]"), "http://ma.local:8095").single()

    @Test
    fun `stream details name the provider actually serving the track`() {
        val q = queue(
            """
            {
              "queue_id": "p1",
              "current_item": {
                "queue_item_id": "q1",
                "streamdetails": {
                  "provider": "opensubsonic--abc123",
                  "audio_format": {"content_type": "flac", "sample_rate": 44100, "bit_depth": 16}
                },
                "media_item": {"item_id": "7", "provider": "library", "name": "Song", "media_type": "track"}
              }
            }
            """
        )
        assertEquals("opensubsonic--abc123", q.streamProvider)
    }

    @Test
    fun `provider mappings survive on the media item`() {
        val q = queue(
            """
            {
              "queue_id": "p1",
              "current_item": {
                "queue_item_id": "q1",
                "media_item": {
                  "item_id": "7", "provider": "library", "name": "Song", "media_type": "track",
                  "provider_mappings": [
                    {"provider_domain": "opensubsonic", "provider_instance": "opensubsonic--abc"},
                    {"provider_domain": "opensubsonic", "provider_instance": "opensubsonic--abc"}
                  ]
                }
              }
            }
            """
        )
        // De-duplicated, so a track mapped twice doesn't look like two backends.
        assertEquals(listOf("opensubsonic"), q.currentItem?.providerDomains)
    }

    @Test
    fun `a queue with no current item reports no stream provider`() {
        val q = queue("""{"queue_id": "p1"}""")
        assertEquals(null, q.streamProvider)
        assertEquals(null, q.currentItem)
    }
}
