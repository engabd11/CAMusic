package com.engabd.sendpin.hue

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A snapshot of what the phone is doing, for [PhoneConductorLayer].
 *
 * @param tiltX smoothed gravity-vector tilt, left/right, roughly -1..1.
 * @param tiltY smoothed gravity-vector tilt, up/down, roughly -1..1.
 * @param flick one-shot: a sharp jerk happened since the last [DeviceMotionSource.state] read.
 * @param rotationRateZ gyroscope yaw rate, rad/s.
 * @param active false once the phone has sat still for the spec's 5 s auto-disable window.
 */
data class DeviceMotionState(
    val tiltX: Float = 0f,
    val tiltY: Float = 0f,
    val flick: Boolean = false,
    val rotationRateZ: Float = 0f,
    val active: Boolean = false,
)

/** Gravity-vector tilt on each horizontal axis, -1..1. Pure, for testing without a real sensor. */
internal fun tiltFrom(gx: Float, gy: Float, gravity: Float): Pair<Float, Float> =
    (gx / gravity).coerceIn(-1f, 1f) to (gy / gravity).coerceIn(-1f, 1f)

/**
 * A deliberate wrist flick, not the ordinary jostle of holding or walking
 * with the phone — a sharp acceleration-magnitude spike between consecutive
 * samples, gated by [thresholdMs2].
 */
internal fun isFlick(previousMagnitude: Float, magnitude: Float, thresholdMs2: Float): Boolean =
    abs(magnitude - previousMagnitude) > thresholdMs2

/** Whether [timeoutNanos] has elapsed since the phone last moved. */
internal fun isStillnessTimedOut(lastMotionAtNanos: Long, nowNanos: Long, timeoutNanos: Long): Boolean =
    (nowNanos - lastMotionAtNanos) >= timeoutNanos

/**
 * Turns the phone's accelerometer and gyroscope into a lighting-relevant
 * snapshot for [PhoneConductorLayer]. See `docs/creative-light-shows.md`.
 *
 * No manifest permission needed — `SensorManager.getDefaultSensor` requires
 * none on Android 12+. [start]/[stop] are idempotent and cheap: the caller
 * (`DirectLightSync`) is expected to call them every time the setting or the
 * streaming state changes, not just once.
 */
class DeviceMotionSource(context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    @Volatile private var running = false

    // Low-passed accelerometer vector — the gravity direction, for tilt.
    @Volatile private var gx = 0f
    @Volatile private var gy = 0f
    private var lastAccelMagnitude = SensorManager.GRAVITY_EARTH
    @Volatile private var lastMotionAtNanos = 0L
    @Volatile private var flickPending = false
    @Volatile private var rotationRateZ = 0f

    fun start() {
        if (running) return
        val sm = sensorManager ?: return
        accelerometer?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroscope?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        running = true
        lastMotionAtNanos = System.nanoTime()
    }

    fun stop() {
        if (!running) return
        sensorManager?.unregisterListener(this)
        running = false
    }

    /**
     * The latest snapshot. [DeviceMotionState.flick] is consumed on read —
     * a caller polling this once a frame sees each flick exactly once, the
     * same one-shot shape [StructureState.dropNow] already has.
     */
    val state: DeviceMotionState
        get() {
            if (!running) return DeviceMotionState()
            val (tiltX, tiltY) = tiltFrom(gx, gy, SensorManager.GRAVITY_EARTH)
            val flick = flickPending
            flickPending = false
            val active = !isStillnessTimedOut(lastMotionAtNanos, System.nanoTime(), STILLNESS_TIMEOUT_NANOS)
            return DeviceMotionState(tiltX, tiltY, flick, rotationRateZ, active)
        }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> onAccelerometer(event.values[0], event.values[1], event.values[2])
            Sensor.TYPE_GYROSCOPE -> onGyroscope(event.values[2])
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun onAccelerometer(x: Float, y: Float, z: Float) {
        gx += (x - gx) * TILT_SMOOTHING
        gy += (y - gy) * TILT_SMOOTHING

        val magnitude = sqrt(x * x + y * y + z * z)
        val flick = isFlick(lastAccelMagnitude, magnitude, FLICK_JERK_THRESHOLD)
        val moved = abs(magnitude - SensorManager.GRAVITY_EARTH) > STILLNESS_ACCEL_THRESHOLD
        lastAccelMagnitude = magnitude
        if (flick) {
            flickPending = true
            lastMotionAtNanos = System.nanoTime()
        } else if (moved) {
            lastMotionAtNanos = System.nanoTime()
        }
    }

    private fun onGyroscope(rateZ: Float) {
        rotationRateZ = rateZ
        if (abs(rateZ) > STILLNESS_ROTATION_THRESHOLD) lastMotionAtNanos = System.nanoTime()
    }

    private companion object {
        private const val TILT_SMOOTHING = 0.15f

        /** m/s^2 acceleration-magnitude spike a deliberate flick clears; ordinary holding/walking does not. */
        private const val FLICK_JERK_THRESHOLD = 15f
        private const val STILLNESS_ACCEL_THRESHOLD = 1f
        private const val STILLNESS_ROTATION_THRESHOLD = 0.15f

        /** Spec-fixed: the one non-negotiable number in this feature. */
        private const val STILLNESS_TIMEOUT_NANOS = 5_000_000_000L
    }
}
