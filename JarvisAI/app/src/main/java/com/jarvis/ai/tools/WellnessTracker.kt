package com.jarvis.ai.tools

import com.jarvis.ai.core.Prefs
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * v1.4.0: wellness tracker — manual sleep + hydration logging (no watch or
 * Health Connect needed). "ami ghumate jachhi" / "ghum theke uthlam" /
 * "ek glass jol khelam" — Jarvis hiseb rakhbe. Goal: 8 ghonta ghum,
 * dine 2000ml (8 glass) jol.
 */
object WellnessTracker {

    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
    private const val WATER_GOAL_ML = 2000

    private fun load(): JSONObject {
        val raw = Prefs.wellnessJson.trim()
        if (raw.isEmpty()) return JSONObject().put("sleep", JSONArray()).put("water", JSONArray())
        return runCatching { JSONObject(raw) }.getOrDefault(JSONObject().put("sleep", JSONArray()).put("water", JSONArray()))
    }

    private fun save(o: JSONObject) {
        Prefs.wellnessJson = o.toString()
    }

    private fun today(): String = dayFmt.format(Date())

    /** Parses "23:30" / "7:00" / "11pm" into minutes since midnight. */
    private fun parseClock(s: String): Int? {
        val m = Regex("(\\d{1,2})\\s*[:.]?\\s*(\\d{2})?\\s*(am|pm)?").find(s.trim().lowercase()) ?: return null
        var h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: 0
        val ap = m.groupValues[3]
        if (ap == "pm" && h < 12) h += 12
        if (ap == "am" && h == 12) h = 0
        if (h !in 0..23 || min !in 0..59) return null
        return h * 60 + min
    }

    private fun fmtMins(mins: Int): String = "${mins / 60}h ${mins % 60}m"

    fun logSleep(bed: String, wake: String): String {
        val b = parseClock(bed) ?: return "\"$bed\" bujhlam na — jemon: '11:30pm' ba '23:30' bolo."
        var w = parseClock(wake) ?: return "\"$wake\" bujhlam na — jemon: '7:00am' ba '07:00' bolo."
        if (w <= b) w += 24 * 60
        val mins = w - b
        if (mins < 30 || mins > 20 * 60) return "Ghumer somoy-ta thik lagche na (${fmtMins(mins)}) — bed ar wake time abar bolo."
        val o = load()
        val arr = o.optJSONArray("sleep") ?: JSONArray()
        arr.put(JSONObject().put("date", today()).put("bed", bed).put("wake", wake).put("mins", mins))
        o.put("sleep", arr)
        save(o)
        val verdict = when {
            mins >= 7 * 60 -> "✅ Bhalo ghum hoyeche!"
            mins >= 6 * 60 -> "Thik ache, kintu 7-8 ghonta hole aro bhalo hoto."
            else -> "😴 Ghum kom hoyeche — aaj taratari ghumio."
        }
        return "🌙 Sleep log kore dilam: ${fmtMins(mins)} ($bed → $wake). $verdict"
    }

    fun logWater(amount: String): String {
        val s = amount.trim().lowercase()
        val ml = when {
            Regex("(\\d+)\\s*(l|litre|liter)").containsMatchIn(s) ->
                Regex("(\\d+)\\s*(l|litre|liter)").find(s)!!.groupValues[1].toInt() * 1000
            Regex("(\\d+)\\s*ml").containsMatchIn(s) ->
                Regex("(\\d+)\\s*ml").find(s)!!.groupValues[1].toInt()
            Regex("(\\d+)\\s*glass").containsMatchIn(s) ->
                Regex("(\\d+)\\s*glass").find(s)!!.groupValues[1].toInt() * 250
            Regex("^\\d+$").matches(s) -> s.toInt() * 250
            s.contains("glass") || s.contains("ek glass") -> 250
            else -> 250
        }.coerceIn(50, 2000)
        val o = load()
        val arr = o.optJSONArray("water") ?: JSONArray()
        var todayMl = 0
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            if (e.optString("date") == today()) todayMl += e.optInt("ml", 0)
        }
        arr.put(JSONObject().put("date", today()).put("ml", ml))
        o.put("water", arr)
        save(o)
        val total = todayMl + ml
        val glasses = total / 250
        return if (total >= WATER_GOAL_ML) {
            "💧 +${ml}ml — aaj total ${total}ml ($glasses glass)! 🎉 Goal complete (2000ml)!"
        } else {
            "💧 +${ml}ml — aaj total ${total}ml ($glasses/8 glass). Aro ${WATER_GOAL_ML - total}ml baki."
        }
    }

    fun status(): String {
        val o = load()
        val sb = StringBuilder("🏥 Wellness status:\n")
        val sleep = o.optJSONArray("sleep") ?: JSONArray()
        if (sleep.length() == 0) {
            sb.append("😴 Ghum: ekhono log kora hoy ni — \"ghumate jachhi\" / \"uthlam\" bolo.\n")
        } else {
            val last = sleep.getJSONObject(sleep.length() - 1)
            val recent = (0 until sleep.length()).map { sleep.getJSONObject(it).optInt("mins", 0) }.takeLast(7)
            val avg = recent.average().toInt()
            sb.append("😴 Last ghum: ${fmtMins(last.optInt("mins", 0))} (${last.optString("date")}) — 7 din-er avg: ${fmtMins(avg)} (goal 8h).\n")
        }
        var todayMl = 0
        val water = o.optJSONArray("water") ?: JSONArray()
        for (i in 0 until water.length()) {
            val e = water.getJSONObject(i)
            if (e.optString("date") == today()) todayMl += e.optInt("ml", 0)
        }
        sb.append("💧 Aaj jol: ${todayMl}ml / ${WATER_GOAL_ML}ml (${todayMl / 250}/8 glass).")
        if (todayMl < WATER_GOAL_ML) sb.append(" Aro ${WATER_GOAL_ML - todayMl}ml khao.")
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        if (hour in 6..22 && todayMl < 500) sb.append("\n💡 Ekhono kom jol khaoa hoyeche — ek glass kheye nao.")
        return sb.toString()
    }
}
