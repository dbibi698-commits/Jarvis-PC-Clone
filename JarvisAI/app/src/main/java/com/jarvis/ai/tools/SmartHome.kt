package com.jarvis.ai.tools

import com.jarvis.ai.core.GeminiChat
import com.jarvis.ai.core.Prefs
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Smart home control (v1.3.8) — via Home Assistant (the hub that talks to
 * almost every smart device: lights, fans, AC, switches, plugs).
 *
 * Needs in Settings > Smart Home: your Home Assistant URL
 * (http://homeassistant.local:8123) + a Long-Lived Access Token
 * (Home Assistant → Profile → Long-Lived Access Tokens).
 * Without Home Assistant, Jarvis opens the Google Home app instead.
 */
object SmartHome {
    fun isConfigured(): Boolean = Prefs.homeAssistantUrl.trim().isNotBlank() && Prefs.homeAssistantToken.trim().isNotBlank()

    private fun base(): String = Prefs.homeAssistantUrl.trim().trimEnd('/')

    private fun req(path: String, body: JSONObject? = null): Request {
        val b = Request.Builder().url("${base()}$path")
            .header("Authorization", "Bearer ${Prefs.homeAssistantToken.trim()}")
            .header("Content-Type", "application/json")
        return if (body != null) b.post(body.toString().toRequestBody("application/json".toMediaType())).build()
        else b.build()
    }

    /** Test the connection (GET /api/ returns "API running."). */
    fun test(cb: (String) -> Unit) {
        Thread {
            val msg = runCatching {
                if (!isConfigured()) return@runCatching "Home Assistant URL + token bosao age (Settings > Smart Home)."
                GeminiChat.http.newCall(req("/api/")).execute().use { r ->
                    if (r.code in 200..299) "✓ Home Assistant connected! (${base()})"
                    else "❌ Home Assistant connect hoy ni (HTTP ${r.code}) — URL/token thik ache kina dekho."
                }
            }.getOrElse { "❌ Home Assistant connect hoy ni: ${it.message}" }
            cb(msg)
        }.start()
    }

    /** Call a Home Assistant service, e.g. light/turn_on on light.bedroom. */
    fun callService(domain: String, service: String, entityId: String, cb: (String) -> Unit) {
        Thread {
            val msg = runCatching {
                if (!isConfigured()) return@runCatching "Smart home control korte Home Assistant URL + token lagbe (Settings > Smart Home). Na thakle Google Home app khule debo."
                val body = JSONObject().put("entity_id", entityId)
                GeminiChat.http.newCall(req("/api/services/$domain/$service", body)).execute().use { r ->
                    if (r.code in 200..299) "✓ $entityId — $service done."
                    else "❌ Device control hoy ni (HTTP ${r.code}) — entity ID thik ache kina dekho (device_status diye list dekho)."
                }
            }.getOrElse { "❌ Smart home failed: ${it.message}" }
            cb(msg)
        }.start()
    }

    /** List devices and their states (first 30). */
    fun states(cb: (String) -> Unit) {
        Thread {
            val msg = runCatching {
                if (!isConfigured()) return@runCatching "Smart home dekhte Home Assistant URL + token lagbe (Settings > Smart Home)."
                GeminiChat.http.newCall(req("/api/states")).execute().use { r ->
                    if (r.code !in 200..299) return@runCatching "❌ States ante parlam na (HTTP ${r.code})."
                    val arr = JSONArray(r.body!!.string())
                    if (arr.length() == 0) return@runCatching "Home Assistant-e kono device pelam na."
                    val lines = mutableListOf<String>()
                    for (i in 0 until minOf(arr.length(), 30)) {
                        val o = arr.getJSONObject(i)
                        lines += "${o.optString("entity_id")}: ${o.optString("state")}"
                    }
                    "Smart home devices (${arr.length()}):\n" + lines.joinToString("\n")
                }
            }.getOrElse { "❌ Smart home failed: ${it.message}" }
            cb(msg)
        }.start()
    }
}
