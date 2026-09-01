package com.engabd.sendpin.p2p

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Collaborative queue: a QR code on Now Playing that, when scanned, opens a
 * local web page where friends add tracks to the queue without installing
 * anything.
 *
 * This object holds the pure URL encoding and the queue-event protocol.
 * The [QueueServer] is the thin HTTP + WebSocket shell around it.
 */

/**
 * One event in the collaborative queue protocol.
 *
 * The server publishes [QueueState] to connected clients and receives
 * [QueueCommand] from them. The protocol is JSON over WebSocket, one
 * message per frame.
 */
@Serializable
sealed class QueueEvent {
    /** The server tells clients what the queue looks like right now. */
    @Serializable
    data class QueueState(
        val tracks: List<QueueTrack> = emptyList(),
        val currentIndex: Int = -1,
    ) : QueueEvent()

    /** A client asks the server to add a track. */
    @Serializable
    data class AddTrack(
        val title: String,
        val artist: String,
        /** The library track id, if the client found it via search. */
        val trackId: String? = null,
    ) : QueueEvent()

    /** A client asks the server to remove a track from the queue. */
    @Serializable
    data class RemoveTrack(val index: Int) : QueueEvent()
}

@Serializable
data class QueueTrack(
    val id: String,
    val title: String,
    val artist: String,
    val durationMs: Long = 0,
)

object CollaborativeQueue {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * The URL a QR code should encode. Plain HTTP on the phone's local IP
     * and the chosen port. The web page at this URL is the collaborative
     * queue interface.
     */
    fun encodeQueueUrl(ip: String, port: Int): String = "http://$ip:$port"

    /**
     * Parse a queue URL back to IP and port. Returns null if the URL is
     * not the expected shape.
     */
    fun decodeQueueUrl(url: String): Pair<String, Int>? {
        if (!url.startsWith("http://")) return null
        val rest = url.removePrefix("http://")
        val colon = rest.indexOf(':')
        if (colon < 0) return null
        val ip = rest.substring(0, colon)
        val portStr = rest.substring(colon + 1)
        val port = portStr.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return ip to port
    }

    fun encodeEvent(event: QueueEvent): String {
        return when (event) {
            is QueueEvent.QueueState -> json.encodeToString(QueueEvent.QueueState.serializer(), event)
            is QueueEvent.AddTrack -> json.encodeToString(QueueEvent.AddTrack.serializer(), event)
            is QueueEvent.RemoveTrack -> json.encodeToString(QueueEvent.RemoveTrack.serializer(), event)
        }
    }

    fun decodeEvent(raw: String): QueueEvent? {
        return runCatching {
            val obj = json.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonObject
                ?: return null
            // The discriminator is the presence of the right fields.
            when {
                "tracks" in obj -> json.decodeFromString(QueueEvent.QueueState.serializer(), raw)
                "index" in obj -> json.decodeFromString(QueueEvent.RemoveTrack.serializer(), raw)
                "title" in obj -> json.decodeFromString(QueueEvent.AddTrack.serializer(), raw)
                else -> null
            }
        }.getOrNull()
    }
}