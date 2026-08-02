package com.engabd.sendpin.ma

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What the speaker is actually being fed.
 *
 * `streamdetails.audio_format` is the format Music Assistant *opened* — the file as
 * the provider hands it over. The app used to label that "Playing", which is why the
 * codec badge always agreed with the source however much converting MA did on the
 * way out. The real per-player output is `streamdetails.dsp[<player_id>]
 * .output_format`, and picking the right entry out of that map is what these cover.
 */
class MaStreamDetailsTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun queue(body: String) =
        MaParse.queues(json.parseToJsonElement("[$body]"), "http://ma.local:8095").single()

    @Test
    fun `the dsp entry for a player is what that player is playing`() {
        val q = queue(
            """
            {
              "queue_id": "kitchen",
              "current_item": {
                "queue_item_id": "q1",
                "streamdetails": {
                  "audio_format": {"content_type": "flac", "sample_rate": 96000, "bit_depth": 24},
                  "dsp": {
                    "kitchen": {
                      "state": "enabled",
                      "output_format": {"content_type": "flac", "sample_rate": 48000, "bit_depth": 16}
                    }
                  }
                }
              }
            }
            """
        )
        // Source: the hi-res file MA opened.
        assertEquals("flac", q.inputFormat?.codec)
        assertEquals(96000, q.inputFormat?.sampleRateHz)
        assertEquals(24, q.inputFormat?.bitDepth)
        // Playing: what the speaker is actually handed, which is not the same thing.
        val out = q.outputFor("kitchen")
        assertEquals(48000, out?.sampleRateHz)
        assertEquals(16, out?.bitDepth)
    }

    @Test
    fun `a synced member falls back to the leader's entry, then to a lone one`() {
        val q = queue(
            """
            {
              "queue_id": "lounge",
              "current_item": {
                "queue_item_id": "q1",
                "streamdetails": {
                  "audio_format": {"content_type": "flac", "sample_rate": 44100, "bit_depth": 16},
                  "dsp": {
                    "lounge": {
                      "output_format": {"content_type": "opus", "sample_rate": 48000, "bit_depth": 16}
                    }
                  }
                }
              }
            }
            """
        )
        // The member has no entry of its own; the leader's is the honest answer.
        assertEquals("opus", q.outputFor(playerId = "bedroom", leaderId = "lounge")?.codec)
        // And with neither id matching, a single unambiguous entry still resolves.
        assertEquals("opus", q.outputFor(playerId = "bedroom")?.codec)
    }

    @Test
    fun `no dsp map at all falls back to what MA opened`() {
        val q = queue(
            """
            {
              "queue_id": "p1",
              "current_item": {
                "queue_item_id": "q1",
                "streamdetails": {
                  "audio_format": {"content_type": "mpeg", "sample_rate": 44100, "bit_depth": 16, "bit_rate": 320}
                }
              }
            }
            """
        )
        val out = q.outputFor("p1")
        assertEquals("mpeg", out?.codec)
        assertEquals(320, out?.bitrateKbps)
    }

    @Test
    fun `a disabled dsp pipeline still reports the format it is emitting`() {
        // "disabled" means no filtering, not no output — the format is still the
        // honest answer to what the speaker receives.
        val q = queue(
            """
            {
              "queue_id": "p1",
              "current_item": {
                "queue_item_id": "q1",
                "streamdetails": {
                  "audio_format": {"content_type": "flac", "sample_rate": 44100, "bit_depth": 16},
                  "dsp": {
                    "p1": {
                      "state": "disabled",
                      "output_format": {"content_type": "flac", "sample_rate": 44100, "bit_depth": 16}
                    }
                  }
                }
              }
            }
            """
        )
        assertEquals("flac", q.outputFor("p1")?.codec)
        // Nothing changed on the way out, which the badge reads as "no transcoding".
        assertEquals(true, q.inputFormat?.sameFormatAs(q.outputFor("p1")!!))
    }

    @Test
    fun `a bitrate in bits per second is normalised to kbps`() {
        // MA documents bit_rate in kbps, but a provider filling it in bits per second
        // would otherwise render as "1411000k" on the badge.
        val q = queue(
            """
            {
              "queue_id": "p1",
              "current_item": {
                "queue_item_id": "q1",
                "streamdetails": {
                  "audio_format": {"content_type": "flac", "sample_rate": 44100, "bit_depth": 16, "bit_rate": 1411000}
                }
              }
            }
            """
        )
        assertEquals(1411, q.inputFormat?.bitrateKbps)
    }

    @Test
    fun `an unknown codec is no reading at all, not a badge saying unknown`() {
        val q = queue(
            """
            {
              "queue_id": "p1",
              "current_item": {
                "queue_item_id": "q1",
                "streamdetails": {"audio_format": {"content_type": "?", "sample_rate": 44100}}
              }
            }
            """
        )
        assertNull(q.inputFormat)
        assertNull(q.outputFor("p1"))
    }

    @Test
    fun `the legacy flat streamdetails shape is still read`() {
        // Older servers put codec and rate at the streamdetails root rather than
        // nested under audio_format.
        val q = queue(
            """
            {
              "queue_id": "p1",
              "current_item": {
                "queue_item_id": "q1",
                "streamdetails": {"codec": "flac", "sample_rate": 44100, "bit_depth": 16}
              }
            }
            """
        )
        assertEquals("flac", q.inputFormat?.codec)
        assertEquals(44100, q.inputFormat?.sampleRateHz)
    }

    @Test
    fun `a queue item's index is offset by the page it came from`() {
        // Without this, every page's fallback index restarts at zero and
        // `player_queues/play_index` plays the wrong track past the first page.
        val page = """
            [
              {"queue_item_id": "a", "name": "One"},
              {"queue_item_id": "b", "name": "Two"}
            ]
        """
        val second = MaParse.queueItems(json.parseToJsonElement(page), null, indexOffset = 500)
        assertEquals(listOf(500, 501), second.map { it.index })

        // A server that sends `index` is believed over the fallback.
        val explicit = MaParse.queueItems(
            json.parseToJsonElement("""[{"queue_item_id": "a", "name": "One", "index": 7}]"""),
            null,
            indexOffset = 500,
        )
        assertEquals(7, explicit.single().index)
    }
}
