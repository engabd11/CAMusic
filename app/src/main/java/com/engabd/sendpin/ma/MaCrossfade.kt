package com.engabd.sendpin.ma

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

/**
 * Turning a Music Assistant player's own crossfade on for a DJ Radio set, and back.
 *
 * On Music Assistant the server builds the queue and decodes the audio, so the phone
 * cannot crossfade; the player's own setting is what does it. That setting is a
 * per-player config entry whose spelling has moved between MA versions: a plain
 * `crossfade` boolean, then a mode with named options (MA's Smart Fades, which plans
 * the join from its own beat analysis, alongside a standard crossfade and "disabled").
 * So nothing here hard-codes a type: the entry is found by the keys MA has used, and
 * "on" is read off the options the server itself declares.
 */
object MaCrossfade {

    /** The plain keys MA has used for a player's crossfade, newest first. */
    val KEYS = listOf("smart_fades_mode", "crossfade_mode", "crossfade")

    /** Option values that mean "no crossfade". Anything else counts as on. */
    private val OFF = setOf("disabled", "off", "none", "false", "0", "")

    /** One change to make, and what to put back afterwards. */
    data class Change(
        val key: String,
        val on: JsonPrimitive,
        /** The value in force before the change, for [Restore]. */
        val previous: JsonPrimitive,
        /** "Smart Fades" or "crossfade", for the toast. */
        val description: String,
    )

    /**
     * The change that turns crossfade on for a player with these [entries], or null
     * when the player has no crossfade setting or it is already on. A listener who has
     * already chosen a crossfade keeps their choice; DJ Radio only switches it on when
     * it is off.
     */
    fun turnOn(entries: List<MaConfigEntry>): Change? {
        val entry = KEYS.firstNotNullOfOrNull { key -> entries.firstOrNull { it.plainKey == key } } ?: return null
        val current = entry.value ?: return null
        if (entry.type == "boolean") {
            if (current.content.toBooleanStrictOrNull() == true) return null
            return Change(entry.key, JsonPrimitive(true), current, "crossfade")
        }
        if (entry.options.isEmpty()) return null
        if (current.content.lowercase() !in OFF) return null
        val values = entry.options.map { it.first }
        val smart = values.firstOrNull { it.contains("smart", ignoreCase = true) && it.lowercase() !in OFF }
        val chosen = smart ?: values.firstOrNull { it.lowercase() !in OFF } ?: return null
        return Change(
            key = entry.key,
            on = JsonPrimitive(chosen),
            previous = current,
            description = if (smart != null) "Smart Fades" else "crossfade",
        )
    }

    /**
     * What to put back once a set ends, kept in settings until it has been put back.
     *
     * Persisted because a set can outlive the process: if the app is killed mid-set the
     * player would otherwise keep a crossfade the listener never chose. The next start
     * puts it back (see `LibraryViewModel.restoreMaCrossfade`).
     */
    @Serializable
    data class Restore(
        val playerId: String,
        val key: String,
        /** The previous value, as JSON text, so a boolean stays a boolean. */
        val previousJson: String,
    )
}
