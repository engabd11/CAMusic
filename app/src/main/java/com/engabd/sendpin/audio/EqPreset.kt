package com.engabd.sendpin.audio

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * A named equaliser curve, recalled in one go.
 *
 * The EQ tab exposes ten bands, a preamp, and an auto-preamp toggle — a dozen
 * controls that together describe one sound, and that a listener sets from
 * memory every time they switch from headphones to speakers or from a bass-heavy
 * track to a vocal one. This is the missing noun: it carries the whole curve and
 * nothing else, so a preset can be applied without touching the EQ's enabled
 * switch, and deleted without losing the curve it was modelled on.
 *
 * Same serialisation pattern as [com.engabd.sendpin.hue.ShowPreset]: @Serializable,
 * stable UUID id, encode/decode companions that return null on a read failure
 * rather than an empty list — the distinction that stops a corrupt read from
 * silently flattening every curve the listener took the trouble to save.
 *
 * @see LocalDsp.Config
 */
@Serializable
data class EqPreset(
    /**
     * Stable across renames, because [EqGenrePresetRule] points at presets by id.
     * A rule that broke every time a preset was renamed would be worse than no rules.
     */
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    /** The band settings, same shape as [LocalDsp.Config.bands]. */
    val bands: List<LocalDsp.Band> = LocalDsp.Config.defaultBands(),
    /** Applied before the bands; negative buys headroom for a boost. */
    val preampDb: Float = 0f,
    /** When true, [preampDb] is ignored and headroom is computed from the bands. */
    val autoPreamp: Boolean = true,
) {
    /** What the row under the name says: the number of bands moved off flat. */
    fun summary(): String {
        val moved = bands.count { it.enabled && it.altersSignal() }
        return buildString {
            append(if (moved == 0) "Flat" else "$moved band${if (moved == 1) "" else "s"}")
            append(" · ")
            append(if (autoPreamp) "auto preamp" else "${preampDb}dB preamp")
        }
    }

    /**
     * The [LocalDsp.Config] this preset describes, with the EQ enabled.
     *
     * The caller decides whether to actually switch the EQ on — applying a preset
     * is "this is the curve", not "turn the EQ on for me" — so this returns a
     * Config with [enabled] true, but the caller may wrap it or discard it.
     */
    fun toConfig(enabled: Boolean = true): LocalDsp.Config = LocalDsp.Config(
        enabled = enabled,
        bands = bands,
        preampDb = preampDb,
        autoPreamp = autoPreamp,
    )

    companion object {
        /**
         * Presets a listener has not made yet, so the feature is not an empty list.
         *
         * Each one is a starting point for a genre, not a definitive answer: a
         * listener who actually cares about bass will adjust the boost and save
         * their own. The frequencies are the ISO third-octave centres the default
         * band set already uses, so the editor shows the moved sliders in the
         * right places.
         */
        fun starters(): List<EqPreset> = listOf(
            EqPreset(
                name = "Bass boost",
                bands = flatBands().with(62f, +6f).with(125f, +4f),
                autoPreamp = true,
            ),
            EqPreset(
                name = "Vocal forward",
                bands = flatBands().with(125f, -2f).with(1_000f, +3f).with(2_000f, +2f),
                autoPreamp = true,
            ),
            EqPreset(
                name = "Flat",
                bands = flatBands(),
                autoPreamp = true,
            ),
            EqPreset(
                name = "Electronic",
                bands = flatBands().with(62f, +4f).with(250f, -2f).with(8_000f, +2f),
                autoPreamp = true,
            ),
            EqPreset(
                name = "Classical",
                bands = flatBands().with(31f, +2f).with(16_000f, +1f),
                autoPreamp = true,
            ),
        )

        /** The ten flat bands, for building starter presets without repeating the list. */
        private fun flatBands(): List<LocalDsp.Band> = LocalDsp.Config.defaultBands()

        /** Copy a band list with [freq]'s band gain set to [gainDb], leaving the rest alone. */
        private fun List<LocalDsp.Band>.with(freq: Float, gainDb: Float): List<LocalDsp.Band> =
            map { if (it.frequency == freq) it.copy(gainDb = gainDb) else it }

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun encode(list: List<EqPreset>): String =
            json.encodeToString(ListSerializer(serializer()), list)

        /**
         * The stored list, or **null** when it could not be read.
         *
         * Null rather than an empty list, for the same reason
         * [com.engabd.sendpin.hue.ShowPreset.decode] does it: an empty list is a
         * real answer a caller may act on by overwriting, and turning a
         * recoverable decode failure into "the user has no presets" would then
         * destroy them on the next write.
         */
        fun decode(raw: String): List<EqPreset>? = runCatching {
            json.decodeFromString(ListSerializer(serializer()), raw)
        }.getOrNull()
    }
}

/**
 * Which EQ preset to apply for a track's genre, if any.
 *
 * Same matching contract as [com.engabd.sendpin.hue.GenrePresetRule]: genre
 * strings arrive from half a dozen different servers and agree about nothing,
 * so matching is case-insensitive and substring-based in *both* directions —
 * a rule for "jazz" catches "Vocal Jazz", and a rule for "Progressive House"
 * is caught by a track tagged "house". First rule wins, so the list order is
 * the priority order.
 *
 * A separate class rather than reusing the hue rule, because the two point at
 * different preset types: the hue rule's [presetId] resolves to a [ShowPreset],
 * this one's to an [EqPreset]. Merging them would couple two unrelated features
 * for the sake of one shared `matches` method.
 */
@Serializable
data class EqGenrePresetRule(
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

        fun encode(list: List<EqGenrePresetRule>): String =
            json.encodeToString(ListSerializer(serializer()), list)

        fun decode(raw: String): List<EqGenrePresetRule>? = runCatching {
            json.decodeFromString(ListSerializer(serializer()), raw)
        }.getOrNull()

        /**
         * The EQ preset for [trackGenre], or null to leave the curve alone.
         *
         * A track with no genre, or one nothing matches, deliberately changes
         * nothing rather than falling back to a default: the alternative is the
         * EQ resetting itself between two tracks off the same record because one
         * of them happened to carry a tag.
         */
        fun presetFor(
            rules: List<EqGenrePresetRule>,
            presets: List<EqPreset>,
            trackGenre: String?,
        ): EqPreset? {
            val genre = trackGenre?.takeIf { it.isNotBlank() } ?: return null
            val rule = rules.firstOrNull { it.matches(genre) } ?: return null
            return presets.firstOrNull { it.id == rule.presetId }
        }
    }
}