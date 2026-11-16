package com.jarvis.ai.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jarvis.ai.tools.Scheduler

/**
 * v1.3.9 — Fires scheduled reminders and routines (AlarmManager), re-arms all
 * alarms after a reboot, and fires "charging" routines when the charger is
 * plugged in. Registered in AndroidManifest.
 */
class ScheduleReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_REMINDER = "jarvis.REMINDER"
        const val EXTRA_ID = "sched_id"
    }

    override fun onReceive(ctx: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED -> Scheduler.rearmAll(ctx)
            Intent.ACTION_POWER_CONNECTED -> Scheduler.fireChargingRoutines(ctx)
            ACTION_REMINDER -> {
                val id = intent.getStringExtra(EXTRA_ID) ?: return
                Scheduler.fire(ctx, id)
            }
        }
    }
}
