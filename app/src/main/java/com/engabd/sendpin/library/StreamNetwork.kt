package com.engabd.sendpin.library

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the network the phone is on right now is one the user pays for by the byte.
 *
 * Read by every place that picks a stream format, so a server can have one quality
 * on Wi-Fi and another on mobile data ([ServerConfig.streamFormatFor]). "Metered" is
 * Android's own answer rather than "is cellular": a phone hotspot the user marked as
 * metered is exactly the connection a cheaper stream is for, and an unmetered
 * cellular plan is not.
 *
 * Process-wide and started once from the application, because the answer is
 * needed by code that has no Context of its own (the stream-URL builders) and a
 * callback per caller would be a dozen registrations for one fact.
 */
object StreamNetwork {

    private val _metered = MutableStateFlow(false)

    /** True on a metered network. False before [start], and when nothing is connected. */
    val metered: StateFlow<Boolean> = _metered.asStateFlow()

    /** The current answer, for code that reads it once while building a URL. */
    val isMetered: Boolean get() = _metered.value

    private val _networkAvailable = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    /**
     * A network has just become the default — arriving home, Wi-Fi coming back. The
     * moment a socket that gave up waiting should try again, rather than at the end
     * of whatever backoff it had reached.
     */
    val networkAvailable: kotlinx.coroutines.flow.SharedFlow<Unit> = _networkAvailable

    @Volatile private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        _metered.value = runCatching {
            cm.getNetworkCapabilities(cm.activeNetwork)?.let(::isMetered) ?: false
        }.getOrDefault(false)
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    _networkAvailable.tryEmit(Unit)
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    _metered.value = isMetered(caps)
                }

                // Nothing connected streams nothing, so the Wi-Fi format is as good an
                // answer as any, and it is the one the next network most often is.
                override fun onLost(network: Network) {
                    _metered.value = false
                }
            })
        }
    }

    private fun isMetered(caps: NetworkCapabilities): Boolean =
        // A VPN reports its underlying network's meteredness on API 31+, which is
        // this app's minimum, so it needs no special case.
        !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
}
