package com.jarvis.ai.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.jarvis.ai.core.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v1.4.0: health tracking — on-device sensors + Health Connect onboarding.
 *
 * - Steps: the phone's step-counter sensor (needs ACTIVITY_RECOGNITION).
 *   The baseline is captured near midnight by the background watchdog tick,
 *   so "steps today" is the real count since midnight.
 * - Heart rate: the phone's heart-rate sensor IF it has one (most phones
 *   don't — the sensor lives on a smartwatch, which syncs via Health
 *   Connect). BODY_SENSORS permission is declared for it.
 * - Health Connect (sleep, watch heart rate, weight…): Jarvis opens the
 *   Health Connect permission screen so the user can grant access — reading
 *   that data needs the Health Connect client library, which is the next
 *   step once a watch is connected. Until then Jarvis says so honestly.
 */
object HealthTrack {

    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)

    private fun todayStr() = dayFmt.format(Date())

    private fun hasStepPerm(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    /** Reads the raw step-counter value once (up to 5s wait). Null if no sensor / timeout. */
    private fun readStepCounter(ctx: Context): Long? {
        val sm = ctx.getSystemService(SensorManager::class.java) ?: return null
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return null
        val latch = CountDownLatch(1)
        var value: Long? = null
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                value = e.values[0].toLong()
                latch.countDown()
            }
            override fun onAccuracyChanged(s: Sensor?, acc: Int) {}
        }
        val main = Handler(Looper.getMainLooper())
        main.post { sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL) }
        latch.await(5, TimeUnit.SECONDS)
        main.post { sm.unregisterListener(listener) }
        return value
    }

    /** Called by the background watchdog tick — captures the midnight baseline. */
    fun maybeCaptureBaseline(ctx: Context) {
        if (Prefs.healthStepsDate == todayStr()) return
        if (!hasStepPerm(ctx)) return
        Thread {
            val total = readStepCounter(ctx) ?: return@Thread
            Prefs.healthStepsDate = todayStr()
            Prefs.healthStepsBase = total
        }.start()
    }

    fun stepsToday(ctx: Context, cb: (String) -> Unit) {
        if (!hasStepPerm(ctx)) {
            cb("Steps porte ACTIVITY_RECOGNITION permission lagbe — app khullei chabe, ba Settings > Permissions-e giye Allow koro.")
            return
        }
        Thread {
            val total = readStepCounter(ctx)
            if (total == null) {
                cb("Ei phone-e step counter sensor pelam na — health tracking-er jonno Health Connect + smartwatch lagbe (health_permissions bolo).")
                return@Thread
            }
            val today = todayStr()
            val steps = if (Prefs.healthStepsDate != today) {
                Prefs.healthStepsDate = today
                Prefs.healthStepsBase = total
                0L
            } else {
                (total - Prefs.healthStepsBase).coerceAtLeast(0L)
            }
            val km = steps * 0.762 / 1000.0
            val kcal = steps * 0.04
            cb("👟 Aaj steps: $steps (anumanik %.2f km, ~%.0f kcal)".format(km, kcal))
        }.start()
    }

    fun heartRate(ctx: Context, cb: (String) -> Unit) {
        val sm = ctx.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        if (sensor == null) {
            cb("Ei phone-e heart rate sensor nei — heart rate smartwatch theke ase, Health Connect diye (health_permissions bolo, watch connect koro).")
            return
        }
        Thread {
            val latch = CountDownLatch(1)
            var value = 0f
            val listener = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    if (e.values[0] > 0) { value = e.values[0]; latch.countDown() }
                }
                override fun onAccuracyChanged(s: Sensor?, acc: Int) {}
            }
            val main = Handler(Looper.getMainLooper())
            main.post { sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL) }
            latch.await(10, TimeUnit.SECONDS)
            main.post { sm.unregisterListener(listener) }
            cb(if (value > 0) "❤️ Heart rate: ${value.toInt()} bpm" else "Heart rate sensor ache, kintu reading pelam na — sensor-er upor angul rakho 10 second, tarpor abar bolo.")
        }.start()
    }

    fun healthConnectStatus(ctx: Context): String {
        val pm = ctx.packageManager
        val hasApp = runCatching { pm.getPackageInfo("com.google.android.apps.healthdata", 0) }.isSuccess
        val builtIn = android.os.Build.VERSION.SDK_INT >= 34
        return when {
            builtIn -> "Health Connect phone-ei built-in ache (Android 14+). Watch/app theke data sync korte 'health_permissions' bolo — permission screen khule debo."
            hasApp -> "Health Connect app install ache. Watch/app theke data sync korte 'health_permissions' bolo — permission screen khule debo."
            else -> "Health Connect install kora nei — Play Store theke 'Health Connect by Android' install koro, tarpor 'health_permissions' bolo."
        }
    }

    fun summary(ctx: Context, cb: (String) -> Unit) {
        stepsToday(ctx) { stepsMsg ->
            val hr = ctx.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_HEART_RATE) != null
            val sb = StringBuilder("🏥 Health summary (aaj):\n$stepsMsg")
            sb.append("\n❤️ Heart rate sensor: ${if (hr) "ache — 'health_status' bolle reading nebo" else "phone-e nei (watch lagbe)"}")
            sb.append("\n🔗 ${healthConnectStatus(ctx)}")
            sb.append("\n\nNote: sleep, weight, blood pressure — egulo Health Connect / watch theke ase; ei version-e Jarvis segulo porte pare na (onno app-er sathe health data integration nei). Steps ar heart-rate sensor — etai Jarvis nije theke korte pare.")
            cb(sb.toString())
        }
    }

    /** Opens the Health Connect permission screen (plain Intent — no client library needed). */
    fun openHealthConnectSettings(ctx: Context): String {
        val intent = Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { ctx.startActivity(intent) }.isSuccess) {
            return "Health Connect settings khule dilam — ekhane Jarvis-ke Steps, Heart rate, Sleep, Exercise allow koro. Tarpor 'health_status' bolo."
        }
        val store = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { ctx.startActivity(store) }.isSuccess) {
            return "Health Connect app install kora nei — Play Store khule dilam, install kore 'health_permissions' bolo."
        }
        return "Health Connect settings khulte parlam na — Play Store theke 'Health Connect by Android' install koro."
    }
}
