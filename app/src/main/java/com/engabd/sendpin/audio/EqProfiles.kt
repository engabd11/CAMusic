package com.engabd.sendpin.audio

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A curve saved under a name: one of yours, or an AutoEQ import. */
@Serializable
data class EqPreset(val name: String, val config: LocalDsp.Config)

/**
 * The equaliser's saved curves, and the optional curve per output.
 *
 * Kept beside the one curve the equaliser always had (`AppSettings.localDsp`),
 * which stays the curve in use whenever "a curve for each output" is off, and the
 * starting point for an output that has none of its own yet.
 *
 * @param byOutput a curve per [OutputKey.id], written the first time the curve is
 *   changed while that output is playing.
 * @param outputNames what to call each of those outputs, for the list of them.
 */
@Serializable
data class EqProfiles(
    val saved: List<EqPreset> = emptyList(),
    val perOutput: Boolean = false,
    val byOutput: Map<String, LocalDsp.Config> = emptyMap(),
    val outputNames: Map<String, String> = emptyMap(),
) {
    /** Save [config] as [name], replacing a curve already saved under that name. */
    fun withSaved(name: String, config: LocalDsp.Config): EqProfiles {
        val clean = name.trim().take(MAX_NAME)
        val rest = saved.filterNot { it.name.equals(clean, ignoreCase = true) }
        return copy(saved = (rest + EqPreset(clean, config)).takeLast(MAX_SAVED))
    }

    fun withoutSaved(name: String): EqProfiles = copy(saved = saved.filterNot { it.name == name })

    fun withOutputCurve(output: OutputKey, config: LocalDsp.Config): EqProfiles = copy(
        byOutput = byOutput + (output.id to config),
        outputNames = outputNames + (output.id to output.label),
    )

    fun withoutOutputCurve(id: String): EqProfiles = copy(byOutput = byOutput - id, outputNames = outputNames - id)

    companion object {
        const val MAX_SAVED = 40
        const val MAX_NAME = 40

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun encode(profiles: EqProfiles): String = json.encodeToString(serializer(), profiles)

        /** Null when the stored text cannot be read, for the reason [LocalDsp.decode] gives. */
        fun decode(raw: String): EqProfiles? = runCatching { json.decodeFromString(serializer(), raw) }.getOrNull()
    }
}

/** Which curve runs, and which curve an edit changes. */
object EqSelection {
    /**
     * The curve that should be running: the output's own when "a curve for each
     * output" is on and it has one, otherwise the one shared curve.
     */
    fun effective(global: LocalDsp.Config, profiles: EqProfiles, output: OutputKey?): LocalDsp.Config =
        if (profiles.perOutput && output != null) profiles.byOutput[output.id] ?: global else global

    /** Whether an edit made now belongs to [output] rather than to the shared curve. */
    fun editsOutput(profiles: EqProfiles, output: OutputKey?): Boolean = profiles.perOutput && output != null
}
