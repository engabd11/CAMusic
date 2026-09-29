package com.engabd.sendpin.hue

import kotlinx.serialization.json.Json
import java.util.Base64
import java.util.UUID

/**
 * A saved light show as something that can be sent to someone else.
 *
 * `camusic://show/<preset>`: the [ShowPreset] as JSON, in URL-safe Base64, behind a
 * link the app opens. Plain text rather than a file so it goes through any chat,
 * and the same text pasted into the Lights tab does the same thing when a chat
 * app does not make the link tappable.
 *
 * A preset already carries nothing but the show — no bridge, no area, no master
 * switch (see [ShowPreset]) — so sharing one cannot hand anyone the sender's room.
 * What comes in is a new show beside the listener's own: a fresh [ShowPreset.id]
 * (the sender's id means nothing here, and must not collide with a local one), its
 * values clamped to what the controls allow, and never applied until they tap it.
 */
object ShowShare {

    const val SCHEME = "camusic"
    const val HOST = "show"
    private const val PREFIX = "$SCHEME://$HOST/"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    /** The link for [preset]. */
    fun link(preset: ShowPreset): String {
        // The id stays behind: it is the sender's, and a receiver gives it a new one.
        val body = json.encodeToString(ShowPreset.serializer(), preset.copy(id = ""))
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(body.toByteArray(Charsets.UTF_8))
    }

    /** What the share sheet sends: a line a person can read, and the link. */
    fun message(preset: ShowPreset): String =
        "Light show “${preset.name.ifBlank { "Untitled" }}” for CAMusic — open it in the app, " +
            "or paste it on the Lights tab:\n${link(preset)}"

    /**
     * The show in [text] — a link on its own, or a whole shared message — as a new
     * preset ready to save, or null when there is none or it does not decode.
     */
    fun parse(text: String?): ShowPreset? {
        if (text.isNullOrBlank()) return null
        val at = text.indexOf(PREFIX)
        if (at < 0) return null
        val encoded = text.substring(at + PREFIX.length).takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' }
        if (encoded.isEmpty()) return null
        val decoded = runCatching {
            String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
        }.getOrNull() ?: return null
        val preset = runCatching { json.decodeFromString(ShowPreset.serializer(), decoded) }.getOrNull()
            ?: return null
        return sanitise(preset)
    }

    /** Within what the Lights tab's own controls could have produced. */
    internal fun sanitise(p: ShowPreset): ShowPreset = p.copy(
        id = UUID.randomUUID().toString(),
        name = p.name.trim().take(40).ifBlank { "Shared show" },
        brightness = p.brightness.coerceIn(5, 100),
        tunables = p.tunables
            .filterKeys { it in SyncoEngine.TUNABLE_KEYS }
            .mapValues { (_, v) -> if (v.isFinite()) v.coerceIn(0f, 2f) else 1f },
    )
}
