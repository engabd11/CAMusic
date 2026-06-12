package com.cyborgautomation.sendspin.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class Message {
    @Serializable
    @SerialName("client/hello")
    data class ClientHello(
        @SerialName("client_id") val clientId: String,
        val name: String,
        val version: Int = 1,
        @SerialName("supported_roles") val supportedRoles: List<String> = listOf("player@v1"),
        @SerialName("device_info") val deviceInfo: DeviceInfo? = null,
        @SerialName("player@v1_support") val playerV1Support: PlayerV1Support? = null,
    ) : Message()

    @Serializable
    @SerialName("server/hello")
    data class ServerHello(
        @SerialName("server_id") val serverId: String,
        @SerialName("server_name") val serverName: String,
        val version: Int,
        @SerialName("active_roles") val activeRoles: List<String> = emptyList(),
    ) : Message()

    @Serializable
    @SerialName("client/time")
    data class ClientTime(
        val t1: Long,  // microseconds
    ) : Message()

    @Serializable
    @SerialName("server/time")
    data class ServerTime(
        val t1: Long,
        val t2: Long,
        val t3: Long,
    ) : Message()

    @Serializable
    @SerialName("stream/start")
    data class StreamStart(
        val format: AudioFormatSpec,
        @SerialName("stream_id") val streamId: String,
    ) : Message()

    @Serializable
    @SerialName("stream/end")
    data class StreamEnd(
        @SerialName("stream_id") val streamId: String,
    ) : Message()

    @Serializable
    @SerialName("server/state")
    data class ServerState(
        val playing: Boolean = false,
        @SerialName("track_title") val trackTitle: String = "",
        val artist: String = "",
        val album: String = "",
        @SerialName("artwork_url") val artworkUrl: String? = null,
        val volume: Float = 1.0f,
        val muted: Boolean = false,
        @SerialName("stream_format") val streamFormat: AudioFormatSpec? = null,
    ) : Message()

    @Serializable
    @SerialName("client/state")
    data class ClientState(
        val volume: Float? = null,
        val muted: Boolean? = null,
    ) : Message()

    @Serializable
    @SerialName("server/command")
    data class ServerCommand(
        val command: String,  // "play", "pause", "stop", "next", "previous"
        val volume: Float? = null,
        val mute: Boolean? = null,
    ) : Message()

    @Serializable
    @SerialName("client/goodbye")
    data class ClientGoodbye(
        val reason: String = "user_disconnect",
    ) : Message()

    @Serializable
    @SerialName("group/update")
    data class GroupUpdate(
        @SerialName("group_id") val groupId: String,
        val members: List<String> = emptyList(),
    ) : Message()
}

@Serializable
data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    @SerialName("os") val os: String = "Android",
)

@Serializable
data class PlayerV1Support(
    @SerialName("supported_formats") val supportedFormats: List<AudioFormatSpec>,
    @SerialName("buffer_capacity") val bufferCapacity: Int = 65536,
    @SerialName("supported_commands") val supportedCommands: List<String> = listOf(
        "play", "pause", "stop", "next", "previous", "volume", "mute", "seek"
    ),
)
