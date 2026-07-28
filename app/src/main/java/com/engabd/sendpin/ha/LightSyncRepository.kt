package com.engabd.sendpin.ha

import kotlinx.serialization.json.*

/** A selectable media player the area can follow. */
data class HaMediaPlayer(val entityId: String, val name: String)

/** One Hue Synco entertainment area (an HA device) with its controllable entities. */
data class LightArea(
    val id: String,                 // HA device_id
    val name: String,
    val enabled: Boolean,
    val switchEntity: String?,
    val mode: String?,
    val modeEntity: String?,
    val modeOptions: List<String>,
    val effect: String?,
    val effectEntity: String?,
    val effectOptions: List<String>,
    val colour: String?,
    val colourEntity: String?,
    val colourOptions: List<String>,
    val brightnessPct: Int,
    val brightnessEntity: String?,
    val timingMs: Int,
    val timingEntity: String?,
    // Settings driven via the hue_music_sync.set_options service (not entities):
    val mediaPlayer: String,        // "" = Auto (follow whatever plays)
    val autoLevels: List<String>,   // Auto rungs (only meaningful when mode == auto)
    val autoTiming: Boolean,
    val advanced: Boolean,
    val tunables: Map<String, Float>,
)

/**
 * Discovers and drives the **Hue Synco** integration's per-area entities over the
 * HA WebSocket API. Each area is an HA device carrying a `switch` (enable), three
 * `select`s (mode / effect / colour) and two `number`s (brightness / timing).
 * MA-only stack by design — light sync lives in Home Assistant alongside MA.
 */
class LightSyncRepository(private val ha: HaClient) {

    companion object {
        const val PLATFORM = "hue_music_sync"
        // Colour swatches surfaced as dots in the UI (subset of the ColorScheme enum).
        val PALETTE_SWATCHES = listOf(
            "sunset", "ocean", "forest", "lavender", "ember", "rainbow",
        )
        const val ALBUM_COLOUR = "album_art_v2"
        // Advanced live tunables (keys + labels match const.TUNABLE_KEYS in the integration).
        val TUNABLE_DEFS = listOf(
            "reactivity" to "Reactivity", "glow" to "Glow", "movement" to "Movement",
            "contrast" to "Contrast", "colour_speed" to "Colour speed", "loudness" to "Loudness",
        )
        // The intensity rungs Auto may pick from (const.INTENSITY_LADDER).
        val AUTO_RUNGS = listOf("subtle", "medium", "high", "intense", "extreme")
    }

    suspend fun discover(): List<LightArea> {
        val entities = ha.entityRegistryList()
        val devices = ha.deviceRegistryList()
        val states = ha.getStates()

        val deviceName = HashMap<String, String>()
        for (d in devices) {
            val o = d as? JsonObject ?: continue
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: continue
            deviceName[id] = (o["name_by_user"]?.jsonPrimitive?.contentOrNull
                ?: o["name"]?.jsonPrimitive?.contentOrNull ?: id)
        }

        // entity_id -> (state, attributes)
        val stateOf = HashMap<String, Pair<String?, JsonObject>>()
        for (s in states) {
            val o = s as? JsonObject ?: continue
            val eid = o["entity_id"]?.jsonPrimitive?.contentOrNull ?: continue
            stateOf[eid] = (o["state"]?.jsonPrimitive?.contentOrNull) to
                (o["attributes"] as? JsonObject ?: JsonObject(emptyMap()))
        }

        // Group our entities by device.
        val byDevice = LinkedHashMap<String, MutableList<JsonObject>>()
        for (e in entities) {
            val o = e as? JsonObject ?: continue
            if (o["platform"]?.jsonPrimitive?.contentOrNull != PLATFORM) continue
            if (o["disabled_by"]?.jsonPrimitive?.contentOrNull != null) continue
            val dev = o["device_id"]?.jsonPrimitive?.contentOrNull ?: continue
            byDevice.getOrPut(dev) { mutableListOf() }.add(o)
        }

        return byDevice.map { (deviceId, ents) ->
            fun find(pred: (JsonObject) -> Boolean): JsonObject? = ents.firstOrNull(pred)
            fun key(o: JsonObject) = o["translation_key"]?.jsonPrimitive?.contentOrNull
            fun uid(o: JsonObject) = o["unique_id"]?.jsonPrimitive?.contentOrNull ?: ""
            fun eid(o: JsonObject?) = o?.get("entity_id")?.jsonPrimitive?.contentOrNull

            val switch = find { it["entity_id"]?.jsonPrimitive?.contentOrNull?.startsWith("switch.") == true && uid(it).endsWith("_sync") }
            val mode = find { key(it) == "mode" || uid(it).endsWith("_mode") }
            val effect = find { key(it) == "effect" || uid(it).endsWith("_effect") }
            val colour = find { key(it) == "colour" || uid(it).endsWith("_colour") }
            val brightness = find { key(it) == "brightness" || uid(it).endsWith("_brightness") }
            val timing = find { key(it) == "timing" || uid(it).endsWith("_timing") }

            val switchEid = eid(switch)
            val modeEid = eid(mode)
            val effectEid = eid(effect)
            val colourEid = eid(colour)
            val brightnessEid = eid(brightness)
            val timingEid = eid(timing)

            fun st(entityId: String?) = entityId?.let { stateOf[it] }

            // The sync switch carries the non-entity settings as attributes (see the
            // integration's area_attributes): advanced/tunables/media_player/auto_*.
            val sw = st(switchEid)?.second ?: JsonObject(emptyMap())
            val tun = (sw["tunables"] as? JsonObject)?.mapNotNull { (k, v) ->
                val f = (v as? JsonPrimitive)?.let { it.floatOrNull ?: it.contentOrNull?.toFloatOrNull() }
                if (f != null) k to f else null
            }?.toMap() ?: emptyMap()

            LightArea(
                id = deviceId,
                name = (deviceName[deviceId] ?: "Area").removePrefix("Music Sync — ").removePrefix("Music Sync - "),
                enabled = st(switchEid)?.first == "on",
                switchEntity = switchEid,
                mode = st(modeEid)?.first,
                modeEntity = modeEid,
                modeOptions = optionsOf(st(modeEid)),
                effect = st(effectEid)?.first,
                effectEntity = effectEid,
                effectOptions = optionsOf(st(effectEid)),
                colour = st(colourEid)?.first,
                colourEntity = colourEid,
                colourOptions = optionsOf(st(colourEid)),
                brightnessPct = st(brightnessEid)?.first?.toFloatOrNull()?.toInt() ?: 70,
                brightnessEntity = brightnessEid,
                timingMs = st(timingEid)?.first?.toFloatOrNull()?.toInt() ?: 0,
                timingEntity = timingEid,
                mediaPlayer = sw["media_player"]?.jsonPrimitive?.contentOrNull ?: "",
                autoLevels = (sw["auto_levels"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: AUTO_RUNGS,
                autoTiming = sw["auto_timing"]?.jsonPrimitive?.booleanOrNull ?: false,
                advanced = sw["advanced"]?.jsonPrimitive?.booleanOrNull ?: false,
                tunables = tun,
            )
        }
    }

    /** Media players the areas can follow (active players + a friendly name). */
    suspend fun mediaPlayers(): List<HaMediaPlayer> = ha.getStates().mapNotNull { s ->
        val o = s as? JsonObject ?: return@mapNotNull null
        val eid = o["entity_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        if (!eid.startsWith("media_player.")) return@mapNotNull null
        val state = o["state"]?.jsonPrimitive?.contentOrNull
        if (state == "unavailable") return@mapNotNull null
        val name = (o["attributes"] as? JsonObject)?.get("friendly_name")?.jsonPrimitive?.contentOrNull ?: eid
        HaMediaPlayer(eid, name)
    }

    private fun optionsOf(entry: Pair<String?, JsonObject>?): List<String> =
        (entry?.second?.get("options") as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

    // --- control ----------------------------------------------------------

    suspend fun setEnabled(area: LightArea, on: Boolean) {
        val eid = area.switchEntity ?: return
        ha.callService("switch", if (on) "turn_on" else "turn_off", eid)
    }

    suspend fun setMode(area: LightArea, option: String) = selectOption(area.modeEntity, option)
    suspend fun setEffect(area: LightArea, option: String) = selectOption(area.effectEntity, option)
    suspend fun setColour(area: LightArea, option: String) = selectOption(area.colourEntity, option)

    private suspend fun selectOption(entityId: String?, option: String) {
        entityId ?: return
        ha.callService("select", "select_option", entityId, buildJsonObject { put("option", option) })
    }

    suspend fun setBrightness(area: LightArea, pct: Int) =
        setNumber(area.brightnessEntity, pct.coerceIn(5, 100))

    suspend fun setTiming(area: LightArea, ms: Int) =
        setNumber(area.timingEntity, ms.coerceIn(-500, 500))

    private suspend fun setNumber(entityId: String?, value: Int) {
        entityId ?: return
        ha.callService("number", "set_value", entityId, buildJsonObject { put("value", value) })
    }

    // --- advanced settings via the hue_music_sync.set_options service -------

    /** Call set_options targeting the area's sync switch (matches the HA card). */
    private suspend fun setOptions(area: LightArea, data: JsonObject) {
        val eid = area.switchEntity ?: return
        ha.callService(PLATFORM, "set_options", eid, data)
    }

    /** [entityId] "" = Auto (follow whatever plays). */
    suspend fun setFollowPlayer(area: LightArea, entityId: String) =
        setOptions(area, buildJsonObject { put("media_player", entityId) })

    suspend fun setAutoLevels(area: LightArea, levels: List<String>) =
        setOptions(area, buildJsonObject { put("auto_levels", JsonArray(levels.map { JsonPrimitive(it) })) })

    suspend fun setAutoTiming(area: LightArea, on: Boolean) =
        setOptions(area, buildJsonObject { put("auto_timing", on) })

    suspend fun setAdvanced(area: LightArea, on: Boolean) =
        setOptions(area, buildJsonObject { put("advanced", on) })

    /** Send the FULL tunable map (the integration replaces the stored dict). */
    suspend fun setTunables(area: LightArea, tunables: Map<String, Float>) =
        setOptions(area, buildJsonObject {
            put("tunables", buildJsonObject { tunables.forEach { (k, v) -> put(k, v) } })
        })
}
