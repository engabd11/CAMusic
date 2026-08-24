package com.engabd.sendpin.gesture

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.engabd.sendpin.SendpinApp
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Shake to skip, flip face-down to pause, double-tap the body to play/pause.
 *
 * Deliberately not built on [com.engabd.sendpin.hue.DeviceMotionSource]: that class
 * is lighting-scoped in its own doc (a *lighting-relevant* snapshot for
 * `PhoneConductorLayer`), polled once per render frame rather than push-based, and
 * has no proximity reading at all — face-down detection needs one. Only the
 * `SensorManager` registration idiom is worth mirroring; the two classes have no
 * reason to share code beyond that.
 *
 * `start()`/`stop()` are idempotent, matching the process-scoped monitor pattern
 * in `SendpinApp` (`callPauseObserver`, `speedMonitor`).
 */
class PlaybackGestureMonitor(context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val proximity = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)

    @Volatile private var running = false

    private var lastMagnitude = SensorManager.GRAVITY_EARTH
    private var lastShakeAtMs = 0L
    private var lastTapAtMs = 0L
    private var lastDoubleTapAtMs = 0L
    private var pendingTap = false

    // Smoothed gravity Z — same low-pass idea as DeviceMotionSource's tilt, so a
    // single noisy sample can't flip the face-down state on its own.
    private var gz = 0f
    private var proximityNear = false
    private var faceDown = false

    fun start() {
        if (running) return
        val sm = sensorManager ?: return
        accelerometer?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        proximity?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        running = true
    }

    fun stop() {
        if (!running) return
        sensorManager?.unregisterListener(this)
        running = false
        // Every reading goes back to its at-rest value, not just the derived
        // gesture state. A stale `gz` and `proximityNear` from whatever position
        // the phone was in when this was switched off would otherwise still be
        // sitting there next time it comes on, and a proximity event arriving
        // before the first accelerometer sample would read them as a fresh
        // face-down and pause the music on nothing.
        faceDown = false
        pendingTap = false
        proximityNear = false
        gz = 0f
        lastMagnitude = SensorManager.GRAVITY_EARTH
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> onAccelerometer(event.values[0], event.values[1], event.values[2])
            Sensor.TYPE_PROXIMITY -> onProximity(event.values[0], event.sensor.maximumRange)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun onAccelerometer(x: Float, y: Float, z: Float) {
        gz += (z - gz) * TILT_SMOOTHING
        updateFaceDown()

        val magnitude = sqrt(x * x + y * y + z * z)
        val jump = abs(magnitude - lastMagnitude)
        lastMagnitude = magnitude
        val now = System.currentTimeMillis()

        when {
            jump > SHAKE_THRESHOLD -> {
                pendingTap = false
                if (now - lastShakeAtMs > SHAKE_COOLDOWN_MS) {
                    lastShakeAtMs = now
                    playbackOwner()?.next()
                }
            }
            jump in TAP_MIN..TAP_MAX -> {
                if (pendingTap && now - lastTapAtMs <= DOUBLE_TAP_WINDOW_MS) {
                    pendingTap = false
                    if (now - lastDoubleTapAtMs > DOUBLE_TAP_COOLDOWN_MS) {
                        lastDoubleTapAtMs = now
                        playbackOwner()?.playPause()
                    }
                } else {
                    pendingTap = true
                    lastTapAtMs = now
                }
            }
        }
    }

    private fun onProximity(distance: Float, maxRange: Float) {
        // A binary proximity sensor reports 0 for "near"; a ranging one reports
        // anywhere below its max. Either way, "near" is "well short of max range".
        proximityNear = distance < maxRange * PROXIMITY_NEAR_FRACTION
        updateFaceDown()
    }

    /** Fires once on the transition into face-down, not on every sample while held there. */
    private fun updateFaceDown() {
        val down = proximityNear && gz < FACE_DOWN_GZ_THRESHOLD
        if (down && !faceDown) playbackOwner()?.pause()
        faceDown = down
    }

    private fun playbackOwner() = SendpinApp.instance.playbackOwner

    private companion object {
        /** m/s^2 acceleration-magnitude jump a deliberate shake clears. */
        const val SHAKE_THRESHOLD = 20f
        const val SHAKE_COOLDOWN_MS = 800L

        /** A lighter jump than a shake — tapping the phone's body rather than shaking it. */
        const val TAP_MIN = 15f
        const val TAP_MAX = SHAKE_THRESHOLD
        const val DOUBLE_TAP_WINDOW_MS = 400L
        const val DOUBLE_TAP_COOLDOWN_MS = 600L

        const val TILT_SMOOTHING = 0.15f
        const val PROXIMITY_NEAR_FRACTION = 0.5f
        const val FACE_DOWN_GZ_THRESHOLD = -8f
    }
}
