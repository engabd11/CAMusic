package com.engabd.sendpin.hue

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * A whole light show, named and recalled in one go.
 *
 * Everything on the Lights tab is one global set of switches: pick an intensity, a
 * palette, a brightness, six feature toggles and up to a dozen tunables, and that
 * is the show — until you want a different one, at which point you set all of it
 * again from memory. A dinner is not a party is not a film, and the difference
 * between them is a dozen controls nobody remembers the state of.
 *
 * So this is the missing noun. It carries **every** control that shapes a show and
 * nothing that does not: no bridge address, no entertainment area, no master
 * switch. Applying a preset must never move the room to another bridge, take the
 * lights over when they were off, or point at an area that has since been deleted.
 *
 * @see com.engabd.sendpin.data.AppSettings.showPresets
 */
@Serializable
data class ShowPreset(
    /**
     * Stable across renames, because [com.engabd.sendpin.data.AppSettings.genrePresetRules]
     * points at presets by id. A rule that broke every time a preset was renamed
     * would be worse than no rules.
     */
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val intensity: String = "high",
    /** Wire keys, the same comma-free list `lightSyncAutoLevels` stores. */
    val autoLevels: List<String> = listOf("subtle", "medium", "high"),
    val color: String = "album_art_v2",
    /** 5..100, as the slider offers. */
    val brightness: Int = 100,
    /** syncoV2 tunable names to multipliers. Missing keys mean 1.0. */
    val tunables: Map<String, Float> = emptyMap(),
    val spatial: Boolean = false,
    val musicDna: Boolean = false,
    val emotionalArc: Boolean = false,
    val phantomStage: Boolean = false,
    val stemSeparation: Boolean = false,
    val phoneConductor: Boolean = false,
) {
    /** What the row under the name says: the two or three things that differ most. */
    fun summary(): String {
        val layers = listOf(
            "Music DNA" to musicDna,
            "Emotional arc" to emotionalArc,
            "Phantom stage" to phantomStage,
            "Phone conductor" to phoneConductor,
            "Room gestures" to spatial,
        ).count { it.second }
        return buildString {
            append(intensity.replaceFirstChar { it.uppercase() })
            append(" · ")
            append(brightness)
            append('%')
            if (layers > 0) append(" · $layers layer${if (layers == 1) "" else "s"}")
        }
    }

    /**
     * Whether this preset describes the same show as [other] — everything except who
     * it is, which is [id] and [name].
     *
     * This is what "which preset is active" means. The alternative, remembering which
     * chip was last tapped, answers a different question: it stays lit after a slider
     * moves, is lost the moment the Lights tab is left, and says nothing at all when a
     * genre rule applies a preset by itself.
     *
     * Tunables are compared through [normalisedTunables], because an explicit 1.0 and a
     * missing key are the same show and the two spellings both occur — `applyShowPreset`
     * writes whatever the preset carried, and the screen writes every key it has a
     * slider for.
     */
    fun matches(other: ShowPreset): Boolean =
        intensity == other.intensity &&
            autoLevels.toSet() == other.autoLevels.toSet() &&
            color == other.color &&
            brightness == other.brightness &&
            spatial == other.spatial &&
            musicDna == other.musicDna &&
            emotionalArc == other.emotionalArc &&
            phantomStage == other.phantomStage &&
            stemSeparation == other.stemSeparation &&
            phoneConductor == other.phoneConductor &&
            normalisedTunables() == other.normalisedTunables()

    /**
     * [tunables] with the noise taken out: keys the engine does not have dropped, and
     * every multiplier that is neutral dropped with them, so "1.0" and "absent" are one
     * value. Rounded to three places — these arrive from a slider and from a JSON
     * round trip, and neither promises the last bit.
     */
    fun normalisedTunables(): Map<String, Float> = tunables
        .filterKeys { it in SyncoEngine.TUNABLE_KEYS }
        .mapValues { (_, v) -> Math.round(v.coerceIn(0f, 2f) * 1000f) / 1000f }
        .filterValues { it != 1f }

    companion object {
        /**
         * The id of the built-in [default] show.
         *
         * Fixed rather than a fresh [UUID], for two reasons that both bite later: a
         * [GenrePresetRule] points at a preset by id and would break on every restart,
         * and "is this the active one" cannot survive a process death against an id
         * that changes.
         */
        const val DEFAULT_ID = "__default__"

        /**
         * The show as it ships — the one every setting on the Lights tab falls back to
         * when it has never been touched.
         *
         * This needs no values of its own: `ShowPreset()`'s constructor defaults *are*
         * the app's per-setting defaults, one for one (`AppSettings.lightSyncIntensity`
         * and the ten flows beside it), and `captureShowPreset` uses the same literals
         * as its own fallbacks. Somewhere to go back to without undoing a preset by
         * hand, and it can never drift from the defaults it names.
         */
        fun default(): ShowPreset = ShowPreset(id = DEFAULT_ID, name = "Default")

        /** Presets a listener has not made yet, so the feature is not an empty list. */
        fun starters(): List<ShowPreset> = listOf(
            ShowPreset(
                name = "Dinner",
                intensity = "subtle",
                autoLevels = listOf("subtle"),
                brightness = 45,
                emotionalArc = true,
            ),
            ShowPreset(
                name = "Party",
                intensity = "extreme",
                autoLevels = listOf("high", "intense", "extreme"),
                brightness = 100,
                phantomStage = true,
                spatial = true,
            ),
            ShowPreset(
                name = "Film score",
                intensity = "medium",
                autoLevels = listOf("subtle", "medium"),
                brightness = 70,
                emotionalArc = true,
                spatial = true,
            ),
        )

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun encode(list: List<ShowPreset>): String =
            json.encodeToString(ListSerializer(serializer()), list)

        /**
         * The stored list, or **null** when it could not be read.
         *
         * Null rather than an empty list, for the same reason
         * [com.engabd.sendpin.data.AppSettings.decodeServers] does it: an empty list is
         * a real answer that a caller may act on by overwriting, and turning a
         * recoverable decode failure into "the user has no presets" would then
         * destroy them on the next write.
         */
        fun decode(raw: String): List<ShowPreset>? = runCatching {
            json.decodeFromString(ListSerializer(serializer()), raw)
        }.getOrNull()
    }
}

/**
 * Which preset to apply for a track's genre, if any.
 *
 * Genre strings arrive from half a dozen different servers and agree about
 * nothing — "Drum & Bass", "drum and bass", "Electronic; DnB" — so matching is
 * case-insensitive and substring-based in *both* directions: a rule for "jazz"
 * catches "Vocal Jazz", and a rule for "Progressive House" is caught by a track
 * tagged "house". First rule wins, so the list order is the priority order.
 */
@Serializable
data class GenrePresetRule(
    val genre: String = "",
    val presetId: String = "",
) {
    fun matches(trackGenre: String): Boolean {
        val a = genre.trim().lowercase()
        val b = trackGenre.trim().lowercase()
        if (a.isEmpty() || b.isEmpty()) return false
        return b.contains(a) || a.contains(b)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun encode(list: List<GenrePresetRule>): String =
            json.encodeToString(ListSerializer(serializer()), list)

        fun decode(raw: String): List<GenrePresetRule>? = runCatching {
            json.decodeFromString(ListSerializer(serializer()), raw)
        }.getOrNull()

        /**
         * The preset for [trackGenre], or null to leave the show alone.
         *
         * A track with no genre, or one nothing matches, deliberately changes
         * nothing rather than falling back to a default: the alternative is the
         * room resetting itself between two tracks off the same record because one
         * of them happened to carry a tag.
         */
        fun presetFor(
            rules: List<GenrePresetRule>,
            presets: List<ShowPreset>,
            trackGenre: String?,
        ): ShowPreset? {
            val genre = trackGenre?.takeIf { it.isNotBlank() } ?: return null
            val rule = rules.firstOrNull { it.matches(genre) } ?: return null
            return presets.firstOrNull { it.id == rule.presetId }
        }
    }
}
