package com.jarvis.ai.core

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.jarvis.ai.service.LockHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.3.5 Turbo — instant local answers, no network, no waiting.
 * Returns null when the question needs the AI (then Gemini handles it).
 * Only answers things that are unambiguous from the words alone.
 */
object QuickAnswer {
    fun tryAnswer(ctx: Context, raw: String): String? {
        val q = raw.trim().lowercase(Locale.ENGLISH)
        val bn = raw.trim()
        // time
        if (q.contains("what time") || q.contains("time kot") || q.contains("ekhon time") ||
            q.contains("koyta baje") || q.contains("kotota baje") || bn.contains("সময় কত") || bn.contains("কয়টা বাজে")) {
            return "Ekhon time: " + SimpleDateFormat("h:mm a", Locale.ENGLISH).format(Date())
        }
        // date / day
        if ((q.contains("today") && q.contains("date")) || (q.contains("ajke") && q.contains("date")) ||
            bn.contains("আজকের তারিখ") || q.contains("what day") || bn.contains("আজ কি বার") || bn.contains("আজকে কি বার")) {
            return "Ajke: " + SimpleDateFormat("EEEE, d MMMM yyyy", Locale.ENGLISH).format(Date())
        }
        // battery
        if (q.contains("battery") || bn.contains("ব্যাটারি")) {
            val bm = ctx.getSystemService(BatteryManager::class.java)
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val charging = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_CHARGING
            return "Battery $level%${if (charging) " (charging)" else ""}."
        }
        // screen lock — instant action
        if (q.contains("screen lock") || q.contains("lock koro") || q.contains("lock the screen") || bn.contains("স্ক্রিন লক")) {
            return if (LockHelper.lockNow(ctx)) "Screen locked. 🔒"
            else "Screen lock korte Device Admin lagbe — Settings-e Enable Device Admin koro."
        }
        // who are you
        if (q == "tumi ke" || q == "who are you" || q.contains("tomar nam ki") || q.contains("your name") || bn.contains("তুমি কে")) {
            return "Ami Jarvis — tomar AI assistant. Kotha bolo, ami shunbo. 😊"
        }
        return null
    }
}
