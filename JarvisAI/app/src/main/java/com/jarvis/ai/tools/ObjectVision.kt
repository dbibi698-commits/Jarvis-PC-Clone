package com.jarvis.ai.tools

import android.content.Context
import com.jarvis.ai.core.GeminiChat
import com.jarvis.ai.core.Prefs
import com.jarvis.ai.service.JarvisAccessibilityService
import java.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * v1.4.0: object recognition — Jarvis sees the screen (or a photo) and
 * Gemini Vision tells you what objects are in it. This is camera + AI
 * vision, NOT true AR (no ARCore 3D overlays). Needs a Gemini API key.
 */
object ObjectVision {

    /** One-shot vision call (no agentic tool loop) — returns the description or null. */
    fun describe(jpeg: ByteArray, prompt: String): String? = runCatching {
        val key = Prefs.apiKey.trim()
        if (key.isBlank()) return null
        val model = Prefs.chatModel.ifBlank { "gemini-2.5-flash" }
        val parts = JSONArray()
            .put(JSONObject().put("text", prompt))
            .put(JSONObject().put("inline_data", JSONObject()
                .put("mime_type", "image/jpeg")
                .put("data", Base64.getEncoder().encodeToString(jpeg))))
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
        val req = Request.Builder().url("${GeminiChat.BASE}/models/$model:generateContent")
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        GeminiChat.http.newCall(req).execute().use { r ->
            if (r.code !in 200..299) return null
            val j = JSONObject(r.body!!.string())
            j.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
                .let { p -> (0 until p.length()).mapNotNull { p.getJSONObject(it).optString("text").takeIf { t -> t.isNotBlank() } }.joinToString("") }
                .takeIf { it.isNotBlank() }
        }
    }.getOrNull()

    /** v1.4.2: duto mukh-er photo compare — eki manush kina? JSON uttor dey. */
    fun compareFaces(refJpeg: ByteArray, selfieJpeg: ByteArray): String? = runCatching {
        val key = Prefs.apiKey.trim()
        if (key.isBlank()) return null
        val model = Prefs.chatModel.ifBlank { "gemini-2.5-flash" }
        val prompt = "Compare the two faces in these two photos. First photo is the OWNER's reference photo. Second photo is the person currently holding the phone. Are they the SAME person? Answer with ONLY this JSON, nothing else: {\"same_person\": true or false, \"confidence\": 0-100}. Be strict: different person, heavy disguise, or a photo of a screen/photo instead of a live face counts as NOT the same person."
        val parts = JSONArray()
            .put(JSONObject().put("text", prompt))
            .put(JSONObject().put("inline_data", JSONObject()
                .put("mime_type", "image/jpeg")
                .put("data", Base64.getEncoder().encodeToString(refJpeg))))
            .put(JSONObject().put("inline_data", JSONObject()
                .put("mime_type", "image/jpeg")
                .put("data", Base64.getEncoder().encodeToString(selfieJpeg))))
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
        val req = Request.Builder().url("${GeminiChat.BASE}/models/$model:generateContent")
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        GeminiChat.http.newCall(req).execute().use { r ->
            if (r.code !in 200..299) return null
            val j = JSONObject(r.body!!.string())
            j.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
                .let { p -> (0 until p.length()).mapNotNull { p.getJSONObject(it).optString("text").takeIf { t -> t.isNotBlank() } }.joinToString("") }
                .takeIf { it.isNotBlank() }
        }
    }.getOrNull()

    /** Screenshot the current screen and identify the objects in it. */
    suspend fun identify(ctx: Context, acc: JarvisAccessibilityService): String {
        if (Prefs.apiKey.trim().isBlank()) return "Object chinte Gemini API key lagbe — Settings-e Gemini key bosao, tarpor abar bolo."
        val jpeg = acc.screenshotJpeg() ?: return "Screenshot nite parlam na — abar try koro."
        val prompt = "Look at this phone screenshot carefully. List every object / UI element / person / animal / text you can see, with where each one is (top-left, center, bottom-right, etc.). Be concise, one line per object. Reply in the same language the user speaks (Bengali, Hindi or English)."
        val out = describe(jpeg, prompt) ?: return "Chhobi-ta dekhte parlam na — internet ba Gemini key check koro."
        return "👁️ Screen-e ja dekhlam:\n$out"
    }
}
