package com.vintechafrica.jvinbuddy

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Touch-free body senses from the phone's accelerometer:
 *  - bump:   one sharp knock
 *  - shake:  3+ sharp jolts within 1.2 s
 *  - lifted: robot tilted more than 30 degrees from how it normally sits, for 0.7 s
 *            (picked up), then "put down" when it is level again for 1 s
 * The robot's own starts and stops also jolt the phone, so bumps and lifts
 * are ignored while it is driving.
 */
class Senses(
    context: Context,
    private val onBump: () -> Unit,
    private val onShake: () -> Unit = {},
    private val onLift: (Boolean) -> Unit = {},
    private val isDriving: () -> Boolean = { false },
) : SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var lastBump = 0L
    private var lastShake = 0L
    private val peaks = ArrayDeque<Long>()

    // Gravity direction, smoothed
    private val g = FloatArray(3)
    private var gReady = false
    private var baseline: FloatArray? = null
    private var stillSince = 0L
    private var tiltSince = 0L
    private var levelSince = 0L
    private var lifted = false
    private var lastDriveAt = 0L

    fun start() {
        accel?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val now = SystemClock.elapsedRealtime()
        val magnitude = sqrt(x * x + y * y + z * z)
        val jolt = abs(magnitude - SensorManager.GRAVITY_EARTH)
        val driving = isDriving()
        if (driving) lastDriveAt = now
        val recentlyDriving = now - lastDriveAt < 800

        // ---------------------------------------------------------- bump / shake
        if (jolt > BUMP_THRESHOLD) {
            if (peaks.isEmpty() || now - peaks.last() > 150) peaks.addLast(now)
            while (peaks.isNotEmpty() && now - peaks.first() > 1200) peaks.removeFirst()
            if (peaks.size >= 3 && now - lastShake > 3000) {
                lastShake = now
                peaks.clear()
                onShake()
            } else if (!recentlyDriving && now - lastBump > 2500 && peaks.size == 1) {
                lastBump = now
                onBump()
            }
        }

        // ---------------------------------------------------------- lift
        val a = 0.1f
        if (!gReady) {
            g[0] = x; g[1] = y; g[2] = z; gReady = true
        } else {
            g[0] += a * (x - g[0]); g[1] += a * (y - g[1]); g[2] += a * (z - g[2])
        }

        // Learn "normal" position while sitting still and not driving.
        if (jolt < 0.6f && !recentlyDriving) {
            if (stillSince == 0L) stillSince = now
            if (!lifted && now - stillSince > 2000) {
                val b = baseline
                if (b == null) baseline = g.copyOf()
                else if (angleTo(b) < 12f) for (i in 0..2) b[i] = g[i] // follow small changes
            }
        } else {
            stillSince = 0L
        }

        val base = baseline ?: return
        if (recentlyDriving) {
            tiltSince = 0L
            return
        }
        val angle = angleTo(base)
        if (!lifted) {
            if (angle > 30f) {
                if (tiltSince == 0L) tiltSince = now
                if (now - tiltSince > 700) {
                    lifted = true
                    levelSince = 0L
                    onLift(true)
                }
            } else {
                tiltSince = 0L
            }
        } else {
            if (angle < 12f && jolt < 1.5f) {
                if (levelSince == 0L) levelSince = now
                if (now - levelSince > 1000) {
                    lifted = false
                    tiltSince = 0L
                    onLift(false)
                }
            } else {
                levelSince = 0L
            }
        }
    }

    private fun angleTo(b: FloatArray): Float {
        val dot = g[0] * b[0] + g[1] * b[1] + g[2] * b[2]
        val n1 = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2])
        val n2 = sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2])
        if (n1 < 0.1f || n2 < 0.1f) return 0f
        return Math.toDegrees(acos((dot / (n1 * n2)).coerceIn(-1f, 1f).toDouble())).toFloat()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object { const val BUMP_THRESHOLD = 6.0f }
}
