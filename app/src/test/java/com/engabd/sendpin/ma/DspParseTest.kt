package com.engabd.sendpin.ma

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [DspParse] — the bidirectional conversion between MA's `DSPConfig`
 * wire format and the app's [DspConfig] model. The wire shapes come from the
 * MA API schema (see `docs/improvement-roadmap.md` and the DSP command
 * reference PDF).
 */
class DspParseTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ── Parsing (server → app) ──────────────────────────────────────────

    @Test
    fun `parses a disabled default config`() {
        val wire = buildJsonObject {
            put("enabled", false)
            put("input_gain", 0.0)
            put("output_gain", 0.0)
        }
        val cfg = DspParse.config(wire)
        assertFalse(cfg.enabled)
        assertEquals(0f, cfg.inputGain)
        assertEquals(0f, cfg.outputGain)
        assertTrue(cfg.filters.isEmpty())
    }

    @Test
    fun `parses an enabled config with a parametric EQ filter`() {
        val wire = buildJsonObject {
            put("enabled", true)
            put("input_gain", 2.5)
            put("output_gain", -1.0)
            put("filters", kotlinx.serialization.json.JsonArray(listOf(
                buildJsonObject {
                    put("enabled", true)
                    put("type", "parametric_eq")
                    put("preamp", 3.0)
                    put("bands", kotlinx.serialization.json.JsonArray(listOf(
                        buildJsonObject {
                            put("frequency", 100.0)
                            put("q", 0.7)
                            put("gain", -3.5)
                            put("type", "low_shelf")
                            put("enabled", true)
                            put("channel", "ALL")
                        },
                        buildJsonObject {
                            put("frequency", 5000.0)
                            put("q", 1.4)
                            put("gain", 2.0)
                            put("type", "peak")
                            put("enabled", false)
                            put("channel", "FR")
                        }
                    )))
                }
            )))
        }
        val cfg = DspParse.config(wire)
        assertTrue(cfg.enabled)
        assertEquals(2.5f, cfg.inputGain)
        assertEquals(-1f, cfg.outputGain)
        assertEquals(1, cfg.filters.size)

        val eq = (cfg.filters[0] as DspFilter.Eq).filter
        assertTrue(eq.enabled)
        assertEquals(3f, eq.preamp)
        assertEquals(2, eq.bands.size)

        val b0 = eq.bands[0]
        assertEquals(100f, b0.frequency)
        assertEquals(0.7f, b0.q)
        assertEquals(-3.5f, b0.gain)
        assertEquals(EqBandType.LOW_SHELF, b0.bandType)
        assertTrue(b0.enabled)
        assertEquals(AudioChannel.ALL, b0.channel)

        val b1 = eq.bands[1]
        assertEquals(5000f, b1.frequency)
        assertEquals(EqBandType.PEAK, b1.bandType)
        assertFalse(b1.enabled)
        assertEquals(AudioChannel.FR, b1.channel)
    }

    @Test
    fun `parses a tone control filter`() {
        val wire = buildJsonObject {
            put("enabled", true)
            put("filters", kotlinx.serialization.json.JsonArray(listOf(
                buildJsonObject {
                    put("enabled", true)
                    put("type", "tone_control")
                    put("bass_level", 4.0)
                    put("mid_level", -2.0)
                    put("treble_level", 1.5)
                }
            )))
        }
        val cfg = DspParse.config(wire)
        val tone = (cfg.filters[0] as DspFilter.Tone).filter
        assertTrue(tone.enabled)
        assertEquals(4f, tone.bassLevel)
        assertEquals(-2f, tone.midLevel)
        assertEquals(1.5f, tone.trebleLevel)
    }

    @Test
    fun `parses mixed filter chain in order`() {
        val wire = buildJsonObject {
            put("enabled", true)
            put("filters", kotlinx.serialization.json.JsonArray(listOf(
                buildJsonObject {
                    put("enabled", true)
                    put("type", "parametric_eq")
                    put("bands", kotlinx.serialization.json.JsonArray(listOf(
                        buildJsonObject {
                            put("frequency", 1000.0)
                            put("q", 1.0)
                            put("gain", 0.0)
                            put("type", "peak")
                            put("enabled", true)
                            put("channel", "ALL")
                        }
                    )))
                },
                buildJsonObject {
                    put("enabled", true)
                    put("type", "tone_control")
                    put("bass_level", 0.0)
                    put("mid_level", 0.0)
                    put("treble_level", 0.0)
                }
            )))
        }
        val cfg = DspParse.config(wire)
        assertEquals(2, cfg.filters.size)
        assertTrue(cfg.filters[0] is DspFilter.Eq)
        assertTrue(cfg.filters[1] is DspFilter.Tone)
    }

    @Test
    fun `parses per-channel preamp`() {
        val wire = buildJsonObject {
            put("enabled", true)
            put("filters", kotlinx.serialization.json.JsonArray(listOf(
                buildJsonObject {
                    put("enabled", true)
                    put("type", "parametric_eq")
                    put("per_channel_preamp", buildJsonObject {
                        put("FL", 1.5)
                        put("FR", -0.5)
                    })
                    put("bands", kotlinx.serialization.json.JsonArray(listOf()))
                }
            )))
        }
        val cfg = DspParse.config(wire)
        val eq = (cfg.filters[0] as DspFilter.Eq).filter
        assertEquals(2, eq.perChannelPreamp.size)
        assertEquals(1.5f, eq.perChannelPreamp["FL"])
        assertEquals(-0.5f, eq.perChannelPreamp["FR"])
    }

    // ── Preset parsing ─────────────────────────────────────────────────

    @Test
    fun `parses a preset`() {
        val wire = buildJsonObject {
            put("name", "Headphone")
            put("preset_id", "abc123")
            put("config", buildJsonObject {
                put("enabled", true)
                put("input_gain", 0.0)
                put("output_gain", 0.0)
            })
        }
        val preset = DspParse.preset(wire)
        assertNotNull(preset)
        assertEquals("Headphone", preset.name)
        assertEquals("abc123", preset.presetId)
        assertTrue(preset.config.enabled)
    }

    @Test
    fun `parses a preset list`() {
        val arr = kotlinx.serialization.json.JsonArray(listOf(
            buildJsonObject {
                put("name", "Flat")
                put("config", buildJsonObject { put("enabled", false) })
            },
            buildJsonObject {
                put("name", "Bass Boost")
                put("preset_id", "xyz")
                put("config", buildJsonObject { put("enabled", true) })
            }
        ))
        val presets = DspParse.presets(arr)
        assertEquals(2, presets.size)
        assertEquals("Flat", presets[0].name)
        assertNull(presets[0].presetId)
        assertEquals("Bass Boost", presets[1].name)
        assertEquals("xyz", presets[1].presetId)
    }

    // ── Serialization (app → server) ───────────────────────────────────

    @Test
    fun `serializes a config back to wire format`() {
        val cfg = DspConfig(
            enabled = true,
            inputGain = 1.5f,
            outputGain = -0.5f,
            filters = listOf(
                DspFilter.Eq(ParametricEQFilter(
                    enabled = true,
                    preamp = 2f,
                    bands = listOf(EqBand(frequency = 500f, q = 1f, gain = 3f, typeWire = "peak")),
                )),
                DspFilter.Tone(ToneControlFilter(enabled = true, bassLevel = 2f, trebleLevel = -1f)),
            ),
        )
        val wire = DspParse.toJson(cfg)
        assertTrue(wire["enabled"].toString() == "true")
        // The round-trip through the parser should recover the same structure.
        val recovered = DspParse.config(wire)
        assertEquals(cfg.enabled, recovered.enabled)
        assertEquals(cfg.inputGain, recovered.inputGain)
        assertEquals(cfg.outputGain, recovered.outputGain)
        assertEquals(2, recovered.filters.size)
        assertTrue(recovered.filters[0] is DspFilter.Eq)
        assertTrue(recovered.filters[1] is DspFilter.Tone)

        val eq = (recovered.filters[0] as DspFilter.Eq).filter
        assertEquals(1, eq.bands.size)
        assertEquals(500f, eq.bands[0].frequency)
        assertEquals(3f, eq.bands[0].gain)

        val tone = (recovered.filters[1] as DspFilter.Tone).filter
        assertEquals(2f, tone.bassLevel)
        assertEquals(-1f, tone.trebleLevel)
    }

    @Test
    fun `serializes per-channel preamp`() {
        val cfg = DspConfig(
            enabled = true,
            filters = listOf(
                DspFilter.Eq(ParametricEQFilter(
                    enabled = true,
                    perChannelPreamp = mapOf("FL" to 1f, "FR" to -1f),
                )),
            ),
        )
        val wire = DspParse.toJson(cfg)
        val recovered = DspParse.config(wire)
        val eq = (recovered.filters[0] as DspFilter.Eq).filter
        assertEquals(1f, eq.perChannelPreamp["FL"])
        assertEquals(-1f, eq.perChannelPreamp["FR"])
    }

    @Test
    fun `serializes a preset`() {
        val preset = DspPreset(
            name = "Club",
            config = DspConfig(enabled = true, inputGain = 0f, outputGain = 0f),
            presetId = "p1",
        )
        val wire = DspParse.presetToJson(preset)
        assertEquals("Club", wire["name"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        assertNotNull(wire["config"])
        assertEquals("p1", wire["preset_id"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
    }

    // ── Edge cases ─────────────────────────────────────────────────────

    @Test
    fun `handles missing filters array`() {
        val wire = buildJsonObject {
            put("enabled", true)
            put("input_gain", 0.0)
            put("output_gain", 0.0)
        }
        val cfg = DspParse.config(wire)
        assertTrue(cfg.enabled)
        assertTrue(cfg.filters.isEmpty())
    }

    @Test
    fun `handles integer gain values`() {
        // MA may send gain as an integer instead of a float.
        val wire = buildJsonObject {
            put("enabled", true)
            put("input_gain", 3)
            put("output_gain", -2)
        }
        val cfg = DspParse.config(wire)
        assertEquals(3f, cfg.inputGain)
        assertEquals(-2f, cfg.outputGain)
    }

    @Test
    fun `handles unknown filter type gracefully`() {
        val wire = buildJsonObject {
            put("enabled", true)
            put("filters", kotlinx.serialization.json.JsonArray(listOf(
                buildJsonObject {
                    put("enabled", true)
                    put("type", "some_future_filter")
                    put("mystery_field", 42)
                }
            )))
        }
        val cfg = DspParse.config(wire)
        // Unknown filter types are silently dropped — forward compatibility.
        assertTrue(cfg.filters.isEmpty())
    }

    @Test
    fun `EqBand summary formats correctly`() {
        val band = EqBand(frequency = 1000f, q = 1f, gain = 2.5f, typeWire = "peak")
        assertEquals("1.0 kHz · peak · +2.5 dB", band.summary)

        val lowBand = EqBand(frequency = 80f, q = 0.7f, gain = -3f, typeWire = "low_shelf")
        assertEquals("80 Hz · low_shelf · -3.0 dB", lowBand.summary)
    }
}