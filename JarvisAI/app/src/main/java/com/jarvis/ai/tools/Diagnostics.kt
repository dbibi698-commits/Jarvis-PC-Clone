package com.jarvis.ai.tools

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import com.jarvis.ai.App
import java.io.File

/**
 * v1.3.9 — Deep device diagnostics WITHOUT root. Battery, storage, memory,
 * CPU, Android version, security patch, network, uptime, installed apps and
 * the last crash. Honest by design: it reports what Android allows a normal
 * app to see — it cannot read kernel logs or fix hardware, and it never roots
 * the phone (rooting voids warranty and can brick the phone).
 */
object Diagnostics {
    fun report(ctx: Context): String {
        val sb = StringBuilder("🔍 Device diagnostics (no root):\n")
        sb.appendLine("- Phone: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("- Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), security patch: ${Build.VERSION.SECURITY_PATCH}")
        // battery
        val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (level >= 0) level * 100 / scale else -1
        val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)?.div(10f) ?: 0f
        val volt = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val health = when (intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "OVERHEAT"
            BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over-voltage"
            BatteryManager.BATTERY_HEALTH_COLD -> "cold"
            else -> "unknown"
        }
        val status = when (intent?.getIntExtra(BatteryManager.EXTRA_STATUS, 0)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            else -> "unknown"
        }
        sb.appendLine("- Battery: $pct% ($status), health: $health, temp: ${temp}°C, voltage: ${volt}mV")
        // storage
        val sf = StatFs(Environment.getDataDirectory().path)
        sb.appendLine("- Storage: ${sf.availableBytes / (1024 * 1024)}MB free / ${sf.totalBytes / (1024 * 1024)}MB total")
        // memory
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        sb.appendLine("- Memory: ${mi.availMem / (1024 * 1024)}MB free / ${mi.totalMem / (1024 * 1024)}MB total${if (mi.lowMemory) " ⚠️ LOW" else ""}")
        sb.appendLine("- CPU cores: ${Runtime.getRuntime().availableProcessors()}")
        val upHours = SystemClock.elapsedRealtime() / 3600000
        sb.appendLine("- Uptime: ${upHours}h since last restart")
        // network
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork
        val caps = net?.let { cm.getNetworkCapabilities(it) }
        val netType = when {
            caps == null -> "offline"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            else -> "other"
        }
        sb.appendLine("- Network: $netType")
        // apps
        val apps = runCatching { ctx.packageManager.getInstalledApplications(0).size }.getOrDefault(-1)
        sb.appendLine("- Installed apps: $apps")
        // last crash
        val crash = File(App.instance.filesDir, "crash-last.txt")
        sb.appendLine("- Last Jarvis crash: " + if (crash.exists()) java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.ENGLISH).format(java.util.Date(crash.lastModified())) else "none recorded")
        sb.appendLine("\nKono problem thakle 'troubleshoot' bolo — Jarvis nije fix korar chesta korbe.")
        return sb.toString()
    }
}
