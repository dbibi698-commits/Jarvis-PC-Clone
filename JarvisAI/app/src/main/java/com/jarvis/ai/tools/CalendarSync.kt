package com.jarvis.ai.tools

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * v1.4.0: smart calendar sync — the phone's own calendar (Google Calendar /
 * any calendar app that syncs to it). Reads events, adds events, and warns
 * about clashes before booking. Needs Calendar permission (asked on launch,
 * also in Settings > Permissions).
 */
object CalendarSync {

    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.ENGLISH)
    private val dateTimeFmt = SimpleDateFormat("dd MMM, HH:mm", Locale.ENGLISH)

    private fun hasPerm(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private fun noPermMsg() = "Calendar permission dao ni — app khullei chabe, ba Settings > Permissions-e giye Calendar Allow koro, tarpor abar bolo."

    /** Parses "today" / "tomorrow" / "yyyy-MM-dd" / "this week" into a [start, end) range. */
    private fun rangeFor(whenText: String): Pair<Long, Long>? {
        val s = whenText.trim().lowercase()
        val cal = Calendar.getInstance()
        return when {
            s.isBlank() || s == "today" || s == "aj" || s == "aaj" -> {
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                val start = cal.timeInMillis
                cal.add(Calendar.DAY_OF_YEAR, 1)
                start to cal.timeInMillis
            }
            s == "tomorrow" || s == "kal" -> {
                cal.add(Calendar.DAY_OF_YEAR, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                val start = cal.timeInMillis
                cal.add(Calendar.DAY_OF_YEAR, 1)
                start to cal.timeInMillis
            }
            s == "this week" || s == "week" -> {
                val start = System.currentTimeMillis()
                start to (start + 7L * 24 * 3600_000L)
            }
            else -> runCatching {
                val d = dayFmt.parse(s) ?: return null
                val c2 = Calendar.getInstance().apply { time = d; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
                val start = c2.timeInMillis
                c2.add(Calendar.DAY_OF_YEAR, 1)
                start to c2.timeInMillis
            }.getOrNull()
        }
    }

    fun listEvents(ctx: Context, whenText: String): String {
        if (!hasPerm(ctx)) return noPermMsg()
        val range = rangeFor(whenText) ?: return "\"$whenText\" bujhlam na — 'today', 'tomorrow', 'this week' ba '2026-11-16' bolo."
        val (start, end) = range
        val events = queryEvents(ctx, start, end)
        if (events.isEmpty()) {
            val label = whenText.trim().ifBlank { "today" }
            return "📅 $label-e kono appointment nei — ekdom free acho!"
        }
        val sb = StringBuilder("📅 Appointments (${events.size}):\n")
        for (e in events) {
            sb.append("• ${dateTimeFmt.format(Date(e.start))} – ${timeFmt.format(Date(e.end))}: ${e.title}")
            if (e.location.isNotBlank()) sb.append(" @ ${e.location}")
            sb.append("\n")
        }
        return sb.toString().trimEnd()
    }

    fun addEvent(ctx: Context, title: String, startText: String, durationMin: Int, description: String, location: String): String {
        if (!hasPerm(ctx)) return noPermMsg()
        if (title.isBlank()) return "Kiser appointment? Title-ta bolo — jemon: \"Doctor appointment\"."
        val start = Scheduler.parseTime(startText)
            ?: return "\"$startText\" bujhlam na — jemon: 'in 30 minutes', '18:30', 'tomorrow 9:00' bolo."
        val dur = if (durationMin > 0) durationMin else 60
        val end = start + dur * 60_000L
        val clashes = queryEvents(ctx, start, end)
        val calId = findCalendarId(ctx) ?: return "Calendar khuje pelam na — phone-e ekta calendar account (Google) add koro, tarpor abar bolo."
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.DESCRIPTION, description)
            put(CalendarContract.Events.EVENT_LOCATION, location)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            put(CalendarContract.Events.HAS_ALARM, 1)
        }
        val uri = runCatching { ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) }.getOrNull()
            ?: return "Calendar-e add korte parlam na — abar try koro."
        val clashNote = if (clashes.isNotEmpty()) {
            "\n⚠️ Clash! Oi somoy age thekei ache: " + clashes.joinToString(", ") { "${dateTimeFmt.format(Date(it.start))} ${it.title}" }
        } else ""
        return "✅ Calendar-e add kore dilam: \"$title\" — ${dateTimeFmt.format(Date(start))} (${dur} min)${if (location.isNotBlank()) " @ $location" else ""}$clashNote"
    }

    private data class Ev(val start: Long, val end: Long, val title: String, val location: String)

    private fun queryEvents(ctx: Context, start: Long, end: Long): List<Ev> = runCatching {
        val out = mutableListOf<Ev>()
        val uri = CalendarContract.Events.CONTENT_URI
        val proj = arrayOf(
            CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND,
            CalendarContract.Events.TITLE, CalendarContract.Events.EVENT_LOCATION
        )
        val sel = "${CalendarContract.Events.DTSTART} < ? AND ${CalendarContract.Events.DTEND} > ? AND ${CalendarContract.Events.DELETED} = 0"
        ctx.contentResolver.query(uri, proj, sel, arrayOf(end.toString(), start.toString()), "${CalendarContract.Events.DTSTART} ASC")?.use { c ->
            while (c.moveToNext() && out.size < 30) {
                out += Ev(
                    c.getLong(0), c.getLong(1),
                    c.getString(2) ?: "(no title)",
                    c.getString(3) ?: ""
                )
            }
        }
        out
    }.getOrDefault(emptyList())

    private fun findCalendarId(ctx: Context): Long? = runCatching {
        val uri = CalendarContract.Calendars.CONTENT_URI
        val proj = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.IS_PRIMARY
        )
        var fallback: Long? = null
        ctx.contentResolver.query(uri, proj, "${CalendarContract.Calendars.VISIBLE} = 1", null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val type = c.getString(1) ?: ""
                val level = c.getInt(2)
                val primary = c.getInt(3) == 1
                if (level < 500) continue
                if (fallback == null) fallback = id
                if (primary || type == "com.google") return id
            }
        }
        fallback
    }.getOrNull()
}
