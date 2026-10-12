package com.engabd.sendpin.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Browses one mDNS (DNS-SD) service type and keeps the resolved services in
 * [services], for the add-a-server picker. Used for MPD's `_mpd._tcp`, which
 * MPD itself publishes when built with Avahi or Bonjour support (Debian's and
 * most distributions' packages are), as do Mopidy-MPD and moOde.
 *
 * Resolves run one at a time, each with its own listener. Before Android 14
 * NsdManager refuses a second resolve on a listener that is still busy
 * ("listener already in use"), so a network with two servers of the same type
 * would otherwise lose the second one.
 *
 * Only IPv4 addresses are kept: every address field in the app is typed as
 * `host:port`, which an IPv6 literal would break.
 */
class NsdBrowser(context: Context, private val serviceType: String) {
    /** One resolved service. */
    data class Service(val name: String, val host: String, val port: Int)

    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())

    private val _services = MutableStateFlow<List<Service>>(emptyList())
    val services: StateFlow<List<Service>> = _services.asStateFlow()

    private val _browsing = MutableStateFlow(false)
    val browsing: StateFlow<Boolean> = _browsing.asStateFlow()

    private var listener: NsdManager.DiscoveryListener? = null
    // Touched only on the main thread: NsdManager calls back there, and every
    // callback below hops to it before touching this state.
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun start() {
        if (listener != null) return
        _services.value = emptyList()
        _browsing.value = true
        val l = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(type: String, errorCode: Int) {
                Log.w(TAG, "Browse for $type failed to start: $errorCode")
                main.post { _browsing.value = false; listener = null }
            }
            override fun onStopDiscoveryFailed(type: String, errorCode: Int) = Unit
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post {
                    if (listener == null) return@post
                    if (queue.none { it.serviceName == info.serviceName }) queue.addLast(info)
                    resolveNext()
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                main.post { _services.value = _services.value.filterNot { it.name == info.serviceName } }
            }
        }
        listener = l
        try {
            nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            Log.w(TAG, "Browse for $serviceType failed: ${e.message}")
            listener = null
            _browsing.value = false
        }
    }

    fun stop() {
        val l = listener ?: return
        listener = null
        queue.clear()
        _browsing.value = false
        runCatching { nsd.stopServiceDiscovery(l) }
    }

    @Suppress("DEPRECATION") // resolveService: the API 31-33 path; still works on 34+.
    private fun resolveNext() {
        if (resolving || listener == null) return
        val next = queue.removeFirstOrNull() ?: return
        resolving = true
        try {
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "Resolve of ${info.serviceName} failed: $errorCode")
                    main.post { resolving = false; resolveNext() }
                }
                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = ipv4Of(info)
                    main.post {
                        resolving = false
                        if (listener != null && host != null) {
                            val found = Service(info.serviceName, host, info.port)
                            _services.value = _services.value.filterNot { it.name == found.name } + found
                        }
                        resolveNext()
                    }
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Resolve of ${next.serviceName} could not start: ${e.message}")
            resolving = false
            main.post { resolveNext() }
        }
    }

    @Suppress("DEPRECATION") // NsdServiceInfo.host: the only address before API 34.
    private fun ipv4Of(info: NsdServiceInfo): String? {
        val addresses: List<InetAddress> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) info.hostAddresses
            else listOfNotNull(info.host)
        return addresses.firstOrNull { it is Inet4Address }?.hostAddress
    }

    private companion object {
        const val TAG = "NsdBrowser"
    }
}
