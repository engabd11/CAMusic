package com.engabd.sendpin.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Looper
import androidx.core.content.ContextCompat
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.audio.SpeedAdaptiveGain
import com.engabd.sendpin.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * GPS speed, shared by the speed-limit alert (3.1) and speed-adaptive volume
 * (3.4) — one location subscription rather than two, and one place both features'
 * opt-in gating lives.
 *
 * Active only while [DrivingMode.active] is true *and* at least one of the two
 * features is turned on — nothing here asks for a location fix, or even keeps its
 * `ACCESS_FINE_LOCATION` permission live in any meaningful sense, for someone who
 * has both switched off.
 */
class SpeedMonitor(private val context: Context, private val drivingMode: DrivingMode) {

    private val settings = AppSettings(context)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val locationManager get() = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val alertTracker = SpeedAlert.Tracker()
    private var toneGenerator: ToneGenerator? = null
    private var gateJob: Job? = null
    private var listening = false

    private val _speedKmh = MutableStateFlow(0f)
    val speedKmh: StateFlow<Float> = _speedKmh

    private val locationListener = LocationListener { location -> onLocation(location) }

    fun start() {
        gateJob?.cancel()
        gateJob = scope.launch {
            combine(
                drivingMode.active,
                settings.speedLimitAlertEnabled,
                settings.speedAdaptiveVolume,
            ) { active, alert, adaptive -> active && (alert || adaptive) }
                .distinctUntilChanged()
                .collect { shouldRun -> if (shouldRun) startLocationUpdates() else stopLocationUpdates() }
        }
    }

    fun stop() {
        gateJob?.cancel()
        stopLocationUpdates()
    }

    private fun startLocationUpdates() {
        if (listening) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        listening = true
        alertTracker.reset()
        runCatching {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                MIN_UPDATE_INTERVAL_MS,
                0f,
                locationListener,
                Looper.getMainLooper(),
            )
        }
    }

    private fun stopLocationUpdates() {
        if (!listening) return
        listening = false
        runCatching { locationManager.removeUpdates(locationListener) }
        _speedKmh.value = 0f
        alertTracker.reset()
        SendpinApp.instance.localPlayer.speedGainTarget = 1f
    }

    private fun onLocation(location: Location) {
        if (!location.hasSpeed()) return
        val kmh = location.speed * 3.6f
        _speedKmh.value = kmh
        scope.launch {
            if (settings.speedAdaptiveVolume.first()) {
                SendpinApp.instance.localPlayer.speedGainTarget = SpeedAdaptiveGain.gainFactor(kmh)
            }
            if (settings.speedLimitAlertEnabled.first()) checkAlert(kmh)
        }
    }

    private suspend fun checkAlert(kmh: Float) {
        val limit = settings.drivingSpeedLimitKmh.first()
        if (limit <= 0) return
        val tolerance = settings.drivingSpeedTolerancePct.first()
        val trigger = SpeedAlert.triggerSpeedKmh(limit, tolerance)
        if (alertTracker.onReading(kmh, trigger, System.currentTimeMillis())) beep()
    }

    /** A single short, gentle tone — not an alarm. See the plan's own safety note. */
    private fun beep() {
        val tg = toneGenerator ?: runCatching {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, BEEP_VOLUME)
        }.getOrNull()?.also { toneGenerator = it } ?: return
        runCatching { tg.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_DURATION_MS) }
    }

    companion object {
        private const val MIN_UPDATE_INTERVAL_MS = 5_000L
        private const val BEEP_VOLUME = 60 // 0..100, a notification-level tone, not a klaxon
        private const val BEEP_DURATION_MS = 200
    }
}
