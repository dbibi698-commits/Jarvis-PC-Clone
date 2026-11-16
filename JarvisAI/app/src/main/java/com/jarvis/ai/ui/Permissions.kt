package com.jarvis.ai.ui

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jarvis.ai.service.JarvisAccessibilityService

/** Every permission Jarvis needs, with one-tap shortcuts to the right system screen. */
object Permissions {
    val runtime: Array<String> get() = buildList {
        add(Manifest.permission.RECORD_AUDIO); add(Manifest.permission.CAMERA)
        add(Manifest.permission.CALL_PHONE); add(Manifest.permission.READ_PHONE_STATE); add(Manifest.permission.ANSWER_PHONE_CALLS)
        add(Manifest.permission.READ_CALL_LOG); add(Manifest.permission.READ_CONTACTS); add(Manifest.permission.WRITE_CONTACTS)
        add(Manifest.permission.SEND_SMS); add(Manifest.permission.READ_SMS); add(Manifest.permission.RECEIVE_SMS)
        add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.READ_CALENDAR); add(Manifest.permission.WRITE_CALENDAR)
        if (Build.VERSION.SDK_INT >= 33) {
            add(Manifest.permission.READ_MEDIA_IMAGES); add(Manifest.permission.READ_MEDIA_VIDEO); add(Manifest.permission.READ_MEDIA_AUDIO)
            add(Manifest.permission.POST_NOTIFICATIONS)
        } else add(Manifest.permission.READ_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 26) add(Manifest.permission.READ_PHONE_NUMBERS)
        if (Build.VERSION.SDK_INT >= 29) add(Manifest.permission.ACTIVITY_RECOGNITION)
        add(Manifest.permission.BODY_SENSORS)
    }.toTypedArray()

    fun missingRuntime(ctx: Context) = runtime.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }

    fun requestRuntime(a: Activity) {
        val m = missingRuntime(a)
        if (m.isNotEmpty()) ActivityCompat.requestPermissions(a, m.toTypedArray(), 42)
    }

    data class Special(val title: String, val granted: (Context) -> Boolean, val open: (Activity) -> Unit)

    val specials = listOf(
        Special("Accessibility — full phone control (tap, scroll, type, see screen)", { JarvisAccessibilityService.isOn }) {
            it.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        },
        Special("Notification access — read & reply WhatsApp/Messenger in real time", { c ->
            NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName)
        }) { it.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
        Special("Display over other apps — floating Jarvis orb", { Settings.canDrawOverlays(it) }) {
            it.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + it.packageName)))
        },
        Special("Default Digital Assistant — long-press Home opens Jarvis", { c ->
            val cur = Settings.Secure.getString(c.contentResolver, "voice_interaction_service") ?: ""
            cur.startsWith(c.packageName)
        }) {
            runCatching { it.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
                .onFailure { _ -> it.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
        },
        Special("Ignore battery optimization — keep \"Hey Jarvis\" always on", { c ->
            c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)
        }) {
            it.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + it.packageName)))
        },
        Special("Modify system settings — brightness etc.", { Settings.System.canWrite(it) }) {
            it.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + it.packageName)))
        },
        Special("All files access — save/read any file", { if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true }) {
            if (Build.VERSION.SDK_INT >= 30) it.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + it.packageName)))
        },
        Special("App permissions — mic, camera, phone, SMS, contacts, gallery…", { missingRuntime(it).isEmpty() }) {
            if (missingRuntime(it).isNotEmpty()) requestRuntime(it)
            else it.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + it.packageName)))
        },
    )
}
