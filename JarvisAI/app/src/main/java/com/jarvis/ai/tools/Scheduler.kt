package com.jarvis.ai.tools

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.ContactsContract
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import com.jarvis.ai.App
import com.jarvis.ai.core.Prefs
import com.jarvis.ai.service.LockHelper
import com.jarvis.ai.service.ScheduleReceiver
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * v1.3.9 — Smart scheduling + context-aware task automation.
 * - schedule_task: one-time or daily reminders ("remind me at 6pm to call mom").
 * - create_routine: automation rules — trigger "daily HH:MM" or "charging",
 *   action "speak:<text>" | "device:<entity>:on|off" | "lock".
 * Stored as JSON in Prefs (survives restarts); alarms are re-armed after reboot
 * by ScheduleReceiver. All on-device — no server.
 */
object Scheduler {
    private fun load(): JSONArray = runCatching { JSONArray(Prefs.schedulesJson) }.getOrDefault(JSONArray())
    private fun save(a: JSONArray) { Prefs.schedulesJson = a.toString() }

    private fun alarmIntent(ctx: Context, id: String): PendingIntent {
        val i = Intent(ctx, ScheduleReceiver::class.java)
            .setAction(ScheduleReceiver.ACTION_REMINDER)
            .putExtra(ScheduleReceiver.EXTRA_ID, id)
        return PendingIntent.getBroadcast(ctx, id.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun setAlarm(ctx: Context, id: String, atMillis: Long) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, alarmIntent(ctx, id)) }
            .onFailure { runCatching { am.set(AlarmManager.RTC_WAKEUP, atMillis, alarmIntent(ctx, id)) } }
    }

    private fun cancelAlarm(ctx: Context, id: String) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching { am.cancel(alarmIntent(ctx, id)) }
    }

    /** Parses "in 30 minutes", "in 2 hours", "18:30", "6:30pm", "tomorrow 9:00", "9am". */
    fun parseTime(input: String): Long? {
        val s = input.trim().lowercase()
        val now = Calendar.getInstance()
        val relMin = Regex("in\\s+(\\d+)\\s*(min|mins|minute|minutes)").find(s)
        if (relMin != null) {
            return now.timeInMillis + relMin.groupValues[1].toLong() * 60_000L
        }
        val relHr = Regex("in\\s+(\\d+)\\s*(hour|hours|hr|hrs)").find(s)
        if (relHr != null) {
            return now.timeInMillis + relHr.groupValues[1].toLong() * 3600_000L
        }
        val tomorrow = s.contains("tomorrow") || s.contains("kal")
        val m = Regex("(\\d{1,2})\\s*[:.]?\\s*(\\d{2})?\\s*(am|pm)?").find(s) ?: return null
        var hour = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: 0
        val ampm = m.groupValues[3]
        if (ampm == "pm" && hour < 12) hour += 12
        if (ampm == "am" && hour == 12) hour = 0
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, min)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (tomorrow) cal.add(Calendar.DAY_OF_YEAR, 1)
        else if (cal.timeInMillis <= now.timeInMillis) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    fun scheduleReminder(ctx: Context, text: String, timeInput: String, daily: Boolean = false): String {
        val at = parseTime(timeInput) ?: return "Time-ta bujhlam na — 'in 30 minutes', '18:30' ba 'tomorrow 9:00' erokom bolo."
        val id = "rem-${System.currentTimeMillis()}"
        val a = load()
        a.put(JSONObject().put("id", id).put("kind", "reminder").put("text", text)
            .put("time", at).put("repeat", if (daily) "daily" else "once"))
        save(a)
        setAlarm(ctx, id, at)
        val whenStr = java.text.SimpleDateFormat("EEEE, dd MMM h:mm a", java.util.Locale.ENGLISH).format(java.util.Date(at))
        return "⏰ Reminder set: \"$text\" — $whenStr${if (daily) " (prottek din)" else ""}."
    }

    /**
     * v1.4.0: Automatic messaging system — nirdishto somoye SMS nije theke
     * chole jabe (daily ba ekbar). SMS-ei automatic hoy — WhatsApp-er kono
     * official API nei, tai WhatsApp-e scheduled message sombhob noy.
     * Confirm kore nio: recipient + message thik ache kina, karon SMS
     * asol manush-er kache jabe, ar OTP kokhono pathano hobe na.
     */
    fun scheduleMessage(ctx: Context, to: String, text: String, timeInput: String, daily: Boolean = false): String {
        val at = parseTime(timeInput) ?: return "Time-ta bujhlam na — 'in 30 minutes', '18:30' ba 'tomorrow 9:00' erokom bolo."
        val id = "msg-${System.currentTimeMillis()}"
        val a = load()
        a.put(JSONObject().put("id", id).put("kind", "message").put("to", to).put("text", text)
            .put("time", at).put("repeat", if (daily) "daily" else "once"))
        save(a)
        setAlarm(ctx, id, at)
        val whenStr = java.text.SimpleDateFormat("EEEE, dd MMM h:mm a", java.util.Locale.ENGLISH).format(java.util.Date(at))
        return "📩 Automatic message set: \"$to\"-ke \"$text\" — $whenStr${if (daily) " (prottek din)" else ""}. Somoy hole SMS nije theke chole jabe."
    }

    private fun sendScheduledSms(ctx: Context, to: String, text: String) {
        runCatching {
            val number = resolveNumber(ctx, to)
            if (number == null) {
                notify(ctx, "📩 Message pathate parlam na", "'$to'-er number pelam na — contact-e ache kina dekho.")
                return
            }
            val sm = SmsManager.getDefault()
            sm.sendMultipartTextMessage(number, null, sm.divideMessage(text), null, null)
            notify(ctx, "📩 Message pathalam", "$to-ke: \"$text\"")
        }.onFailure { notify(ctx, "📩 Message pathate parlam na", "${it.message}") }
    }

    private fun resolveNumber(ctx: Context, to: String): String? {
        val digits = to.filter { it.isDigit() || it == '+' }
        if (digits.length >= 6) return digits
        return ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$to%"), null
        )?.use { c -> if (c.moveToFirst()) c.getString(1).replace(" ", "") else null }
    }

    fun createRoutine(ctx: Context, name: String, trigger: String, action: String): String {
        val t = trigger.trim().lowercase()
        val id = "rou-${System.currentTimeMillis()}"
        val a = load()
        val obj = JSONObject().put("id", id).put("kind", "routine").put("name", name)
            .put("trigger", t).put("action", action.trim())
        if (t.startsWith("daily")) {
            val at = parseTime(t.removePrefix("daily").trim()) ?: return "Trigger-ta bujhlam na — 'daily 07:00' ba 'charging' bolo."
            obj.put("time", at).put("repeat", "daily")
            a.put(obj); save(a)
            setAlarm(ctx, id, at)
            return "🤖 Routine banano holo: \"$name\" — prottek din ${java.text.SimpleDateFormat("h:mm a", java.util.Locale.ENGLISH).format(java.util.Date(at))}-e \"$action\" korbe."
        }
        if (t == "charging") {
            obj.put("repeat", "charging")
            a.put(obj); save(a)
            return "🤖 Routine banano holo: \"$name\" — charger lagalei \"$action\" korbe."
        }
        return "Trigger-ta bujhlam na — 'daily 07:00' ba 'charging' bolo."
    }

    fun listAll(): String {
        val a = load()
        if (a.length() == 0) return "Kono reminder ba routine nei."
        val sb = StringBuilder()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val whenStr = if (o.has("time")) java.text.SimpleDateFormat("dd MMM h:mm a", java.util.Locale.ENGLISH).format(java.util.Date(o.getLong("time"))) else o.optString("trigger")
            if (o.optString("kind") == "message") sb.appendLine("📩 [${o.getString("id")}] ${o.optString("to")}-ke \"${o.optString("text")}\" — $whenStr (${o.optString("repeat")})")
            else if (o.optString("kind") == "reminder") sb.appendLine("⏰ [${o.getString("id")}] \"${o.optString("text")}\" — $whenStr (${o.optString("repeat")})")
            else sb.appendLine("🤖 [${o.getString("id")}] ${o.optString("name")} — trigger: ${o.optString("trigger")}, action: ${o.optString("action")}")
        }
        return sb.toString()
    }

    fun cancel(ctx: Context, id: String): String {
        val a = load()
        for (i in 0 until a.length()) {
            if (a.getJSONObject(i).getString("id") == id) {
                a.remove(i); save(a); cancelAlarm(ctx, id)
                return "✅ Cancel kore dilam: $id"
            }
        }
        return "Oi id-ta pelam na: $id — list_schedules diye id dekho."
    }

    /** Fired by ScheduleReceiver when an alarm goes off. */
    fun fire(ctx: Context, id: String) {
        val a = load()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (o.getString("id") != id) continue
            if (o.optString("kind") == "message") {
                sendScheduledSms(ctx, o.optString("to"), o.optString("text"))
                if (o.optString("repeat") == "daily") {
                    val next = o.getLong("time") + 24 * 3600_000L
                    o.put("time", next); save(a); setAlarm(ctx, id, next)
                } else { a.remove(i); save(a) }
            } else if (o.optString("kind") == "reminder") {
                notify(ctx, "⏰ Reminder", o.optString("text"))
                if (o.optString("repeat") == "daily") {
                    val next = o.getLong("time") + 24 * 3600_000L
                    o.put("time", next); save(a); setAlarm(ctx, id, next)
                } else { a.remove(i); save(a) }
            } else {
                runRoutineAction(ctx, o.optString("action"))
                if (o.optString("repeat") == "daily") {
                    val next = o.getLong("time") + 24 * 3600_000L
                    o.put("time", next); save(a); setAlarm(ctx, id, next)
                }
            }
            return
        }
    }

    fun fireChargingRoutines(ctx: Context) {
        val a = load()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (o.optString("kind") == "routine" && o.optString("trigger") == "charging") {
                runRoutineAction(ctx, o.optString("action"))
            }
        }
    }

    fun rearmAll(ctx: Context) {
        val a = load()
        val now = System.currentTimeMillis()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (!o.has("time")) continue
            var at = o.getLong("time")
            if (o.optString("repeat") == "daily") {
                while (at <= now) at += 24 * 3600_000L
                o.put("time", at)
            } else if (at <= now) continue
            setAlarm(ctx, o.getString("id"), at)
        }
        save(a)
    }

    private fun runRoutineAction(ctx: Context, action: String) {
        Thread {
            runCatching {
                when {
                    action.startsWith("speak:") -> notify(ctx, "🤖 Jarvis", action.removePrefix("speak:").trim())
                    action == "lock" -> LockHelper.lockNow(ctx)
                    // v1.4.0: routine thekeo automatic message — action "message:<to>:<text>"
                    action.startsWith("message:") -> {
                        val rest = action.removePrefix("message:")
                        val idx = rest.indexOf(":")
                        if (idx > 0) sendScheduledSms(ctx, rest.substring(0, idx), rest.substring(idx + 1))
                    }
                    // v1.4.0: daily briefing routine — weather + calendar + news, e.g. action "briefing" at 7:00 daily
                    action == "briefing" -> DailyBriefing.briefing(ctx) { text -> notify(ctx, "☀️ Daily Briefing", text) }
                    action.startsWith("device:") -> {
                        // device:<entity>:on|off  e.g. device:light.bedroom:on
                        val parts = action.removePrefix("device:").split(":")
                        if (parts.size == 2) {
                            val entity = parts[0]
                            val domain = entity.substringBefore(".")
                            val service = if (parts[1].equals("on", true)) "turn_on" else "turn_off"
                            if (SmartHome.isConfigured()) SmartHome.callService(domain, service, entity) {}
                        }
                    }
                }
            }
        }.start()
    }

    private fun notify(ctx: Context, title: String, text: String) {
        runCatching {
            val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
            val n = NotificationCompat.Builder(ctx, App.CH_ALERT)
                .setContentTitle(title).setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setAutoCancel(true).build()
            nm.notify((System.currentTimeMillis() % 100000).toInt(), n)
        }
    }
}
