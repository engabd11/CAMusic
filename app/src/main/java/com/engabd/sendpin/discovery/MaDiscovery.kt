package com.engabd.sendpin.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Discovers **Music Assistant servers** on the LAN via mDNS.
 *
 * MA advertises the long-standing `_mass._tcp` service on its web port (default
 * 8095). That one host:port serves **both** the Sendspin player socket
 * (`/sendspin`) and the MA API (`/ws`) — the same arrangement the massdroid /
 * MA-app clients use — so the discovered server is used for both. (The Sendspin
 * spec also defines `_sendspin-server._tcp`; whether MA advertises it varies, so
 * `_mass._tcp` is the reliable target. Verify against your server if discovery
 * comes up empty and enter the URL manually.)
 */
class MaDiscovery(private val context: Context) {
    companion object {
        private const val TAG = "MaDiscovery"
        private const val SERVICE_TYPE = "_mass._tcp."
    }

    data class MaServer(
        val name: String,
        val host: String,
        val port: Int,
    ) {
        /** Base HTTP URL — the MA API (`/ws`) + imageproxy live here. */
        val baseUrl: String get() = "http://$host:$port"
        /** The Sendspin player WebSocket URL (before the handshake). */
        val webSocketUrl: String get() = "ws://$host:$port/sendspin"
        val displayName: String get() = "$name ($host:$port)"
    }

    private val nsdManager: NsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val _discoveredServers = MutableStateFlow<List<MaServer>>(emptyList())
    val discoveredServers: StateFlow<List<MaServer>> = _discoveredServers.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var resolveListener: NsdManager.ResolveListener? = null

    private val pendingResolves = mutableSetOf<String>()

    fun startDiscovery() {
        if (_isDiscovering.value) return
        _isDiscovering.value = true
        _discoveredServers.value = emptyList()
        pendingResolves.clear()

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery start failed: $errorCode")
                _isDiscovering.value = false
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery stop failed: $errorCode")
            }

            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "Discovery started for $serviceType")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Discovery stopped")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${serviceInfo.serviceName}")
                if (serviceInfo.serviceName !in pendingResolves) {
                    pendingResolves.add(serviceInfo.serviceName)
                    // Guard against a null listener: onStartDiscoveryFailed clears
                    // _isDiscovering but doesn't null out resolveListener, and a
                    // service-found callback could still be in flight before the
                    // discovery listener itself is torn down.
                    val listener = resolveListener
                    if (listener != null) {
                        nsdManager.resolveService(serviceInfo, listener)
                    } else {
                        pendingResolves.remove(serviceInfo.serviceName)
                    }
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
                _discoveredServers.value = _discoveredServers.value.filter {
                    it.name != serviceInfo.serviceName
                }
            }
        }

        resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
                pendingResolves.remove(serviceInfo.serviceName)
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Resolved: ${serviceInfo.serviceName} at ${serviceInfo.host}:${serviceInfo.port}")
                pendingResolves.remove(serviceInfo.serviceName)

                val server = MaServer(
                    name = serviceInfo.serviceName,
                    host = serviceInfo.host?.hostAddress ?: return,
                    port = serviceInfo.port,
                )
                val current = _discoveredServers.value.toMutableList()
                current.removeAll { it.name == server.name }
                current.add(server)
                _discoveredServers.value = current
            }
        }

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    fun stopDiscovery() {
        discoveryListener?.let {
            try { nsdManager.stopServiceDiscovery(it) } catch (_: Exception) {}
        }
        _isDiscovering.value = false
        discoveryListener = null
        resolveListener = null
    }
}
