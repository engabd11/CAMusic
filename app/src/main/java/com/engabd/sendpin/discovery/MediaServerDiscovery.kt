package com.engabd.sendpin.discovery

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * Discovers **Jellyfin and Emby servers** on the LAN via their UDP
 * auto-discovery beacon.
 *
 * This is not mDNS/NsdManager. Jellyfin and Emby (which forked from the same
 * MediaBrowser lineage and kept the wire format) answer a plain UDP broadcast
 * on port 7359 with a JSON reply naming the server's own address:
 * `{"Address":"http://192.168.0.156:8096","Id":"…","Name":"Hogwarts","EndpointAddress":null}`
 * (the shape a real Jellyfin 10.x sent on 2026-10-12).
 *
 * The reply carries nothing that says which fork sent it, but the question
 * does: Jellyfin answers only "who is JellyfinServer?" and Emby only "who is
 * EmbyServer?" (checked the same day: a Jellyfin server ignored the Emby
 * question). So each question goes out on its own socket, and a reply's fork
 * is the socket it came back on. That keeps a Jellyfin server out of the Emby
 * list, where tapping it only led to a failed connect.
 */
class MediaServerDiscovery(private val context: Context) {
    companion object {
        private const val TAG = "MediaServerDiscovery"
        private const val DISCOVERY_PORT = 7359

        /**
         * The reply's server, or null for anything that is not one. Internal so
         * a unit test can hold the parser to the shape real servers send.
         */
        internal fun parseReply(body: String, fork: Fork): Found? = try {
            val o = Json.parseToJsonElement(body) as? JsonObject
            val url = o?.get("Address")?.jsonPrimitive?.contentOrNull?.trim()?.trimEnd('/')
            if (url.isNullOrBlank() || !(url.startsWith("http://") || url.startsWith("https://"))) null
            else Found(fork, o["Name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: url, url)
        } catch (e: Exception) {
            null
        }
    }

    /** Which server answered. Each fork only answers its own question. */
    enum class Fork(internal val question: String) {
        JELLYFIN("who is JellyfinServer?"),
        EMBY("who is EmbyServer?"),
    }

    /** One server that answered the beacon. */
    data class Found(val fork: Fork, val name: String, val url: String)

    /**
     * One scan: broadcast each fork's question, then collect replies until
     * [timeoutMs] runs out. Not a Flow: a broadcast reply either arrives inside
     * the window or it doesn't, unlike mDNS's ongoing found/lost lifecycle.
     */
    suspend fun scan(timeoutMs: Long = 1500L): List<Found> = withContext(Dispatchers.IO) {
        // Keyed by fork and url so a server that answers more than once inside the
        // window (some reply once per network interface) is one row, not several.
        val results = LinkedHashMap<Pair<Fork, String>, Found>()
        val sockets = LinkedHashMap<Fork, DatagramSocket>()
        try {
            for (fork in Fork.entries) {
                val socket = DatagramSocket().apply {
                    broadcast = true
                    // Per-receive poll, not the overall budget. Short, because the
                    // loop below takes turns between the two sockets.
                    soTimeout = 100
                }
                sockets[fork] = socket
                bindToWifiIfAvailable(context, socket, TAG)
                val message = fork.question.toByteArray(Charsets.UTF_8)
                socket.send(DatagramPacket(message, message.size, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT))
            }

            val buf = ByteArray(2048)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                // A blocking socket read has no suspension point, so cancellation
                // (backing out of the add-server screen mid-scan) is checked here,
                // once per poll, or the scan would run out its window regardless.
                ensureActive()
                for ((fork, socket) in sockets) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        socket.receive(packet)
                        parseReply(String(packet.data, 0, packet.length, Charsets.UTF_8), fork)?.let {
                            results[fork to it.url] = it
                        }
                    } catch (_: SocketTimeoutException) {
                        // Nothing on this socket yet; try the other, then go round.
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Discovery scan failed: ${e.message}")
        } finally {
            sockets.values.forEach { it.close() }
        }
        results.values.toList()
    }
}
