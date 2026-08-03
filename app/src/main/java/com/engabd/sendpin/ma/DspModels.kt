package com.engabd.sendpin.ma

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

// ─── Enums ────────────────────────────────────────────────────────────────

/** The filter types MA's parametric EQ supports. */
enum class EqBandType(val wire: String) {
    PEAK("peak"),
    HIGH_SHELF("high_shelf"),
    LOW_SHELF("low_shelf"),
    HIGH_PASS("high_pass"),
    LOW_PASS("low_pass"),
    NOTCH("notch"),
    UNKNOWN("unknown");

    companion object {
        fun fromWire(s: String?) = entries.firstOrNull { it.wire == s } ?: UNKNOWN
    }
}

/** Which channel(s) a band targets. */
enum class AudioChannel(val wire: String) {
    ALL("ALL"),
    FL("FL"),
    FR("FR");

    companion object {
        fun fromWire(s: String?) = entries.firstOrNull { it.wire == s } ?: ALL
    }
}

// ─── Config models (mirror the MA DSPConfig schema exactly) ────────────────

/**
 * One parametric EQ band — the same shape MA's
 * `ParametricEQBand` schema defines.
 */
@Serializable
data class EqBand(
    @SerialName("frequency") val frequency: Float = 1000f,
    @SerialName("q") val q: Float = 1f,
    @SerialName("gain") val gain: Float = 0f,
    @SerialName("type") val typeWire: String = "peak",
    @SerialName("enabled") val enabled: Boolean = true,
    @SerialName("channel") val channelWire: String = "ALL",
) {
    val bandType get() = EqBandType.fromWire(typeWire)
    val channel get() = AudioChannel.fromWire(channelWire)

    /** A human label, e.g. "1.0 kHz · peak · +2.0 dB". */
    val summary: String
        get() {
            val freqStr = when {
                frequency >= 1000f -> "%.1f kHz".format(frequency / 1000f)
                else -> "%.0f Hz".format(frequency)
            }
            val gainStr = "%+.1f dB".format(gain)
            return "$freqStr · ${bandType.wire} · $gainStr"
        }
}

/**
 * A parametric EQ filter — one entry in the `filters[]` chain.
 * Carries an arbitrary number of [EqBand]s plus per-channel preamp.
 */
@Serializable
@Immutable
data class ParametricEQFilter(
    @SerialName("enabled") val enabled: Boolean = false,
    @SerialName("preamp") val preamp: Float = 0f,
    @SerialName("per_channel_preamp") val perChannelPreamp: Map<String, Float> = emptyMap(),
    @SerialName("type") val type: String = "parametric_eq",
    @SerialName("bands") val bands: List<EqBand> = emptyList(),
)

/**
 * A simple 3-band tone control — bass / mid / treble levels in dB.
 */
@Serializable
data class ToneControlFilter(
    @SerialName("enabled") val enabled: Boolean = false,
    @SerialName("type") val type: String = "tone_control",
    @SerialName("bass_level") val bassLevel: Float = 0f,
    @SerialName("mid_level") val midLevel: Float = 0f,
    @SerialName("treble_level") val trebleLevel: Float = 0f,
)

/**
 * Which filter a [DspConfig] entry is. MA's schema uses a `oneOf` with a
 * `type` discriminator.
 */
@Immutable
sealed class DspFilter {
    abstract val enabled: Boolean

    data class Eq(val filter: ParametricEQFilter) : DspFilter() {
        override val enabled get() = filter.enabled
    }

    data class Tone(val filter: ToneControlFilter) : DspFilter() {
        override val enabled get() = filter.enabled
    }
}

/**
 * The full per-player DSP configuration — mirrors MA's `DSPConfig` schema.
 *
 * MA runs an ordered filter chain: input gain → filters[] → output gain →
 * limiter. The `filters` array may contain a mix of parametric EQ and tone
 * control entries, applied in order.
 */
@Immutable
data class DspConfig(
    val enabled: Boolean = false,
    val inputGain: Float = 0f,
    val outputGain: Float = 0f,
    val filters: List<DspFilter> = emptyList(),
)

/**
 * A named, reusable DSP preset — mirrors MA's `DSPConfigPreset` schema.
 */
data class DspPreset(
    val name: String,
    val config: DspConfig,
    val presetId: String? = null,
)

// ─── Parsing / serialization ──────────────────────────────────────────────

/**
 * Parse a `DSPConfig` JSON object from `config/players/dsp/get`.
 *
 * The `filters` array uses a `type` discriminator (`"parametric_eq"` or
 * `"tone_control"`) to decide which schema each entry follows.
 */
object DspParse {

    fun config(o: JsonObject): DspConfig {
        val enabled = (o["enabled"] as? JsonPrimitive)?.booleanOrNull ?: false
        val inputGain = num(o["input_gain"]) ?: 0f
        val outputGain = num(o["output_gain"]) ?: 0f
        val filtersArr = o["filters"]
        val filters = mutableListOf<DspFilter>()
        if (filtersArr is kotlinx.serialization.json.JsonArray) {
            for (entry in filtersArr) {
                val fo = entry as? JsonObject ?: continue
                val type = str(fo["type"]) ?: continue
                when (type) {
                    "parametric_eq" -> filters += DspFilter.Eq(eqFilter(fo))
                    "tone_control" -> filters += DspFilter.Tone(toneFilter(fo))
                }
            }
        }
        return DspConfig(enabled, inputGain, outputGain, filters)
    }

    fun preset(o: JsonObject): DspPreset? {
        val name = str(o["name"]) ?: return null
        val configObj = o["config"] as? JsonObject ?: return DspPreset(name, DspConfig(), str(o["preset_id"]))
        return DspPreset(name, config(configObj), str(o["preset_id"]))
    }

    fun presets(arr: kotlinx.serialization.json.JsonArray?): List<DspPreset> =
        arr?.mapNotNull { preset(it as? JsonObject ?: return@mapNotNull null) } ?: emptyList()

    private fun eqFilter(o: JsonObject): ParametricEQFilter {
        val enabled = (o["enabled"] as? JsonPrimitive)?.booleanOrNull ?: false
        val preamp = num(o["preamp"]) ?: 0f
        val perCh = (o["per_channel_preamp"] as? JsonObject)
            ?.mapNotNull { (k, v) -> num(v)?.let { k to it } }
            ?.toMap() ?: emptyMap()
        val bandsArr = o["bands"] as? kotlinx.serialization.json.JsonArray
        val bands = bandsArr?.mapNotNull { b ->
            val bo = b as? JsonObject ?: return@mapNotNull null
            EqBand(
                frequency = num(bo["frequency"]) ?: 1000f,
                q = num(bo["q"]) ?: 1f,
                gain = num(bo["gain"]) ?: 0f,
                typeWire = str(bo["type"]) ?: "peak",
                enabled = (bo["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true,
                channelWire = str(bo["channel"]) ?: "ALL",
            )
        } ?: emptyList()
        return ParametricEQFilter(enabled, preamp, perCh, "parametric_eq", bands)
    }

    private fun toneFilter(o: JsonObject): ToneControlFilter {
        return ToneControlFilter(
            enabled = (o["enabled"] as? JsonPrimitive)?.booleanOrNull ?: false,
            type = "tone_control",
            bassLevel = num(o["bass_level"]) ?: 0f,
            midLevel = num(o["mid_level"]) ?: 0f,
            trebleLevel = num(o["treble_level"]) ?: 0f,
        )
    }

    // ── Serialization back to MA's wire format ──────────────────────────

    private fun jBool(b: Boolean) = JsonPrimitive(b)
    private fun jNum(f: Float) = JsonPrimitive(f)
    private fun jStr(s: String) = JsonPrimitive(s)

    fun toJson(config: DspConfig): JsonObject = buildJsonObject {
        put("enabled", jBool(config.enabled))
        put("input_gain", jNum(config.inputGain))
        put("output_gain", jNum(config.outputGain))
        if (config.filters.isNotEmpty()) {
            put("filters", kotlinx.serialization.json.JsonArray(config.filters.map { filterJson(it) }))
        }
    }

    fun presetToJson(preset: DspPreset): JsonObject = buildJsonObject {
        put("name", jStr(preset.name))
        put("config", toJson(preset.config))
        preset.presetId?.let { put("preset_id", jStr(it)) }
    }

    private fun filterJson(f: DspFilter): JsonObject = when (f) {
        is DspFilter.Eq -> buildJsonObject {
            put("enabled", jBool(f.filter.enabled))
            put("type", jStr("parametric_eq"))
            put("preamp", jNum(f.filter.preamp))
            if (f.filter.perChannelPreamp.isNotEmpty()) {
                put("per_channel_preamp", buildJsonObject {
                    f.filter.perChannelPreamp.forEach { (k, v) -> put(k, jNum(v)) }
                })
            }
            if (f.filter.bands.isNotEmpty()) {
                put("bands", kotlinx.serialization.json.JsonArray(f.filter.bands.map { bandJson(it) }))
            }
        }
        is DspFilter.Tone -> buildJsonObject {
            put("enabled", jBool(f.filter.enabled))
            put("type", jStr("tone_control"))
            put("bass_level", jNum(f.filter.bassLevel))
            put("mid_level", jNum(f.filter.midLevel))
            put("treble_level", jNum(f.filter.trebleLevel))
        }
    }

    private fun bandJson(b: EqBand): JsonObject = buildJsonObject {
        put("frequency", jNum(b.frequency))
        put("q", jNum(b.q))
        put("gain", jNum(b.gain))
        put("type", jStr(b.typeWire))
        put("enabled", jBool(b.enabled))
        put("channel", jStr(b.channelWire))
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun str(el: kotlinx.serialization.json.JsonElement?): String? =
        (el as? JsonPrimitive)?.contentOrNull

    private fun num(el: kotlinx.serialization.json.JsonElement?): Float? =
        (el as? JsonPrimitive)?.let { it.floatOrNull ?: it.contentOrNull?.toFloatOrNull() }
}