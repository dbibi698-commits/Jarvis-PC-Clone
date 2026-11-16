package com.jarvis.ai.core

import android.content.Context
import android.os.BatteryManager
import org.json.JSONObject
import java.util.Calendar

/**
 * v1.3.9 — Adaptive learning + predictive engine (all on-device, private).
 * Every tool call and app open is counted (with the hour it happened). From
 * that Jarvis learns the user's habits and predicts what they will want next:
 * "Tumi ei somoy usually YouTube kholo — khule dibo?" The suggestions row in
 * MainActivity and the predict_next tool read this.
 */
object AdaptiveLearning {
    private fun counts(): JSONObject = runCatching { JSONObject(Prefs.toolUsageJson) }.getOrDefault(JSONObject())

    fun recordTool(name: String) {
        if (name.isBlank()) return
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val j = counts()
        j.put(name, j.optInt(name, 0) + 1)
        val hk = "$name@$hour"
        j.put(hk, j.optInt(hk, 0) + 1)
        Prefs.toolUsageJson = j.toString()
    }

    fun recordApp(pkg: String) = recordTool("app:$pkg")

    fun topTools(n: Int = 3): List<String> {
        val j = counts()
        return j.keys().asSequence()
            .filter { !it.contains("@") && !it.startsWith("app:") }
            .sortedByDescending { j.optInt(it, 0) }
            .take(n).toList()
    }

    /** What the user most often does at THIS hour (needs 3+ occurrences). */
    fun predictNext(): List<Pair<String, Int>> {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val j = counts()
        return j.keys().asSequence()
            .filter { it.endsWith("@$hour") }
            .map { it.removeSuffix("@$hour") to j.optInt(it, 0) }
            .filter { it.second >= 3 }
            .sortedByDescending { it.second }
            .take(3).toList()
    }

    fun toolToCommand(tool: String): String = when {
        tool == "open_app" -> "WhatsApp kholo"
        tool == "battery_status" -> "Battery koto?"
        tool == "record_screen" -> "Screen record koro"
        tool == "control_device" -> "Light on koro"
        tool == "translate" -> "Translate koro"
        tool == "google_search" -> "Google-e news search koro"
        tool == "make_video" -> "Video banao"
        tool.startsWith("app:") -> "${tool.removePrefix("app:")} kholo"
        else -> tool
    }

    /** Smart suggestions for the chips row: time-of-day + habits + battery. */
    fun suggestions(ctx: Context): List<String> {
        val out = mutableListOf<String>()
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        // predicted next action from behaviour patterns
        predictNext().forEach { (tool, _) -> out += toolToCommand(tool) }
        // time of day
        when (hour) {
            in 5..10 -> { out += "Aajker khobor dao"; out += "Aajker weather kemon?" }
            in 11..16 -> { out += "Battery koto?"; out += "YouTube-e gaan chalao" }
            in 17..21 -> { out += "Aajker din ta kemon gelo?"; out += "Screen record koro" }
            else -> { out += "Kal sokal 7tay alarm dao"; out += "Screen lock koro" }
        }
        // battery low -> suggest it
        runCatching {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (level in 1..20) out.add(0, "Battery $level% — battery status dao")
        }
        // top tools the user actually uses
        topTools(2).forEach { out += toolToCommand(it) }
        return out.distinct().take(5)
    }

    /** Dynamic personality adaptation: classify the user's interaction style. */
    fun recordMessageStyle(text: String) {
        val low = text.lowercase()
        val style = when {
            low.contains("please") || low.contains("kindly") || low.contains("doya kore") || low.contains("দয়া করে") || low.contains("apni") || low.contains("আপনি") -> "formal"
            low.contains("haha") || low.contains("lol") || low.contains("😂") || low.contains("moja") || low.contains("মজা") || low.contains("darun") || low.contains("দারুণ") -> "playful"
            text.trim().length <= 25 -> "short"
            text.trim().length >= 200 -> "detailed"
            else -> "casual"
        }
        val j = runCatching { JSONObject(Prefs.styleJson) }.getOrDefault(JSONObject())
        j.put(style, j.optInt(style, 0) + 1)
        Prefs.styleJson = j.toString()
    }

    fun dominantStyle(): String {
        val j = runCatching { JSONObject(Prefs.styleJson) }.getOrDefault(JSONObject())
        var best = "casual"; var bestN = 0
        listOf("short", "detailed", "playful", "formal", "casual").forEach {
            val n = j.optInt(it, 0)
            if (n > bestN) { bestN = n; best = it }
        }
        return best
    }

    fun styleGuidance(): String = when (dominantStyle()) {
        "short" -> "The user likes SHORT answers — keep replies to 1-2 sentences unless they ask for detail."
        "detailed" -> "The user likes DETAILED answers — explain thoroughly with steps."
        "playful" -> "The user is playful — be warm, witty and fun, match their energy."
        "formal" -> "The user is formal — be polite, respectful and structured."
        else -> "Match the user's tone — casual and warm."
    }
    fun proactiveHint(): String? {
        val p = predictNext().firstOrNull() ?: return null
        val cmd = toolToCommand(p.first)
        return "✨ Tumi ei somoy usually \"$cmd\" koro — bole dilei kore dibo."
    }
}
