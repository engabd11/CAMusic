package com.engabd.sendpin.hue

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Data models for the Hue CLIP v2 API and the Entertainment streaming protocol.
 * Mirrors the shapes returned by the bridge's REST endpoints, simplified to the
 * fields the app actually reads.
 *
 * The Hue Bridge's CLIP v2 API returns resources wrapped in a `{"data": [...]}` envelope.
 * Each resource has an `id`, a `type`, and resource-specific fields. Error responses are
 * `{"errors": [...]}`. We parse the `data` array and ignore `errors` (they are rare and
 * the app surfaces failures through HTTP status codes).
 */

/**
 * One entertainment configuration (an "Entertainment Area" in the Hue app).
 * Created by the user in the Hue app — the app reads, starts and stops these.
 */
@Serializable
data class EntertainmentConfig(
    val id: String,
    @SerialName("type") val typeRef: String = "entertainment_configuration",
    val name: String = "",
    val status: String = "inactive",     // "inactive" | "active"
    @SerialName("configuration_type") val configurationType: String = "room",  // "room" | "screen"
    val channels: List<EntertainmentChannel> = emptyList(),
    @SerialName("active_streamer") val activeStreamer: ResourceRef? = null,
) {
    val isStreaming: Boolean get() = status == "active"
}

/**
 * One channel within an entertainment configuration. Each channel maps to one light
 * and carries its position in the room (x, y, z in metres relative to the TV/user).
 */
@Serializable
data class EntertainmentChannel(
    @SerialName("channel_id") val channelId: Int,
    val position: ChannelPosition = ChannelPosition(),
)

@Serializable
data class ChannelPosition(
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
)

/**
 * A light resource from `GET /resource/light`. Used to resolve per-light colour gamuts.
 */
@Serializable
data class HueLight(
    val id: String,
    val owner: ResourceRef? = null,
    val color: HueColor? = null,
)

@Serializable
data class ResourceRef(
    val rid: String = "",
    val rtype: String = "",
)

@Serializable
data class HueColor(
    val gamut: HueGamut? = null,
)

@Serializable
data class HueGamut(
    val red: GamutPoint = GamutPoint(),
    val green: GamutPoint = GamutPoint(),
    val blue: GamutPoint = GamutPoint(),
)

@Serializable
data class GamutPoint(
    val x: Float = 0f,
    val y: Float = 0f,
)

/**
 * An entertainment service (links a channel member to a device).
 */
@Serializable
data class EntertainmentService(
    val id: String,
    val owner: ResourceRef? = null,
)

/**
 * The response from `POST /api` (link-button pairing).
 * Returns the username (app_key) and clientkey (PSK) on success,
 * or an error if the link button hasn't been pressed.
 */
@Serializable
data class HuePairingResponse(
    val success: HuePairingSuccess? = null,
    val error: HueError? = null,
)

@Serializable
data class HuePairingSuccess(
    val username: String = "",
    val clientkey: String = "",
)

@Serializable
data class HueError(
    val type: Int = 0,
    val address: String = "",
    val description: String = "",
)

/**
 * The bridge's config (from `GET /api/0/config`), used during discovery to
 * validate a found bridge and read its bridge ID and software version.
 */
@Serializable
data class HueBridgeConfig(
    val name: String = "",
    @SerialName("swversion") val swVersion: String = "",
    @SerialName("apiversion") val apiVersion: String = "",
    @SerialName("bridgeid") val bridgeId: String = "",
    @SerialName("modelid") val modelId: String = "",
)