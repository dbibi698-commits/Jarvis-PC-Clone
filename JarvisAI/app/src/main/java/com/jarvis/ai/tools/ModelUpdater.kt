package com.jarvis.ai.tools

import com.jarvis.ai.core.GeminiChat
import com.jarvis.ai.core.Prefs
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * v1.3.9 — Self-updating AI models: Jarvis asks Google's API which Gemini
 * models exist right now and tells the user if a newer one is available.
 * Honest note: Jarvis cannot retrain or rewrite Google's models — it can only
 * discover the newest model names and switch to them (the model fields in
 * Settings > AI API Keys are free text for exactly this reason). The user's
 * own learning (corrections, habits, memories) is what keeps improving on
 * the phone — see AdaptiveLearning.
 */
object ModelUpdater {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    fun checkModels(cb: (String) -> Unit) {
        if (Prefs.apiKey.isBlank()) { cb("Model check korte Gemini API key lagbe — Settings > AI API Keys-e key bosao."); return }
        Thread {
            val msg = runCatching {
                val req = Request.Builder()
                    .url("${GeminiChat.BASE}/models?key=${Prefs.apiKey}&pageSize=50").build()
                val body = http.newCall(req).execute().use { r -> r.body?.string() ?: "" }
                val arr = JSONObject(body).optJSONArray("models") ?: return@runCatching "Model list pelam na — API key check koro."
                val models = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    val methods = m.optJSONArray("supportedGenerationMethods")
                    var ok = false
                    if (methods != null) for (j in 0 until methods.length()) if (methods.getString(j) == "generateContent") ok = true
                    if (ok) models += m.getString("name").removePrefix("models/")
                }
                val cur = Prefs.chatModel
                val sb = StringBuilder("🤖 Available Gemini models (${models.size}):\n")
                models.take(12).forEach { sb.appendLine("- $it${if (it == cur) "  ← tumi ekhon eta use korcho" else ""}") }
                if (models.isNotEmpty() && !models.contains(cur)) {
                    sb.appendLine("\nTomar current model ($cur) list-e nei — notun model-e switch korte Settings > AI API Keys-e chat model bodle dao.")
                } else {
                    sb.appendLine("\nTumi latest model-ei acho, ba list-e tomar model ache.")
                }
                sb.toString()
            }.getOrElse { "Model check holo na: ${it.message}" }
            android.os.Handler(android.os.Looper.getMainLooper()).post { cb(msg) }
        }.start()
    }
}
