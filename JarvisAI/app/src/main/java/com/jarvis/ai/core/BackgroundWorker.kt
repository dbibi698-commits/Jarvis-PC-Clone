package com.jarvis.ai.core

import android.content.Context
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.jarvis.ai.App

/**
 * v1.3.9 — Seamless background processing without screen visibility.
 * Runs a job on a background thread while holding a partial wake lock, so the
 * CPU stays awake even when the screen is off (Doze can't kill it mid-job).
 * Used for sync uploads/downloads and other long background work.
 */
object BackgroundWorker {
    fun run(ctx: Context, label: String, work: () -> String, onDone: (String) -> Unit) {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:background:$label").apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 1000L)
        }
        Thread {
            val result = runCatching { work() }.getOrElse { "Failed: ${it.message}" }
            runCatching { if (lock.isHeld) lock.release() }
            android.os.Handler(android.os.Looper.getMainLooper()).post { onDone(result) }
        }.start()
    }

    /** A long-lived partial wake lock for the foreground service's background mode. */
    fun holdPartial(ctx: Context, tag: String): PowerManager.WakeLock? = runCatching {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:$tag").apply {
            setReferenceCounted(false)
            acquire(30 * 60 * 1000L)
        }
    }.getOrNull()

    fun notifyDone(ctx: Context, title: String, text: String) {
        runCatching {
            val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
            val n = NotificationCompat.Builder(ctx, App.CH_ALERT)
                .setContentTitle(title).setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setAutoCancel(true).build()
            nm.notify((System.currentTimeMillis() % 100000).toInt(), n)
        }
    }
}
