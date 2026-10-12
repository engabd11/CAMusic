package com.engabd.sendpin.discovery

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * Discovers **Plex Media Servers** on the LAN with GDM, Plex's own discovery
 * protocol, so a server can be picked before anyone has signed in.
 *
 * GDM is an HTTP-shaped UDP exchange: `M-SEARCH * HTTP/1.1` sent to the
 * multicast group 239.0.0.250 on port 32414, answered by each server with a
 * unicast `HTTP/1.0 200 OK` whose header lines name it:
 *
 * ```
 * HTTP/1.0 200 OK
 * Content-Type: plex/media-server
 * Resource-Identifier: 0123abcd…
 * Name: Living Room
 * Port: 32400
 * Version: 1.41.0.8992
 * ```
 *
 * The server's address is where the reply came from; the port is in the body.
 * This is the exchange Plex's own apps and python-plexapi's `GDM.scan()` use.
 * No Plex server was reachable from the development machine, so the parser is
 * held to that documented shape by unit tests rather than to a live reply.
 *
 * Signing in still happens on plex.tv. That flow also lists the account's
 * servers ([com.engabd.sendpin.plex.PlexAuth.listResources]), including remote
 * ones GDM cannot see; this only saves typing on the LAN before or without it.
 */
class PlexGdmDiscovery(private val context: Context) {
    companion object {
        private const val TAG = "PlexGdmDiscovery"
        private const val GDM_PORT = 32414
        private const val GDM_GROUP = "239.0.0.250"
        private val SEARCH = "M-SEARCH * HTTP/1.1\r\n\r\n".toByteArray(Charsets.US_ASCII)

        /**
         * The server a GDM reply describes, or null for anything else: a
         * non-200 status, a client (players answer GDM too, with another
         * content type), or a reply with no usable port.
         */
        internal fun parseReply(body: String, fromHost: String): Found? {
            val lines = body.lines()
            if (lines.firstOrNull()?.contains("200 OK") != true) return null
            val headers = lines.drop(1).mapNotNull { line ->
                val key = line.substringBefore(':', "").trim()
                if (key.isEmpty()) null else key.lowercase() to line.substringAfter(':').trim()
            }.toMap()
            val type = headers["content-type"]
            if (type != null && type != "plex/media-server") return null
            val port = headers["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val url = "http://$fromHost:$port"
            return Found(headers["name"]?.takeIf { it.isNotBlank() } ?: url, url)
        }
    }

    /** One server that answered. */
    data class Found(val name: String, val url: String)

    /** One scan: search, then collect replies until [timeoutMs] runs out. */
    suspend fun scan(timeoutMs: Long = 1500L): List<Found> = withContext(Dispatchers.IO) {
        val results = LinkedHashMap<String, Found>()
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket().apply {
                broadcast = true
                soTimeout = 200
            }
            bindToWifiIfAvailable(context, socket, TAG)
            // The multicast group is what GDM defines; the broadcast is a second
            // way in for networks whose access point drops multicast to clients.
            for (target in listOf(GDM_GROUP, "255.255.255.255")) {
                runCatching {
                    socket.send(DatagramPacket(SEARCH, SEARCH.size, InetAddress.getByName(target), GDM_PORT))
                }.onFailure { Log.w(TAG, "GDM search to $target failed: ${it.message}") }
            }

            val buf = ByteArray(2048)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                ensureActive()
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val from = packet.address as? Inet4Address ?: continue
                    parseReply(String(packet.data, 0, packet.length, Charsets.UTF_8), from.hostAddress ?: continue)
                        ?.let { results[it.url] = it }
                } catch (_: SocketTimeoutException) {
                    // Keep polling until the overall deadline.
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "GDM scan failed: ${e.message}")
        } finally {
            socket?.close()
        }
        results.values.toList()
    }
}
