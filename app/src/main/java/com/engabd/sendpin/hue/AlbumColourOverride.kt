package com.engabd.sendpin.hue

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

/**
 * Serialiser for [Rgb] — which is `Triple<Float, Float, Float>`, a typealias the
 * light-sync engine uses everywhere but which [Serializable] cannot handle
 * automatically (stdlib `Triple` has no serializer). Encoded as a comma-separated
 * string `"r,g,b"`, which is compact and reads naturally in a stored blob.
 */
object RgbSerializer : KSerializer<Rgb> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Rgb", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Rgb) {
        encoder.encodeString("${value.first},${value.second},${value.third}")
    }

    override fun deserialize(decoder: Decoder): Rgb {
        val parts = decoder.decodeString().split(",")
        return Triple(
            parts[0].toFloat(),
            parts[1].toFloat(),
            parts[2].toFloat(),
        )
    }
}

/**
 * Per-album colour palette overrides for Light Sync.
 *
 * The extractor in [AlbumColours] does a good job in the general case, but some
 * sleeves defeat it — a cover that is all one tone, or one where the dominant
 * cluster is a colour the room genuinely should not be. Rather than tuning the
 * algorithm for one record, a listener who knows what they want can pin a
 * palette to an album id and the show will use those colours verbatim.
 *
 * A map from album id to the explicit list of [Rgb] colours to use. Stored as a
 * JSON blob in DataStore, the same pattern as [ShowPreset]: one string key,
 * [encode]d and [decode]d through kotlinx.serialization, null on decode failure
 * so a corrupted blob is never mistaken for an intentional empty map.
 *
 * @see com.engabd.sendpin.data.AppSettings.albumColourOverrides
 */
@Serializable
data class AlbumColourOverride(
    val overrides: Map<String, List<@Serializable(with = RgbSerializer::class) Rgb>> = emptyMap(),
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun encode(map: Map<String, List<Rgb>>): String =
            json.encodeToString(serializer(), AlbumColourOverride(map))

        /**
         * The stored map, or **null** when it could not be read.
         *
         * Null rather than an empty map, for the same reason
         * [ShowPreset.decode] does it: an empty map is a real answer a caller
         * may act on by overwriting, and turning a recoverable decode failure
         * into "the user has no overrides" would destroy them on the next
         * write.
         */
        fun decode(raw: String): Map<String, List<Rgb>>? = runCatching {
            json.decodeFromString(serializer(), raw).overrides
        }.getOrNull()
    }
}