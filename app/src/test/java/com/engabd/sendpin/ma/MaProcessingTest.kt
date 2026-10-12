package com.engabd.sendpin.ma

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `streamdetails.audio_processing`: Music Assistant's own account of every stage
 * between the file and each player, which the quality card's signal path is built from.
 */
class MaProcessingTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun queue(body: String) =
        MaParse.queues(json.parseToJsonElement("[$body]"), "http://ma.local:8095").single()

    @Test
    fun `shared processing and each player's leg are read`() {
        val q = queue(
            """
            {
              "queue_id": "up1",
              "current_item": {
                "queue_item_id": "q1",
                "streamdetails": {
                  "audio_format": {"content_type": "flac", "sample_rate": 44100, "bit_depth": 16},
                  "audio_processing": {
                    "input_fidelity": {"quality": "lossless", "bit_perfect": null},
                    "queue_processing": {
                      "pcm_format": {"content_type": "f32le", "sample_rate": 44100, "bit_depth": 32},
                      "normalization": {"mode": "dynamic", "measurement_source": "track", "target_lufs": -14, "measured_lufs": -9.5, "applied_gain_db": -4.5},
                      "playback_speed": 1.0,
                      "crossfade_mode": "smart_crossfade",
                      "overlay_active": false
                    },
                    "outputs": [
                      {
                        "player_ids": ["up1"],
                        "dsp": {
                          "state": "enabled", "input_gain": 0, "output_gain": -2, "preset_id": "p",
                          "filters": [
                            {"type": "parametric_eq", "enabled": true, "bands": []},
                            {"type": "tone_control", "enabled": false}
                          ]
                        },
                        "source_channel": "FL",
                        "output_format": {"content_type": "flac", "sample_rate": 48000, "bit_depth": 16},
                        "fidelity": {"quality": "lossless", "bit_perfect": false}
                      },
                      {
                        "player_ids": ["pc"],
                        "dsp": {"state": "disabled", "input_gain": 0, "output_gain": 0, "filters": []},
                        "output_format": {"content_type": "s24le", "sample_rate": 44100, "bit_depth": 24},
                        "fidelity": {"quality": "lossless", "bit_perfect": true}
                      }
                    ]
                  }
                }
              }
            }
            """
        )
        val shared = q.processing!!
        assertEquals("f32le", shared.pcmFormat?.codec)
        assertEquals(32, shared.pcmFormat?.bitDepth)
        assertEquals("dynamic", shared.normalizationMode)
        assertEquals(-4.5f, shared.appliedGainDb)
        assertEquals("smart_crossfade", shared.crossfadeMode)

        val phone = q.processingFor("up1")!!
        assertTrue(phone.dspActive)
        // A filter that is switched off is not in the path.
        assertEquals(listOf("parametric_eq"), phone.filters)
        assertEquals(-2f, phone.outputGainDb)
        assertEquals("FL", phone.sourceChannel)
        assertEquals(false, phone.bitPerfect)

        val pc = q.processingFor("pc")!!
        assertEquals(false, pc.dspActive)
        assertEquals(true, pc.bitPerfect)
    }

    @Test
    fun `a server without audio_processing reports none`() {
        val q = queue(
            """
            {"queue_id": "p1", "current_item": {"queue_item_id": "q1",
              "streamdetails": {"audio_format": {"content_type": "flac", "sample_rate": 44100, "bit_depth": 16}}}}
            """
        )
        assertNull(q.processing)
        assertNull(q.processingFor("p1"))
    }
}
