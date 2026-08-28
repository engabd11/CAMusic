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
import kotlinx.coroutines.flow.asStateFlow
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
 *
 * Speed-limit source: when [AppSettings.speedLimitAutoDetect] is enabled, the
 * alert uses [OfflineSpeedLimitProvider] to look up the posted limit from GPS
 * coordinates against a local SQLite database — no internet, no tracking. When
 * auto-detect is off, or the database has no data for the current location, it
 * falls back to the manually-set limit ([AppSettings.drivingSpeedLimitKmh]).
 * The last detected limit is exposed as [detectedLimitKmh] for the UI to display.
 */
class SpeedMonitor(private val context: Context, private val drivingMode: DrivingMode) {

    private val settings = AppSettings(context)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val locationManager get() = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val alertTracker = SpeedAlert.Tracker()
    private var toneGenerator: ToneGenerator? = null
    private var gateJob: Job? = null
    private var listening = false

    /** The offline speed-limit provider. Null until first needed. */
    private var speedLimitProvider: SpeedLimitProvider? = null

    private val _speedKmh = MutableStateFlow(0f)
    val speedKmh: StateFlow<Float> = _speedKmh.asStateFlow()

    /** The last speed limit detected from GPS location, or null if auto-detect is off or no data. */
    private val _detectedLimitKmh = MutableStateFlow<Int?>(null)
    val detectedLimitKmh: StateFlow<Int?> = _detectedLimitKmh.asStateFlow()

    /** The speed limit currently in effect — detected, or manual if auto-detect is off. */
    private val _activeLimitKmh = MutableStateFlow<Int?>(null)
    val activeLimitKmh: StateFlow<Int?> = _activeLimitKmh.asStateFlow()

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
        // Lazily initialise the speed-limit provider. It opens the database if present;
        // if not, it just returns null for all queries until the user downloads the data.
        if (speedLimitProvider == null) {
            speedLimitProvider = OfflineSpeedLimitProvider(context)
        }
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
        // Close the speed-limit provider to release the SQLite file handle. It
        // will be re-opened lazily when location updates resume. Keeping it open
        // while driving mode is off wastes a file descriptor for no purpose.
        speedLimitProvider?.close()
        speedLimitProvider = null
        _speedKmh.value = 0f
        _detectedLimitKmh.value = null
        _activeLimitKmh.value = null
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
            if (settings.speedLimitAlertEnabled.first()) checkAlert(kmh, location)
        }
    }

    /**
     * Decide whether to beep for the current reading.
     *
     * The limit comes from one of two sources, in priority order:
     * 1. If auto-detect is on and the database has data: the detected limit
     * 2. The manually-set limit (the original behaviour, preserved as fallback)
     *
     * The detected limit is also exposed via [detectedLimitKmh] / [activeLimitKmh]
     * so the UI can show "Detected: 60 km/h" in real time.
     */
    private suspend fun checkAlert(kmh: Float, location: Location) {
        val autoDetect = settings.speedLimitAutoDetect.first()
        val manualLimit = settings.drivingSpeedLimitKmh.first()
        val tolerance = settings.drivingSpeedTolerancePct.first()

        var limit: Int? = null
        var detected: Int? = null

        if (autoDetect) {
            val provider = speedLimitProvider
            if (provider != null && provider.ready.value) {
                detected = provider.getSpeedLimit(location.latitude, location.longitude)
                _detectedLimitKmh.value = detected
            }
            // Use detected if available, otherwise fall back to manual
            limit = detected ?: manualLimit.takeIf { it > 0 }
        } else {
            _detectedLimitKmh.value = null
            limit = manualLimit.takeIf { it > 0 }
        }

        _activeLimitKmh.value = limit

        if (limit == null || limit <= 0) return
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