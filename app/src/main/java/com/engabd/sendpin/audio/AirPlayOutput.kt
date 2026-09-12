package com.engabd.sendpin.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * AirPlay as a [NetworkOutput]: the phone decodes, then streams 16-bit
 * stereo PCM to an AirPlay receiver over the network.
 *
 * This is the Kotlin half of the JNI bridge to the vendored
 * `airplay2-sender-cpp` library (Apache-2.0). The native half lives in
 * `app/src/main/cpp/airplay/airplay_jni.cpp` and builds into
 * `libairplay_sendpin.so`.
 *
 * ## How it works
 *
 * 1. [AirPlayDiscovery] discovers `_raop._tcp` services on the LAN via
 *    Android's `NsdManager`.
 * 2. The user picks a device. [connect] is called with the device's IP,
 *    port, name, and the auth mode resolved from its mDNS TXT record.
 * 3. The native `RaopSender` runs the AirPlay 2 handshake (HAP pairing,
 *    encrypted RTSP control channel, event channel, ALAC realtime stream)
 *    or the AirPlay 1 fallback (plain RTSP + L16/44100).
 * 4. [AirPlayOutputProcessor] taps ExoPlayer's decoded PCM and feeds it
 *    here via [writePcm], which pushes it into the native ring buffer.
 * 5. The native pacer pulls from the ring and sends ALAC frames at
 *    44100 Hz, pushing silence when the ring runs dry (paused).
 *
 * ## Pairing
 *
 * Apple TV 4+ uses HAP on-screen PIN: the receiver displays a 4-digit
 * code, the user enters it, and the first successful pairing produces
 * long-term credentials that are persisted (see [onCredentials]) so
 * later connects skip the PIN.
 *
 * HomePod and macOS use HAP transient pairing (no PIN needed).
 *
 * AirPort Express and older RAOP speakers use AirPlay 1 (no pairing).
 *
 * ## Threading
 *
 * - [writePcm] is called on ExoPlayer's audio thread (real-time, must
 *   not block). The native ring buffer is lock-free SPSC.
 * - [connect] / [disconnect] / [setVolume] etc. are called from
 *   coroutines on [Dispatchers.IO].
 * - Native callbacks ([onLaunched], [onClosed], [onPinRequired],
 *   [onCredentials]) arrive on the native poll loop thread. They are
 *   marshalled to [scope] (Main) to update the StateFlows.
 */
class AirPlayOutput : NetworkOutput {

    /**
     * The native bridge pointer. Set by [nativeInit], cleared by
     * [nativeDestroy]. Read by the JNI side via the field ID cached on
     * first call — the name `nativePtr` is the contract.
     */
    @Suppress("unused")
    private var nativePtr: Long = 0L

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> get() = _connected.asStateFlow()

    private val _deviceName = MutableStateFlow<String?>(null)
    override val deviceName: StateFlow<String?> get() = _deviceName.asStateFlow()

    /** Whether the receiver is showing a PIN, waiting for the user. */
    private val _waitingForPin = MutableStateFlow(false)
    val waitingForPin: StateFlow<Boolean> get() = _waitingForPin.asStateFlow()

    /** The PIN the user needs to enter, or null when not pairing. */
    private val _pinDeviceName = MutableStateFlow<String?>(null)
    val pinDeviceName: StateFlow<String?> get() = _pinDeviceName.asStateFlow()

    init {
        if (!loadNative()) {
            // The library failed to load — the native build is either not
            // compiled or the ABI doesn't match. This is not a crash: the
            // AirPlay button simply won't appear.
            // See AirPlayOutput.available for the gating check.
        }
    }

    private fun loadNative(): Boolean = try {
        System.loadLibrary("airplay_sendpin")
        nativePtr = nativeInit()
        nativePtr != 0L
    } catch (e: UnsatisfiedLinkError) {
        false
    }

    companion object {
        /**
         * Whether the AirPlay native library is loaded and ready.
         *
         * The AirPlay button in Now Playing checks this before showing.
         * If the native build is not compiled (e.g. on a CI runner without
         * the NDK), the feature is simply absent.
         */
        fun available(): Boolean = try {
            System.loadLibrary("airplay_sendpin")
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        }

        init {
            // Best-effort preload so available() is a cheap check.
            try { System.loadLibrary("airplay_sendpin") } catch (_: UnsatisfiedLinkError) {}
        }
    }

    override suspend fun connect(
        host: String,
        port: Int,
        name: String,
        authMode: AuthMode,
        airplay2: Boolean,
        deviceId: String,
        credentialsJson: String,
    ) {
        if (nativePtr == 0L) return
        _deviceName.value = name
        nativeStart(host, port, name, authMode.nativeValue, airplay2, deviceId, credentialsJson, "")
    }

    override suspend fun disconnect() {
        if (nativePtr == 0L) return
        nativeStop()
        _connected.value = false
        _waitingForPin.value = false
        _pinDeviceName.value = null
    }

    override fun writePcm(buffer: ByteArray, offset: Int, length: Int) {
        if (nativePtr == 0L) return
        nativeWritePcm(buffer, offset, length)
    }

    override fun setVolume(volume: Float) {
        if (nativePtr == 0L) return
        nativeSetVolume(volume.coerceIn(0f, 1f))
    }

    override fun submitPin(pin: String) {
        if (nativePtr == 0L) return
        nativeSubmitPin(pin)
        _waitingForPin.value = false
    }

    override fun isWaitingForPin(): Boolean {
        if (nativePtr == 0L) return false
        return nativeIsWaitingForPin()
    }

    override fun setNowPlaying(
        title: String,
        artist: String,
        album: String,
        cover: ByteArray?,
        coverMime: String?,
    ) {
        if (nativePtr == 0L) return
        nativeSetNowPlaying(title, artist, album, cover, coverMime)
    }

    override fun isActive(): Boolean {
        if (nativePtr == 0L) return false
        return nativeIsActive()
    }

    protected fun finalize() {
        if (nativePtr != 0L) {
            nativeDestroy()
        }
    }

    // ── Native callbacks (called from the native poll loop thread) ──────

    /**
     * Called by the native side when the AirPlay session is launched
     * (RECORD accepted) or fails.
     *
     * @param ok true if the session launched, false if it failed
     * @param error the error message when [ok] is false
     */
    @Suppress("unused")
    private fun onLaunched(ok: Boolean, error: String) {
        scope.launch {
            _connected.value = ok
            if (!ok) {
                _waitingForPin.value = false
                _pinDeviceName.value = null
            }
        }
    }

    /** Called by the native side when the session ends (TEARDOWN or dropped). */
    @Suppress("unused")
    private fun onClosed() {
        scope.launch {
            _connected.value = false
            _waitingForPin.value = false
            _pinDeviceName.value = null
        }
    }

    /**
     * Called by the native side when the receiver is showing a PIN and
     * waiting for the user to enter it.
     *
     * @param deviceName the receiver's display name (for the PIN dialog)
     */
    @Suppress("unused")
    private fun onPinRequired(deviceName: String) {
        scope.launch {
            _waitingForPin.value = true
            _pinDeviceName.value = deviceName
        }
    }

    /**
     * Called by the native side when a first pairing succeeds and
     * produces long-term credentials. The host should persist these
     * keyed by [deviceId] so later connects skip the PIN.
     *
     * @param deviceId the receiver's mDNS instance id (creds key)
     * @param credsJson opaque JSON blob to persist
     */
    @Suppress("unused")
    private fun onCredentials(deviceId: String, credsJson: String) {
        scope.launch {
            // Persist credentials — the caller (SendpinApp or a settings
            // store) wires this to DataStore. For now, we just surface it
            // as a flow so the UI can save it.
            _onCredentials?.invoke(deviceId, credsJson)
        }
    }

    /** Set by the app to persist pairing credentials. */
    var _onCredentials: ((String, String) -> Unit)? = null

    // ── JNI declarations ────────────────────────────────────────────────

    private external fun nativeInit(): Long
    private external fun nativeDestroy()
    private external fun nativeStart(
        host: String, port: Int, name: String,
        authInt: Int, airplay2: Boolean,
        deviceId: String, credsJson: String, password: String,
    )
    private external fun nativeStop()
    private external fun nativeWritePcm(buffer: ByteArray, offset: Int, length: Int)
    private external fun nativeSetVolume(volume: Float)
    private external fun nativeSubmitPin(pin: String)
    private external fun nativeSetNowPlaying(
        title: String, artist: String, album: String,
        cover: ByteArray?, coverMime: String?,
    )
    private external fun nativeIsWaitingForPin(): Boolean
    private external fun nativeIsActive(): Boolean
}