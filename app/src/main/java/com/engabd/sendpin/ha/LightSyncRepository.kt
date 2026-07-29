package com.engabd.sendpin.ha

import kotlinx.serialization.json.*

/** A selectable media player the area can follow. */
data class HaMediaPlayer(
    val entityId: String,
    val name: String,
    val state: String = "idle",       // playing | paused | idle | off | …
    val nowPlaying: String? = null,   // "Artist — Title" when something is on
) {
    val isPlaying get() = state == "playing"
}

/**
 * One of the integration's selectable colour schemes.
 *
 * [colours] are the palette's real anchor colours, converted from the HSV
 * anchors in syncoV2's `color/palette.py` — the same colours the bulbs will
 * actually cycle through, so a swatch previews the scheme rather than standing
 * in for it. Schemes are gradients, not single colours: Rainbow is seven stops
 * and Honolulu four, and a one-colour dot can't tell them apart.
 */
data class ColourScheme(val key: String, val label: String, val colours: List<Long>)

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

        /**
         * The 16 preset schemes from `ColorScheme` in syncoV2's const.py, with the
         * real anchor colours from its `_SCHEMES` table (HSV → sRGB). The three
         * dynamic schemes (album art v1/v2, song) are separate: they have no fixed
         * colours, so the UI shows them as live sources rather than swatches.
         */
        val PALETTES = listOf(
            ColourScheme("sunset", "Sunset", listOf(0xFFB62BD9, 0xFFFF268E, 0xFFFF2633, 0xFFFF6126, 0xFFFFA133, 0xFFFFCD4D)),
            ColourScheme("ocean", "Ocean", listOf(0xFF40FFD1, 0xFF26FFFF, 0xFF19BAFF, 0xFF1975FF, 0xFF2445F2)),
            ColourScheme("forest", "Forest", listOf(0xFF90FF4D, 0xFF37FF33, 0xFF24F25E, 0xFF30F2AC)),
            ColourScheme("lavender", "Lavender", listOf(0xFFA073FF, 0xFFC059FF, 0xFFFC66FF, 0xFFFF73C7)),
            ColourScheme("ember", "Ember", listOf(0xFFFF1927, 0xFFFF3519, 0xFFFF5E19, 0xFFFF9B26, 0xFFFFC633)),
            ColourScheme("aurora", "Aurora", listOf(0xFF33FFC2, 0xFF40FF5E, 0xFF33C2FF, 0xFF7D40FF, 0xFFFF59D1)),
            ColourScheme("rainbow", "Rainbow", listOf(0xFFFF1919, 0xFFFF8C19, 0xFFFFFF19, 0xFF27FF26, 0xFF26FFFF, 0xFF2726FF, 0xFFFF26FF)),
            ColourScheme("tropical", "Tropical", listOf(0xFFFF3395, 0xFFFF47ED, 0xFFFF334B, 0xFFFF602E, 0xFFFFB84D)),
            ColourScheme("savanna", "Savanna", listOf(0xFFFF3326, 0xFFFF6726, 0xFFFF8E26, 0xFFFFBA33, 0xFFFFD952)),
            ColourScheme("blossom", "Blossom", listOf(0xFFFF94B4, 0xFFFFB59E, 0xFFFFECAB, 0xFFE3A8FF, 0xFFB8EAFF)),
            ColourScheme("honolulu", "Honolulu", listOf(0xFFFF2667, 0xFFFF5A26, 0xFFD633FF, 0xFF33FFFF)),
            ColourScheme("galaxy", "Galaxy", listOf(0xFF1923FF, 0xFF6C26FF, 0xFFBA26FF, 0xFFFF38DF)),
            ColourScheme("neon", "Neon", listOf(0xFF0FF5FC, 0xFFFF2EF0, 0xFF27FF14, 0xFFFF2E73)),
            ColourScheme("peacock", "Peacock", listOf(0xFF1ED4E6, 0xFF1F78FF, 0xFF16D993, 0xFFFFC633)),
            ColourScheme("citrus", "Citrus", listOf(0xFFFFEC3D, 0xFF98EB2F, 0xFFFF971C, 0xFFFF615C)),
            ColourScheme("rosegold", "Rose gold", listOf(0xFFFFD0C2, 0xFFFF9A8C, 0xFFE07D5E, 0xFFF5CA5D)),
        )

        const val ALBUM_COLOUR = "album_art_v2"
        const val ALBUM_COLOUR_V1 = "album_art"
        const val SONG_COLOUR = "song"

        /** Every dynamic (runtime-derived) scheme, so the UI can recognise one. */
        val DYNAMIC_COLOURS = setOf(ALBUM_COLOUR, ALBUM_COLOUR_V1, SONG_COLOUR)

        // Advanced live tunables (keys + labels match const.TUNABLE_KEYS in the integration).
        val TUNABLE_DEFS = listOf(
            "reactivity" to "Reactivity", "glow" to "Glow", "movement" to "Movement",
            "contrast" to "Contrast", "colour_speed" to "Colour speed", "loudness" to "Loudness",
        )
        // The intensity rungs Auto may pick from (const.INTENSITY_LADDER).
        val AUTO_RUNGS = listOf("subtle", "medium", "high", "intense", "extreme")

        /** One-line summaries of each rung, from `SyncMode` in const.py. */
        val MODE_BLURBS = mapOf(
            "auto" to "Picks a level live from the music",
            "subtle" to "Seamless — steady level, colour just flows",
            "medium" to "Gentle club — visible dimming, soft flashes",
            "high" to "The band — kicks, guitar and vocals split across lamps",
            "intense" to "Club — the room follows energy, colour jumps on beats",
            "extreme" to "Max club — dark room, fast beats chase side to side",
        )

        /** From `SyncEffect` in const.py. */
        val EFFECT_BLURBS = mapOf(
            "music" to "Beat and frequency choreography",
            "movies" to "Calm — brightness follows the soundtrack",
            "fireworks" to "Bursts ignite on big beats, then fade",
        )
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

        return byDevice.mapNotNull { (deviceId, ents) ->
            fun find(pred: (JsonObject) -> Boolean): JsonObject? = ents.firstOrNull(pred)
            fun key(o: JsonObject) = o["translation_key"]?.jsonPrimitive?.contentOrNull
            fun uid(o: JsonObject) = o["unique_id"]?.jsonPrimitive?.contentOrNull ?: ""
            fun eid(o: JsonObject?) = o?.get("entity_id")?.jsonPrimitive?.contentOrNull

            fun isSwitch(o: JsonObject) =
                o["entity_id"]?.jsonPrimitive?.contentOrNull?.startsWith("switch.") == true
            // Prefer the canonical sync switch, but take any switch the device has
            // rather than lose a real zone to a renamed unique_id.
            val switch = find { isSwitch(it) && (uid(it).endsWith("_sync") || key(it) == "sync") }
                ?: find { isSwitch(it) }
            val mode = find { key(it) == "mode" || uid(it).endsWith("_mode") }
            val effect = find { key(it) == "effect" || uid(it).endsWith("_effect") }
            val colour = find { key(it) == "colour" || uid(it).endsWith("_colour") }
            val brightness = find { key(it) == "brightness" || uid(it).endsWith("_brightness") }
            val timing = find { key(it) == "timing" || uid(it).endsWith("_timing") }

            // An entertainment zone is a device with a sync switch on it. The
            // integration also registers service/diagnostic devices — the music
            // library among them — under the same platform, and those were being
            // listed as zones with every control dead, since there is nothing on
            // them to enable, dim or colour.
            val switchEid = eid(switch) ?: return@mapNotNull null
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

    /**
     * Media players the areas can follow. Carries each player's state and what it
     * is playing, and puts whatever is playing at the front — a house with a dozen
     * `media_player` entities is otherwise an unordered wall of identical pills.
     */
    suspend fun mediaPlayers(): List<HaMediaPlayer> = ha.getStates().mapNotNull { s ->
        val o = s as? JsonObject ?: return@mapNotNull null
        val eid = o["entity_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        if (!eid.startsWith("media_player.")) return@mapNotNull null
        val state = o["state"]?.jsonPrimitive?.contentOrNull ?: "idle"
        if (state == "unavailable") return@mapNotNull null
        val attrs = o["attributes"] as? JsonObject ?: JsonObject(emptyMap())
        fun attr(k: String) = attrs[k]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val title = attr("media_title")
        val artist = attr("media_artist")
        HaMediaPlayer(
            entityId = eid,
            name = attr("friendly_name") ?: eid,
            state = state,
            nowPlaying = when {
                title == null -> null
                artist == null -> title
                else -> "$artist — $title"
            },
        )
    }.sortedWith(compareByDescending<HaMediaPlayer> { it.isPlaying }.thenBy { it.name.lowercase() })

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
