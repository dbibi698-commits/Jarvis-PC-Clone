package com.jarvis.ai.tools

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

/**
 * v1.4.0: automated daily summary — weather (Open-Meteo, free, no key),
 * today's calendar appointments, top news headlines (Google News RSS) and
 * today's reminders, in one short briefing. Saved to workspace too.
 * Weather needs internet; IP-based location is used (no GPS dependency).
 * Routine action "briefing" speaks/sends it every day at the set time.
 */
object DailyBriefing {

    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.ENGLISH)

    fun briefing(ctx: Context, cb: (String) -> Unit) {
        Thread {
            val text = runCatching { build(ctx) }.getOrElse { "Briefing banate parlam na: ${it.message}" }
            runCatching {
                val f = File(ctx.getExternalFilesDir(null), "workspace/daily-briefing-${dayFmt.format(Date())}.md")
                f.parentFile?.mkdirs()
                f.writeText("# Daily Briefing — ${dayFmt.format(Date())} ${timeFmt.format(Date())}\n\n$text")
            }
            cb(text)
        }.start()
    }

    private fun build(ctx: Context): String {
        val sb = StringBuilder("☀️ **Daily Briefing** — ${dayFmt.format(Date())}\n")
        sb.append("\n🌤️ Weather: ${weather()}")
        sb.append("\n\n📅 ${CalendarSync.listEvents(ctx, "today")}")
        val schedules = runCatching { Scheduler.listAll() }.getOrNull()?.take(600)
        if (!schedules.isNullOrBlank()) sb.append("\n\n⏰ Reminders & routines:\n$schedules")
        sb.append("\n\n📰 Top news:\n${news()}")
        return sb.toString()
    }

    private fun weather(): String = runCatching {
        val locJson = get("http://ip-api.com/json/") ?: return "ante parlam na (internet check koro)"
        val loc = JSONObject(locJson)
        val lat = loc.optDouble("lat", 0.0)
        val lon = loc.optDouble("lon", 0.0)
        val city = loc.optString("city", "tomar elaka")
        if (lat == 0.0 && lon == 0.0) return "location pelam na"
        val w = get(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m&timezone=auto"
        ) ?: return "ante parlam na"
        val cur = JSONObject(w).getJSONObject("current")
        val temp = cur.optDouble("temperature_2m", Double.NaN)
        val hum = cur.optInt("relative_humidity_2m", -1)
        val wind = cur.optDouble("wind_speed_10m", Double.NaN)
        val desc = when (cur.optInt("weather_code", -1)) {
            0 -> "porishkar akash ☀️"; 1, 2 -> "halka megh ⛅"; 3 -> "meghla ☁️"
            45, 48 -> "kuasha 🌫️"; 51, 53, 55 -> "halka brishti 🌦️"
            61, 63, 65 -> "brishti 🌧️"; 66, 67 -> "thanda brishti 🌧️"
            71, 73, 75, 77 -> "tusharpat ❄️"; 80, 81, 82 -> "jhor brishti ⛈️"
            85, 86 -> "tushar jhor ❄️"; 95 -> "bojropat ⛈️"; 96, 99 -> "shila brishti ⛈️"
            else -> "—"
        }
        "$city: ${if (temp.isNaN()) "?" else "%.1f".format(temp)}°C, $desc" +
            (if (hum >= 0) ", humidity $hum%" else "") +
            (if (!wind.isNaN()) ", hawa %.0f km/h".format(wind) else "")
    }.getOrElse { "ante parlam na (${it.message})" }

    private fun news(): String = runCatching {
        val rss = get("https://news.google.com/rss?hl=en-IN&gl=IN&ceid=IN:en") ?: return "ante parlam na"
        val titles = Regex("<item>.*?<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
            .findAll(rss).map { it.groupValues[1] }.take(5).toList()
        if (titles.isEmpty()) return "ante parlam na"
        titles.mapIndexed { i, t -> "${i + 1}. ${unescape(t)}" }.joinToString("\n")
    }.getOrElse { "ante parlam na" }

    private fun unescape(s: String): String = s
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace(Regex("<!\\[CDATA\\[(.*?)\\]\\]>")) { it.groupValues[1] }

    private fun get(url: String): String? = runCatching {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000; readTimeout = 10000
            setRequestProperty("User-Agent", "JarvisAI/1.4.0")
        }
        if (c.responseCode !in 200..299) return null
        c.inputStream.readBytes().toString(Charsets.UTF_8)
    }.getOrNull()
}
