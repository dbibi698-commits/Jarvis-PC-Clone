package com.jarvis.ai.core

import com.jarvis.ai.App
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.4.1 — choto activity log: user Jarvis-ke diye ki ki korlo (tool-er nam +
 * somoy). Heartbeat-er sathe backend-e jay — sudhu jokhon Owner monitoring ON.
 * Max 20-ta entry, tar beshi hole purono gulo muche jay.
 */
object ActivityLog {
    private val file get() = File(App.instance.filesDir, "activity.json")

    fun record(toolName: String) {
        runCatching {
            val a = runCatching { JSONArray(file.readText()) }.getOrDefault(JSONArray())
            val time = SimpleDateFormat("HH:mm", Locale.ENGLISH).format(Date())
            a.put("$time — $toolName")
            while (a.length() > 20) a.remove(0)
            file.writeText(a.toString())
        }
    }

    fun recent(): List<String> = runCatching {
        val a = JSONArray(file.readText())
        List(a.length()) { a.getString(it) }
    }.getOrDefault(emptyList())
}
