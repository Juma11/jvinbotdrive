package com.vintechafrica.jvinbuddy

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Feels bumps and lifts through the phone's accelerometer.
 * The robot's own starts and stops also jolt the phone, so the brain ignores
 * bumps while it is driving hard. Raise BUMP_THRESHOLD if it triggers too often.
 */
class Senses(context: Context, private val onBump: () -> Unit) : SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var lastBump = 0L

    fun start() {
        accel?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
        val magnitude = sqrt(x * x + y * y + z * z)
        val now = SystemClock.elapsedRealtime()
        if (abs(magnitude - SensorManager.GRAVITY_EARTH) > BUMP_THRESHOLD && now - lastBump > 2500) {
            lastBump = now
            onBump()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object { const val BUMP_THRESHOLD = 6.0f }
}
