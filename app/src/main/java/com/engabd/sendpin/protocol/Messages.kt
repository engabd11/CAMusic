package com.engabd.sendpin.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Sendspin wire messages, matching what Music Assistant actually speaks: plain
 * WebSocket + JSON, each message a `{ "type", "payload" }` envelope. Modeled on
 * the working massdroid client (MIT) and MA's own mobile app (Apache-2.0); see
 * `docs/protocol-alignment.md`.
 */

/**
 * MA multiplies a sometimes-float media duration by 1000 without an int cast, so
 * `track_duration` / `track_progress` arrive as `123456` **or** `123456.0`. A
 * strict `Long` serializer would reject the float, drop the whole `server/state`,
 * and trigger reconnect loops — so accept both shapes and truncate toward zero.
 */
object FlexibleLongSerializer : KSerializer<Long?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleLong", PrimitiveKind.LONG)

    override fun serialize(encoder: Encoder, value: Long?) {
        if (value != null) encoder.encodeLong(value)
    }

    override fun deserialize(decoder: Decoder): Long? {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeLong()
        val element = jsonDecoder.decodeJsonElement()
        if (element !is JsonPrimitive) return null
        if (element.isString) return element.content.toLongOrNull()
        return element.longOrNull ?: element.doubleOrNull?.toLong()
    }
}

// --- Outgoing messages ----------------------------------------------------

@Serializable
data class SendspinAuthMessage(
    val type: String = "auth",
    val token: String,
    @SerialName("client_id") val clientId: String,
)

@Serializable
data class DeviceInfo(
    @SerialName("product_name") val productName: String,
    val manufacturer: String,
    @SerialName("software_version") val softwareVersion: String,
)

@Serializable
data class PlayerV1Support(
    @SerialName("supported_formats") val supportedFormats: List<AudioFormatSpec>,
    // Bytes of compressed audio the server may stream ahead (Sendspin spec). ~30 s
    // of FLAC so a throughput dip rides the buffer instead of underrunning.
    @SerialName("buffer_capacity") val bufferCapacity: Int = 4_000_000,
    @SerialName("supported_commands") val supportedCommands: List<String> = listOf("volume", "mute"),
)

@Serializable
data class ClientHelloPayload(
    @SerialName("client_id") val clientId: String,
    val name: String,
    val version: Int = 1,
    @SerialName("supported_roles") val supportedRoles: List<String> = listOf("player@v1", "metadata@v1"),
    @SerialName("device_info") val deviceInfo: DeviceInfo? = null,
    @SerialName("player@v1_support") val playerV1Support: PlayerV1Support? = null,
)

@Serializable
data class SendspinClientHello(
    val type: String = "client/hello",
    val payload: ClientHelloPayload,
)

@Serializable
data class PlayerStateInfo(
    val volume: Int = 100,
    val muted: Boolean = false,
    @SerialName("static_delay_ms") val staticDelayMs: Int = 0,
)

@Serializable
data class ClientStatePayload(
    val state: String = "synchronized",
    val player: PlayerStateInfo = PlayerStateInfo(),
)

@Serializable
data class SendspinClientState(
    val type: String = "client/state",
    val payload: ClientStatePayload = ClientStatePayload(),
)

@Serializable
data class ClientTimePayload(@SerialName("client_transmitted") val clientTransmitted: Long)

@Serializable
data class SendspinClientTime(
    val type: String = "client/time",
    val payload: ClientTimePayload,
)

@Serializable
data class RequestFormatPlayerPayload(
    val codec: String,
    @SerialName("sample_rate") val sampleRate: Int = 48000,
    @SerialName("bit_depth") val bitDepth: Int = 16,
    val channels: Int = 2,
)

@Serializable
data class RequestFormatPayload(val player: RequestFormatPlayerPayload)

@Serializable
data class SendspinRequestFormat(
    val type: String = "stream/request-format",
    val payload: RequestFormatPayload,
)

@Serializable
data class GoodbyePayload(val reason: String = "user_request")

@Serializable
data class SendspinGoodbye(
    val type: String = "client/goodbye",
    val payload: GoodbyePayload = GoodbyePayload(),
)

// --- Incoming payloads ----------------------------------------------------

@Serializable
data class ServerTimePayload(
    @SerialName("client_transmitted") val clientTransmitted: Long,
    @SerialName("server_received") val serverReceived: Long,
    @SerialName("server_transmitted") val serverTransmitted: Long = 0,
)

@Serializable
data class StreamStartPlayerInfo(
    val codec: String = "opus",
    @SerialName("sample_rate") val sampleRate: Int = 48000,
    val channels: Int = 2,
    @SerialName("bit_depth") val bitDepth: Int = 16,
    @SerialName("codec_header") val codecHeader: String? = null,
)

@Serializable
data class StreamStartPayload(val player: StreamStartPlayerInfo = StreamStartPlayerInfo())

@Serializable
data class MetadataProgressPayload(
    /** Milliseconds into the current track, per the spec. */
    @SerialName("track_progress")
    @Serializable(with = FlexibleLongSerializer::class)
    val trackProgress: Long? = null,
    /** Total track length in milliseconds. */
    @SerialName("track_duration")
    @Serializable(with = FlexibleLongSerializer::class)
    val trackDuration: Long? = null,
    /**
     * Playback speed multiplier **×1000** — the spec's own unit, so 1000 is normal.
     *
     * Read through [speedMilli] rather than directly, and parsed flexibly for the
     * same reason `track_duration` is: Music Assistant is documented (see
     * `docs/protocol-alignment.md`) to send floats where the spec says integer, and a
     * strict `Int` here would throw and take the *entire* `server/state` down with
     * it — title, artist and progress all lost on a message that was only ever
     * awkward about one field.
     */
    @SerialName("playback_speed")
    @Serializable(with = FlexibleLongSerializer::class)
    val playbackSpeed: Long? = null,
) {
    /**
     * [playbackSpeed] normalised to the spec's ×1000 form, defaulting to normal.
     *
     * Guarded because this field is trivially easy to send in the obvious-but-wrong
     * unit: a server that means "normal speed" and writes a bare `1` would otherwise
     * be taken as 1/1000th speed, and the progress bar would sit still for the whole
     * track. Nothing sane sits between "a plain multiplier" and "×1000", so anything
     * at or below 10 is read as the former.
     */
    val speedMilli: Long
        get() = when {
            playbackSpeed == null || playbackSpeed <= 0L -> 1000L
            playbackSpeed <= 10L -> playbackSpeed * 1000L
            else -> playbackSpeed
        }
}

@Serializable
data class ServerMetadataPayload(
    val timestamp: Long? = null,
    val title: String? = null,
    val artist: String? = null,
    @SerialName("album_artist") val albumArtist: String? = null,
    val album: String? = null,
    @SerialName("artwork_url") val artworkUrl: String? = null,
    val year: Int? = null,
    val track: Int? = null,
    val progress: MetadataProgressPayload? = null,
    val repeat: String? = null,
    val shuffle: Boolean? = null,
)

@Serializable
data class ServerStatePayload(val metadata: ServerMetadataPayload? = null)

@Serializable
data class PlayerCommandPayload(
    val command: String,          // "volume" | "mute" | …
    val volume: Int? = null,
    val mute: Boolean? = null,
)

@Serializable
data class ServerCommandPayload(val player: PlayerCommandPayload? = null)

// --- A parsed, UI-friendly now-playing snapshot ---------------------------

data class NowPlaying(
    val title: String,
    val artist: String,
    val album: String,
    val artworkUrl: String?,
    val durationMs: Long?,
    val progressMs: Long?,
    /**
     * `metadata.timestamp` — the **server-clock microsecond** instant [progressMs] was
     * true at, in the same time domain as the binary audio frames.
     *
     * This is what makes the position bar exact rather than approximately right. The
     * spec's formula is
     * `progress + (now − timestamp) × playback_speed / 1_000_000`, so with the clock
     * filter mapping server time onto local time the reading can be aged precisely
     * instead of being assumed to describe the moment it happened to arrive. Null on a
     * server that omits it, which falls back to exactly that assumption.
     */
    val progressAtServerUs: Long? = null,
    /** Playback speed ×1000, per the spec; 1000 is normal. */
    val speedMilli: Long = 1000L,
)

// --- Incoming dispatch ----------------------------------------------------

sealed class SendspinIncoming {
    data object AuthOk : SendspinIncoming()
    data class AuthError(val message: String) : SendspinIncoming()
    data class ServerHello(val raw: JsonObject) : SendspinIncoming()

    // clientReceivedUs (T4) must be stamped at the WebSocket onMessage callback,
    // not here — capturing it after coroutine dispatch biases the clock offset.
    data class ServerTime(val payload: ServerTimePayload, val clientReceivedUs: Long = 0L) : SendspinIncoming()
    data class GroupUpdate(
        val playbackState: String? = null,
        val groupId: String? = null,
        val groupName: String? = null,
    ) : SendspinIncoming()
    data class StreamStart(val payload: StreamStartPayload) : SendspinIncoming()
    data object StreamEnd : SendspinIncoming()
    data object StreamClear : SendspinIncoming()
    data class ServerState(val payload: ServerStatePayload) : SendspinIncoming()
    data class ServerCommand(val payload: ServerCommandPayload) : SendspinIncoming()
    data class Unknown(val type: String) : SendspinIncoming()

    companion object {
        fun parse(text: String, json: Json): SendspinIncoming {
            val obj = json.parseToJsonElement(text).jsonObject
            val type = obj["type"]?.jsonPrimitive?.content ?: return Unknown("no_type")
            val payload = obj["payload"]
            return when (type) {
                "auth_ok" -> AuthOk
                "auth_error" -> AuthError(obj["message"]?.jsonPrimitive?.content ?: "Authentication failed")
                "server/hello" -> ServerHello(obj)
                "server/time" -> payload?.let {
                    ServerTime(json.decodeFromJsonElement(ServerTimePayload.serializer(), it))
                } ?: Unknown(type)
                "group/update" -> payload?.let {
                    GroupUpdate(
                        playbackState = (it as? JsonObject)?.get("playback_state")?.jsonPrimitive?.contentOrNull,
                        groupId = (it as? JsonObject)?.get("group_id")?.jsonPrimitive?.contentOrNull,
                        groupName = (it as? JsonObject)?.get("group_name")?.jsonPrimitive?.contentOrNull,
                    )
                } ?: GroupUpdate()
                "stream/start" -> StreamStart(
                    payload?.let { json.decodeFromJsonElement(StreamStartPayload.serializer(), it) }
                        ?: StreamStartPayload()
                )
                "stream/end" -> StreamEnd
                "stream/clear" -> StreamClear
                "server/state" -> ServerState(
                    payload?.let { json.decodeFromJsonElement(ServerStatePayload.serializer(), it) }
                        ?: ServerStatePayload()
                )
                "server/command" -> payload?.let {
                    ServerCommand(json.decodeFromJsonElement(ServerCommandPayload.serializer(), it))
                } ?: Unknown(type)
                else -> Unknown(type)
            }
        }
    }
}
