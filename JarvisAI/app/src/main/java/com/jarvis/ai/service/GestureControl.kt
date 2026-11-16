package com.jarvis.ai.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs

/**
 * v1.3.9 — Gesture control: shake the phone to wake Jarvis (or stop it).
 * Listens to the accelerometer while the foreground service runs. A sharp
 * shake (speed > threshold) toggles live voice, throttled to once per 3s so
 * one shake never fires twice. Battery cost is tiny (SENSOR_DELAY_NORMAL).
 * Enable in Settings > Gesture Control; auto-disabled on low battery by
 * ResourceManager.
 */
object GestureControl {
    private var sensorManager: SensorManager? = null
    private var listener: SensorEventListener? = null
    private var lastShake = 0L

    fun start(ctx: Context, onShake: () -> Unit) {
        if (listener != null) return
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        listener = object : SensorEventListener {
            private var lx = 0f; private var ly = 0f; private var lz = 0f; private var lt = 0L
            override fun onSensorChanged(e: SensorEvent) {
                val now = System.currentTimeMillis()
                if (now - lt < 100) return
                val dt = (now - lt).toFloat().coerceAtLeast(1f)
                val speed = abs(e.values[0] + e.values[1] + e.values[2] - lx - ly - lz) / dt * 10000
                lx = e.values[0]; ly = e.values[1]; lz = e.values[2]; lt = now
                if (speed > 1200 && now - lastShake > 3000) {
                    lastShake = now
                    onShake()
                }
            }
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }
        runCatching { sm.registerListener(listener, accel, SensorManager.SENSOR_DELAY_NORMAL) }
        sensorManager = sm
    }

    fun stop() {
        runCatching { listener?.let { sensorManager?.unregisterListener(it) } }
        listener = null
        sensorManager = null
    }
}
