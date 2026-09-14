package com.engabd.sendpin.discovery

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
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
 * Discovers **Jellyfin and Emby servers** on the LAN via their shared UDP
 * auto-discovery beacon.
 *
 * This is not mDNS/NsdManager — Jellyfin (and Emby, which forked from the
 * same MediaBrowser lineage and kept the wire format) answer a plain UDP
 * broadcast on port 7359 with a JSON reply naming the server's own address:
 * `{"Address":"http://192.168.0.9:8096","Id":"...","Name":"My Server"}`.
 * One scan finds either fork; a reply carries no field reliably telling the
 * two apart, so the caller (`ui/design/DiscoveredServerPicker.kt`) shows
 * every result under whichever of the two the user is actually setting up —
 * a wrong-fork tap is harmless, since the normal connect attempt that
 * follows knows the difference and fails cleanly if it's the wrong one.
 *
 * **Unverified against a real Jellyfin or Emby server.** Written from the
 * documented beacon protocol; the reply's exact JSON shape and this
 * environment's ability to broadcast on the phone's actual Wi-Fi interface
 * (rather than, say, a VPN or mobile-data default route) have not been
 * confirmed against live hardware. See `docs/plan/server-mdns-discovery.md`.
 */
class MediaServerDiscovery(private val context: Context) {
    companion object {
        private const val TAG = "MediaServerDiscovery"
        private const val DISCOVERY_PORT = 7359
        private const val MESSAGE = "who is JellyfinServer?"
    }

    /** One server that answered the beacon. */
    data class Found(val name: String, val url: String)

    /**
     * One scan: broadcast the beacon, then collect replies until [timeoutMs]
     * runs out. Not a Flow — a broadcast reply either arrives inside the
     * window or it doesn't, unlike mDNS's ongoing found/lost lifecycle.
     */
    suspend fun scan(timeoutMs: Long = 1500L): List<Found> = withContext(Dispatchers.IO) {
        // Keyed by url so a server that answers more than once inside the
        // window (some beacons reply per network interface) is one row, not several.
        val results = LinkedHashMap<String, Found>()
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket().apply {
                broadcast = true
                soTimeout = 200  // per-receive poll, not the overall budget
            }
            val message = MESSAGE.toByteArray(Charsets.UTF_8)
            socket.send(DatagramPacket(message, message.size, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT))

            val buf = ByteArray(2048)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    parseReply(String(packet.data, 0, packet.length, Charsets.UTF_8))?.let {
                        results[it.url] = it
                    }
                } catch (_: SocketTimeoutException) {
                    // Keep polling until the overall deadline above.
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Discovery scan failed: ${e.message}")
        } finally {
            socket?.close()
        }
        results.values.toList()
    }

    private fun parseReply(body: String): Found? = try {
        val o = Json.parseToJsonElement(body) as? JsonObject
        val url = o?.get("Address")?.jsonPrimitive?.contentOrNull
        if (url.isNullOrBlank()) null
        else Found(o["Name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: url, url)
    } catch (e: Exception) {
        null
    }
}
