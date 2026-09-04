package com.engabd.sendpin.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
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
import kotlinx.coroutines.launch

/**
 * GPS speed, shared by the speed-limit alert (3.1) and speed-adaptive volume
 * (3.4) — one location subscription rather than two, and one place both features'
 * opt-in gating lives.
 *
 * Active only while [DrivingMode.speedWatchActive] is true *and* at least one of the
 * two features is turned on — nothing here asks for a location fix, or even keeps
 * its `ACCESS_FINE_LOCATION` permission live in any meaningful sense, for someone
 * who has both switched off.
 *
 * Speed-limit source: when [AppSettings.speedLimitAutoDetect] is enabled, the
 * alert uses [OfflineSpeedLimitProvider] to look up the posted limit from GPS
 * coordinates against a local SQLite database that ships in the APK — no
 * internet, no tracking, and nothing to download. When auto-detect is off, or
 * the location is outside the data's coverage, it falls back to the manually-set
 * limit ([AppSettings.drivingSpeedLimitKmh]). The last detected limit is exposed
 * as [detectedLimitKmh], and what the data itself is doing as [limitDataStatus],
 * for the UI to display.
 *
 * ## Staying alive behind another app
 *
 * The whole point of driving mode is that the driver is looking at Google Maps, not
 * at this. A location subscription taken by a backgrounded app is not throttled so
 * much as switched off — `ACCESS_FINE_LOCATION` is a while-in-use grant — so
 * [DrivingLocationService] is started alongside the subscription and stopped with
 * it. That service exists for no other reason; see its own doc.
 *
 * ## What it asks the platform for
 *
 * A fix a second, from the fused provider where the platform has one and raw GPS
 * otherwise. Both numbers used to be looser — five seconds, GPS only — and both fed
 * straight into the alert being late or absent: the alert's confirmation window is
 * measured in fixes' worth of time, and the coarser the interval the longer a driver
 * has to hold a speed before anything happens. A fix a second is what a satnav asks
 * for, on a phone that is plugged in and driving.
 */
class SpeedMonitor(private val context: Context, private val drivingMode: DrivingMode) {

    private val settings = AppSettings(context)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val locationManager get() = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val alertTracker = SpeedAlert.Tracker()
    private var gateJob: Job? = null
    private var settingsJob: Job? = null
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

    /**
     * One line about the bundled speed-limit data — unpacking, or ready with its
     * version and size — or null when nothing is listening for locations.
     *
     * Straight from [SpeedLimitProvider.statusDescription], so the settings card
     * reports the provider's own answer rather than a second guess at it.
     */
    private val _limitDataStatus = MutableStateFlow<String?>(null)
    val limitDataStatus: StateFlow<String?> = _limitDataStatus.asStateFlow()

    /**
     * Whether fixes are actually arriving, in one line, for the settings card.
     *
     * The one question the UI could not answer before, and the first one anyone asks
     * when an alert does not fire: is this thing even receiving anything? Null while
     * nothing is listening.
     */
    private val _fixStatus = MutableStateFlow<String?>(null)
    val fixStatus: StateFlow<String?> = _fixStatus.asStateFlow()

    private var statusJob: Job? = null

    /**
     * The settings the location callback needs, mirrored so it can read them without
     * suspending.
     *
     * Every fix used to `first()` four DataStore flows on the main thread before it
     * could decide anything. Collected once here instead, which is both cheaper and
     * the only way the callback can stay a plain function.
     */
    @Volatile private var alertEnabled = false
    @Volatile private var adaptiveEnabled = false
    @Volatile private var autoDetect = false
    @Volatile private var manualLimitKmh = 0
    @Volatile private var tolerancePct = 5

    /** The previous fix, for the derived-speed fallback. */
    private var lastFix: Location? = null

    /** Guards against a second lookup starting while one is still on the database. */
    @Volatile private var lookupInFlight = false

    private val locationListener = LocationListener { location -> onLocation(location) }

    fun start() {
        gateJob?.cancel()
        gateJob = scope.launch {
            combine(
                drivingMode.speedWatchActive,
                settings.speedLimitAlertEnabled,
                settings.speedAdaptiveVolume,
            ) { active, alert, adaptive -> active && (alert || adaptive) }
                .distinctUntilChanged()
                .collect { shouldRun -> if (shouldRun) startLocationUpdates() else stopLocationUpdates() }
        }
        settingsJob?.cancel()
        settingsJob = scope.launch {
            combine(
                settings.speedLimitAlertEnabled,
                settings.speedAdaptiveVolume,
                settings.speedLimitAutoDetect,
                settings.drivingSpeedLimitKmh,
                settings.drivingSpeedTolerancePct,
            ) { alert, adaptive, auto, limit, tolerance ->
                Tunables(alert, adaptive, auto, limit, tolerance)
            }.distinctUntilChanged().collect {
                alertEnabled = it.alert
                adaptiveEnabled = it.adaptive
                autoDetect = it.auto
                manualLimitKmh = it.limitKmh
                tolerancePct = it.tolerancePct
            }
        }
    }

    fun stop() {
        gateJob?.cancel()
        settingsJob?.cancel()
        stopLocationUpdates()
    }

    private fun startLocationUpdates() {
        if (listening) return
        // Inline rather than through [DrivingLocationService.hasLocationPermission],
        // which asks the same question: lint reads a `checkSelfPermission` guard in
        // the same function as proof that `requestLocationUpdates` below is allowed,
        // and cannot follow the call into another class to find it.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        listening = true
        alertTracker.reset()
        lastFix = null
        _fixStatus.value = "Waiting for a GPS fix…"
        // Started before the subscription, not after: this is what keeps the grant
        // alive once the driver switches to their map, and a subscription taken
        // first would spend the gap being the very background request the service
        // exists to prevent.
        DrivingLocationService.start(context)
        // Lazily initialise the speed-limit provider. It opens the database if present;
        // if not, it just returns null for all queries until the user downloads the data.
        if (speedLimitProvider == null) {
            val provider = OfflineSpeedLimitProvider(context)
            speedLimitProvider = provider
            // Republish the provider's own status line whenever it changes state,
            // so the settings card can say "unpacking" and then name the data.
            statusJob?.cancel()
            statusJob = scope.launch {
                provider.ready.collect { _limitDataStatus.value = provider.statusDescription() }
            }
        }
        // The fused provider is the platform's own blend of GPS, the sensors and the
        // network, and on a phone in a cradle it is both faster to first fix and
        // steadier through a tunnel or an urban canyon than raw GPS. It is not always
        // present or enabled, so raw GPS stays the fallback rather than the only
        // choice — and the *enabled* check is what makes it a real fallback:
        // `requestLocationUpdates` accepts a disabled provider without complaint and
        // then simply never calls back.
        val providers = buildList {
            if (LocationManager.FUSED_PROVIDER in locationManager.allProviders) {
                add(LocationManager.FUSED_PROVIDER)
            }
            add(LocationManager.GPS_PROVIDER)
        }.filter { runCatching { locationManager.isProviderEnabled(it) }.getOrDefault(false) }
        val subscribed = providers.any { provider ->
            runCatching {
                locationManager.requestLocationUpdates(
                    provider,
                    MIN_UPDATE_INTERVAL_MS,
                    0f,
                    locationListener,
                    Looper.getMainLooper(),
                )
            }.isSuccess
        }
        if (!subscribed) {
            _fixStatus.value = "Location is switched off on this phone."
        }
    }

    private fun stopLocationUpdates() {
        if (!listening) return
        listening = false
        runCatching { locationManager.removeUpdates(locationListener) }
        DrivingLocationService.stop(context)
        // Close the speed-limit provider to release the SQLite file handle. It
        // will be re-opened lazily when location updates resume. Keeping it open
        // while driving mode is off wastes a file descriptor for no purpose.
        statusJob?.cancel()
        statusJob = null
        _limitDataStatus.value = null
        _fixStatus.value = null
        speedLimitProvider?.close()
        speedLimitProvider = null
        _speedKmh.value = 0f
        _detectedLimitKmh.value = null
        _activeLimitKmh.value = null
        lastFix = null
        alertTracker.reset()
        SendpinApp.instance.localPlayer.speedGainTarget = 1f
    }

    private fun onLocation(location: Location) {
        val nowMs = System.currentTimeMillis()
        val kmh = speedKmhFrom(location)
        lastFix = location
        if (kmh == null) {
            // A fix with no usable speed still says the subscription is alive, which
            // is worth reporting; it just cannot feed the alert.
            _fixStatus.value = "Fix received, no speed yet."
            return
        }
        _speedKmh.value = kmh
        _fixStatus.value = "GPS ${kmh.toInt()} km/h" +
            (location.accuracy.takeIf { location.hasAccuracy() && it > 0f }
                ?.let { ", ±${it.toInt()} m" } ?: "")

        if (adaptiveEnabled) {
            SendpinApp.instance.localPlayer.speedGainTarget = SpeedAdaptiveGain.gainFactor(kmh)
        }
        if (alertEnabled) checkAlert(kmh, location, nowMs)
    }

    /**
     * The reading's speed in km/h, or null if there is no honest one to be had.
     *
     * `hasSpeed()` is false on some first fixes and on providers that report
     * position only, and the old code simply returned — leaving the speed at
     * whatever it last was and the alert with nothing to judge. Two fixes and the
     * time between them are a perfectly good speed, and over the second-scale
     * interval this subscribes at they are an accurate one.
     */
    private fun speedKmhFrom(location: Location): Float? {
        if (location.hasSpeed()) return location.speed * 3.6f
        val previous = lastFix ?: return null
        val elapsedMs = location.time - previous.time
        if (elapsedMs !in MIN_DERIVE_GAP_MS..MAX_DERIVE_GAP_MS) return null
        val metres = location.distanceTo(previous)
        return (metres / (elapsedMs / 1000f)) * 3.6f
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
     *
     * The database lookup is the one slow part, and it does not block the decision.
     * A fix is judged against the limit already known for this stretch of road while
     * the next lookup runs alongside it — a road's posted limit does not change
     * between two fixes a second apart, and making every beep wait on SQLite is how
     * a warning arrives after the corner it was about.
     */
    private fun checkAlert(kmh: Float, location: Location, nowMs: Long) {
        if (autoDetect) {
            refreshDetectedLimit(location)
        } else if (_detectedLimitKmh.value != null) {
            _detectedLimitKmh.value = null
        }

        val manual = manualLimitKmh.takeIf { it > 0 }
        val limit = if (autoDetect) (_detectedLimitKmh.value ?: manual) else manual
        _activeLimitKmh.value = limit

        if (limit == null || limit <= 0) return
        val trigger = SpeedAlert.triggerSpeedKmh(limit, tolerancePct)
        if (alertTracker.onReading(kmh, trigger, nowMs)) {
            SpeedAlertSound.play(context, alertSoundId)
        }
    }

    /** Look up the posted limit for this fix, off the main thread, one at a time. */
    private fun refreshDetectedLimit(location: Location) {
        val provider = speedLimitProvider ?: return
        if (!provider.ready.value || lookupInFlight) return
        lookupInFlight = true
        // Accuracy is passed through rather than assumed: the database matches a
        // point against road geometry within a fixed radius, and a fix that is
        // honestly ±25 m needs that radius widened by 25 m or it lands beside every
        // road it is actually on.
        val accuracy = if (location.hasAccuracy()) location.accuracy else 0f
        scope.launch {
            try {
                _detectedLimitKmh.value = provider.getSpeedLimit(
                    location.latitude,
                    location.longitude,
                    accuracyMeters = accuracy,
                )
            } catch (_: Exception) {
                // A lookup that failed says nothing about the road; the last known
                // limit stands until one succeeds, and the manual fallback covers a
                // provider that never answers.
            } finally {
                lookupInFlight = false
            }
        }
    }

    /**
     * The chosen warning sound, mirrored for the same reason the tunables above are.
     *
     * Read on its own rather than folded into [Tunables] because `combine` tops out
     * at five flows without a nested one, and this is the least interesting of the six.
     */
    @Volatile private var alertSoundId: String = AppSettings.SPEED_ALERT_TONE

    init {
        scope.launch { settings.speedAlertSound.collect { alertSoundId = it } }
    }

    private data class Tunables(
        val alert: Boolean,
        val adaptive: Boolean,
        val auto: Boolean,
        val limitKmh: Int,
        val tolerancePct: Int,
    )

    companion object {
        /**
         * How often the platform is asked for a fix.
         *
         * A second, not the five it used to be. The alert confirms an overage over a
         * window of *time*, so a coarse interval does not make it more forgiving —
         * it makes it blind between fixes, and at 100 km/h five seconds is 140 m of
         * road, easily a whole speed zone.
         */
        private const val MIN_UPDATE_INTERVAL_MS = 1_000L

        /** Two fixes closer together than this cannot be differenced usefully. */
        private const val MIN_DERIVE_GAP_MS = 300L

        /** Two fixes further apart than this are a resumed subscription, not a journey. */
        private const val MAX_DERIVE_GAP_MS = 30_000L
    }
}
