package com.engabd.sendpin.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * The metadata role's state, merged the way the spec says a `server/state` delta is
 * (§server/state): a shallow merge in which a present field replaces the old value, a
 * `null` field clears it, a nested object (`progress`) is replaced whole — never
 * deep-merged — and a `null` role object clears everything. Music Assistant happens to
 * send the full object every time, but a server that sends only `progress` on a seek
 * is within its rights and must not wipe the title.
 *
 * Pure, so the merge rules can be tested without a socket.
 */
class MetadataState {
    private val fields = LinkedHashMap<String, JsonElement>()

    /**
     * Apply one `server/state`'s `metadata` value: null for "key absent" (nothing
     * changes), [JsonNull] for "role cleared", else the changed fields. Returns whether
     * the state changed.
     */
    fun apply(metadata: JsonElement?): Boolean = when (metadata) {
        null -> false
        is JsonNull -> {
            val had = fields.isNotEmpty()
            fields.clear()
            had
        }
        is JsonObject -> {
            for ((key, value) in metadata) {
                if (value is JsonNull) fields.remove(key) else fields[key] = value
            }
            true
        }
        else -> false
    }

    fun clear() = fields.clear()

    val isEmpty: Boolean get() = fields.isEmpty()

    /** The merged state as the typed payload, or null when the role holds nothing. */
    fun snapshot(json: Json): ServerMetadataPayload? =
        if (fields.isEmpty()) null else json.decodeFromJsonElement(ServerMetadataPayload.serializer(), JsonObject(fields))
}
