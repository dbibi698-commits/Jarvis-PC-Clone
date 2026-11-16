package com.jarvis.ai.tools

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * v1.4.0: "kaj faki dhora" — focus report for the USER'S OWN phone.
 * UsageStats (which app was in the foreground, when) during work hours,
 * with distracting apps (video / social / games) called out by name.
 * Needs Usage Access permission (Settings > Apps > Special app access >
 * Usage access > Jarvis AI). It can only see THIS phone's usage — it cannot
 * spy on anyone else's phone.
 */
object FocusWatch {

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.ENGLISH)

    private val DISTRACTING_PKGS = setOf(
        "com.google.android.youtube", "com.instagram.android", "com.facebook.katana",
        "com.facebook.lite", "com.zhiliaoapp.musically", "com.twitter.android",
        "com.whatsapp", "org.telegram.messenger", "com.snapchat.android",
        "com.reddit.frontpage", "com.spotify.music", "com.netflix.mediaclient",
        "com.google.android.apps.youtube.music"
    )

    private fun isDistracting(pkg: String, label: String): Boolean {
        val p = pkg.lowercase(); val l = label.lowercase()
        if (p in DISTRACTING_PKGS) return true
        if (p.contains("game") || l.contains("game")) return true
        val gameHints = listOf("pubg", "freefire", "ludo", "candy crush", "subway surfer",
            "call of duty", "clash", "poker", "casino", "betting", "teen patti", "rummy")
        return gameHints.any { p.contains(it.replace(" ", "")) || l.contains(it) }
    }

    fun hasPermission(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java) ?: return false
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }

    fun openUsageSettings(ctx: Context) {
        runCatching {
            ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun parseClock(s: String, cal: Calendar): Long? {
        val parts = s.trim().split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
        if (h !in 0..23 || m !in 0..59) return null
        return cal.apply { set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    }

    fun report(ctx: Context, dateArg: String, workStart: String, workEnd: String, cb: (String) -> Unit) {
        Thread {
            val msg = runCatching { doReport(ctx, dateArg, workStart, workEnd) }.getOrElse { "Focus report banate parlam na: ${it.message}" }
            cb(msg)
        }.start()
    }

    private fun doReport(ctx: Context, dateArg: String, workStart: String, workEnd: String): String {
        if (!hasPermission(ctx)) {
            openUsageSettings(ctx)
            return "Usage access ON nei — Settings khule dilam: Apps > Special app access > Usage access > Jarvis AI → Allow koro, tarpor abar 'focus report' bolo. Eta chara ami kon app kotokhon khola chilo dekhte pari na."
        }
        val cal = Calendar.getInstance()
        if (dateArg.lowercase().contains("yesterday") || dateArg.lowercase().contains("kal")) {
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val dayStart = cal.timeInMillis
        val ws = parseClock(workStart, Calendar.getInstance().apply { timeInMillis = dayStart }) ?: dayStart + 10 * 3600_000L
        val we = parseClock(workEnd, Calendar.getInstance().apply { timeInMillis = dayStart }) ?: dayStart + 18 * 3600_000L
        val now = System.currentTimeMillis()
        val end = minOf(we, now)
        if (end <= ws) return "Kaj-er somoy ekhono shuru hoy ni (${timeFmt.format(Date(ws))} theke ${timeFmt.format(Date(we))})."

        val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return "UsageStats pelam na."
        val events = usm.queryEvents(ws, end)
        val fgStart = mutableMapOf<String, Long>()
        val totals = mutableMapOf<String, Long>()
        val ev = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            when (ev.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> fgStart[ev.packageName] = ev.timeStamp
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    val s = fgStart.remove(ev.packageName)
                    if (s != null && ev.timeStamp > s) {
                        totals[ev.packageName] = (totals[ev.packageName] ?: 0L) + (ev.timeStamp - s)
                    }
                }
            }
        }
        for ((pkg, s) in fgStart) totals[pkg] = (totals[pkg] ?: 0L) + (end - s)

        val pm = ctx.packageManager
        fun label(pkg: String): String = runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull() ?: pkg

        val totalMin = totals.values.sum() / 60000
        val sorted = totals.entries.sortedByDescending { it.value }
        val distracting = sorted.filter { isDistracting(it.key, label(it.key)) }
        val dMin = distracting.sumOf { it.value } / 60000

        fun fmt(mins: Long): String = if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "${mins}m"

        val sb = StringBuilder()
        sb.append("📊 Focus report — ${SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).format(Date(dayStart))} (${timeFmt.format(Date(ws))}–${timeFmt.format(Date(we))})\n")
        sb.append("📱 Kaj-er somoye total screen time: ${fmt(totalMin)}\n")
        if (sorted.isEmpty()) {
            sb.append("Kono app usage pelam na — hoyto phone-ta kaj-er somoye khola hoy ni. 👏\n")
        } else {
            sb.append("\nTop apps:\n")
            sorted.take(5).forEach { sb.append("• ${label(it.key)} — ${fmt(it.value / 60000)}\n") }
        }
        if (dMin > 0) {
            sb.append("\n⚠️ Kaj faki: ${fmt(dMin)} — ")
            sb.append(distracting.take(4).joinToString(", ") { "${label(it.key)} ${fmt(it.value / 60000)}" })
            sb.append("\n")
            sb.append(
                when {
                    dMin >= 120 -> "2 ghontar beshi faki! 😤 Aaj ar na — phone-ta side-e rakho, kaj sesh kore tarpor YouTube."
                    dMin >= 60 -> "1 ghontar beshi chole geche. Ektu focus koro — 25 min kaj, 5 min break (Pomodoro) try koro."
                    else -> "Olpo faki — thik ache, kintu nojor rakho."
                }
            )
        } else {
            sb.append("\n✅ Kaj-er somoye kono faki dhora pore ni — bhalo focus! 🎉")
        }
        return sb.toString()
    }
}
