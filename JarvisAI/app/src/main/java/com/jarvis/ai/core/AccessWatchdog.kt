package com.jarvis.ai.core

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.jarvis.ai.App
import com.jarvis.ai.service.JarvisAccessibilityService

/**
 * v1.4.0: Accessibility watchdog.
 *
 * Why it exists: Android (especially vivo / Xiaomi / Oppo) turns Jarvis's
 * Accessibility OFF after an app update, a battery kill, or a restart — and
 * Android FORBIDS any app from turning its own Accessibility back on. So
 * Jarvis cannot force it on; it can only watch and tell the user fast, with
 * one tap to the Accessibility settings. The notification fires once per
 * off-episode (no spam) and resets when Accessibility is back on.
 *
 * The user also needs this ONCE: App info -> top-right ⋮ -> "Allow restricted
 * settings" — without it Android keeps blocking Accessibility for sideloaded
 * apps after every update.
 */
object AccessWatchdog {

    private const val NOTIF_ID = 9401
    @Volatile private var notified = false

    fun reset() {
        notified = false
    }

    fun openAccessibilitySettings(ctx: Context) {
        runCatching {
            ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    fun checkAndNotify(ctx: Context) {
        if (JarvisAccessibilityService.isOn) {
            notified = false
            return
        }
        if (notified) return
        notified = true
        runCatching {
            val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
            val openIntent = PendingIntent.getActivity(
                ctx, 0,
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = NotificationCompat.Builder(ctx, App.CH_ALERT)
                .setContentTitle("⚠️ Jarvis Accessibility OFF hoye geche")
                .setContentText("Screen dekha / tap kora bondho. Tap kore abar ON koro — na hole Jarvis phone control korte parbe na.")
                .setStyle(NotificationCompat.BigTextStyle().bigText("Accessibility bondho hoye geche — Jarvis screen dekhte ba tap korte parbe na. Tap kore Settings kholo, 'Jarvis AI' ON koro. Update-er por ba battery kill-er por eta hoy; ekbar App info → ⋮ → 'Allow restricted settings' ON korle ar bondho hobe na."))
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentIntent(openIntent)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIF_ID, n)
        }
    }
}
