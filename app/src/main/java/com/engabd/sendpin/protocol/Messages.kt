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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Sendspin wire messages: JSON `{ "type", "payload" }` envelopes, per the published
 * specification (sendspin-audio.com/build/spec). Every field name here is the spec's;
 * fields the spec does not define for a message are not sent ("Clients and servers MUST
 * NOT send fields the specification does not define"), and unknown incoming fields are
 * ignored.
 *
 * Two message families are new with the encrypted protocol: the cleartext
 * `client/init` / `server/init` / `noise/handshake` trio that precedes the Noise
 * handshake, and the `server/activate` + pairing + management set that follows it. The
 * legacy (cleartext, pre-encryption) hello — `client_id` and `version` inside
 * `client/hello`, roles inside `server/hello` — is kept for Music Assistant servers
 * older than 2.10, gated on their schema version.
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

// --- Cleartext init and Noise handshake --------------------------------------

/** Only on Music Assistant's authenticated `:8095/sendspin` proxy, before anything else. */
@Serializable
data class SendspinAuthMessage(
    val type: String = "auth",
    val token: String,
    @SerialName("client_id") val clientId: String,
)

@Serializable
data class ClientInitPayload(
    @SerialName("client_id") val clientId: String,
    val version: Int = 1,
    val suite: String,
)

@Serializable
data class SendspinClientInit(
    val type: String = "client/init",
    val payload: ClientInitPayload,
)

@Serializable
data class ServerInitPayload(
    @SerialName("server_id") val serverId: String,
    val version: Int,
)

@Serializable
data class NoiseHandshakePayload(val data: String)

/** One Noise handshake message, base64url in `data`; both directions, first and re-handshake. */
@Serializable
data class SendspinNoiseHandshake(
    val type: String = "noise/handshake",
    val payload: NoiseHandshakePayload,
)

@Serializable
data class NoiseMsg1Payload(@SerialName("psk_id") val pskId: String)

// --- Hello ----------------------------------------------------------------

@Serializable
data class DeviceInfo(
    @SerialName("product_name") val productName: String,
    val manufacturer: String,
    @SerialName("software_version") val softwareVersion: String,
)

@Serializable
data class PlayerV1Support(
    @SerialName("supported_formats") val supportedFormats: List<AudioFormatSpec>,
    // Bytes of compressed audio the server may stream ahead (Sendspin spec), so a
    // throughput dip rides the buffer instead of underrunning. ~30 s of CD-rate
    // FLAC — but only ~7 s at 24/192, which is worth knowing before raising the
    // advertised rates any further.
    @SerialName("buffer_capacity") val bufferCapacity: Int = 4_000_000,
    @SerialName("supported_commands") val supportedCommands: List<String> = listOf("volume", "mute"),
)

/** One entry of `supported_pair_methods` (spec §pair-method descriptor). */
@Serializable
data class PairMethodDescriptor(
    val method: String,
    val locations: List<String>? = null,
)

@Serializable
data class UnpairedAccess(val enabled: Boolean)

@Serializable
data class ClientHelloPayload(
    val name: String,
    @SerialName("supported_roles") val supportedRoles: List<String> = listOf("player@v1", "metadata@v1"),
    @SerialName("device_info") val deviceInfo: DeviceInfo? = null,
    @SerialName("player@v1_support") val playerV1Support: PlayerV1Support? = null,
    /**
     * Encrypted sessions only: `user` when this session was admitted by a pairing
     * record, else `none`; the pairing methods on offer; the guest-access toggle.
     * Null (omitted) on a legacy hello, which is the pre-spec shape exactly — a
     * Music Assistant 2.10 in transition mode takes `unpaired_access` on a cleartext
     * hello as a guest approval, and its downgrade protection then refuses that
     * client id unencrypted for ever after.
     */
    @SerialName("trust_level") val trustLevel: String? = null,
    @SerialName("supported_pair_methods") val supportedPairMethods: List<PairMethodDescriptor>? = null,
    @SerialName("unpaired_access") val unpairedAccess: UnpairedAccess? = null,
    /**
     * Legacy (cleartext) hello only. Encrypted sessions carry both in `client/init`
     * and must not repeat them here; null is omitted from the wire.
     */
    @SerialName("client_id") val clientId: String? = null,
    val version: Int? = null,
)

@Serializable
data class SendspinClientHello(
    val type: String = "client/hello",
    val payload: ClientHelloPayload,
)

// --- State, time, format, goodbye -------------------------------------------

/**
 * The `player` object of `client/state`. Every field is required on the initial
 * report and optional in deltas; the client sends its full state each time (the
 * spec allows resending unchanged fields), so in practice all are present.
 */
@Serializable
data class PlayerStateInfo(
    val volume: Int? = null,
    val muted: Boolean? = null,
    @SerialName("static_delay_ms") val staticDelayMs: Int? = null,
    /**
     * From the server's `stream/start` transmit time to the first chunk this engine can
     * play in full: decoder set-up, the output stream opening, and the start buffer it
     * insists on. A hint; the server may give less.
     */
    @SerialName("required_lead_time_ms") val requiredLeadTimeMs: Int? = null,
    /** The queued audio the server should keep this player above while streaming. */
    @SerialName("min_buffer_ms") val minBufferMs: Int? = null,
    /**
     * `set_static_delay` is advertised here, at the state level, where the spec puts it
     * (hello may only list volume/mute). Without it Music Assistant never creates the
     * "Static playback delay (ms)" setting for this player.
     */
    @SerialName("supported_commands") val supportedCommands: List<String>? = null,
)

@Serializable
data class ClientStatePayload(
    /** True only once the clock filter can place samples — see [SendspinClient]. */
    val available: Boolean? = null,
    val player: PlayerStateInfo? = null,
)

@Serializable
data class SendspinClientState(
    val type: String = "client/state",
    val payload: ClientStatePayload,
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

// --- Pairing and management (client → server) --------------------------------

@Serializable
data class ClientPairFinalizePayload(@SerialName("long_term_psk") val longTermPsk: String)

@Serializable
data class SendspinClientPairFinalize(
    val type: String = "client/pair-finalize",
    val payload: ClientPairFinalizePayload,
)

@Serializable
data class PairAbortPayload(val reason: String)

@Serializable
data class SendspinPairAbort(
    val type: String = "pair/abort",
    val payload: PairAbortPayload,
)

@Serializable
data class ManagementResultPayload(
    val result: String,
    val data: JsonElement? = null,
)

@Serializable
data class SendspinManagementResult(
    val type: String = "management/result",
    val payload: ManagementResultPayload,
)

// --- Incoming payloads ----------------------------------------------------

@Serializable
data class ServerTimePayload(
    @SerialName("client_transmitted") val clientTransmitted: Long,
    @SerialName("server_received") val serverReceived: Long,
    @SerialName("server_transmitted") val serverTransmitted: Long = 0,
)

@Serializable
data class ActivatePairing(
    val method: String,
    @SerialName("pin_length") val pinLength: Int? = null,
)

@Serializable
data class ServerActivatePayload(
    val activities: List<String> = emptyList(),
    /** Sticky: null keeps the previous set; required (and so present) on the first. */
    @SerialName("active_roles") val activeRoles: List<String>? = null,
    val pairing: ActivatePairing? = null,
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
data class StreamStartPayload(
    @SerialName("server_transmitted") val serverTransmitted: Long = 0,
    val player: StreamStartPlayerInfo? = null,
)

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
    val command: String,          // "volume" | "mute" | "set_static_delay"
    val volume: Int? = null,
    val mute: Boolean? = null,
    /**
     * Only present on `set_static_delay`. The spec lets the server push the
     * per-player latency trim, not just read the one we report in `client/state`.
     */
    @SerialName("static_delay_ms") val staticDelayMs: Int? = null,
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

    /** Encrypted sessions get `{name}`; legacy sessions get roles and a server id too. */
    data class ServerHello(val raw: JsonObject) : SendspinIncoming()
    data class ServerActivate(val payload: ServerActivatePayload) : SendspinIncoming()

    // clientReceivedUs (T4) must be stamped at the WebSocket onMessage callback,
    // not here — capturing it after coroutine dispatch biases the clock offset.
    data class ServerTime(val payload: ServerTimePayload, val clientReceivedUs: Long = 0L) : SendspinIncoming()
    data class GroupUpdate(
        val playbackState: String? = null,
        val groupId: String? = null,
        val groupName: String? = null,
    ) : SendspinIncoming()
    data class StreamStart(val payload: StreamStartPayload) : SendspinIncoming()
    data class StreamEnd(val roles: List<String>?) : SendspinIncoming()
    data class StreamClear(val roles: List<String>?) : SendspinIncoming()
    data class ServerState(val payload: ServerStatePayload) : SendspinIncoming()
    data class ServerCommand(val payload: ServerCommandPayload) : SendspinIncoming()
    data object ServerPairFinalize : SendspinIncoming()
    data class PairAbort(val reason: String) : SendspinIncoming()
    data object ServerUnpair : SendspinIncoming()
    /** `management/<request>`, payload kept raw for [ManagementHandler]. */
    data class Management(val request: String, val payload: JsonObject?) : SendspinIncoming()
    data class Unknown(val type: String) : SendspinIncoming()

    companion object {
        private fun roles(payload: JsonElement?): List<String>? =
            ((payload as? JsonObject)?.get("roles") as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

        fun parse(text: String, json: Json): SendspinIncoming {
            val obj = json.parseToJsonElement(text).jsonObject
            val type = obj["type"]?.jsonPrimitive?.content ?: return Unknown("no_type")
            val payload = obj["payload"]
            return when (type) {
                "auth_ok" -> AuthOk
                "auth_error" -> AuthError(obj["message"]?.jsonPrimitive?.content ?: "Authentication failed")
                "server/hello" -> ServerHello(obj)
                "server/activate" -> ServerActivate(
                    payload?.let { json.decodeFromJsonElement(ServerActivatePayload.serializer(), it) }
                        ?: ServerActivatePayload(),
                )
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
                        ?: StreamStartPayload(),
                )
                "stream/end" -> StreamEnd(roles(payload))
                "stream/clear" -> StreamClear(roles(payload))
                "server/state" -> ServerState(
                    payload?.let { json.decodeFromJsonElement(ServerStatePayload.serializer(), it) }
                        ?: ServerStatePayload(),
                )
                "server/command" -> payload?.let {
                    ServerCommand(json.decodeFromJsonElement(ServerCommandPayload.serializer(), it))
                } ?: Unknown(type)
                "server/pair-finalize" -> ServerPairFinalize
                "pair/abort" -> PairAbort((payload as? JsonObject)?.get("reason")?.jsonPrimitive?.contentOrNull ?: "")
                "server/unpair" -> ServerUnpair
                else -> if (type.startsWith("management/")) {
                    Management(type.removePrefix("management/"), payload as? JsonObject)
                } else Unknown(type)
            }
        }
    }
}
