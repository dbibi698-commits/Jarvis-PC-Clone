package com.jarvis.ai.tools

import android.content.Context
import android.hardware.camera2.CameraManager
import com.jarvis.ai.core.Prefs
import com.jarvis.ai.service.LockHelper
import org.json.JSONObject

/**
 * v1.3.9 — AI-powered gesture control: the user teaches Jarvis what a gesture
 * means, by voice. "Shake korle YouTube kholo" — and from then on, shaking
 * the phone opens YouTube. Mappings are stored in Prefs and the shake handler
 * in AssistantService runs them. Unmapped gestures fall back to the default
 * (toggle live voice). Supported actions: open <app>, lock, torch on/off, live.
 */
object GestureMapper {
    private fun map(): JSONObject = runCatching { JSONObject(Prefs.gestureMapJson) }.getOrDefault(JSONObject())

    fun set(gesture: String, action: String): String {
        val g = gesture.trim().lowercase()
        if (g != "shake") return "Ami ekhon sudhu 'shake' gesture-ta map korte pari (orb swipe fixed: up = talk, down = stop)."
        val a = action.trim()
        if (a.isEmpty()) return "Action-ta bolo — jemon 'open YouTube', 'lock', 'torch on'."
        val m = map()
        m.put(g, a)
        Prefs.gestureMapJson = m.toString()
        return "🤖 Gesture set: phone shake korle ami \"$a\" korbo."
    }

    fun list(): String {
        val m = map()
        if (m.length() == 0) return "Kono custom gesture mapping nei — shake korle live voice toggle hoy (default)."
        val sb = StringBuilder("Gesture mappings:\n")
        m.keys().forEach { sb.appendLine("- $it → ${m.getString(it)}") }
        return sb.toString()
    }

    /** Runs the mapped action for a gesture. Returns true if it handled it. */
    fun run(ctx: Context, gesture: String): Boolean {
        val action = map().optString(gesture.lowercase(), "").trim()
        if (action.isEmpty()) return false
        val low = action.lowercase()
        return when {
            low == "live" -> false
            low == "lock" -> LockHelper.lockNow(ctx)
            low == "torch on" || low == "torch off" -> runCatching {
                val cm = ctx.getSystemService(CameraManager::class.java)
                val id = cm.cameraIdList.firstOrNull() ?: return false
                cm.setTorchMode(id, low == "torch on"); true
            }.getOrDefault(false)
            low.startsWith("open ") -> {
                val app = AppFinder.find(ctx, action.substring(5).trim())
                app != null && AppFinder.launch(ctx, app.pkg)
            }
            else -> false
        }
    }
}
