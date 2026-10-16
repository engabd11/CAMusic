package com.engabd.sendpin.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/**
 * Whether anything is answering at a server's address, asked quickly and only twice.
 *
 * A connect used to go straight into the full sign-in: a Jellyfin ping, an
 * authenticate and a library listing, or Navidrome's extensions and ping, each one
 * its own ten-second connect timeout against an address with nothing on it. With the
 * phone away from home that was the better part of a minute on "Connecting…" before
 * anything said the server was out of reach. This asks the one question first: two
 * short tries at the address, and if neither connects the library says so and offers
 * the downloads.
 *
 * A TCP connect and nothing more. Whether the server *likes* us is the sign-in's
 * question, and it has the better message for it; a socket opening is all "there is
 * something at this address" needs. It also holds for libraries that do not speak
 * HTTP (MPD, SMB), and a NAS slow to answer its first request still counts as there.
 */
object ServerReach {

    const val ATTEMPTS = 2
    private const val CONNECT_TIMEOUT_MS = 3_000
    private const val GAP_MS = 1_000L
    /** Addresses tried per attempt — an IPv6 and an IPv4 answer for one name, say. */
    private const val ADDRESSES = 2

    /** Host and port out of [url], with the port the scheme implies when it has none. */
    internal fun hostPort(url: String): Pair<String, Int>? {
        val raw = url.trim()
        val withScheme = if ("://" in raw) raw else "http://$raw"
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val port = uri.port.takeIf { it > 0 } ?: when (uri.scheme?.lowercase()) {
            "https", "wss" -> 443
            "http", "ws" -> 80
            else -> return null
        }
        return host to port
    }

    /**
     * True if [url] took a connection within [ATTEMPTS] short tries. An address this
     * cannot make sense of is left to the sign-in, so it answers true.
     */
    suspend fun reachable(url: String): Boolean {
        val (host, port) = hostPort(url) ?: return true
        repeat(ATTEMPTS) { i ->
            if (i > 0) delay(GAP_MS)
            if (connects(host, port)) return true
        }
        return false
    }

    private suspend fun connects(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        val addresses = try {
            InetAddress.getAllByName(host).take(ADDRESSES)
        } catch (e: IOException) {
            // No such name: from away from home, a `.local` or LAN-only name.
            return@withContext false
        }
        addresses.any { address ->
            try {
                Socket().use { it.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS) }
                true
            } catch (e: IOException) {
                false
            }
        }
    }
}
