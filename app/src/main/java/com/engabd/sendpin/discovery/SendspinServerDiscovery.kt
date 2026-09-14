package com.engabd.sendpin.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress

/**
 * Finds the Sendspin server's endpoint the way the spec's client-initiated mode says to
 * (§Client Initiated Connections): the server advertises `_sendspin-server._tcp` with a
 * REQUIRED `path` TXT record and the port it listens on, and the client "initiates a
 * WebSocket connection using the advertised address and path".
 *
 * Scoped to one host — the Music Assistant server the user configured — because that
 * is the server this player belongs to; a household with two Sendspin servers is not a
 * choice this app makes for the user. What discovery adds over deriving
 * `ws://host:8927/sendspin` is the port and path the server *actually* advertises,
 * which is what keeps a non-default deployment working. The caller falls back to the
 * derived form when nothing answers in time.
 */
class SendspinServerDiscovery(context: Context) {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    /** The advertised endpoint of the Sendspin server at [host], or null if none answered within [timeoutMs]. */
    suspend fun resolve(host: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): String? {
        val wanted = runCatching { InetAddress.getByName(host) }.getOrNull()
        val result = CompletableDeferred<String?>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { result.complete(null) }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsd.resolveService(
                    serviceInfo,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val addr = info.host ?: return
                            if (wanted != null && addr != wanted && addr.hostAddress != host) return
                            val path = info.attributes["path"]?.let { String(it, Charsets.UTF_8) }?.takeIf { it.startsWith("/") } ?: "/sendspin"
                            val literal = addr.hostAddress ?: return
                            val hostPart = if (':' in literal) "[$literal]" else literal
                            Log.i(TAG, "Sendspin server ${info.serviceName.take(8)}… at $hostPart:${info.port}$path")
                            result.complete("ws://$hostPart:${info.port}$path")
                        }
                    },
                )
            }
        }
        return try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            withTimeoutOrNull(timeoutMs) { result.await() }
        } catch (e: Exception) {
            Log.w(TAG, "discovery unavailable: ${e.message}")
            null
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    private companion object {
        const val TAG = "SendspinDiscovery"
        const val SERVICE_TYPE = "_sendspin-server._tcp."

        /** Long enough for a Bonjour answer on a LAN; short enough not to hold up the connect. */
        const val DEFAULT_TIMEOUT_MS = 1_500L
    }
}
