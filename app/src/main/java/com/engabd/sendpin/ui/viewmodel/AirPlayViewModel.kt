package com.engabd.sendpin.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.audio.AirPlayDiscovery
import com.engabd.sendpin.audio.AirPlayOutput
import com.engabd.sendpin.data.AppSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * AirPlay from the Now Playing screen: which receivers are on the network, which
 * one this phone is streaming to, and the PIN a receiver asks for the first time.
 *
 * The output itself is process-scoped (`LocalPlayer.airPlayOutput`, wired into the
 * local ExoPlayer's chain by `AirPlayOutputProcessor`); this only drives it. What
 * reaches the receiver is what this phone decodes itself — Navidrome, Jellyfin,
 * files on the phone, the streaming accounts. A Music Assistant queue plays to
 * MA's own speakers and never passes through here, which the sheet says.
 */
class AirPlayViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = AppSettings(app)
    private val output: AirPlayOutput? = (app as SendpinApp).localPlayer.airPlayOutput

    /** False when the native sender did not build or load: the button is simply absent. */
    val available: Boolean = output != null

    val connected: StateFlow<Boolean> = output?.connected ?: MutableStateFlow(false)
    val deviceName: StateFlow<String?> = output?.deviceName ?: MutableStateFlow(null)
    val waitingForPin: StateFlow<Boolean> = output?.waitingForPin ?: MutableStateFlow(false)
    val pinDeviceName: StateFlow<String?> = output?.pinDeviceName ?: MutableStateFlow(null)

    /** The receiver being connected to, from the tap until the session reports. */
    private val _connecting = MutableStateFlow<String?>(null)
    val connecting: StateFlow<String?> = _connecting

    /** The last failure, for the sheet. Cleared by the next attempt. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /** Receivers announced on the network — live only while [startDiscovery] is on. */
    private val _devices = MutableStateFlow<List<AirPlayDiscovery.AirPlayDevice>>(emptyList())
    val devices: StateFlow<List<AirPlayDiscovery.AirPlayDevice>> = _devices
    private var discoveryJob: Job? = null

    init {
        viewModelScope.launch {
            connected.collect { if (it) _connecting.value = null }
        }
    }

    /** Browse for receivers while the sheet is open; [stopDiscovery] when it closes. */
    fun startDiscovery() {
        if (discoveryJob?.isActive == true) return
        discoveryJob = viewModelScope.launch {
            runCatching {
                AirPlayDiscovery(getApplication()).discover().collect { _devices.value = it }
            }.onFailure { _error.value = "Could not browse the network: ${it.message}" }
        }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
    }

    fun connect(device: AirPlayDiscovery.AirPlayDevice) {
        val out = output ?: return
        _error.value = null
        _connecting.value = device.name
        viewModelScope.launch {
            try {
                // A receiver paired before hands its credentials back so the PIN is
                // not asked again; a fresh one gets an empty blob and asks.
                val creds = settings.airPlayCredentials.first()[device.deviceId].orEmpty()
                if (out.connected.value) out.disconnect()
                out.connect(
                    host = device.host,
                    port = device.port,
                    name = device.name,
                    authMode = device.authMode,
                    airplay2 = device.airplay2,
                    deviceId = device.deviceId,
                    credentialsJson = creds,
                )
            } catch (e: Exception) {
                _connecting.value = null
                _error.value = e.message ?: "Could not reach ${device.name}"
            }
        }
    }

    fun disconnect() {
        val out = output ?: return
        _connecting.value = null
        viewModelScope.launch { runCatching { out.disconnect() } }
    }

    fun submitPin(pin: String) {
        output?.submitPin(pin.trim())
    }

    /** Mirror the phone's volume slider onto the receiver, 0..1. */
    fun setVolume(level01: Float) {
        if (connected.value) output?.setVolume(level01.coerceIn(0f, 1f))
    }

    /** What the receiver's own Now Playing shows — an Apple TV renders this itself. */
    fun setNowPlaying(title: String, artist: String, album: String) {
        if (connected.value) output?.setNowPlaying(title, artist, album, cover = null, coverMime = null)
    }

    override fun onCleared() {
        stopDiscovery()
        // The session outlives the screen on purpose: the music keeps going to the
        // receiver until the user disconnects it.
    }

    /** True while a session is up or being set up — the badge lights on either. */
    val active: StateFlow<Boolean> = if (output == null) MutableStateFlow(false) else
        kotlinx.coroutines.flow.combine(connected, _connecting) { c, name -> c || name != null }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)
}
