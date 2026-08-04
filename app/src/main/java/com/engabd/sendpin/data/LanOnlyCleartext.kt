package com.engabd.sendpin.data

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Refuses to send a plaintext request anywhere but the local network.
 *
 * This is the restriction `network_security_config.xml` is often assumed to carry and
 * cannot: Android's `<domain>` element matches DNS labels by suffix and has no concept
 * of CIDR, so "everything in 10/8, 172.16/12 and 192.168/16" is not expressible there,
 * and this app's servers are discovered over mDNS or typed in by hand rather than known
 * at build time. Here the address *is* known — at the moment of the call — so here is
 * where the rule can actually be applied.
 *
 * What it protects: the app sends a Music Assistant password, a Navidrome password and
 * a Home Assistant long-lived token. Over `http://` those cross the network in the
 * clear. On a home LAN that is a considered trade — nobody runs TLS on their NAS — but
 * the same request to a hostname out on the internet is a credential disclosure to
 * every hop in between, and until now nothing distinguished the two.
 *
 * Deliberately generous about what counts as local, because the cost of a false
 * positive is a user who cannot reach their own server:
 *
 *  - any loopback or RFC1918 literal, plus RFC6598 carrier NAT and link-local
 *  - any single-label host (`nas`, `music`) — a name with no dots only resolves on a
 *    local network
 *  - the usual home-network suffixes: `.local`, `.lan`, `.home`, `.internal`, `.arpa`
 *
 * Everything else must use TLS. The failure is a plain [IOException] naming the host
 * and saying what to do about it, because it surfaces to the user as a connection
 * error and "Cleartext refused" with no explanation would be worse than the exposure.
 *
 * Applies to WebSockets too: OkHttp opens one with an ordinary HTTP upgrade request,
 * which passes through application interceptors like any other call.
 */
object LanOnlyCleartext : Interceptor {

    /** Suffixes a home network hands out. */
    private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home", ".internal", ".localdomain", ".arpa")

    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        if (url.isHttps || isLocal(url.host)) return chain.proceed(chain.request())
        throw IOException(
            "Refusing to send credentials in the clear to ${url.host}. " +
                "That address is not on a local network, so use https:// for it."
        )
    }

    /** Whether [host] is somewhere only reachable from this network. */
    fun isLocal(host: String): Boolean {
        val h = host.trim().trimEnd('.').lowercase().removeSurrounding("[", "]")
        if (h.isEmpty()) return false
        if (h == "localhost") return true
        // A name with no dot in it cannot be resolved by public DNS.
        if (!h.contains('.') && !h.contains(':')) return true
        if (LOCAL_SUFFIXES.any { h.endsWith(it) }) return true
        if (h.contains(':')) return isLocalV6(h)
        return isLocalV4(h)
    }

    private fun isLocalV4(h: String): Boolean {
        val parts = h.split('.')
        if (parts.size != 4) return false
        val o = parts.map { it.toIntOrNull() ?: return false }
        if (o.any { it !in 0..255 }) return false
        return when {
            o[0] == 127 -> true                                   // loopback
            o[0] == 10 -> true                                    // 10/8
            o[0] == 192 && o[1] == 168 -> true                    // 192.168/16
            o[0] == 172 && o[1] in 16..31 -> true                 // 172.16/12
            o[0] == 169 && o[1] == 254 -> true                    // link-local
            o[0] == 100 && o[1] in 64..127 -> true                // RFC6598 carrier NAT
            else -> false
        }
    }

    private fun isLocalV6(h: String): Boolean =
        h == "::1" || h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd")
}
