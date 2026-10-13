package com.engabd.sendpin.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.net.DatagramSocket

/**
 * Route a discovery socket through the Wi-Fi network specifically, when the
 * phone has one connected.
 *
 * A plain [DatagramSocket] otherwise follows whatever the OS considers the
 * *default* network, which can be mobile data or a VPN even while Wi-Fi is
 * connected, and a beacon sent down the wrong interface never reaches the LAN
 * the server is on. Best-effort: leaves the socket unbound if there is no
 * Wi-Fi network or the bind fails, which is never worse than not calling this.
 */
internal fun bindToWifiIfAvailable(context: Context, socket: DatagramSocket, tag: String) {
    try {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        @Suppress("DEPRECATION") // allNetworks: the one call that lists a non-default Wi-Fi network.
        val wifiNetwork = cm.allNetworks.firstOrNull { network ->
            cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: return
        wifiNetwork.bindSocket(socket)
    } catch (e: Exception) {
        Log.w(tag, "Could not bind the discovery socket to Wi-Fi, using the default network instead: ${e.message}")
    }
}
