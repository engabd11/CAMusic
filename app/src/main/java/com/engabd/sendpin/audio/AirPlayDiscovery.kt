package com.engabd.sendpin.audio

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Discovers AirPlay receivers on the local network via mDNS.
 *
 * AirPlay devices advertise two service types:
 * - `_raop._tcp` — the RAOP (AirPlay audio) service. This is the one we
 *   connect to for audio streaming.
 * - `_airplay._tcp` — the AirPlay control service. We read its TXT record
 *   for device metadata (model, auth flags, features).
 *
 * The [discover] function returns a [Flow] that emits the current list of
 * discovered devices whenever it changes. Each [AirPlayDevice] carries
 * the host, port, name, and the auth mode resolved from the TXT record.
 *
 * ## Auth mode resolution
 *
 * The mDNS TXT record for `_raop._tcp` (and the corresponding
 * `_airplay._tcp` record) carries flags that tell us how to
 * authenticate:
 *
 * - `pw=true` → [AuthMode.PASSWORD] (RTSP digest auth)
 * - `am=AirPort*` → [AuthMode.AUTH_SETUP] (MFiSAP, AirPort Express gen 2)
 * - `sf` flag with HomeKit bits (bit 0xx80) → [AuthMode.HAP_PIN] (Apple TV)
 * - No special flags → [AuthMode.HAP_TRANSIENT] (HomePod / macOS) or
 *   [AuthMode.NONE] for very old receivers
 *
 * The `features` flag is a 64-bit hex bitmask. Bit 0x10000 (HomeKit) and
 * the `sf` (status flags) field together determine the auth path. See
 * `raop_auth.h` for the C++ enum this maps to.
 *
 * ## Android NsdManager
 *
 * Android's `NsdManager` wraps mDNS (Bonjour) discovery. It works on all
 * Android versions (minSdk 31 here), requires no permissions beyond
 * `ACCESS_NETWORK_STATE` and local network access, and is the same
 * mechanism the existing app uses for the Sendspin `_sendspin-server._tcp`
 * discovery.
 */
class AirPlayDiscovery(private val context: Context) {

    /**
     * One discovered AirPlay receiver.
     *
     * @param host the receiver's IP address (dotted-quad)
     * @param port the receiver's RTSP port (7000 for most AirPlay devices)
     * @param name the receiver's display name (from the mDNS service name)
     * @param deviceId the mDNS instance id (used to key stored credentials)
     * @param authMode how the receiver wants to be authenticated
     * @param airplay2 true for the encrypted AP2 path, false for legacy RAOP
     */
    data class AirPlayDevice(
        val host: String,
        val port: Int,
        val name: String,
        val deviceId: String,
        val authMode: AuthMode,
        val airplay2: Boolean,
    )

    /**
     * Discovers AirPlay receivers on the network.
     *
     * Emits the full list of discovered devices whenever a device is
     * found, lost, or updated. The flow remains active until the
     * collector cancels it, at which point discovery is stopped.
     */
    fun discover(): Flow<List<AirPlayDevice>> = callbackFlow {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val devices = mutableMapOf<String, AirPlayDevice>()

        fun emitList() {
            trySend(devices.values.toList().sortedBy { it.name })
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onDiscoveryStopped(serviceType: String?) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                // Resolve to get the host, port, and TXT record.
                nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(si: NsdServiceInfo?, errorCode: Int) {}
                    override fun onServiceResolved(si: NsdServiceInfo) {
                        val host = si.host?.hostAddress ?: return
                        val port = si.port.takeIf { it > 0 } ?: 7000
                        val name = si.serviceName
                        val deviceId = si.serviceName // instance name = device id for creds
                        val txt = si.attributes

                        // Resolve auth mode from TXT record.
                        val pw = txt["pw"]?.let { String(it) } == "true"
                        val am = txt["am"]?.let { String(it) } ?: ""
                        val sf = txt["sf"]?.let { String(it) }?.toIntOrNull() ?: 0
                        val features = txt["features"]?.let { String(it) }?.toLongOrNull(16) ?: 0L

                        val authMode = when {
                            pw -> AuthMode.PASSWORD
                            am.startsWith("AirPort") -> AuthMode.AUTH_SETUP
                            // HomeKit flag in features (bit 17 = 0x20000) or
                            // sf bit 0x4 (unpaired) → HAP PIN (Apple TV).
                            (features and 0x20000L) != 0L || (sf and 0x4) != 0 -> AuthMode.HAP_PIN
                            (features and 0x10000L) != 0L -> AuthMode.HAP_TRANSIENT
                            else -> AuthMode.NONE
                        }

                        // AirPlay 2 if the device advertises HomeKit features.
                        val airplay2 = (features and 0x10000L) != 0L

                        val device = AirPlayDevice(host, port, name, deviceId, authMode, airplay2)
                        devices[host] = device
                        emitList()
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                // Remove by name — we may not have the host here.
                val toRemove = devices.entries.firstOrNull { it.value.name == serviceInfo.serviceName }?.key
                if (toRemove != null) {
                    devices.remove(toRemove)
                    emitList()
                }
            }
        }

        try {
            nsd.discoverServices("_raop._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            // NsdManager can throw if discovery is already running or the
            // network is unavailable. Close the flow gracefully.
            close(e)
            return@callbackFlow
        }

        awaitClose {
            try { nsd.stopServiceDiscovery(listener) } catch (_: Exception) {}
        }
    }
}